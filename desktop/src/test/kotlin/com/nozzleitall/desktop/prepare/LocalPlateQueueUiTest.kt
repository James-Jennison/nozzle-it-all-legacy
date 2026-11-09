package com.nozzleitall.desktop.prepare

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.nozzleitall.desktop.App
import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.SlicingSetup
import com.nozzleitall.desktop.queue.PrintQueue
import com.nozzleitall.desktop.ui.NozzleTheme
import com.nozzleitall.project.MeshIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalPlateQueueUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun savedPlatesCreateSeparateLocalJobsThroughSendReview() {
        assumeTrue("Requires the pinned native engine", System.getenv("NOZZLE_ENGINE")?.let { File(it).canExecute() } == true)
        val root = Files.createTempDirectory("nia-local-plate-queue").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val prepare = app.prepare
        val source = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val cube = MeshIO.readStl(File(source, "site-src/assets/test-cube-20mm.stl").readBytes())
        try {
            app.finishFirstRun(SlicingSetup("snapmaker_u1", "Snapmaker U1", "0.4", toolheads = 4))
            app.destination = Destination.PREPARE
            prepare.items += PrepItem(1, "First cube", cube, 70f, 70f)
            prepare.duplicateCurrentPlate()
            prepare.renamePlate(1, "Second plate")
            val saved = prepare.save()
            val before = saved.readBytes()
            prepare.open(saved)
            val plates = prepare.plateIds.toList()
            assertEquals(2, plates.size)
            assertTrue(app.fleet.printers.isEmpty())
            compose.setContent { NozzleTheme("dark") { App(app) } }
            for (index in plates.indices) {
                compose.runOnIdle { prepare.showPlate(index) }
                compose.onNodeWithTag("slice").performClick()
                compose.waitUntil(timeoutMillis = 120_000) {
                    compose.onAllNodesWithTag("open-send").fetchSemanticsNodes().isNotEmpty() ||
                        prepare.slice is SliceState.Failed
                }
                assertTrue("Slice failed: ${prepare.slice}", prepare.slice is SliceState.Done)
                compose.onNodeWithTag("open-send").performClick()
                compose.onNodeWithTag("add-to-queue").performClick()
                compose.runOnIdle { assertEquals(index + 1, app.fleet.queue.value.size) }
            }
            compose.runOnIdle { prepare.open(saved) }
            val jobs = PrintQueue(File(app.paths.data, "queue")).items()
            assertEquals(plates.toSet(), jobs.map { it.plateId }.toSet())
            assertEquals(1, jobs.map { it.projectId }.distinct().size)
            assertTrue(jobs.all { it.projectId != null && it.projectRevision != null && it.printerId == null && !it.startUncertain })
            assertTrue(jobs.all { it.jobId != null && it.artifactId != null })
            assertEquals(2, jobs.map { it.jobId }.distinct().size)
            assertEquals(plates, prepare.plateIds.toList())
            assertArrayEquals(before, saved.readBytes())
        } finally {
            scope.cancel()
        }
    }
    @Test
    fun bambuProfileSlicesAnExactBundleAndQueuesItWithNoPrinter() {
        assumeTrue("Requires the Bambu-enabled native engine", System.getenv("NIA_TEST_BAMBU_BUNDLE") != null &&
            System.getenv("NOZZLE_ENGINE")?.let { File(it).canExecute() } == true)
        val root = Files.createTempDirectory("nia-local-bambu-queue").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val prepare = app.prepare
        val source = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val cube = MeshIO.readStl(File(source, "site-src/assets/test-cube-20mm.stl").readBytes())
        try {
            app.finishFirstRun(SlicingSetup("bambu_x1_carbon", "Bambu Lab X1 Carbon", "0.4", toolheads = 1))
            app.destination = Destination.PREPARE
            prepare.items += PrepItem(1, "Fixture cube", cube, 130f, 130f)
            assertTrue(app.fleet.printers.isEmpty())
            compose.setContent { NozzleTheme("dark") { App(app) } }
            compose.onNodeWithTag("slice").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(timeoutMillis = 120_000) {
                prepare.slice is SliceState.Done || prepare.slice is SliceState.Failed
            }
            val done = prepare.slice as? SliceState.Done ?: error("Slice failed: ${prepare.slice}")
            val bundle = requireNotNull(done.result.bundle)
            assertNull(com.nozzleitall.desktop.jobs.bambuLanProblem(bundle))
            assertNotNull(done.preview)
            java.util.zip.ZipFile(bundle).use { archive ->
                val plate = archive.getEntry("Metadata/plate_1.gcode")
                assertArrayEquals(done.result.gcode.readBytes(), archive.getInputStream(plate).use { it.readBytes() })
            }
            compose.onNodeWithTag("open-send").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("add-to-queue").performSemanticsAction(SemanticsActions.OnClick)
            compose.runOnIdle {
                val row = PrintQueue(File(app.paths.data, "queue")).items().single()
                assertNull(row.printerId)
                assertTrue(row.fileName.endsWith(".gcode.3mf"))
                assertEquals(-1L, Files.mismatch(bundle.toPath(), app.fleet.queuedFile(row).toPath()))
            }
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }

}
