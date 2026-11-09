package com.nozzleitall.desktop

import com.nozzleitall.desktop.jobs.LocalJobStore
import com.nozzleitall.desktop.jobs.PreparedArtifactStore
import com.nozzleitall.desktop.queue.PrintQueue
import com.nozzleitall.printer.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.UUID

class PreparedArtifactStoreTest {

    @Test fun legacyArtifactMigratesAndBundleKeepsItsOwnVerifiedFilename() {
        val root = Files.createTempDirectory("nia-prepared-format").toFile()
        val store = PreparedArtifactStore(root)
        val plain = File(root, "plate.gcode").apply { writeText("G28\n") }
        val old = store.prepare(plain)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(root, "artifacts.sqlite").absolutePath}").use { db ->
            db.createStatement().use { it.execute("ALTER TABLE artifacts DROP COLUMN output") }
        }
        val reopened = PreparedArtifactStore(root)
        assertEquals(old, reopened.record(old.id))
        assertTrue(reopened.verifiedFile(old).name.endsWith(".gcode"))
        val bundle = File(root, "plate.gcode.3mf").apply { writeText("format persistence fixture") }
        val saved = reopened.prepare(bundle)
        val again = PreparedArtifactStore(root)
        assertEquals(saved, again.record(saved.id))
        assertTrue(again.verifiedFile(saved).name.endsWith(".gcode.3mf"))
        assertTrue(again.orphanBlobs().isEmpty())
    }
    @Test fun desktopBambuLanRequiresOneReadableExternalSpoolAndFixedNozzleMapping() {
        val root = Files.createTempDirectory("nia-bambu-lan-output").toFile()
        fun bundle(name: String, plate: String): File = File(root, "$name.gcode.3mf").apply {
            java.util.zip.ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("Metadata/slice_info.config"))
                zip.write("<plate>$plate</plate>".toByteArray())
                zip.closeEntry()
            }
        }
        val spool = """<filament id="1" type="PLA"/>"""
        val second = """<filament id="2" type="PETG"/>"""
        val dynamic = """<metadata key="enable_filament_dynamic_map" value="true"/>"""
        assertNull(com.nozzleitall.desktop.jobs.bambuLanProblem(bundle("external", spool)))
        assertNotNull(com.nozzleitall.desktop.jobs.bambuLanProblem(bundle("multi", spool + second)))
        assertNotNull(com.nozzleitall.desktop.jobs.bambuLanProblem(bundle("dynamic", spool + dynamic)))
        assertNotNull(com.nozzleitall.desktop.jobs.bambuLanProblem(bundle("empty", "")))
        assertNotNull(com.nozzleitall.desktop.jobs.bambuLanProblem(File(root, "missing.gcode.3mf")))
    }

    @Test fun reprintQueueCreatesNewJobLinkedToOriginalWithoutStartingPrinter() {
        val root = Files.createTempDirectory("nia-reprint-queue").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val source = File(root, "slice.gcode").apply { writeText("G28\n") }
        val jobs = LocalJobStore(File(paths.data, "jobs.sqlite"))
        val artifacts = PreparedArtifactStore(File(paths.data, "prepared"))
        val retained = artifacts.prepare(source)
        val original = jobs.createJob(LocalJobStore.Origin.APP, artifactSha256 = retained.sha256)
        jobs.bindQueuedStart("p", original.id, "slice.gcode", 100)
        val observed = com.nozzleitall.desktop.history.JobRecord("reprint-observation", "p", "P",
            "slice.gcode", 100, 200, "finished", 10.0, 2)
        jobs.recordCurrentObservation(observed)
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        try {
            assertEquals(original.id, fleet.reprintCandidate(observed)?.sourceJobId)
            assertThrows(IllegalStateException::class.java) {
                fleet.enqueue("Wrong file", source, "p", emptyList(), sourceJobId = original.id)
            }
            assertTrue(fleet.queue.value.isEmpty())
            val queued = fleet.enqueue("Reprint", artifacts.verifiedFile(retained), "p", emptyList(), sourceJobId = original.id)
            val draft = jobs.job(queued.jobId!!)!!
            assertNotEquals(original.id, draft.id)
            assertEquals(original.id, draft.sourceJobId)
            assertFalse(queued.startUncertain)
            assertTrue(jobs.attempts(draft.id).isEmpty())
        } finally { fleet.shutdown() }
    }

    @Test fun reprintRejectsAmbiguousSliceProvenanceForIdenticalBytes() {
        val root = Files.createTempDirectory("nia-reprint-ambiguous").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val source = File(root, "slice.gcode").apply { writeText("G28\n") }
        val artifacts = PreparedArtifactStore(File(paths.data, "prepared"))
        val first = artifacts.prepare(source, "project", 1, "plate", "profile-a")
        artifacts.prepare(source, "project", 1, "plate", "profile-b")
        assertEquals(2, artifacts.recordsByDigest(first.sha256).size)
        val jobs = LocalJobStore(File(paths.data, "jobs.sqlite"))
        val original = jobs.createJob(LocalJobStore.Origin.APP, "project", 1, "plate", first.sha256)
        jobs.bindQueuedStart("p", original.id, "slice.gcode", 100)
        val observed = com.nozzleitall.desktop.history.JobRecord("ambiguous-observation", "p", "P",
            "slice.gcode", 100, 200, "finished", 10.0, 2)
        jobs.recordCurrentObservation(observed)
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        try {
            assertThrows(IllegalStateException::class.java) { fleet.reprintCandidate(observed) }
            assertTrue(fleet.queue.value.isEmpty())
        } finally { fleet.shutdown() }
    }

    @Test fun preparedFileSurvivesSourceRemovalAndDetectsTampering() {
        val root = Files.createTempDirectory("nia-artifacts").toFile()
        val source = File(root, "slice.gcode").apply { writeText("G28\nG1 X1\n") }
        val store = PreparedArtifactStore(File(root, "prepared"))
        val saved = store.prepare(source, "project-1", 2, "plate-1", "printer-profile", nowMillis = 100)
        val original = source.readBytes()
        assertEquals(saved, PreparedArtifactStore(File(root, "prepared")).record(saved.id))
        source.delete()
        val retained = store.verifiedFile(saved)
        assertEquals(saved, store.recordByDigest(saved.sha256))
        assertArrayEquals(original, retained.readBytes())
        assertTrue(store.orphanBlobs().isEmpty())
        retained.appendText("G1 X999\n")
        assertThrows(IllegalStateException::class.java) { store.verifiedFile(saved) }
    }

    @Test fun queueCreatesLinkedDataRecordsWithoutOpeningAPrinterSession() {
        val root = Files.createTempDirectory("nia-artifact-queue").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val source = File(root, "slice.gcode").apply { writeText("G28\n") }
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        val printer = PrinterEntry(PrinterConfig(PrinterIdentity("p", "P", "Test", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "fake"))
        printer.session = object : PrinterSession {
            override val identity = printer.config.identity
            override val capabilities = Capabilities(uploadJob = true, startPrint = true)
            override fun status(): PrinterStatus = error("Local queue operation queried the printer")
            override fun cameras(): List<CameraEndpoint> = error("Local queue operation queried cameras")
            override fun snapshot(camera: CameraEndpoint): ByteArray = error("Local queue operation read a camera")
            override fun upload(file: File, remoteName: String, progress: UploadProgress): UploadResult = error("Local queue operation uploaded")
            override fun perform(action: PrinterAction): ActionOutcome = error("Local queue operation dispatched a command")
            override fun close() {}
        }
        fleet.printers["p"] = printer
        val queued = fleet.enqueue("Part", source, "p", emptyList(), "project-1", 3, "plate-1", "profile",
            "a".repeat(64), "b".repeat(64))
        assertNotNull(queued.jobId)
        assertNotNull(queued.artifactId)
        val jobs = LocalJobStore(File(paths.data, "jobs.sqlite"))
        val artifact = PreparedArtifactStore(File(paths.data, "prepared")).record(queued.artifactId!!)!!
        assertEquals(artifact.sha256, jobs.job(queued.jobId!!)!!.artifactSha256)
        assertEquals("plate-1", jobs.job(queued.jobId!!)!!.plateId)
        assertEquals("a".repeat(64), artifact.engineBuild)
        assertEquals("b".repeat(64), artifact.inputDigest)
        assertArrayEquals(source.readBytes(), PreparedArtifactStore(File(paths.data, "prepared")).verifiedFile(artifact).readBytes())
        fleet.retargetQueued(queued.id, null)
        fleet.moveInQueue(queued.id, -1)
        fleet.dequeue(queued.id)
        fleet.shutdown()
    }

    @Test fun startupRecoversPendingQueueLinkWithoutDuplicatingPriorArtifactOrJob() {
        val root = Files.createTempDirectory("nia-queue-recover").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val source = File(root, "slice.gcode").apply { writeText("G28\n") }
        val queue = PrintQueue(File(paths.data, "queue"))
        val pending = queue.add("Part", source, "p", pendingLink = true,
            projectId = "project", projectRevision = 2, plateId = "plate", targetProfileId = "profile")
        assertNull(queue.nextFor("p"))
        val artifactId = UUID.nameUUIDFromBytes("artifact:${pending.id}".toByteArray(StandardCharsets.UTF_8)).toString()
        val jobId = UUID.nameUUIDFromBytes("job:${pending.id}".toByteArray(StandardCharsets.UTF_8)).toString()
        val artifacts = PreparedArtifactStore(File(paths.data, "prepared"))
        val artifact = artifacts.prepare(queue.verifiedFile(pending.id, allowPending = true), "project", 2, "plate", "profile",
            nowMillis = pending.addedAtMillis, id = artifactId)
        val jobs = LocalJobStore(File(paths.data, "jobs.sqlite"))
        jobs.createJob(LocalJobStore.Origin.APP, "project", 2, "plate", artifact.sha256, printerId = null,
            nowMillis = pending.addedAtMillis, id = jobId)
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        assertTrue(fleet.queueRecoveryProblems.value.isEmpty())
        val recovered = fleet.queue.value.single()
        assertFalse(recovered.pendingLink)
        assertEquals(jobId, recovered.jobId)
        assertEquals(artifactId, recovered.artifactId)
        assertEquals(artifact, artifacts.record(artifactId))
        assertEquals(1, jobs.jobs().size)
        fleet.shutdown()
    }

    @Test fun damagedPendingQueueFileRemainsVisibleAndUnselectable() {
        val root = Files.createTempDirectory("nia-queue-damaged").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val source = File(root, "slice.gcode").apply { writeText("G28\n") }
        val queue = PrintQueue(File(paths.data, "queue"))
        val pending = queue.add("Part", source, "p", pendingLink = true)
        queue.file(pending).appendText("G1 X9\n")
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        assertTrue(fleet.queue.value.single().pendingLink)
        assertTrue(fleet.queueRecoveryProblems.value.isNotEmpty())
        assertNull(PrintQueue(File(paths.data, "queue")).nextFor("p"))
        fleet.shutdown()
    }

    @Test fun malformedLocalIndexesDoNotBlockFleetStartupOrGetOverwritten() {
        val root = Files.createTempDirectory("nia-corrupt-local").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val queueIndex = File(paths.data, "queue/queue.json").apply { parentFile.mkdirs(); writeText("bad queue") }
        val history = File(paths.data, "history.json").apply { writeText("bad history") }
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        assertTrue(fleet.queue.value.isEmpty())
        assertTrue(fleet.queueRecoveryProblems.value.any { it.contains("cannot be read") })
        assertNotNull(fleet.historyMigrationProblem.value)
        assertEquals("bad queue", queueIndex.readText())
        assertEquals("bad history", history.readText())
        fleet.shutdown()
    }
}
