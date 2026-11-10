package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.CadRecipe
import com.nozzleitall.desktop.prepare.ModelingOps
import com.nozzleitall.desktop.prepare.PrepItem
import com.nozzleitall.desktop.prepare.ProfileCatalog
import com.nozzleitall.desktop.prepare.QualityPreset
import com.nozzleitall.desktop.prepare.SliceEngine
import com.nozzleitall.desktop.prepare.SliceOutcome
import com.nozzleitall.desktop.prepare.SliceRequest
import com.nozzleitall.project.Mesh
import com.nozzleitall.project.MeshIO
import com.nozzleitall.project.ModelObject
import com.nozzleitall.project.Paint
import com.nozzleitall.project.Project3mf
import com.nozzleitall.project.ProjectManifest
import com.nozzleitall.project.ThreeMf
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

    @Test fun repairClosesMissingCubeFaceAndSharesExportedVertices() {
        engineReady()
        val work = Files.createTempDirectory("modeling-open-cube").toFile()
        try {
            val top = cube.bounds()[5]
            val openTriangles = cube.triangles.toList().chunked(3)
                .filterNot { face -> face.all { cube.vertices[it * 3 + 2] == top } }.flatten().toIntArray()
            assertEquals(10, openTriangles.size / 3)
            val fixture = System.getenv("NIA_AUDIT_HOLE_FIXTURE")?.let { path ->
                ThreeMf.read(File(path)).objects.single { it.mesh.triangleCount == 10 }.mesh
            } ?: Mesh(cube.vertices, openTriangles)
            val (repaired, stats) = ModelingOps.repair(fixture, work)
            assertEquals(0, stats.after.openEdges)
            assertEquals(12, repaired.triangleCount)
            val target = File(work, "repaired.3mf")
            target.writeBytes(ThreeMf.write(Project3mf(
                listOf(ModelObject(1, "Repaired cube", repaired)))))
            val reopened = ThreeMf.read(target).objects.single().mesh
            assertEquals(8, reopened.vertexCount)
            val edgeUses = reopened.triangles.toList().chunked(3).flatMap { (a, b, c) ->
                listOf(a to b, b to c, c to a).map { (u, v) -> minOf(u, v) to maxOf(u, v) }
            }.groupingBy { it }.eachCount()
            assertEquals(18, edgeUses.size)
            assertTrue(edgeUses.values.all { it == 2 })
            val profile = ProfileCatalog.byId("snapmaker_u1")!!
            val item = PrepItem(1, "Repaired cube", reopened, profile.bedW / 2f, profile.bedD / 2f)
            val result = SliceEngine(SliceEngine.locateEngine()!!, File(work, "slices")).slice(
                SliceRequest(Project3mf(listOf(ModelObject(1, item.name, reopened, item.placement()))),
                    ProfileCatalog.materialize(File(work, "cache"), profile.id), QualityPreset.STANDARD,
                    false, 15, emptyList())) { _, _ -> }
            assertTrue("$result", result is SliceOutcome.Done)
            assertTrue((result as SliceOutcome.Done).gcode.length() > 0)
            System.getenv("NIA_AUDIT_MESH_OUTPUT")?.let { directory ->
                target.copyTo(File(directory, "repaired.3mf"), overwrite = true)
                result.gcode.copyTo(File(directory, "repaired.gcode"), overwrite = true)
            }
        } finally { work.deleteRecursively() }
    }

    @Test fun simplifyKeepsPaintedCylinderFacetsAndUsesBothTools() {
        engineReady()
        val output = System.getenv("NIA_AUDIT_MESH_OUTPUT")?.let(::File)
        val work = if (output == null) Files.createTempDirectory("modeling-painted-cylinder").toFile()
            else Files.createTempDirectory(output.toPath(), "modeling-painted-cylinder").toFile()
        try {
            val count = 40
            val coords = FloatArray((count * 2 + 2) * 3)
            for (layer in 0..1) {
                for (i in 0 until count) {
                    val a = 2.0 * Math.PI * i / count
                    val at = (layer * count + i) * 3
                    coords[at] = (10 * kotlin.math.cos(a)).toFloat()
                    coords[at + 1] = (10 * kotlin.math.sin(a)).toFloat()
                    coords[at + 2] = layer * 15f
                }
                coords[(count * 2 + layer) * 3 + 2] = layer * 15f
            }
            val triangles = (0 until count).flatMap { i ->
                val j = (i + 1) % count
                listOf(i, j, count + j, i, count + j, count + i, count * 2, j, i, count * 2 + 1, count + i, count + j)
            }.toIntArray()
            val fixture = System.getenv("NIA_AUDIT_CYLINDER_FIXTURE")?.let { path ->
                ThreeMf.read(File(path)).objects.single { it.mesh.triangleCount == 732 }.mesh
            } ?: Mesh(coords, triangles)
            val b = fixture.bounds()
            val split = Paint.Node.Split(3, 0, listOf(2, 2, 0, 2).map {
                Paint.Node.Leaf(it)
            })
            val paint = Array<String?>(fixture.triangleCount) { f ->
                if ((0..2).all { corner -> fixture.vertices[fixture.triangles[f * 3 + corner] * 3 + 2] == b[5] }) {
                    if (f % 2 == 0) Paint.whole(2) else Paint.encode(split)
                } else null
            }
            val painted = fixture.withPaint(paint = paint)
            val fullyPainted = fixture.withPaint(paint = Array(fixture.triangleCount) { Paint.whole(2) })
            assertTrue(runCatching { ModelingOps.simplify(fullyPainted, fixture.triangleCount / 2, work) }
                .exceptionOrNull()?.message.orEmpty().contains("while keeping the painted facets"))
            val target = fixture.triangleCount / 2
            val clean = ModelingOps.simplify(fixture, target, work)
            assertTrue(clean.triangleCount < fixture.triangleCount)
            val simplified = ModelingOps.simplify(painted, target, work)
            assertTrue(simplified.triangleCount < fixture.triangleCount)
            assertEquals(paint.filterNotNull().groupingBy { it }.eachCount(),
                simplified.paint!!.filterNotNull().groupingBy { it }.eachCount())
            val profile = ProfileCatalog.byId("snapmaker_u1")!!
            val item = PrepItem(1, "Cylinder", simplified, profile.bedW / 2f, profile.bedD / 2f, paintSlots = listOf(1, 2))
            val obj = ModelObject(1, "Cylinder", simplified, item.placement())
            val producer = ProjectManifest.Producer("test", "desktop", "test")
            val manifest = ProjectManifest("p", 1, "Cylinder", producer, producer, 1,
                ProjectManifest.PrinterTarget(profile.model, profileId = profile.id),
                listOf(ProjectManifest.PlateEntry(1, "Plate 1",
                    listOf(ProjectManifest.ObjectEntry(1, "Cylinder", 1, paintSlots = listOf(1, 2))))))
            val slots = listOf("#FFFFFF", "#FF0000").mapIndexed { i, color ->
                ProjectManifest.MaterialSlot(i + 1, "PLA", colorHex = color)
            }
            val result = SliceEngine(SliceEngine.locateEngine()!!, File(work, "slices")).slice(
                SliceRequest(Project3mf(listOf(obj), manifest = manifest),
                    ProfileCatalog.materialize(File(work, "cache"), profile.id), QualityPreset.STANDARD,
                    false, 15, slots)) { _, _ -> }
            assertTrue("$result", result is SliceOutcome.Done)
            result as SliceOutcome.Done
            val tools = result.gcode.useLines { lines -> lines.map { it.substringBefore(';').trim() }.filter { it in setOf("T0", "T1") }.toSet() }
            assertEquals(setOf("T0", "T1"), tools)
            assertTrue(result.stats.toolChanges > 0)
            println("Cylinder triangles: ${fixture.triangleCount} original, ${clean.triangleCount} clean, ${simplified.triangleCount} painted. Tools: $tools")
        } finally { if (output == null) work.deleteRecursively() }
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
