package com.nozzleitall.desktop

import com.nozzleitall.printer.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Changing a saved printer's address (and name, model, credentials) keeps its identity and is saved. */
class EditPrinterTest {
    private fun paths(): AppPaths { val tmp = Files.createTempDirectory("edit-printer").toFile(); return AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure() }

    @Test fun anEditedAddressIsKeptUnderTheSameIdAndSurvivesARestart() {
        val p = paths()
        PrinterStore(p).save(listOf(
            PrinterConfig(PrinterIdentity("u1", "U1", "Snapmaker U1", PrinterFamily.PAXX_U1, "http://192.168.1.50", "snapmaker_u1"), "paxx-lan", secret = "key-1"),
            PrinterConfig(PrinterIdentity("cc1", "CC1", "Elegoo CC1", PrinterFamily.ELEGOO, "192.168.1.114"), "elegoo")))
        val scope = CoroutineScope(Dispatchers.Default)
        val fleet = Fleet(p, scope)
        val queued = File(p.data, "edit-target.gcode").apply { writeText("G28\n") }
        val item = fleet.enqueue("Edit target", queued, "u1", emptyList())
        val before = fleet.printers.getValue("u1").config
        fleet.update(before.copy(identity = before.identity.copy(address = "http://192.168.1.77", displayName = "Workshop U1")))
        assertEquals("u1", fleet.queue.value.single { it.id == item.id }.printerId)
        assertEquals(listOf("u1", "cc1"), fleet.order.value)   // still first on the Printers page
        val after = fleet.printers.getValue("u1").config
        assertEquals("http://192.168.1.77", after.identity.address); assertEquals("Workshop U1", after.identity.displayName)
        assertEquals("snapmaker_u1", after.identity.profileId); assertEquals("key-1", after.secret)   // the profile and credential stay
        fleet.shutdown(); scope.coroutineContext[Job]?.cancel()
        val reloaded = PrinterStore(p).load().associateBy { it.identity.id }
        assertEquals("http://192.168.1.77", reloaded.getValue("u1").identity.address); assertEquals("192.168.1.114", reloaded.getValue("cc1").identity.address)
    }

    @Test fun reviewedProfileLinkKeepsQueuedTargetAndRejectsStaleConnection() = runBlocking {
        val p = paths()
        val scope = CoroutineScope(Dispatchers.Default)
        val fleet = Fleet(p, scope, stockHelper = { null })
        try {
            val config = PrinterConfig(
                PrinterIdentity("u1", "Fixture U1", "Snapmaker U1", PrinterFamily.PAXX_U1, "127.0.0.1:1"),
                EXPORT_ONLY,
            )
            assertTrue(fleet.add(config, persist = false))
            val old = fleet.printers.getValue("u1")
            old.status.value = PrinterStatus(PrinterState.READY, ConnectionRoute.LAN)
            val file = File(p.data, "profile-link.gcode").apply { writeText("G28\n") }
            val job = fleet.enqueue("Fixture", file, "u1", emptyList())
            fleet.associatePrinterProfile(old, "snapmaker_u1")
            assertTrue(old.retired)
            assertEquals("u1", fleet.queue.value.single { it.id == job.id }.printerId)
            assertEquals("snapmaker_u1", PrinterStore(p).load().single().identity.profileId)
            assertTrue(runCatching { fleet.associatePrinterProfile(old, "snapmaker_u1") }.isFailure)
        } finally {
            fleet.shutdown()
            scope.coroutineContext[Job]?.cancel()
            p.config.parentFile.deleteRecursively()
        }
    }

    @Test fun aRemovedPrinterIsForgottenAndQueuedJobsBecomeUnassigned() {
        val p = paths()
        PrinterStore(p).save(listOf(
            PrinterConfig(PrinterIdentity("u1", "Workshop U1", "Snapmaker U1", PrinterFamily.PAXX_U1, "192.168.1.50", "snapmaker_u1"), "paxx-lan"),
            PrinterConfig(PrinterIdentity("cc1", "CC1", "Elegoo CC1", PrinterFamily.ELEGOO, "192.168.1.114"), "elegoo")))
        val scope = CoroutineScope(Dispatchers.Default)
        val fleet = Fleet(p, scope, stockHelper = { null })
        val queued = File(p.data, "remove-target.gcode").apply { writeText("G28\n") }
        val item = fleet.enqueue("Remove target", queued, "u1", emptyList())

        assertTrue(fleet.remove("u1"))

        assertFalse("removed printer leaves the live fleet", fleet.printers.containsKey("u1"))
        assertEquals(listOf("cc1"), fleet.order.value)
        assertNull("queued work remains recoverable but is no longer aimed at a missing printer",
            fleet.queue.value.single { it.id == item.id }.printerId)
        assertEquals(listOf("cc1"), PrinterStore(p).load().map { it.identity.id })
        fleet.shutdown(); scope.coroutineContext[Job]?.cancel()
    }

    @Test fun aPrinterRemovalThatCannotBeSavedLeavesTheLiveFleetAndQueueUntouched() {
        val p = paths()
        val scope = CoroutineScope(Dispatchers.Default)
        val fleet = Fleet(p, scope, stockHelper = { null })
        val config = PrinterConfig(
            PrinterIdentity("u1", "Workshop U1", "Snapmaker U1", PrinterFamily.PAXX_U1, "192.168.1.50", "snapmaker_u1"),
            "paxx-lan",
        )
        assertTrue(fleet.add(config, persist = false))
        val queued = File(p.data, "failed-remove-target.gcode").apply { writeText("G28\n") }
        val item = fleet.enqueue("Failed remove target", queued, "u1", emptyList())
        assertTrue(p.printers.mkdir())

        assertFalse(fleet.remove("u1"))

        assertTrue(fleet.printers.containsKey("u1"))
        assertEquals(listOf("u1"), fleet.order.value)
        assertEquals("u1", fleet.queue.value.single { it.id == item.id }.printerId)
        fleet.shutdown(); scope.coroutineContext[Job]?.cancel()
    }

    @Test fun aPolledPrinterRemovalThatCannotBeSavedKeepsItsPollerAndLiveState() {
        val p = paths()
        val scope = CoroutineScope(Dispatchers.Default)
        val pollStarted = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        val printerIdentity = PrinterIdentity(
            "u1", "Workshop U1", "Snapmaker U1", PrinterFamily.PAXX_U1,
            "192.168.1.50", "snapmaker_u1",
        )
        val session = object : PrinterSession {
            override val identity = printerIdentity
            override val capabilities = Capabilities()
            override fun status(): PrinterStatus {
                pollStarted.countDown()
                assertTrue("test poll was not released", releasePoll.await(5, TimeUnit.SECONDS))
                return PrinterStatus(PrinterState.READY, ConnectionRoute.LAN)
            }
            override fun cameras() = emptyList<CameraEndpoint>()
            override fun snapshot(camera: CameraEndpoint) = ByteArray(0)
            override fun upload(file: File, remoteName: String, progress: UploadProgress) = UploadResult.Failed("unused")
            override fun perform(action: PrinterAction) = ActionOutcome.Rejected("unused")
            override fun close() = Unit
        }
        val adapter = object : DeviceAdapter {
            override val id = "polled-removal-test"
            override val displayName = "Polled removal test"
            override val families = setOf(PrinterFamily.PAXX_U1)
            override val mayUseVendorCloud = false
            override fun probe(address: String): DiscoveredPrinter? = null
            override fun open(config: PrinterConfig) = session
        }
        val fleet = Fleet(p, scope, stockHelper = { null }).also { it.registry.registerBuiltIn(adapter) }
        val config = PrinterConfig(printerIdentity, adapter.id)
        assertTrue(fleet.add(config, persist = false))
        assertTrue("printer poller did not start", pollStarted.await(5, TimeUnit.SECONDS))
        val queued = File(p.data, "failed-polled-remove-target.gcode").apply { writeText("G28\n") }
        val item = fleet.enqueue("Failed polled remove target", queued, "u1", emptyList())
        assertTrue(p.printers.mkdir())

        assertFalse(fleet.remove("u1"))
        val entry = fleet.printers.getValue("u1")
        assertFalse(entry.retired)
        assertSame(session, entry.session)
        assertEquals("u1", fleet.queue.value.single { it.id == item.id }.printerId)
        releasePoll.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (entry.status.value.state != PrinterState.READY && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("failed removal cancelled or retired the active poller", PrinterState.READY, entry.status.value.state)
        fleet.shutdown(); scope.coroutineContext[Job]?.cancel()
    }
}
