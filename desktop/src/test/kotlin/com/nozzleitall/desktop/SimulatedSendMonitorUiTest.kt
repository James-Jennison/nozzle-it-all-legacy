package com.nozzleitall.desktop

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nozzleitall.desktop.jobs.LocalJobStore
import com.nozzleitall.desktop.prepare.GcodePreview
import com.nozzleitall.desktop.prepare.PreparedMaterialPlan
import com.nozzleitall.desktop.queue.PrintQueue
import com.nozzleitall.desktop.prepare.SliceOutcome
import com.nozzleitall.desktop.prepare.SliceState
import com.nozzleitall.desktop.prepare.SliceStats
import com.nozzleitall.project.ProjectManifest
import com.nozzleitall.desktop.screens.MonitorScreen
import com.nozzleitall.desktop.screens.QueuePanel
import com.nozzleitall.desktop.ui.NozzleTheme
import com.nozzleitall.printer.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SimulatedSendMonitorUiTest {
    @get:Rule val compose = createComposeRule()

    private class FakeSession(
        override val capabilities: Capabilities = Capabilities(uploadJob = true, startPrint = true),
        private val bundle: File? = null,
    ) : PrinterSession {
        override val identity = PrinterIdentity("fake", "Simulated printer", "Fixture", PrinterFamily.KLIPPER,
            "http://127.0.0.1:1", profileId = "fixture")
        @Volatile var reading = PrinterStatus(PrinterState.READY, ConnectionRoute.LAN, observedAtMillis = 1000)
        @Volatile var failStatus = false
        @Volatile var uploads = 0
        @Volatile var starts = 0
        @Volatile var beforeUploadReturn: (() -> Unit)? = null
        val remoteNames = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun status(): PrinterStatus {
            check(!failStatus) { "simulated status transport failure" }
            return reading
        }
        override fun cameras() = emptyList<CameraEndpoint>()
        @Volatile var cameraFrame: ByteArray? = null
        override fun snapshot(camera: CameraEndpoint): ByteArray = cameraFrame ?: error("Simulated camera failure")
        override fun upload(file: File, remoteName: String, progress: UploadProgress): UploadResult {
            check(file.readText() == "G28\n")
            remoteNames += remoteName
            uploads++
            beforeUploadReturn?.invoke()
            return if (uploads == 1) UploadResult.Interrupted("simulated disconnect")
                else UploadResult.Uploaded("remote/$remoteName")
        }
        override fun perform(action: PrinterAction): ActionOutcome {
            when (action) {
                is PrinterAction.StartJob -> check(capabilities.startPrint && action.remotePath.startsWith("remote/"))
                is PrinterAction.UploadAndStart -> {
                    check(capabilities.uploadAndStart)
                    val file = File(action.localPath)
                    if (bundle == null) check(file.readText() == "G28\n")
                    else {
                        check(file.name.endsWith(".gcode.3mf") && action.remoteName.endsWith(".gcode.3mf"))
                        check(java.nio.file.Files.mismatch(file.toPath(), bundle.toPath()) == -1L)
                    }
                }
                else -> error("Unexpected simulated command")
            }
            starts++
            return ActionOutcome.Unknown("simulated lost acknowledgement")
        }
        override fun close() {}
    }

    private fun awaitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun queuedDestinationPickerAppliesOnlyTheChosenCompatiblePrinter() {
        val root = Files.createTempDirectory("nia-queue-destination").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
            val session = FakeSession()
            val matching = session.identity
            val wrong = matching.copy(id = "other", displayName = "Wrong profile fixture", profileId = "other")
            val unknown = matching.copy(id = "unknown", displayName = "Unknown capability fixture")
            for (identity in listOf(matching, wrong, unknown)) {
                app.fleet.printers[identity.id] = PrinterEntry(PrinterConfig(identity, "moonraker")).also {
                    if (identity != unknown) it.capabilities.value = session.capabilities
                }
            }
            app.fleet.order.value = listOf(matching.id, wrong.id, unknown.id)
            val source = File(root, "fixture.gcode").apply { writeText("G28\n") }
            val item = app.fleet.enqueue("Fixture cube", source, null, emptyList(), targetProfileId = "fixture")
            compose.setContent { NozzleTheme("dark") { QueuePanel(app) } }
            compose.onNodeWithContentDescription("Job destination, Any compatible printer").performClick()
            compose.onNodeWithContentDescription("Job destination option Wrong profile fixture").assertDoesNotExist()
            compose.onNodeWithContentDescription("Job destination option Unknown capability fixture").assertDoesNotExist()
            assertThrows(IllegalStateException::class.java) { app.fleet.retargetQueued(item.id, wrong.id) }
            assertThrows(IllegalStateException::class.java) { app.fleet.retargetQueued(item.id, unknown.id) }
            compose.onNodeWithContentDescription("Job destination option Simulated printer").performClick()
            compose.runOnIdle {
                val chosen = app.fleet.queue.value.single()
                assertEquals(matching.id, chosen.printerId)
                assertEquals(item.sha256, chosen.sha256)
                assertEquals(item.toolheadMap, chosen.toolheadMap)
                assertThrows(IllegalStateException::class.java) {
                    app.fleet.retargetQueued(item.id, null, expectedItem = item)
                }
                assertEquals(matching.id, app.fleet.queue.value.single().printerId)
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test fun cameraSnapshotFailureAndRecoveryKeepTheSameJobVisible() {
        val root = Files.createTempDirectory("nia-camera-recovery").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
            val session = FakeSession()
            val entry = PrinterEntry(PrinterConfig(session.identity, "moonraker"))
            entry.session = session
            entry.capabilities.value = session.capabilities.copy(camera = true)
            entry.status.value = session.reading.copy(state = PrinterState.PRINTING, job = JobProgress("fixture.gcode", 0.25f))
            entry.cameras.value = listOf(CameraEndpoint("fixture", "Fixture camera", CameraKind.SNAPSHOT,
                "http://127.0.0.1:1"))
            app.fleet.printers[session.identity.id] = entry
            app.fleet.order.value = listOf(session.identity.id)
            app.openPrinter(session.identity.id)
            val frame = java.io.ByteArrayOutputStream().also {
                javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
            }.toByteArray()
            session.cameraFrame = frame
            compose.setContent { NozzleTheme("dark") { MonitorScreen(app) } }
            fun awaitImage(label: String) = compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("$label of Simulated printer").fetchSemanticsNodes().isNotEmpty()
            }
            awaitImage("Camera image")
            session.cameraFrame = null
            awaitImage("Last available camera image")
            awaitText("The camera snapshot is unavailable. Reconnecting")
            compose.onNodeWithText("fixture.gcode").assertIsDisplayed()
            session.cameraFrame = frame
            awaitImage("Camera image")
            compose.onNodeWithText("fixture.gcode").assertIsDisplayed()
            assertEquals(0, session.uploads)
            assertEquals(0, session.starts)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test fun interruptedUploadRetriesAndUnknownStartStaysHeldAcrossMonitorReconnect() {
        val root = Files.createTempDirectory("nia-simulated-send").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(paths, scope)
        val session = FakeSession()
        val entry = PrinterEntry(PrinterConfig(session.identity, "fake"))
        val guard = ActionGuard(session) { 1000 }
        try {
            entry.session = session
            entry.guard = guard
            entry.capabilities.value = session.capabilities
            entry.status.value = session.status()
            app.fleet.printers[session.identity.id] = entry
            app.fleet.order.value = listOf(session.identity.id)
            val source = File(root, "fixture.gcode").apply { writeText("G28\n") }
            val matching = Toolhead(0, nozzleDiameterMm = 0.4, loaded = true, material = Material(type = "PLA"))
            entry.status.value = session.status().copy(toolheads = listOf(matching.copy(nozzleDiameterMm = 0.6)))
            val plan = PreparedMaterialPlan(listOf(PreparedMaterialPlan.Tool(0, 0.4, "PLA", null)), incomplete = false)
            val row = app.fleet.enqueue("Fixture cube", source, session.identity.id, emptyList(),
                targetProfileId = "fixture", engineBuild = "a".repeat(64), inputDigest = "b".repeat(64), materialPlan = plan)
            assertEquals(plan, PrintQueue(File(paths.data, "queue")).items().single().materialPlan)
            compose.setContent { NozzleTheme("dark") { QueuePanel(app) } }
            awaitText("reports a 0.6 mm nozzle")
            compose.onNodeWithTag("queue-review-${row.id}").assertIsNotEnabled()
            assertEquals(0, session.uploads)
            compose.runOnIdle { entry.status.value = session.status().copy(toolheads = listOf(matching)) }
            fun review() = compose.onNodeWithTag("queue-review-${row.id}").performSemanticsAction(SemanticsActions.OnClick)
            fun confirm() = compose.onNodeWithTag("confirm-action").performSemanticsAction(SemanticsActions.OnClick)
            review()
            compose.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
            assertEquals(0, session.uploads)
            review()
            val replacement = FakeSession()
            compose.runOnIdle {
                entry.session = replacement
                entry.guard = ActionGuard(replacement) { 1000 }
            }
            confirm()
            awaitText("The queued job or printer changed")
            assertEquals(0, session.uploads)
            assertEquals(0, replacement.uploads)
            compose.runOnIdle {
                entry.session = session
                entry.guard = guard
            }
            review()
            confirm()
            awaitText("simulated disconnect")
            assertEquals(0, session.starts)
            review()
            confirm()
            awaitText("Start printing on Simulated printer?")
            assertEquals(2, session.uploads)
            assertEquals(0, session.starts)
            compose.runOnIdle {
                entry.session = replacement
                entry.guard = ActionGuard(replacement) { 1000 }
            }
            confirm()
            awaitText("The queued job or printer changed; review it again")
            assertFalse(app.fleet.queue.value.single().startUncertain)
            assertEquals(0, replacement.starts)
            assertEquals(0, session.starts)
            compose.runOnIdle {
                entry.session = session
                entry.guard = guard
            }
            review()
            confirm()
            awaitText("Start printing on Simulated printer?")
            assertEquals(3, session.uploads)
            confirm()
            awaitText("Start outcome is unknown")
            assertEquals(1, session.starts)
            assertTrue(guard.needsReconcile)
            val held = app.fleet.queue.value.single()
            assertTrue(held.startUncertain)
            assertTrue(held.startRemotePath!!.startsWith("remote/"))

            fun checkPrinter() = compose.onAllNodesWithText("Check printer")[0].performSemanticsAction(SemanticsActions.OnClick)
            session.failStatus = true
            checkPrinter()
            awaitText("Commands stay blocked")
            assertTrue(guard.needsReconcile)
            session.failStatus = false
            session.reading = PrinterStatus(PrinterState.OFFLINE, ConnectionRoute.LAN, observedAtMillis = 1001)
            checkPrinter()
            awaitText("can't be checked (Offline)")
            assertTrue(guard.needsReconcile)
            session.reading = PrinterStatus(PrinterState.PRINTING, ConnectionRoute.LAN,
                job = JobProgress(requireNotNull(held.startRemotePath), 0.25f), observedAtMillis = 999)
            checkPrinter()
            awaitText("can't be checked (Printing)")
            assertTrue(guard.needsReconcile)
            session.reading = session.reading.copy(observedAtMillis = 1002)
            checkPrinter()
            awaitText("Checked: the printer is printing")
            assertFalse(guard.needsReconcile)
            assertTrue(app.fleet.queue.value.single().startUncertain)
            compose.onNodeWithText("Confirm it started").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("I checked this printer's current and recent jobs and confirmed this print started.").performSemanticsAction(SemanticsActions.OnClick)
            confirm()
            compose.waitUntil(15_000) { app.fleet.queue.value.isEmpty() }
            compose.runOnIdle {
                app.fleet.recordEnd(entry, session.reading)
                app.fleet.recordEnd(entry, session.reading.copy(state = PrinterState.FINISHED, observedAtMillis = 1003))
            }
            assertEquals(1, session.starts)
            val jobs = LocalJobStore(File(paths.data, "jobs.sqlite"))
            assertEquals(1, jobs.attempts(requireNotNull(row.jobId)).size)
            assertNull(jobs.activeQueuedAttempt(session.identity.id))
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }
    @Test fun directSendBlocksReportedMismatchAndOnlyHandsOffAfterUpload() {
        val root = Files.createTempDirectory("nia-simulated-direct-send").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val session = FakeSession()
        val entry = PrinterEntry(PrinterConfig(session.identity, "fake"))
        val expected = Material(type = "PLA", fromTag = true, spoolId = "sliced-spool")
        val matching = Toolhead(0, nozzleDiameterMm = 0.4, loaded = true, material = expected)
        val second = Toolhead(1, nozzleDiameterMm = 0.6, loaded = true, material = Material(type = "PETG"))
        try {
            app.finishFirstRun(SlicingSetup("snapmaker_u1", "Snapmaker U1", "0.4", toolheads = 4))
            entry.session = session
            entry.guard = ActionGuard(session) { 1000 }
            entry.capabilities.value = session.capabilities.copy(toolheadState = true, materialState = true)
            entry.status.value = session.reading.copy(toolheads = listOf(matching.copy(nozzleDiameterMm = 0.6,
                material = expected.copy(type = "PETG", spoolId = "different-spool")), second))
            app.fleet.printers[session.identity.id] = entry
            app.fleet.order.value = listOf(session.identity.id)
            app.prepare.printerId = session.identity.id
            app.destination = Destination.PREPARE
            val source = File(root, "fixture.gcode").apply { writeText("G28\n") }
            val preview = GcodePreview(listOf(GcodePreview.Layer(0.2f, floatArrayOf(0f, 0f, 1f, 1f, 1f, 1f, 2f, 2f),
                byteArrayOf(0, 1), floatArrayOf())), truncated = false)
            app.prepare.slice = SliceState.Done(
                SliceOutcome.Done(source, SliceStats(1, 10.0, 1.0, 1.0, 0), emptyList(), nozzleDiameters = listOf(0.4, 0.6)),
                preview, targetProfileId = "fixture",
                materials = listOf(ProjectManifest.MaterialSlot(1, "PLA", toolhead = 0, spoolId = "sliced-spool", fromTag = true),
                    ProjectManifest.MaterialSlot(2, "PETG", toolhead = 1)))
            app.prepare.showPreview = true
            compose.setContent { NozzleTheme("dark") { App(app) } }
            compose.onNodeWithTag("open-send").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("upload-only").performSemanticsAction(SemanticsActions.OnClick)
            awaitText("reports a 0.6 mm nozzle")
            awaitText("reports PETG")
            awaitText("The RFID spool")
            compose.onNodeWithTag("send-readiness").assertIsNotEnabled()
            assertEquals(0, session.uploads)
            compose.runOnIdle { entry.status.value = session.reading.copy(toolheads = listOf(matching, second)) }
            compose.onNodeWithTag("send-readiness").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag("send") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            source.writeText("G1 X9\n")
            compose.onNodeWithTag("send").performSemanticsAction(SemanticsActions.OnClick)
            awaitText("The prepared file changed after review")
            assertEquals(0, session.uploads)
            source.writeText("G28\n")
            compose.onNodeWithTag("send").performSemanticsAction(SemanticsActions.OnClick)
            awaitText("simulated disconnect")
            assertNull(app.monitorHandoff)
            val replacement = FakeSession()
            session.beforeUploadReturn = {
                entry.session = replacement
                entry.guard = ActionGuard(replacement) { 1000 }
            }
            compose.onNodeWithTag("send").performSemanticsAction(SemanticsActions.OnClick)
            awaitText("The printer connection changed during the transfer")
            assertNull(app.monitorHandoff)
            assertEquals(0, replacement.uploads)
            compose.runOnIdle {
                session.beforeUploadReturn = null
                entry.session = session
                entry.guard = ActionGuard(session) { 1000 }
                entry.status.value = session.reading.copy(toolheads = emptyList())
            }
            awaitText("I checked the printer · Continue")
            compose.onNodeWithTag("send-readiness").performSemanticsAction(SemanticsActions.OnClick)
            compose.runOnIdle { entry.status.value = session.reading.copy(toolheads = listOf(matching, second)) }
            compose.onNodeWithTag("send").assertDoesNotExist()
            compose.onNodeWithTag("send-readiness").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("send").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(15_000) { app.monitorHandoff?.state == MonitorHandoffState.UPLOADED }
            assertEquals(Destination.MONITOR, app.destination)
            assertEquals(3, session.uploads)
            assertEquals(1, session.remoteNames.distinct().size)
            assertEquals(0, session.starts)
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }

    @Test fun previewStartsInContextAndRecoversLostAcknowledgmentThroughMonitor() {
        val root = Files.createTempDirectory("nia-direct-print").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val session = FakeSession(Capabilities(uploadAndStart = true, acceptedOutputs = setOf("gcode")))
        val entry = PrinterEntry(PrinterConfig(session.identity, "fake"))
        try {
            app.finishFirstRun(SlicingSetup("snapmaker_u1", "Snapmaker U1", "0.4", toolheads = 4))
            entry.session = session
            entry.guard = ActionGuard(session) { 1000 }
            entry.capabilities.value = session.capabilities
            entry.status.value = session.status()
            app.fleet.printers[session.identity.id] = entry
            app.fleet.order.value = listOf(session.identity.id)
            app.prepare.printerId = session.identity.id
            app.destination = Destination.PREPARE
            val source = File(root, "fixture.gcode").apply { writeText("G28\n") }
            val preview = GcodePreview(listOf(GcodePreview.Layer(0.2f, floatArrayOf(0f, 0f, 1f, 1f),
                byteArrayOf(0), floatArrayOf())), truncated = false)
            app.prepare.slice = SliceState.Done(
                SliceOutcome.Done(source, SliceStats(1, 10.0, 1.0, 1.0, 0), emptyList()),
                preview, targetProfileId = "fixture")
            app.prepare.showPreview = true
            compose.setContent { NozzleTheme("dark") { App(app) } }
            compose.onNodeWithTag("open-send").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag("print-plate-review") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            fun review() {
                compose.onNodeWithTag("print-plate-review").performSemanticsAction(SemanticsActions.OnClick)
                awaitText("Continue to Start")
                compose.onNodeWithText("I checked that this file, printer profile, and loaded materials are compatible.")
                    .performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithTag("confirm-action").performSemanticsAction(SemanticsActions.OnClick)
                awaitText("Start printing on Simulated printer?")
                assertEquals(Destination.PREPARE, app.destination)
                assertEquals(0, session.starts)
            }
            review()
            compose.onNodeWithText("Don't send").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(15_000) { app.fleet.queue.value.isEmpty() }
            assertEquals(0, session.starts)
            review()
            compose.onNodeWithTag("confirm-action").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(15_000) { app.destination == Destination.MONITOR }
            assertEquals(session.identity.id, app.selectedPrinter)
            assertEquals(1, session.starts)
            assertTrue(app.fleet.queue.value.single().startUncertain)
            compose.onNodeWithTag("monitor-queue-history").performSemanticsAction(SemanticsActions.OnClick)
            awaitText("Confirm it started")
            assertEquals(1, session.starts)
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }

    @Test fun combinedUploadAndStartOnlyDispatchesAfterSeparateStartConfirmation() = combinedStart(null)

    @Test fun nativeBambuBundleReachesOnlyTheSeparatelyConfirmedCombinedStart() {
        val path = System.getenv("NIA_TEST_BAMBU_BUNDLE")
        org.junit.Assume.assumeTrue("Requires the native GTHost Bambu bundle fixture", !path.isNullOrBlank())
        combinedStart(File(requireNotNull(path)))
    }

    private fun combinedStart(bundle: File?) {
        val root = Files.createTempDirectory("nia-simulated-combined").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val output = if (bundle == null) "gcode" else "gcode.3mf"
        val session = FakeSession(Capabilities(uploadAndStart = true, acceptedOutputs = setOf(output)), bundle)
        val entry = PrinterEntry(PrinterConfig(session.identity, "fake"))
        try {
            entry.session = session
            entry.guard = ActionGuard(session) { 1000 }
            entry.capabilities.value = session.capabilities
            entry.status.value = session.status()
            app.fleet.printers[session.identity.id] = entry
            app.fleet.order.value = listOf(session.identity.id)
            val source = bundle ?: File(root, "fixture.gcode").apply { writeText("G28\n") }
            val row = app.fleet.enqueue("Combined fixture", source, session.identity.id, emptyList(),
                targetProfileId = "fixture", engineBuild = "a".repeat(64), inputDigest = "b".repeat(64))
            assertTrue(row.fileName.endsWith(".$output"))
            assertTrue(app.fleet.queuedFile(row).name.endsWith(".$output"))
            compose.setContent { NozzleTheme("dark") { QueuePanel(app) } }
            compose.onNodeWithTag("queue-review-${row.id}").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("confirm-action").assertIsNotEnabled()
            compose.onNodeWithText("I checked that this file, printer profile, and loaded materials are compatible.")
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("confirm-action").performSemanticsAction(SemanticsActions.OnClick)
            awaitText("Start printing on Simulated printer?")
            assertEquals(0, session.uploads)
            assertEquals(0, session.starts)
            compose.onNodeWithTag("confirm-action").performSemanticsAction(SemanticsActions.OnClick)
            awaitText("Start outcome is unknown")
            assertEquals(0, session.uploads)
            assertEquals(1, session.starts)
            assertTrue(app.fleet.queue.value.single().startUncertain)
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }

}
