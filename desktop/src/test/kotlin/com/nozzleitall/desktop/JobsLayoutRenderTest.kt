package com.nozzleitall.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import com.nozzleitall.desktop.screens.JobsScreen
import com.nozzleitall.desktop.ui.NozzleTheme
import com.nozzleitall.desktop.ui.Nz
import com.nozzleitall.desktop.history.JobRecord
import com.nozzleitall.desktop.jobs.LocalJobStore
import com.nozzleitall.printer.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Offline active-job layout fixture; no adapter session or printer command is created. */
class JobsLayoutRenderTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun changedHistoryResultKeepsAnOriginalDraftRouteOnJobs() {
        val root = Files.createTempDirectory("nia-jobs-original-draft").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val original = JobRecord("original", "printer", "Printer", "original.gcode", 1, 2, "finished", null, null)
        app.fleet.history.value = listOf(original.copy(outcome = "unknown"))
        app.historyDrafts[original] = HistoryAssessmentDraft(LocalJobStore.Assessment.FAILED, "Original draft note")
        val output = File("build/reports/jobs-original-history-draft.png")
        try {
            SwingUtilities.invokeAndWait {
                val scene = ImageComposeScene(width = 1000, height = 800) {
                    NozzleTheme("dark") {
                        Box(Modifier.fillMaxSize().background(Nz.colors.background)) { JobsScreen(app) }
                    }
                }
                try {
                    scene.render().close()
                    scene.render(1_000_000_000L).use { image ->
                        image.encodeToData()!!.use { output.parentFile.mkdirs(); output.writeBytes(it.bytes) }
                    }
                } finally { scene.close() }
            }
            assertEquals(1000, ImageIO.read(output).width)
            assertEquals("Original draft note", app.historyDrafts[original]?.notes)
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun activeJobRemainsRenderableAtNarrowWidthAndLargeText() {
        val root = Files.createTempDirectory("nia-jobs-layout").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val id = "offline-layout-fixture"
        val entry = PrinterEntry(PrinterConfig(
            PrinterIdentity(id, "Workshop printer with a long descriptive name", "Fixture", PrinterFamily.KLIPPER, "http://127.0.0.1:1"),
            EXPORT_ONLY))
        entry.status.value = PrinterStatus(PrinterState.PRINTING, ConnectionRoute.LAN,
            JobProgress("print-with-a-long-file-name-and-several-materials.gcode", 0.52f, 3600.0))
        app.fleet.printers[id] = entry
        app.fleet.order.value = listOf(id)
        app.fleet.history.value = listOf(JobRecord("completed-layout-fixture", id,
            "Workshop printer with a long descriptive name", "completed-print-with-a-long-file-name.gcode",
            1L, 2L, "finished", 3600.0, 220))
        try {
            for (scale in listOf(1.25f, 2f)) {
                val output = File("build/reports/jobs-active-${(scale * 100).toInt()}pct.png")
                SwingUtilities.invokeAndWait {
                    val scene = ImageComposeScene(width = 780, height = 800) {
                        NozzleTheme("dark", textScale = scale) {
                            Box(Modifier.fillMaxSize().background(Nz.colors.background)) { JobsScreen(app) }
                        }
                    }
                    try {
                        scene.render().close()
                        scene.render(1_000_000_000L).use { image ->
                            image.encodeToData()!!.use { output.parentFile.mkdirs(); output.writeBytes(it.bytes) }
                        }
                    } finally { scene.close() }
                }
                val image = ImageIO.read(output)
                assertEquals(780, image.width)
                assertEquals(800, image.height)
            }
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }
}
