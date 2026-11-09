package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.CadRecipe
import com.nozzleitall.desktop.prepare.ModelingOps
import com.nozzleitall.desktop.prepare.PrepItem
import com.nozzleitall.desktop.prepare.SliceEngine
import com.nozzleitall.project.Mesh
import com.nozzleitall.project.MeshIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Candidate-bound fixtures for the Orca modeling operations exposed by NIA's Compose shell. */
class ModelingOpsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())

    private fun engineReady() = assumeTrue("modeling engine available", SliceEngine.locateEngine() != null)
    private fun moved(mesh: Mesh, dx: Float) = Mesh(FloatArray(mesh.vertices.size) { i ->
        if (i % 3 == 0) mesh.vertices[i] + dx else mesh.vertices[i]
    }, mesh.triangles)
    private fun app(): AppState {
        val tmp = Files.createTempDirectory("modeling-state").toFile()
        return AppState(AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    @Test fun cadRecipeReopensWithEditableParameters() {
        val work = Files.createTempDirectory("modeling-cad-recipe").toFile()
        try {
            val file = File(work, "circle.niacad")
            val original = CadRecipe(circular = true, widthMm = 32.0, depthMm = 6.0, holeMm = 7.0)
            ModelingOps.saveCadRecipe(original, file)
            assertEquals(original, CadRecipe.read(file))
            val changed = CadRecipe.read(file).copy(depthMm = 8.0)
            ModelingOps.saveCadRecipe(changed, file)
            assertEquals(changed, CadRecipe.read(file))
            file.writeText("""{"version":2,"shape":"circle"}""")
            assertTrue(runCatching { CadRecipe.read(file) }.isFailure)
            assertTrue(runCatching { original.copy(holeMm = 32.0) }.isFailure)
            file.writeText("""{"version":1,"shape":"circle"}""")
            assertEquals("The CAD recipe is invalid or missing a dimension.",
                runCatching { CadRecipe.read(file) }.exceptionOrNull()?.message)
            file.writeText(" ".repeat(4097))
            assertEquals("The CAD recipe is too large.", runCatching { CadRecipe.read(file) }.exceptionOrNull()?.message)
            assertEquals("Choose a STEP file ending in .step or .stp.",
                runCatching { ModelingOps.stepMesh(File(work, "mesh.stl"), work) }.exceptionOrNull()?.message)
        } finally { work.deleteRecursively() }
    }

    @Test fun cadRebuildAndStepRoundTripUseRealEngine() {
        engineReady()
        val work = Files.createTempDirectory("modeling-cad-step").toFile()
        try {
            fun depth(mesh: Mesh) = mesh.bounds().let { it[5] - it[2] }
            val rectangle = CadRecipe(widthMm = 26.0, heightMm = 18.0, depthMm = 7.0, holeMm = 4.0)
            val first = ModelingOps.cadMesh(rectangle, work)
            val changed = ModelingOps.cadMesh(rectangle.copy(depthMm = 11.0), work)
            assertEquals(7.0f, depth(first), 0.001f)
            assertEquals(11.0f, depth(changed), 0.001f)
            assertTrue(first.triangleCount > 12)
            val step = File(work, "real.step")
            ModelingOps.exportStep(rectangle, step)
            assertTrue(step.bufferedReader().use { it.readLine() }.startsWith("ISO-10303-21"))
            val reimported = ModelingOps.stepMesh(step, work)
            assertEquals(7.0f, depth(reimported), 0.001f)
            assertTrue(reimported.triangleCount > 12)
            val malformed = File(work, "broken.step").apply { writeText("not a STEP file") }
            assertTrue(runCatching { ModelingOps.stepMesh(malformed, work) }.exceptionOrNull() is ModelingOps.EngineError)
        } finally { work.deleteRecursively() }
    }

    @Test fun measureRepairLayAndBooleanUseRealEngine() {
        engineReady()
        val work = Files.createTempDirectory("modeling-fixture").toFile()
        val measure = ModelingOps.measure(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(3.0, 4.0, 0.0), doubleArrayOf(3.0, 4.0, 12.0))
        assertEquals(5.0, measure.distance, 1e-9)
        assertEquals(90.0, measure.angleDegrees!!, 1e-9)
        assertEquals(13.0, measure.diameter!!, 1e-9)

        val repair = ModelingOps.repairPreview(cube, work)
        assertTrue("the STL fixture should exercise repair, but reported ${repair.before.openEdges} open edges",
            repair.before.openEdges > 0)
        assertEquals(0, repair.after.openEdges)
        val laid = ModelingOps.layOnFace(cube, doubleArrayOf(0.0, 1.0, 0.0))
        assertEquals(cube.triangleCount, laid.triangleCount)

        val union = ModelingOps.boolean(cube, moved(cube, 10f), ModelingOps.BooleanMode.UNION, work)
        val subtract = ModelingOps.boolean(cube, moved(cube, 10f), ModelingOps.BooleanMode.SUBTRACT, work)
        val intersect = ModelingOps.boolean(cube, moved(cube, 10f), ModelingOps.BooleanMode.INTERSECT, work)
        assertTrue(union.triangleCount > 0 && subtract.triangleCount > 0 && intersect.triangleCount > 0)
        val simplified = ModelingOps.simplify(union, union.triangleCount / 2, work)
        assertTrue(simplified.triangleCount < union.triangleCount)
    }

    @Test fun textAndSvgUseOrcaGeometry() {
        engineReady()
        val work = Files.createTempDirectory("modeling-text-svg").toFile()
        val text = ModelingOps.text("NIA", 12.0, 1.6, work)
        assertTrue(text.triangleCount > 0)
        val svg = File(work, "fixture.svg").apply {
            writeText("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 12"><path d="M1 1h22v10H1z M5 3h4v6H5z" fill-rule="evenodd"/></svg>""")
        }
        val imported = ModelingOps.svg(svg, 1.6, 0.1, work)
        assertTrue(imported.triangleCount > 0)
        assertTrue(imported.bounds()[5] - imported.bounds()[2] > 1.5f)
        assertTrue("operation workspaces must be cleaned", work.listFiles().orEmpty().none { it.isDirectory })
    }

    @Test fun processBoundaryDrainsBothStreamsTimesOutAndReportsDiagnostics() {
        val shell = File("/bin/sh")
        assumeTrue("POSIX process-boundary fixture", shell.canExecute())
        val tmp = Files.createTempDirectory("modeling-process").toFile()
        val large = File(tmp, "large.sh").apply {
            writeText("""#!/bin/sh
                |printf '{"ok":true,"pad":"'
                |head -c 131072 /dev/zero | tr '\000' x
                |printf '"}'
                |printf 'diagnostic stream drained' >&2
                |""".trimMargin())
            setExecutable(true)
        }
        val result = ModelingOps.runProcess(listOf(large.absolutePath), 5)
        assertTrue(result.getBoolean("ok"))
        assertEquals(131072, result.getString("pad").length)

        val failed = File(tmp, "failed.sh").apply {
            writeText("#!/bin/sh\nprintf 'engine rejected malformed mesh' >&2\nexit 7\n")
            setExecutable(true)
        }
        try {
            ModelingOps.runProcess(listOf(failed.absolutePath), 5)
            fail("a non-zero process must fail")
        } catch (error: ModelingOps.EngineError) {
            assertTrue(error.message.orEmpty().contains("engine rejected malformed mesh"))
        }

        val slow = File(tmp, "slow.sh").apply {
            writeText("#!/bin/sh\nsleep 30\nprintf '{\"ok\":true}'\n")
            setExecutable(true)
        }
        val started = System.nanoTime()
        try {
            ModelingOps.runProcess(listOf(slow.absolutePath), 1)
            fail("a timed-out process must fail")
        } catch (error: ModelingOps.EngineError) {
            assertTrue(error.message.orEmpty().contains("too long"))
        }
        assertTrue("timeout must remain reachable", TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10)
    }

    @Test fun processBoundaryStopsChildWhenInterrupted() {
        val shell = File("/bin/sh")
        assumeTrue("POSIX process-boundary fixture", shell.canExecute())
        val tmp = Files.createTempDirectory("modeling-cancel").toFile()
        val pidFile = File(tmp, "pid")
        val slow = File(tmp, "slow.sh").apply {
            writeText("#!/bin/sh\nprintf '%s' \$\$ > '${pidFile.absolutePath}'\nsleep 30\n")
            setExecutable(true)
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<JSONObject> { ModelingOps.runProcess(listOf(slow.absolutePath), 60) }
            repeat(100) { if (!pidFile.isFile) Thread.sleep(20) }
            assertTrue("fixture process must start", pidFile.isFile)
            val pid = pidFile.readText().toLong()
            future.cancel(true)
            repeat(100) {
                if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) Thread.sleep(20) else return@repeat
            }
            assertTrue("cancelled modeling process must be gone", !ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test fun raisedAndEngravedTextAreAtomicUndoableOperations() = runBlocking {
        engineReady()
        val app = app(); val p = app.prepare
        withContext(Dispatchers.Main) {
            p.items += PrepItem(1, "cube", cube, 50f, 50f)
            p.selected = 1
            p.history.reset()
            p.addText("N", 6.0, 2.0, ModelingOps.ReliefMode.RAISED)
        }
        withTimeout(120_000) { while (p.toolBusy != null) delay(20) }
        val raisedVolume = p.items.single().volume
        assertTrue("raised text should add volume, got $raisedVolume", raisedVolume > 8_000.0)
        withContext(Dispatchers.Main) { p.history.undo() }
        assertEquals(8_000.0, p.items.single().volume, 1.0)
        withContext(Dispatchers.Main) { p.addText("N", 6.0, 2.0, ModelingOps.ReliefMode.ENGRAVED) }
        withTimeout(120_000) { while (p.toolBusy != null) delay(20) }
        val engravedVolume = p.items.single().volume
        assertTrue("engraved text should remove volume, got $engravedVolume", engravedVolume < 8_000.0)
        withContext(Dispatchers.Main) { p.history.undo() }
        assertEquals(8_000.0, p.items.single().volume, 1.0)
    }

    @Test fun booleanHonoursPlateTransformsAndUndoRedoIsAtomic() = runBlocking {
        engineReady()
        val app = app(); val p = app.prepare
        withContext(Dispatchers.Main) {
            p.items.addAll(listOf(PrepItem(1, "base", cube, 50f, 50f), PrepItem(2, "tool", cube, 60f, 50f)))
            p.history.reset()
            p.selectObject(1); p.selectObject(2, toggle = true)
            p.previewBooleanSelected(ModelingOps.BooleanMode.UNION)
        }
        withTimeout(120_000) { while (p.toolBusy != null) delay(20) }
        withContext(Dispatchers.Main) {
            assertEquals(2, p.items.size)
            assertNotNull(p.meshEditPreview)
            p.discardMeshEditPreview()
            p.applyMeshEditPreview()
            assertEquals(2, p.items.size)
            p.previewBooleanSelected(ModelingOps.BooleanMode.UNION)
        }
        withTimeout(120_000) { while (p.toolBusy != null) delay(20) }
        withContext(Dispatchers.Main) { p.applyMeshEditPreview() }
        assertEquals(1, p.items.size)
        assertTrue("overlapping 20 mm cubes should union to about 12,000 mm³, got ${p.items.single().volume}",
            abs(p.items.single().volume - 12_000.0) < 5.0)
        assertEquals(55f, p.items.single().x, 0.01f)
        withContext(Dispatchers.Main) { p.history.undo() }
        assertEquals(2, p.items.size)
        assertEquals(setOf(1, 2), p.selectedIds)
        withContext(Dispatchers.Main) { p.history.redo() }
        assertEquals(1, p.items.size)
    }

    @Test fun repairPreviewDoesNotMutateAndRepairCreatesOneUndoStep() = runBlocking {
        engineReady()
        val app = app(); val p = app.prepare
        withContext(Dispatchers.Main) {
            p.items += PrepItem(1, "cube", cube, 50f, 50f)
            p.selected = 1; p.history.reset()
            p.previewRepairSelected()
        }
        withTimeout(120_000) { while (p.toolBusy != null) delay(20) }
        val preview = requireNotNull(p.meshEditPreview)
        val stats = requireNotNull(preview.repairStats)
        assertTrue(stats.before.openEdges > 0)
        assertEquals(0, stats.after.openEdges)
        assertSame(cube, p.items.single().mesh)
        assertFalse("Preview must leave history unchanged", p.history.canUndo)
        withContext(Dispatchers.Main) { p.applyMeshEditPreview() }
        assertSame(preview.result.mesh, p.items.single().mesh)
        assertTrue(p.history.canUndo)
        withContext(Dispatchers.Main) { p.history.undo() }
        assertEquals(cube.triangleCount, p.items.single().mesh.triangleCount)
        assertFalse("Repair is a single Undo step", p.history.canUndo)

        withContext(Dispatchers.Main) { p.previewRepairSelected() }
        withTimeout(120_000) { while (p.toolBusy != null) delay(20) }
        assertNotNull(p.meshEditPreview?.repairStats)
        withContext(Dispatchers.Main) {
            p.selected = null
            p.applyMeshEditPreview()
        }
        assertNull(p.meshEditPreview)
        assertFalse("A discarded preview cannot change history", p.history.canUndo)
    }

    @Test fun measurementCanScaleTheSelectedObjectAndUndoAtomically() = runBlocking {
        engineReady()
        val app = app(); val p = app.prepare
        withContext(Dispatchers.Main) {
            p.items += PrepItem(1, "cube", cube, 50f, 50f)
            p.selected = 1
            p.history.reset()
            p.measurePoints(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(10.0, 0.0, 0.0))
        }
        withTimeout(120_000) { while (p.toolBusy != null) delay(20) }
        assertEquals(10.0, p.lastMeasurement!!.distance, 1e-9)
        withContext(Dispatchers.Main) { p.scaleSelectedFromMeasurement(40.0) }
        assertEquals(80f, p.items.single().footprintW, 0.01f)
        assertTrue(p.history.canUndo)
        withContext(Dispatchers.Main) { p.history.undo() }
        assertEquals(20f, p.items.single().footprintW, 0.01f)
    }
}
