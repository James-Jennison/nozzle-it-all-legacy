package com.nozzleitall.desktop

import com.nozzleitall.desktop.queue.PrintQueue
import com.nozzleitall.desktop.screens.queuedRemoteName
import com.nozzleitall.desktop.prepare.isSameQueuedPrint
import com.nozzleitall.desktop.prepare.PreparedSendIdentity
import com.nozzleitall.printer.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PrintQueueTest {
    private fun tmp() = Files.createTempDirectory("queue").toFile()
    private fun gcode(dir: File, text: String = "G28\n") = File.createTempFile("job", ".gcode", dir).apply { writeText(text) }

    @Test fun invalidDraftIdentityLeavesNoUnindexedFile() {
        val root = Files.createTempDirectory("queue-invalid-draft").toFile()
        try {
            val source = File(root, "fixture.gcode").apply { writeText("G28\n") }
            val queue = PrintQueue(File(root, "queue"))
            assertTrue(runCatching { queue.add("Fixture", source, null, draftPlateId = "bad-id") }.isFailure)
            assertTrue(queue.items().isEmpty())
            assertTrue(queue.problems().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun duplicateSendUsesPreparedOutputRatherThanInputModelDigest() {
        val d = tmp()
        val queue = PrintQueue(File(d, "q"))
        val inputDigest = "a".repeat(64)
        val first = queue.add("first slice", gcode(d, "G1 X1\n"), "printer", inputDigest = inputDigest,
            projectId = "project", projectRevision = 1, plateId = "plate", targetProfileId = "profile")
        val second = queue.add("changed settings", gcode(d, "G1 X2\n"), "printer", inputDigest = inputDigest,
            projectId = "project", projectRevision = 1, plateId = "plate", targetProfileId = "profile")
        assertNotEquals(first.sha256, second.sha256)
        fun same(digest: String?, printer: String = "printer", project: String? = "project") =
            isSameQueuedPrint(first, PreparedSendIdentity(printer, digest, project, 1, "plate", "profile", inputDigest, null))
        assertTrue(same(first.sha256))
        assertTrue(isSameQueuedPrint(PrintQueue(File(d, "q")).items().first(),
            PreparedSendIdentity("printer", first.sha256, "project", 1, "plate", "profile", inputDigest, null)))
        assertFalse(same(second.sha256))
        assertFalse(same(first.sha256, printer = "other-printer"))
        assertFalse(same(first.sha256, project = "other-project"))
        assertFalse(same(first.sha256, project = null))
        assertFalse(same(null))
    }

    @Test fun duplicateSendWithoutSavedProjectUsesInputAndEngineIdentity() {
        val d = tmp()
        val queue = PrintQueue(File(d, "q"))
        val inputDigest = "a".repeat(64)
        val engine = "b".repeat(64)
        val plate = java.util.UUID.randomUUID().toString()
        val first = queue.add("unsaved slice", gcode(d, "G1 X1\n"), "printer",
            targetProfileId = "profile", inputDigest = inputDigest, engineBuild = engine, draftPlateId = plate)
        val matching = PreparedSendIdentity("printer", first.sha256, null, null, null, "profile", inputDigest, engine, plate)
        assertTrue(isSameQueuedPrint(PrintQueue(File(d, "q")).items().single(), matching))
        assertFalse(isSameQueuedPrint(first, matching.copy(inputDigest = "c".repeat(64))))
        assertFalse(isSameQueuedPrint(first, matching.copy(engineBuild = "d".repeat(64))))
        assertFalse(isSameQueuedPrint(first, matching.copy(engineBuild = null)))
        assertFalse(isSameQueuedPrint(first, matching.copy(draftPlateId = java.util.UUID.randomUUID().toString())))
        assertFalse(isSameQueuedPrint(first, matching.copy(draftPlateId = null)))
        assertFalse(isSameQueuedPrint(first, matching.copy(projectId = "project", projectRevision = 1, plateId = "plate")))
    }

    @Test fun queueRejectsChangedPreparedBytesBeforePublishingAnItem() {
        val d = tmp()
        val source = gcode(d, "G1 X1\n")
        val queue = PrintQueue(File(d, "q"))
        val expected = queue.add("reference", source, "printer").sha256!!
        source.writeText("G1 X2\n")
        assertThrows(IllegalStateException::class.java) {
            queue.add("changed", source, "printer", expectedSha256 = expected)
        }
        assertEquals(1, queue.items().size)
    }

    @Test fun queuedRemoteNameIsUniqueRootGcodeAcceptedByOneStepUploadAdapters() {
        val d = tmp(); val q = PrintQueue(File(d, "q"))
        val first = queuedRemoteName(q.add("Cube / red", gcode(d), null))
        val second = queuedRemoteName(q.add("Cube / red", gcode(d), null))
        assertTrue(first.matches(Regex("[A-Za-z0-9._ -]{1,120}\\.(gcode|gco|g)", RegexOption.IGNORE_CASE)))
        assertFalse(first.contains('/'))
        assertFalse(first.contains('\\'))
        assertNotEquals(first, second)
    }

    @Test fun transfersToOnePrinterDoNotOverlapAndReleaseAfterFailure() = runBlocking {
        val t = tmp(); val fleet = Fleet(AppPaths(File(t, "c"), File(t, "d"), File(t, "k")).ensure(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        try {
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val first = async(kotlinx.coroutines.Dispatchers.Default) {
                fleet.withPrinterTransfer("p1") { entered.complete(Unit); release.await() }
            }
            entered.await()
            val overlapping = runCatching { fleet.withPrinterTransfer("p1") { "unexpected" } }
            assertTrue(overlapping.exceptionOrNull() is IllegalStateException)
            assertEquals("another printer remains available", "other", fleet.withPrinterTransfer("p2") { "other" })
            release.complete(Unit); first.await()
            assertEquals("lease is released", "retry", fleet.withPrinterTransfer("p1") { "retry" })
            assertTrue(runCatching { fleet.withPrinterTransfer("p1") { error("transfer failed") } }.isFailure)
            assertEquals("lease is released after error", "again", fleet.withPrinterTransfer("p1") { "again" })
            val queued = fleet.enqueue("held", gcode(t), null, emptyList())
            val queueEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val queueRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
            val transfer = async(kotlinx.coroutines.Dispatchers.Default) {
                fleet.withQueuedTransfer(queued.id, "p1") { queueEntered.complete(Unit); queueRelease.await() }
            }
            queueEntered.await()
            assertThrows(IllegalStateException::class.java) { fleet.retargetQueued(queued.id, "p2") }
            assertThrows(IllegalStateException::class.java) { fleet.moveInQueue(queued.id, 1) }
            assertThrows(IllegalStateException::class.java) { fleet.dequeue(queued.id) }
            queueRelease.complete(Unit); transfer.await()
            val target = PrinterIdentity("p2", "Fixture", "Fixture", PrinterFamily.KLIPPER, "http://127.0.0.1:1")
            fleet.printers["p2"] = PrinterEntry(PrinterConfig(target, "moonraker")).also {
                it.capabilities.value = Capabilities(uploadJob = true, startPrint = true)
            }
            fleet.retargetQueued(queued.id, "p2")
            assertEquals("p2", fleet.queue.value.single().printerId)
        } finally { fleet.shutdown() }
    }

    @Test fun anItemKeepsItsOwnCopyOfTheFileInOrderAndSurvivesARestart() {
        val d = tmp(); val src = gcode(d, "G1 X1\n")
        val q = PrintQueue(File(d, "q"))
        val a = q.add("Possum", src, "p1", listOf(0, 1)); val b = q.add("Cube", gcode(d), null)
        src.delete()   // the original can go away
        assertEquals("G1 X1\n", q.file(a).readText())
        assertEquals(listOf("Possum", "Cube"), q.items().map { it.name })
        val again = PrintQueue(File(d, "q"))
        assertEquals(listOf(a.id, b.id), again.items().map { it.id }); assertEquals(listOf(0, 1), again.items()[0].toolheadMap); assertNull(again.items()[1].printerId)
    }

    @Test fun reprintSourceIdentitySurvivesQueueRestartWithoutDispatch() {
        val d = tmp()
        val sourceJobId = java.util.UUID.randomUUID().toString()
        val q = PrintQueue(File(d, "q"))
        val row = q.add("review me", gcode(d), "p1", sourceJobId = sourceJobId)
        assertEquals(sourceJobId, PrintQueue(File(d, "q")).items().single().sourceJobId)
        assertFalse(row.startUncertain)
    }

    @Test fun moveRemoveAndRetarget() {
        val d = tmp(); val q = PrintQueue(File(d, "q"))
        val a = q.add("A", gcode(d), null); val b = q.add("B", gcode(d), null); val c = q.add("C", gcode(d), null)
        q.move(c.id, -2); assertEquals(listOf("C", "A", "B"), q.items().map { it.name })
        q.move(c.id, -5); assertEquals("stays inside the list", listOf("C", "A", "B"), q.items().map { it.name })
        q.retarget(a.id, "p9"); assertEquals("p9", q.items().first { it.id == a.id }.printerId)
        val file = q.file(b); q.remove(b.id)
        assertFalse("removing deletes the stored file", file.exists()); assertEquals(listOf("C", "A"), q.items().map { it.name })
    }

    @Test fun theNextItemForAPrinterIsItsOwnOrAnyOneInOrder() {
        val d = tmp(); val q = PrintQueue(File(d, "q"))
        q.add("for-other", gcode(d), "other"); val any = q.add("any", gcode(d), null); q.add("for-me", gcode(d), "me")
        assertEquals(any.id, q.nextFor("me")!!.id)
        q.remove(any.id); assertEquals("for-me", q.nextFor("me")!!.name); assertEquals("for-other", q.nextFor("other")!!.name)
        assertNull(q.nextFor("nobody"))
    }

    @Test fun aRemovedPrintersItemsFallBackToAny() {
        val d = tmp(); val q = PrintQueue(File(d, "q"))
        q.add("x", gcode(d), "gone"); q.forgetPrinter("gone"); assertNull(q.items()[0].printerId)
    }

    @Test fun anIndexedItemWhoseFileIsMissingIsRetainedAndCannotBeSelected() {
        val d = tmp(); val q = PrintQueue(File(d, "q")); val a = q.add("x", gcode(d), null)
        q.file(a).delete()
        val reopened = PrintQueue(File(d, "q"))
        assertEquals(listOf(a.id), reopened.items().map { it.id })
        assertTrue(reopened.problems().single().contains(a.id))
        assertNull(reopened.nextFor("p1"))
    }

    @Test fun aTamperedQueuedFileCannotBeSelectedAndLegacyIndexRemainsReadable() {
        val d = tmp(); val q = PrintQueue(File(d, "q")); val a = q.add("x", gcode(d), null)
        q.file(a).appendText("G1 X999\n")
        val reopened = PrintQueue(File(d, "q"))
        assertEquals(a.id, reopened.items().single().id)
        assertTrue(reopened.problems().single().contains("digest"))
        assertNull(reopened.nextFor("p1"))
        assertThrows(IllegalStateException::class.java) { reopened.verifiedFile(a.id) }
    }

    @Test fun legacyQueueRowsWithoutDigestsStayVisibleButCannotStart() {
        val d = tmp(); val folder = File(d, "q"); val queue = PrintQueue(folder)
        val item = queue.add("old", gcode(d), "p")
        val index = File(folder, "queue.json")
        val rows = org.json.JSONArray(index.readText())
        rows.getJSONObject(0).remove("sha256")
        index.writeText(rows.toString())
        val reopened = PrintQueue(folder)
        assertEquals(item.id, reopened.items().single().id)
        assertTrue(reopened.problems().any { it.contains("Unverified legacy") })
        assertNull(reopened.nextFor("p"))
        assertThrows(IllegalStateException::class.java) { reopened.verifiedFile(item.id) }
    }

    @Test fun invalidIndexFailsClosedWithoutOverwritingIt() {
        val d = tmp(); val folder = File(d, "q").apply { mkdirs() }
        val index = File(folder, "queue.json").apply { writeText("not JSON") }
        val original = index.readBytes()
        val queue = PrintQueue(folder)
        assertTrue(queue.items().isEmpty())
        assertTrue(queue.problems().single().contains("cannot be read"))
        val failure = assertThrows(IllegalStateException::class.java) { queue.add("new", gcode(d), null) }
        assertTrue(failure.message!!.contains("Recheck queue"))
        assertFalse(failure.message!!.contains("not JSON"))
        assertArrayEquals(original, index.readBytes())
        repeat(2) {
            assertTrue(queue.items().isEmpty())
            assertTrue(queue.problems().single().contains("cannot be read"))
            assertArrayEquals(original, index.readBytes())
        }
        index.writeText("[]")
        assertTrue(queue.problems().isEmpty())
        assertTrue(queue.items().isEmpty())
        queue.add("recovered", gcode(d), null)
        assertEquals(1, queue.items().size)
    }

    @Test fun fleetRecheckRestoresTheQueueAfterAnIndexRepair() {
        val d = tmp(); val paths = AppPaths(File(d, "config"), File(d, "data"), File(d, "cache"))
        val folder = File(paths.data, "queue")
        val originalItem = PrintQueue(folder).add("restored", gcode(d), null)
        val index = File(folder, "queue.json")
        val healthyIndex = index.readBytes()
        index.writeText("not JSON")
        val corruptIndex = index.readBytes()
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        try {
            assertTrue(fleet.printers.isEmpty())
            assertTrue(fleet.queue.value.isEmpty())
            assertTrue(fleet.queueRecoveryProblems.value.single().contains("cannot be read"))
            kotlinx.coroutines.runBlocking { fleet.recheckQueueFromUi() }
            assertArrayEquals(corruptIndex, index.readBytes())
            assertTrue(fleet.queue.value.isEmpty())
            assertTrue(fleet.queueRecoveryProblems.value.single().contains("cannot be read"))
            assertFalse(fleet.queueRechecking.value)
            index.writeBytes(healthyIndex)
            kotlinx.coroutines.runBlocking { fleet.recheckQueueFromUi() }
            assertEquals(originalItem.id, fleet.queue.value.single().id)
            assertTrue(fleet.queueRecoveryProblems.value.isEmpty())
            assertFalse(fleet.queueRechecking.value)
        } finally { fleet.shutdown() }
    }

    @Test fun twoQueueInstancesCannotOverwriteEachOthersAdditions() {
        val d = tmp(); val folder = File(d, "q")
        val first = PrintQueue(folder); val second = PrintQueue(folder)
        val a = first.add("A", gcode(d), null)
        val b = second.add("B", gcode(d), null)
        assertEquals(listOf(a.id, b.id), PrintQueue(folder).items().map { it.id })
        first.retarget(a.id, "p")
        assertEquals(listOf(a.id, b.id), PrintQueue(folder).items().map { it.id })
    }

    @Test fun confirmedStartIntentSurvivesRestartAndBlocksDuplicateStart() {
        val d = tmp(); val folder = File(d, "q")
        val first = PrintQueue(folder)
        val item = first.add("A", gcode(d), "p", pendingLink = true)
        val jobId = java.util.UUID.randomUUID().toString()
        val artifactId = java.util.UUID.randomUUID().toString()
        first.link(item.id, jobId, artifactId)
        first.markStartUncertain(item.id, "p", "uploaded/exact-path.gcode")
        val reopened = PrintQueue(folder)
        assertTrue(reopened.items().single().startUncertain)
        assertEquals("p", reopened.items().single().startPrinterId)
        assertEquals("uploaded/exact-path.gcode", reopened.items().single().startRemotePath)
        assertThrows(IllegalStateException::class.java) {
            reopened.confirmStarted(item.id, "p", jobId, "invented.gcode") { }
        }
        assertEquals("uploaded/exact-path.gcode", PrintQueue(folder).items().single().startRemotePath)
        assertNull(reopened.nextFor("p"))
        assertThrows(IllegalStateException::class.java) { reopened.verifiedFile(item.id) }
        assertThrows(IllegalStateException::class.java) { reopened.remove(item.id) }
        reopened.clearStartUncertain(item.id, "p")
        assertNull(PrintQueue(folder).items().single().startRemotePath)
        assertEquals(item.id, PrintQueue(folder).nextFor("p")?.id)
    }

    @Test fun pathlessLegacyHoldNeedsAnExplicitlyObservedRemotePath() {
        val d = tmp(); val queue = PrintQueue(File(d, "q"))
        val item = queue.add("A", gcode(d), "p", pendingLink = true)
        val jobId = java.util.UUID.randomUUID().toString()
        queue.link(item.id, jobId, java.util.UUID.randomUUID().toString())
        queue.markStartUncertain(item.id, "p")
        assertThrows(IllegalStateException::class.java) {
            queue.confirmStarted(item.id, "p", jobId, "guessed.gcode") { }
        }
        assertTrue(queue.items().single().startUncertain)
        var bound = false
        queue.confirmStarted(item.id, "p", jobId, "printer-reported.gcode", legacyPathObserved = true) { bound = true }
        assertTrue(bound)
        assertTrue(queue.items().isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            com.nozzleitall.desktop.queue.QueueItem.fromJson(item.toJson().put("startRemotePath", "invalid.gcode"))
        }
    }

    @Test fun retargetBeforeStartIntentCannotHoldTheWrongPrinter() {
        val d = tmp(); val queue = PrintQueue(File(d, "q"))
        val item = queue.add("A", gcode(d), "p1", pendingLink = true)
        queue.link(item.id, java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString())
        queue.retarget(item.id, "p2")
        assertThrows(IllegalStateException::class.java) { queue.markStartUncertain(item.id, "p1") }
        assertFalse(queue.items().single().startUncertain)
        assertEquals("p2", queue.items().single().printerId)
        queue.markStartUncertain(item.id, "p2")
        assertEquals("p2", queue.items().single().startPrinterId)
        assertThrows(IllegalStateException::class.java) { queue.retarget(item.id, "p1") }
    }

    @Test fun reviewedRowCannotTransferOrStartAfterAnotherQueueChangesIt() {
        val d = tmp()
        val folder = File(d, "q")
        val first = PrintQueue(folder)
        val item = first.add("A", gcode(d), null, pendingLink = true)
        val reviewed = first.link(item.id, java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString())
        val second = PrintQueue(folder)
        second.retarget(item.id, "p")
        assertThrows(IllegalStateException::class.java) { first.verifiedFile(item.id, expectedItem = reviewed) }
        assertThrows(IllegalStateException::class.java) {
            first.markStartUncertain(item.id, "p", "reviewed.gcode", expectedItem = reviewed)
        }
        assertFalse(second.items().single().startUncertain)
        val current = second.items().single()
        assertTrue(first.verifiedFile(item.id, expectedItem = current).isFile)
        assertTrue(first.markStartUncertain(item.id, "p", "reviewed.gcode", expectedItem = current).startUncertain)
    }

    @Test fun tamperedQueueBytesCannotCreateAHoldWithUnchangedRowMetadata() {
        val d = tmp()
        val queue = PrintQueue(File(d, "q"))
        val item = queue.add("A", gcode(d), "p", pendingLink = true)
        val reviewed = queue.link(item.id, java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString())
        queue.file(item).writeText("G1 X9\n")
        assertEquals(reviewed, queue.items().single())
        assertThrows(IllegalStateException::class.java) {
            queue.markStartUncertain(item.id, "p", "same.gcode", expectedItem = reviewed)
        }
        assertFalse(queue.items().single().startUncertain)
    }

    @Test fun oldResolutionCannotClearOrBindANewerHoldOnTheSameJobAndPath() {
        val d = tmp()
        val folder = File(d, "q")
        val first = PrintQueue(folder)
        val second = PrintQueue(folder)
        val item = first.add("A", gcode(d), "p", pendingLink = true)
        val jobId = java.util.UUID.randomUUID().toString()
        val ready = first.link(item.id, jobId, java.util.UUID.randomUUID().toString())
        val old = first.markStartUncertain(item.id, "p", "same.gcode", expectedItem = ready)
        second.clearStartUncertain(item.id, "p", expectedHold = old)
        val current = second.markStartUncertain(item.id, "p", "same.gcode", expectedItem = ready)
        assertNotEquals(old.startHoldId, current.startHoldId)
        assertThrows(IllegalStateException::class.java) { first.clearStartUncertain(item.id, "p", expectedHold = old) }
        assertThrows(IllegalStateException::class.java) {
            first.confirmStarted(item.id, "p", jobId, "same.gcode", expectedHold = old) { fail("Stale hold was bound") }
        }
        assertEquals(current, first.items().single())
        first.confirmStarted(item.id, "p", jobId, "same.gcode", expectedHold = current) { }
        assertTrue(second.items().isEmpty())
    }

    @Test fun onlyOneQueuedStartCanBeHeldForAPrinter() {
        val d = tmp(); val queue = PrintQueue(File(d, "q"))
        val first = queue.add("A", gcode(d), null, pendingLink = true)
        val second = queue.add("B", gcode(d), null, pendingLink = true)
        listOf(first, second).forEach {
            queue.link(it.id, java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString())
        }
        queue.markStartUncertain(first.id, "p")
        assertThrows(IllegalStateException::class.java) { queue.markStartUncertain(second.id, "p") }
        assertFalse(queue.items().first { it.id == second.id }.startUncertain)
        queue.clearStartUncertain(first.id, "p")
        queue.markStartUncertain(second.id, "p")
        assertEquals("p", queue.items().first { it.id == second.id }.startPrinterId)
    }

    @Test fun separateQueueInstancesRespectTheHeldAndBoundPrinter() {
        val d = tmp(); val folder = File(d, "q")
        val firstView = PrintQueue(folder)
        val a = firstView.add("A", gcode(d), null, pendingLink = true)
        val b = firstView.add("B", gcode(d), null, pendingLink = true)
        listOf(a, b).forEach {
            firstView.link(it.id, java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString())
        }
        val secondView = PrintQueue(folder)
        firstView.markStartUncertain(a.id, "p", "exact.gcode")
        assertThrows(IllegalStateException::class.java) { secondView.markStartUncertain(b.id, "p") }
        var active = false
        firstView.confirmStarted(a.id, "p", firstView.items().first { it.id == a.id }.jobId!!, "exact.gcode") { active = true }
        assertThrows(IllegalStateException::class.java) {
            secondView.markStartUncertain(b.id, "p") { !active }
        }
        assertFalse(secondView.items().first { it.id == b.id }.startUncertain)
    }

    @Test fun aPrinterThatFinishesWithSomethingQueuedIsAnnouncedOnceAndOnlyWhenWorkingBefore() {
        val t = tmp(); val paths = AppPaths(File(t, "c"), File(t, "d"), File(t, "k")).ensure()
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        val sent = mutableListOf<Pair<String, String>>(); fleet.notifier = { a, b -> sent += a to b }
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p1", "CC1", "Elegoo CC1", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "moonraker"))
        val ready = PrinterStatus(PrinterState.READY, ConnectionRoute.LAN)
        val busy = PrinterStatus(PrinterState.PRINTING, ConnectionRoute.LAN, job = JobProgress("a.gcode", 0.5f))
        fleet.enqueue("Possum", gcode(t), "p1", emptyList())
        fleet.noteFree(entry, ready)                  // idle at start: nothing to say
        assertTrue(sent.isEmpty())
        fleet.noteFree(entry, busy); fleet.noteFree(entry, PrinterStatus(PrinterState.OFFLINE, ConnectionRoute.LAN)); fleet.noteFree(entry, ready)
        assertEquals(1, sent.size); assertEquals("CC1 is free", sent[0].first); assertTrue(sent[0].second.contains("Possum"))
        fleet.noteFree(entry, ready); assertEquals("not again while it stays free", 1, sent.size)
        fleet.shutdown()
    }
}
