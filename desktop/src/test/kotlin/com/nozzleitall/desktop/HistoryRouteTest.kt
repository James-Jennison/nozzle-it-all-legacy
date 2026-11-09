package com.nozzleitall.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import com.nozzleitall.desktop.history.JobRecord
import com.nozzleitall.desktop.HistoryAssessmentDraft
import com.nozzleitall.desktop.screens.HistoryScreen
import com.nozzleitall.desktop.screens.canApplyHistoryReview
import com.nozzleitall.desktop.screens.resolveRequestedHistoryRecord
import com.nozzleitall.desktop.screens.dismissHistorySaveNotice
import com.nozzleitall.desktop.screens.discardOriginalHistoryDraft
import com.nozzleitall.desktop.screens.historyReviewAfterLoad
import com.nozzleitall.desktop.screens.historyLoadFailure
import com.nozzleitall.desktop.screens.HistoryLoadPhase
import com.nozzleitall.desktop.screens.historyNoticeLabel
import com.nozzleitall.desktop.screens.retainHistorySaveFailure
import com.nozzleitall.desktop.screens.retainHistorySaveSuccess
import com.nozzleitall.desktop.jobs.LocalJobStore
import com.nozzleitall.desktop.ui.NozzleTheme
import com.nozzleitall.desktop.ui.Nz
import com.nozzleitall.printer.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities

class HistoryRouteTest {
    @Test fun jobsDetailsRetainsExactObservationWhenPrinterWasRemoved() {
        val root = Files.createTempDirectory("nia-history-route").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val older = JobRecord("older", "removed-printer", "Removed printer", "same.gcode", 1, 2, "finished", null, null)
        val selected = JobRecord("selected", "removed-printer", "Removed printer", "same.gcode", 3, 4, "failed", null, null)
        app.fleet.history.value = listOf(older, selected)
        try {
            app.openHistoryRecord(selected)
            assertEquals(Destination.HISTORY, app.destination)
            assertEquals(selected, app.selectedHistoryRecord)
            assertEquals("removed-printer", app.selectedPrinter)
            assertEquals(selected, resolveRequestedHistoryRecord(app.fleet.history.value, app.selectedHistoryRecord))
            assertNull(resolveRequestedHistoryRecord(app.fleet.history.value, selected.copy(id = "missing")))
            renderHistory(app, 900, 850, 1f, "history-removed-printer.png")
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }

    @Test fun duplicateIdRoutesToExactResultAndRemovedResultDoesNotStaySelected() {
        val older = JobRecord("same-id", "printer", "Printer", "first.gcode", 1, 2, "finished", null, null)
        val selected = JobRecord("same-id", "printer", "Printer", "second.gcode", 3, 4, "failed", null, null)
        assertEquals(selected, resolveRequestedHistoryRecord(listOf(older, selected), selected))
        assertNull(resolveRequestedHistoryRecord(listOf(older), selected))
    }

    @Test fun historyLoadRecoverySeparatesTransientAndChangedResults() {
        val changed = historyLoadFailure(IllegalStateException("Machine observation changed; review History before assessing it."))
        assertEquals(HistoryLoadPhase.UNAVAILABLE, changed.second)
        assertTrue(changed.first.contains("Close this detail"))
        assertEquals(HistoryLoadPhase.UNAVAILABLE,
            historyLoadFailure(IllegalStateException("The observed job is missing.")).second)
        assertEquals(HistoryLoadPhase.UNAVAILABLE,
            historyLoadFailure(IllegalStateException("The observed attempt is missing from its job.")).second)
        val temporary = historyLoadFailure(org.sqlite.SQLiteException("database is locked", org.sqlite.SQLiteErrorCode.SQLITE_BUSY))
        assertEquals(HistoryLoadPhase.RETRYABLE_ERROR, temporary.second)
        assertTrue(temporary.first.contains("Retry loading"))
        assertEquals(HistoryLoadPhase.RETRYABLE_ERROR,
            historyLoadFailure(org.sqlite.SQLiteException("table is locked", org.sqlite.SQLiteErrorCode.SQLITE_LOCKED)).second)
        assertEquals(HistoryLoadPhase.RETRYABLE_ERROR,
            historyLoadFailure(org.sqlite.SQLiteException("busy snapshot", org.sqlite.SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT)).second)
        assertEquals(HistoryLoadPhase.RETRYABLE_ERROR,
            historyLoadFailure(org.sqlite.SQLiteException("shared cache locked", org.sqlite.SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE)).second)
        assertEquals(HistoryLoadPhase.UNAVAILABLE,
            historyLoadFailure(java.sql.SQLException("database disk image is malformed", null, 11)).second)
        assertEquals(HistoryLoadPhase.UNAVAILABLE,
            historyLoadFailure(java.sql.SQLException("other driver busy", null, 5)).second)
        assertEquals(HistoryLoadPhase.UNAVAILABLE,
            historyLoadFailure(org.json.JSONException("corrupt observation payload")).second)
        assertTrue(!historyLoadFailure(org.json.JSONException("/private/path")).first.contains("/private/path"))
    }

    @Test fun concurrentSameFileNoticesIdentifyDifferentPrintResults() {
        val a = JobRecord("shared-prefix-first", "printer", "Printer", "same.gcode", 1, 2, "finished", null, null)
        val b = a.copy(id = "shared-prefix-second")
        assertTrue(historyNoticeLabel(a).contains("same.gcode on Printer"))
        assertTrue(historyNoticeLabel(a) != historyNoticeLabel(b))
    }

    @Test fun historyRefreshCannotDiscardAnInFlightAssessmentResultOrFailure() {
        val root = Files.createTempDirectory("nia-history-refresh-save").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val original = JobRecord("observation", "printer", "Printer", "A.gcode", 1, 2, "finished", null, null)
        val other = original.copy(id = "another-observation", fileName = "B.gcode")
        val refreshed = original.copy(outcome = "unknown")
        val review = LocalJobStore.ObservationReview(
            LocalJobStore.Job("job", LocalJobStore.Origin.EXTERNAL, null, null, null, null,
                "printer", null, null, 1), "attempt", null)
        try {
            app.fleet.history.value = listOf(refreshed)
            app.destination = Destination.JOBS
            app.selectedHistoryRecord = original
            app.historyDrafts[original] = HistoryAssessmentDraft(LocalJobStore.Assessment.FAILED, "Keep this exact draft")
            renderHistory(app, 900, 850, 1f, "history-changed-record-draft.png")
            assertEquals("Keep this exact draft", app.historyDrafts[original]?.notes)
            assertTrue(!retainHistorySaveSuccess(app, original, review))
            assertEquals(review, app.historyCompletedSaves[original])
            assertEquals(review, historyReviewAfterLoad(app, original, null))
            assertNull(app.historyCompletedSaves[original])
            app.historyCompletedSaves[original] = review
            assertTrue(app.historySaveNotices[original]!!.message.contains("was saved"))
            retainHistorySaveFailure(app, original, IllegalStateException("write failed"))
            assertTrue(app.historySaveProblems[original]!!.contains("write failed"))
            assertTrue(app.historySaveNotices[original]!!.message.contains("could not be saved"))
            renderHistory(app, 900, 850, 1f, "history-changed-record-failure.png")
            dismissHistorySaveNotice(app, original)
            assertNull(app.historySaveNotices[original])
            assertEquals(review, app.historyCompletedSaves[original])
            app.fleet.history.value = listOf(original, other)
            retainHistorySaveSuccess(app, original, review)
            retainHistorySaveFailure(app, other, IllegalStateException("another failure"))
            assertEquals(2, app.historySaveNotices.size)
            assertTrue(app.historySaveNotices[original]!!.message.contains("saved"))
            assertTrue(app.historySaveNotices[other]!!.isError)
            app.destination = Destination.HISTORY
            app.selectedHistoryRecord = original
            retainHistorySaveFailure(app, original, IllegalStateException("stale failure"))
            assertNull(app.historySaveNotices[original])
            retainHistorySaveSuccess(app, original, review)
            assertNull(app.historySaveProblems[original])
            assertNull(app.historySaveNotices[original])
            app.fleet.history.value = listOf(refreshed)
            app.historySaveProblems[original] = "Earlier write failed"
            discardOriginalHistoryDraft(app, original)
            assertNull(app.historyDrafts[original])
            assertEquals(review, app.historyCompletedSaves[original])
            assertEquals("Earlier write failed", app.historySaveProblems[original])
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }

    @Test fun lateReviewCannotAttachAfterSwitchingAwayAndBackToTheSameRecord() {
        val a = JobRecord("a", "printer", "Printer", "a.gcode", 1, 2, "finished", null, null)
        val b = a.copy(id = "b", fileName = "b.gcode")
        val firstSelection = Any()
        val secondSelection = Any()
        assertTrue(canApplyHistoryReview(firstSelection, firstSelection, a, a, listOf(a, b)))
        assertTrue(!canApplyHistoryReview(firstSelection, secondSelection, b, a, listOf(a, b)))
        assertTrue(!canApplyHistoryReview(firstSelection, secondSelection, a, a, listOf(a, b)))
        assertTrue(!canApplyHistoryReview(secondSelection, secondSelection, a, a, listOf(b)))
    }

    @Test fun normalPrinterHistoryRendersAtNarrowWidthAndDoubleText() {
        val root = Files.createTempDirectory("nia-history-large-text").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val id = "offline-history-fixture"
        app.fleet.printers[id] = PrinterEntry(PrinterConfig(
            PrinterIdentity(id, "Workshop printer with a long descriptive name", "Fixture", PrinterFamily.KLIPPER, "http://127.0.0.1:1"),
            EXPORT_ONLY))
        app.fleet.order.value = listOf(id)
        app.selectedPrinter = id
        app.fleet.history.value = listOf(JobRecord("normal-result", id,
            "Workshop printer with a long descriptive name", "print-with-a-long-name-and-multiple-parts.gcode",
            1, 2, "finished", 3600.0, 220))
        try { renderHistory(app, 780, 800, 2f, "history-normal-200pct.png") }
        finally { app.fleet.shutdown(); scope.cancel() }
    }

    @Test fun missingAssessmentExplainsUnavailableResultWithoutRetry() {
        val root = Files.createTempDirectory("nia-history-missing-review").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val id = "offline-history-fixture"
        app.fleet.printers[id] = PrinterEntry(PrinterConfig(
            PrinterIdentity(id, "Workshop printer", "Fixture", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), EXPORT_ONLY))
        app.fleet.order.value = listOf(id)
        app.selectedPrinter = id
        val record = JobRecord("unimported", id, "Workshop printer", "unimported.gcode", 1, 2, "finished", null, null)
        app.fleet.history.value = listOf(record)
        app.selectedHistoryRecord = record
        try { renderHistory(app, 900, 800, 1f, "history-missing-assessment-unavailable.png") }
        finally { app.fleet.shutdown(); scope.cancel() }
    }

    @Test fun removedSelectionStillRendersRemainingHistory() {
        val root = Files.createTempDirectory("nia-history-removed-selection").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val id = "offline-history-fixture"
        app.fleet.printers[id] = PrinterEntry(PrinterConfig(
            PrinterIdentity(id, "Workshop printer", "Fixture", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), EXPORT_ONLY))
        app.fleet.order.value = listOf(id)
        app.selectedPrinter = id
        val remaining = JobRecord("remaining", id, "Workshop printer", "remaining.gcode", 1, 2, "finished", null, null)
        app.fleet.history.value = listOf(remaining)
        app.selectedHistoryRecord = remaining.copy(id = "removed", fileName = "removed.gcode")
        try { renderHistory(app, 780, 800, 1f, "history-missing-selection.png") }
        finally { app.fleet.shutdown(); scope.cancel() }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun renderHistory(app: AppState, width: Int, height: Int, scale: Float, name: String) {
        val output = File("build/reports/$name")
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(width = width, height = height) {
                NozzleTheme("dark", textScale = scale) {
                    Box(Modifier.fillMaxSize().background(Nz.colors.background)) { HistoryScreen(app) }
                }
            }
            try {
                scene.render().close()
                scene.render(1_000_000_000L).use { image ->
                    image.encodeToData()!!.use { output.parentFile.mkdirs(); output.writeBytes(it.bytes) }
                }
            } finally { scene.close() }
        }
        assertTrue(output.length() > 1_000)
    }
}
