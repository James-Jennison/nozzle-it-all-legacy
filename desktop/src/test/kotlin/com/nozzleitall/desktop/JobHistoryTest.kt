package com.nozzleitall.desktop

import com.nozzleitall.desktop.history.*
import com.nozzleitall.desktop.jobs.LocalJobStore
import com.nozzleitall.printer.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JobHistoryTest {
    private val route = ConnectionRoute.LAN
    private fun printing(file: String, fraction: Float, elapsed: Double = 600.0, layers: Int = 100) =
        PrinterStatus(PrinterState.PRINTING, route, job = JobProgress(file, fraction, elapsed, 10, layers))
    private fun idle(state: PrinterState) = PrinterStatus(state, route)
    private fun finished(path: String) = PrinterStatus(PrinterState.FINISHED, route, completedFileName = path)

    @Test fun aJobThatEndsIsRecordedOnceWithHowItEnded() {
        var now = 1_000_000L
        val t = JobTracker { now }
        assertNull(t.observe("p", "CC1", printing("a.gcode", 0.2f)))
        now += 60_000; assertNull(t.observe("p", "CC1", printing("a.gcode", 0.9f, elapsed = 3600.0)))
        now += 60_000
        val r = t.observe("p", "CC1", finished("a.gcode"))!!
        assertEquals("finished", r.outcome); assertEquals("a.gcode", r.fileName); assertEquals("CC1", r.printerName)
        assertEquals(3600.0, r.elapsedSeconds!!, 0.0); assertEquals(100, r.totalLayers)
        assertNull("the same ending is not recorded twice", t.observe("p", "CC1", idle(PrinterState.FINISHED)))
    }

    @Test fun unnamedOrInexactTerminalReadingNeverConfirmsTheTrackedFile() {
        val tracker = JobTracker()
        tracker.observe("p", "P", printing("folder/part.gcode", 0.9f))
        assertNull(tracker.observe("p", "P", PrinterStatus(PrinterState.FINISHED, route,
            job = JobProgress("folder/part.gcode", 1f))))
        assertNull(tracker.observe("p", "P", finished("other/part.gcode")))
        assertEquals("finished", tracker.observe("p", "P", finished("folder\\part.gcode"))!!.outcome)
    }

    @Test fun stoppedAndFailedAreToldApart() {
        val t = JobTracker()
        t.observe("p", "P", printing("a", 0.3f)); assertEquals("cancelled", t.observe("p", "P", idle(PrinterState.CANCELLED))!!.outcome)
        t.observe("p", "P", printing("b", 0.3f)); assertEquals("failed", t.observe("p", "P", idle(PrinterState.ERROR))!!.outcome)
    }

    @Test fun backToReadyDoesNotClaimAReportedEnding() {
        val t = JobTracker()
        t.observe("p", "P", printing("a", 0.995f)); assertEquals("unknown", t.observe("p", "P", idle(PrinterState.READY))!!.outcome)
        t.observe("p", "P", printing("b", 0.4f)); assertEquals("unknown", t.observe("p", "P", idle(PrinterState.READY))!!.outcome)
    }

    @Test fun reportedElapsedLongerThanLocalClockDoesNotPoisonHistory() {
        val tracker = JobTracker { 5_000L }
        tracker.observe("p", "P", printing("a.gcode", 0.5f, elapsed = 600.0))
        val ended = tracker.observe("p", "P", idle(PrinterState.READY))!!
        assertEquals(0L, ended.startedAtMillis)
        val file = File(Files.createTempDirectory("elapsed-clamp").toFile(), "history.json")
        JobHistoryStore(file).prependPreserving(ended)
        assertEquals(ended, JobHistoryStore(file).load().single())
    }

    @Test fun losingTheConnectionMidPrintEndsNothing() {
        val t = JobTracker()
        t.observe("p", "P", printing("a", 0.5f))
        assertNull(t.observe("p", "P", idle(PrinterState.OFFLINE)))
        assertNull(t.observe("p", "P", idle(PrinterState.UNKNOWN)))
        assertEquals("finished", t.observe("p", "P", finished("a"))!!.outcome)
    }

    @Test fun aPrinterAlreadyFinishedWhenTheAppStartsIsNotANewResult() {
        val t = JobTracker()
        assertNull(t.observe("p", "P", idle(PrinterState.FINISHED)))
        assertNull(t.observe("p", "P", idle(PrinterState.READY)))
    }

    @Test fun twoPrintersAreTrackedSeparately() {
        val t = JobTracker()
        t.observe("a", "A", printing("x", 0.5f)); t.observe("b", "B", printing("y", 0.5f))
        assertEquals("y", t.observe("b", "B", finished("y"))!!.fileName)
        assertEquals("x", t.observe("a", "A", idle(PrinterState.CANCELLED))!!.fileName)
    }

    @Test fun trackerIdentitiesDoNotCollideAcrossRestartsAtTheSameClockTime() {
        val first = JobTracker { 1_000_000L }
        val second = JobTracker { 1_000_000L }
        first.observe("a", "A", printing("a.gcode", 0.5f))
        second.observe("b", "B", printing("b.gcode", 0.5f))
        val one = first.observe("a", "A", finished("a.gcode"))!!
        val two = second.observe("b", "B", finished("b.gcode"))!!
        assertNotEquals(one.id, two.id)
        val store = JobHistoryStore(File(Files.createTempDirectory("tracker-ids").toFile(), "history.json"))
        store.prependPreserving(one)
        assertEquals(2, store.prependPreserving(two).size)
    }

    @Test fun aFilenameSwitchRetainsThePreviousJobAsUnresolved() {
        val tracker = JobTracker { 5_000L }
        assertNull(tracker.observe("p", "P", printing("a.gcode", 0.4f)))
        val unresolved = tracker.observe("p", "P", printing("b.gcode", 0.1f))!!
        assertEquals("a.gcode", unresolved.fileName)
        assertEquals("unknown", unresolved.outcome)
        assertNull(tracker.observe("p", "P", printing("b.gcode", 0.2f)))
        assertEquals("b.gcode", tracker.observe("p", "P", finished("b.gcode"))!!.fileName)
    }

    @Test fun historySurvivesARestartNewestFirstAndIsCapped() {
        val f = File(Files.createTempDirectory("hist").toFile(), "history.json")
        val store = JobHistoryStore(f, limit = 3)
        assertTrue(store.load().isEmpty())
        val recs = (1..5).map { JobRecord("$it", "p", "P", "f$it.gcode", 0, it.toLong(), "finished", 60.0, 10) }
        store.save(recs)
        assertEquals(listOf("1", "2", "3"), store.load().map { it.id })
        assertEquals(60.0, store.load().first().elapsedSeconds!!, 0.0)
    }

    @Test fun malformedHistoryFailsClosedWithoutOverwritingTheSource() {
        val file = File(Files.createTempDirectory("bad-hist").toFile(), "history.json")
        file.writeText("[{\"id\":\"one\",\"printerId\":\"p\",\"fileName\":\"a\"},{}]")
        val original = file.readBytes()
        assertThrows(Exception::class.java) { JobHistoryStore(file).load() }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun aLegacyRowMissingItsOutcomeIsNotRewrittenAsFinished() {
        val file = File(Files.createTempDirectory("incomplete-hist").toFile(), "history.json")
        file.writeText("""[{"id":"old","printerId":"p","fileName":"a.gcode","startedAt":1,"endedAt":2}]""")
        val original = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            JobHistoryStore(file).prependPreserving(JobRecord("new", "p", "P", "b.gcode", 3, 4, "finished", null, null))
        }
        assertArrayEquals(original, file.readBytes())
        assertThrows(Exception::class.java) {
            JobRecord.fromJson(org.json.JSONObject("""{"id":"old","printerId":"p","fileName":"a.gcode","startedAt":1,"endedAt":2}"""))
        }
    }

    @Test fun preservingAppendKeepsAllLegacyRowsBeyondTheDisplayLimit() {
        val file = File(Files.createTempDirectory("long-hist").toFile(), "history.json")
        val original = (1..501).map { JobRecord("old-$it", "p", "P", "$it.gcode", 0, it.toLong(), "finished", null, null) }
        file.writeText(org.json.JSONArray(original.map { it.toJson() }).toString())
        val store = JobHistoryStore(file)
        store.prependPreserving(JobRecord("new", "p", "P", "new.gcode", 600, 700, "unknown", null, null))
        assertEquals(502, store.load().size)
        assertEquals("old-501", store.load().last().id)
    }

    @Test fun retryingTheSameObservationDoesNotDuplicateOrReplaceHistory() {
        val file = File(Files.createTempDirectory("history-retry").toFile(), "history.json")
        val store = JobHistoryStore(file)
        val record = JobRecord("observation", "p", "P", "part.gcode", 10, 20, "finished", null, null)
        store.prependPreserving(record)
        assertEquals(listOf(record), store.prependPreserving(record))
        assertThrows(IllegalStateException::class.java) {
            store.prependPreserving(record.copy(outcome = "failed"))
        }
        assertEquals(listOf(record), store.load())
    }

    @Test fun theNotificationSaysWhatHappenedWhereAndHowLong() {
        val r = JobRecord("1", "p", "CC1", "dir/Possum.gcode", 0, 1, "failed", 7500.0, null)
        assertEquals("Print failed on CC1", r.notificationTitle()); assertEquals("Possum.gcode · 2 h 5 min", r.notificationBody())
        assertEquals("Print finished on CC1", r.copy(outcome = "finished").notificationTitle())
        assertEquals("Print stopped on CC1", r.copy(outcome = "cancelled").notificationTitle())
    }

    @Test fun theFleetRecordsAnEndedJobSavesItAndNotifiesUnlessTurnedOff() {
        val tmp = Files.createTempDirectory("fleet").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        val sent = mutableListOf<Pair<String, String>>()
        fleet.notifier = { t, b -> sent += t to b }
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p1", "CC1", "Elegoo CC1", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "moonraker"))
        fleet.recordEnd(entry, printing("Possum.gcode", 0.5f))
        fleet.recordEnd(entry, finished("Possum.gcode"))
        assertEquals(1, fleet.history.value.size); assertTrue("milestones default to off", sent.isEmpty())
        assertEquals("a restart reads it back", 1, JobHistoryStore(File(paths.data, "history.json")).load().size)
        fleet.settings.value = fleet.settings.value.copy(notifications = false)
        fleet.recordEnd(entry, printing("B.gcode", 0.5f)); fleet.recordEnd(entry, idle(PrinterState.CANCELLED))
        assertEquals(2, fleet.history.value.size); assertEquals("history is kept even with notifications off", 0, sent.size)
        fleet.shutdown()
    }

    @Test fun aCorruptLegacyHistoryDoesNotEraseANewObservation() {
        val tmp = Files.createTempDirectory("fleet-pending-history").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val source = File(paths.data, "history.json").apply { writeText("corrupt legacy source") }
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        fleet.notifier = { _, _ -> }
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p1", "P", "Test", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "fake"))
        fleet.recordEnd(entry, printing("new.gcode", 0.5f))
        fleet.recordEnd(entry, finished("new.gcode"))
        assertEquals("corrupt legacy source", source.readText())
        assertEquals(1, JobHistoryStore(File(paths.data, "history-pending.json")).load().size)
        fleet.shutdown()
        val reopened = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        assertEquals("new.gcode", reopened.history.value.single().fileName)
        assertNotNull(reopened.historyMigrationProblem.value)
        reopened.shutdown()
    }

    @Test fun conflictingPendingAndSavedHistoryStayVisibleForReview() {
        val tmp = Files.createTempDirectory("fleet-conflict-history").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val original = JobRecord("same", "p", "P", "part.gcode", 100, 200, "finished", null, null,
            currentObservation = true)
        JobHistoryStore(File(paths.data, "history.json")).save(listOf(original))
        JobHistoryStore(File(paths.data, "history-pending.json")).save(listOf(original.copy(outcome = "failed")))
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        assertEquals(2, fleet.history.value.size)
        assertNotNull(fleet.historyConflictProblem.value)
        assertTrue(LocalJobStore(File(paths.data, "jobs.sqlite")).jobs().isEmpty())
        assertEquals(original, JobHistoryStore(File(paths.data, "history.json")).load().single())
        fleet.shutdown()
    }

    @Test fun filenameFreeTerminalAfterRestartDoesNotClaimQueuedCompletion() {
        val tmp = Files.createTempDirectory("fleet-resume-attempt").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val source = File(tmp, "part.gcode").apply { writeText("G28\n") }
        val first = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        first.notifier = { _, _ -> }
        val item = first.enqueue("Part", source, "p", emptyList())
        val held = first.markQueuedStartUncertain(item, "p", "nozzle/Part.gcode")
        first.noteQueuedStarted(held, "p", "nozzle/Part.gcode")
        first.dequeue(item.id)
        first.shutdown()
        val reopened = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        reopened.notifier = { _, _ -> }
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p", "P", "Test", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "fake"))
        reopened.recordEnd(entry, idle(PrinterState.FINISHED))
        assertTrue(reopened.history.value.isEmpty())
        assertTrue(reopened.currentObservationProblem.value!!.contains("without a file name"))
        val jobs = com.nozzleitall.desktop.jobs.LocalJobStore(File(paths.data, "jobs.sqlite"))
        val attempt = jobs.attempts(item.jobId!!).single()
        assertEquals(LocalJobStore.MachineState.PRINTING, jobs.machineEvents(attempt.id).last().state)
        assertEquals(item.jobId, jobs.activeQueuedAttempt("p")?.jobId)
        assertEquals(LocalJobStore.Origin.APP, jobs.job(item.jobId!!)!!.origin)
        reopened.shutdown()
    }

    @Test fun savedHistoryCannotOverwriteAConcurrentObservation() {
        val file = File(Files.createTempDirectory("history-save-guard").toFile(), "history.json")
        val first = JobRecord("first", "p", "P", "first.gcode", 100, 200, "finished", null, null)
        val later = first.copy(id = "later", fileName = "later.gcode")
        val store = JobHistoryStore(file)
        store.save(listOf(first))
        JobHistoryStore(file).prependPreserving(later)
        assertThrows(IllegalStateException::class.java) { store.save(listOf(first)) }
        assertEquals(listOf(later, first), store.load())
    }

    @Test fun successfulLegacyImportDoesNotClearPendingHistoryWarning() {
        val tmp = Files.createTempDirectory("pending-history-warning").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        File(paths.data, "history-pending.json").writeText("broken pending history")
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        fleet.notifier = { _, _ -> }
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p", "P", "Test", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "fake"))
        fleet.recordEnd(entry, printing("part.gcode", 0.5f))
        fleet.recordEnd(entry, finished("part.gcode"))
        assertTrue(fleet.historyMigrationProblem.value!!.contains("Pending history cannot be read"))
        assertEquals(1, fleet.history.value.size)
        fleet.shutdown()
    }

    @Test fun restartRemovesAQueueRowWhoseStartWasAlreadyRecorded() {
        val tmp = Files.createTempDirectory("fleet-started-queue").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val source = File(tmp, "part.gcode").apply { writeText("G28\n") }
        val first = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        val item = first.enqueue("Part", source, "p", emptyList())
        val held = first.markQueuedStartUncertain(item, "p", "part.gcode")
        first.noteQueuedStarted(held, "p", "part.gcode")
        first.shutdown() // Crash boundary: the accepted start was bound, but dequeue did not run.
        val reopened = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        assertTrue(reopened.queue.value.isEmpty())
        assertEquals(item.jobId, LocalJobStore(File(paths.data, "jobs.sqlite")).activeQueuedAttempt("p")?.jobId)
        reopened.shutdown()
    }

    @Test fun crashAfterConfirmedStartIntentKeepsQueueHeldUntilExplicitResolution() {
        val tmp = Files.createTempDirectory("fleet-uncertain-start").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val source = File(tmp, "part.gcode").apply { writeText("G28\n") }
        val first = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        val item = first.enqueue("Part", source, "p", emptyList())
        val held = first.markQueuedStartUncertain(item, "p", "part.gcode")
        first.shutdown() // Accepted physical command may have happened; local bind did not.
        val reopened = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        assertTrue(reopened.queue.value.single().startUncertain)
        assertThrows(IllegalStateException::class.java) { reopened.queuedFile(item) }
        reopened.noteQueuedStarted(held, "p", "part.gcode") // Explicit human resolution: it started.
        assertTrue(reopened.queue.value.isEmpty())
        assertEquals(item.jobId, LocalJobStore(File(paths.data, "jobs.sqlite")).activeQueuedAttempt("p")?.jobId)
        reopened.shutdown()
    }

    @Test fun readyAfterRestartClosesQueuedAttemptAsUnknownAndFreesPrinter() {
        val tmp = Files.createTempDirectory("fleet-ready-after-restart").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val source = File(tmp, "part.gcode").apply { writeText("G28\n") }
        val first = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        val item = first.enqueue("Part", source, "p", emptyList())
        val held = first.markQueuedStartUncertain(item, "p", "part.gcode")
        first.noteQueuedStarted(held, "p", "part.gcode")
        first.dequeue(item.id)
        first.shutdown()
        val reopened = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        reopened.notifier = { _, _ -> }
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p", "P", "Test", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "fake"))
        reopened.recordEnd(entry, idle(PrinterState.READY))
        assertEquals("unknown", reopened.history.value.single().outcome)
        val jobs = LocalJobStore(File(paths.data, "jobs.sqlite"))
        assertEquals(null, jobs.activeQueuedAttempt("p"))
        assertEquals(LocalJobStore.MachineState.UNKNOWN, jobs.machineEvents(jobs.attempts(item.jobId!!).single().id).last().state)
        reopened.shutdown()
    }

    @Test fun terminalStatusForAnotherFileDoesNotCompleteTheQueuedAttempt() {
        val tmp = Files.createTempDirectory("fleet-mismatched-terminal").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val source = File(tmp, "part.gcode").apply { writeText("G28\n") }
        val first = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        val item = first.enqueue("Part", source, "p", emptyList())
        val held = first.markQueuedStartUncertain(item, "p", "part.gcode")
        first.noteQueuedStarted(held, "p", "part.gcode")
        first.shutdown()
        val reopened = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        reopened.notifier = { _, _ -> }
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p", "P", "Test", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "fake"))
        reopened.recordEnd(entry, printing("other/part.gcode", 0.5f, elapsed = 10.0))
        reopened.recordEnd(entry, finished("other/part.gcode"))
        reopened.recordEnd(entry, finished("other/part.gcode"))
        assertEquals("other/part.gcode", reopened.history.value.single().fileName)
        val jobs = LocalJobStore(File(paths.data, "jobs.sqlite"))
        val queuedAttempt = jobs.attempts(item.jobId!!).single()
        assertEquals(LocalJobStore.MachineState.PRINTING, jobs.machineEvents(queuedAttempt.id).last().state)
        assertEquals(item.jobId, jobs.activeQueuedAttempt("p")?.jobId)
        assertTrue(jobs.jobs().any { it.origin == LocalJobStore.Origin.OBSERVED_UNLINKED })
        reopened.shutdown()
    }

    @Test fun retiredPrinterEntryCannotRecordAnInFlightStalePoll() {
        val tmp = Files.createTempDirectory("fleet-retired-poll").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p", "P", "Test", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "fake"))
        fleet.recordEnd(entry, printing("old.gcode", 0.5f))
        entry.retired = true
        fleet.recordEnd(entry, idle(PrinterState.FINISHED))
        assertTrue(fleet.history.value.isEmpty())
        fleet.shutdown()
    }

    @Test fun noDurableStoreMeansNoSavedHistoryClaim() {
        val tmp = Files.createTempDirectory("fleet-all-history-stores-fail").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        fleet.notifier = { _, _ -> }
        val db = File(paths.data, "jobs.sqlite")
        val held = File(paths.data, "jobs-held.sqlite")
        assertTrue(db.renameTo(held))
        assertTrue(db.mkdir())
        listOf("history.json", "history-pending.json", "current-observation-retry.json").forEach {
            assertTrue(File(paths.data, it).mkdir())
        }
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p", "P", "Test", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "fake"))
        fleet.recordEnd(entry, printing("part.gcode", 0.5f))
        fleet.recordEnd(entry, finished("part.gcode"))
        assertTrue(fleet.history.value.isEmpty())
        assertTrue(fleet.currentObservationProblem.value!!.contains("could not be saved in any local store"))
        listOf("history.json", "history-pending.json", "current-observation-retry.json").forEach {
            assertTrue(File(paths.data, it).delete())
        }
        assertTrue(db.delete())
        assertTrue(held.renameTo(db))
        fleet.recordEnd(entry, idle(PrinterState.READY))
        assertEquals(1, fleet.history.value.size)
        assertEquals(1, LocalJobStore(db).currentObservedRecords().size)
        fleet.shutdown()
    }

    @Test fun repeatedMismatchedTerminalDuringStorageOutageKeepsOneObservation() {
        val tmp = Files.createTempDirectory("fleet-mismatched-outage").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val source = File(tmp, "part.gcode").apply { writeText("G28\n") }
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        fleet.notifier = { _, _ -> }
        val item = fleet.enqueue("Part", source, "p", emptyList())
        val startHold = fleet.markQueuedStartUncertain(item, "p", "part.gcode")
        fleet.noteQueuedStarted(startHold, "p", "part.gcode")
        val db = File(paths.data, "jobs.sqlite")
        val held = File(paths.data, "jobs-held.sqlite")
        assertTrue(db.renameTo(held))
        assertTrue(db.mkdir())
        val historyFiles = listOf("history.json", "history-pending.json", "current-observation-retry.json")
        historyFiles.forEach { assertTrue(File(paths.data, it).mkdir()) }
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p", "P", "Test", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "fake"))
        fleet.recordEnd(entry, printing("other.gcode", 0.5f))
        val terminal = finished("other.gcode")
        fleet.recordEnd(entry, terminal)
        fleet.recordEnd(entry, terminal)
        assertTrue(fleet.history.value.isEmpty())
        historyFiles.forEach { assertTrue(File(paths.data, it).delete()) }
        assertTrue(db.delete())
        assertTrue(held.renameTo(db))
        fleet.recordEnd(entry, terminal)
        val jobs = LocalJobStore(db)
        val attempt = jobs.attempts(item.jobId!!).single()
        assertEquals(1, fleet.history.value.count { it.fileName == "other.gcode" })
        assertEquals(1, jobs.currentObservedRecords().count { it.fileName == "other.gcode" })
        assertEquals(0, jobs.machineEvents(attempt.id).count { it.state == LocalJobStore.MachineState.UNKNOWN })
        assertEquals(item.jobId, jobs.activeQueuedAttempt("p")?.jobId)
        fleet.shutdown()
    }

    @Test fun historyOnlyMachineObservationRecoversIntoJobTimelineAfterRestart() {
        val tmp = Files.createTempDirectory("fleet-history-only-recovery").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        fleet.notifier = { _, _ -> }
        val db = File(paths.data, "jobs.sqlite")
        val held = File(paths.data, "jobs-held.sqlite")
        assertTrue(db.renameTo(held))
        assertTrue(db.mkdir())
        val retry = File(paths.data, "current-observation-retry.json")
        assertTrue(retry.mkdir())
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p", "P", "Test", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "fake"))
        fleet.recordEnd(entry, printing("part.gcode", 0.5f))
        fleet.recordEnd(entry, finished("part.gcode"))
        assertEquals(1, JobHistoryStore(File(paths.data, "history.json")).load().size)
        assertTrue(JobHistoryStore(File(paths.data, "history.json")).load().single().currentObservation)
        fleet.shutdown()
        assertTrue(retry.delete())
        assertTrue(db.delete())
        assertTrue(held.renameTo(db))
        val reopened = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        assertEquals(1, reopened.history.value.size)
        assertEquals(1, LocalJobStore(db).currentObservedRecords().size)
        assertTrue(LocalJobStore(db).jobs().none { it.origin == LocalJobStore.Origin.LEGACY_UNKNOWN })
        reopened.shutdown()
    }

    @Test fun outboxRetryDoesNotClearAnUnrelatedObservationReadWarning() {
        val tmp = Files.createTempDirectory("observation-warning-owner").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        fleet.currentObservationProblem.value = "Committed machine observations could not be read: storage error"
        val record = JobRecord("observation", "p", "P", "part.gcode", 100, 200, "finished", null, null,
            currentObservation = true)
        JobHistoryStore(File(paths.data, "current-observation-retry.json")).prependPreserving(record)
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p", "P", "Test", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "fake"))
        fleet.recordEnd(entry, idle(PrinterState.OFFLINE))
        assertEquals("Committed machine observations could not be read: storage error", fleet.currentObservationProblem.value)
        assertEquals(1, LocalJobStore(File(paths.data, "jobs.sqlite")).currentObservedRecords().size)
        fleet.shutdown()
    }

    @Test fun committedMachineEvidenceIsRestoredAfterCrashBeforeJsonHistoryWrite() {
        val tmp = Files.createTempDirectory("fleet-history-reconcile").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val jobs = LocalJobStore(File(paths.data, "jobs.sqlite"))
        val job = jobs.createJob(LocalJobStore.Origin.APP, printerId = null)
        val active = jobs.bindQueuedStart("p", job.id, "part.gcode", 100)
        val observed = JobRecord("observation", "p", "P", "part.gcode", 100, 200, "finished", null, null)
        assertEquals(job.id to active.attemptId, jobs.recordCurrentObservation(observed))
        assertFalse(File(paths.data, "history.json").exists())
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        assertEquals(listOf(observed), fleet.history.value)
        assertEquals(listOf(observed), JobHistoryStore(File(paths.data, "history.json")).load())
        assertEquals(job.id, jobs.job(job.id)?.id)
        fleet.shutdown()
    }

    @Test fun committedObservationLeftInRetryOutboxDoesNotDuplicateMachineEvent() {
        val tmp = Files.createTempDirectory("fleet-committed-outbox").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val jobs = LocalJobStore(File(paths.data, "jobs.sqlite"))
        val job = jobs.createJob(LocalJobStore.Origin.APP, printerId = null)
        val active = jobs.bindQueuedStart("p", job.id, "part.gcode", 100)
        val observed = JobRecord("same-observation", "p", "P", "part.gcode", 100, 200, "finished", null, null)
        JobHistoryStore(File(paths.data, "current-observation-retry.json")).prependPreserving(observed)
        assertEquals(job.id to active.attemptId, jobs.recordCurrentObservation(observed))
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        assertEquals(listOf(observed), fleet.history.value)
        assertEquals(2, jobs.machineEvents(active.attemptId).size) // PRINTING then one terminal event.
        assertTrue(JobHistoryStore(File(paths.data, "current-observation-retry.json")).load().isEmpty())
        fleet.shutdown()
    }

    @Test fun transientJobDatabaseFailureRetriesAnObservedEnding() {
        val tmp = Files.createTempDirectory("fleet-observation-retry").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        fleet.notifier = { _, _ -> }
        val db = File(paths.data, "jobs.sqlite")
        val held = File(paths.data, "jobs-held.sqlite")
        assertTrue(db.renameTo(held))
        assertTrue(db.mkdir())
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p", "P", "Test", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "fake"))
        fleet.recordEnd(entry, printing("part.gcode", 0.5f))
        fleet.recordEnd(entry, finished("part.gcode"))
        assertEquals(1, JobHistoryStore(File(paths.data, "current-observation-retry.json")).load().size)
        assertTrue(db.delete())
        assertTrue(held.renameTo(db))
        fleet.recordEnd(entry, idle(PrinterState.READY))
        assertTrue(JobHistoryStore(File(paths.data, "current-observation-retry.json")).load().isEmpty())
        assertEquals(1, LocalJobStore(db).currentObservedRecords().size)
        fleet.shutdown()
    }
}
