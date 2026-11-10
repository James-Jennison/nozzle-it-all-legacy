package com.nozzleitall.desktop.prepare

import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.desktop.AppState
import com.nozzleitall.project.MeshIO
import com.nozzleitall.project.ThreeMf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class LayerActionsTest {
    @Test fun refusesMovedMissingAndDuplicateOutputMarkers() {
        val action = LayerAction(UUID.randomUUID().toString(), 2, 0.45, LayerAction.Kind.PAUSE)
        val file = File.createTempFile("layer-action-verification", ".gcode")
        try {
            val exact = ";LAYER_CHANGE\n;Z:0.25\n;LAYER_CHANGE\n;Z:0.45\n"
            val marker = ";NIA_LAYER_ACTION ${action.id}\nM600\n"
            file.writeText(exact + marker)
            assertNull(LayerAction.verifyExport(file, listOf(action), mapOf(action.id to "M600")))
            file.writeText(exact.replace("0.45", "0.50") + marker)
            assertTrue(LayerAction.verifyExport(file, listOf(action), mapOf(action.id to "M600")).orEmpty().contains("moved"))
            file.writeText(exact)
            assertTrue(LayerAction.verifyExport(file, listOf(action), mapOf(action.id to "M600")).orEmpty().contains("did not include"))
            file.writeText(exact + marker.replace("M600", "M601"))
            assertTrue(LayerAction.verifyExport(file, listOf(action), mapOf(action.id to "M600")).orEmpty().contains("differ"))
            file.writeText(exact + marker + marker)
            assertNotNull(LayerAction.verifyExport(file, listOf(action), mapOf(action.id to "M600")))
        } finally {
            file.delete()
        }
    }

    @Test fun layerCommandAvailabilityAndValidationFollowTheTargetProfile() {
        val bambu = JSONObject().put("printer_model", "Bambu Lab X1 Carbon").put("gcode_flavor", "marlin")
            .put("machine_pause_gcode", "M400 U1")
        assertEquals("M400 U1", LayerAction.command(LayerAction.Kind.PAUSE, bambu, ""))
        assertNull(LayerAction.command(LayerAction.Kind.FILAMENT, bambu, ""))
        val klipper = JSONObject().put("printer_model", "Elegoo Centauri Carbon").put("gcode_flavor", "klipper")
            .put("machine_pause_gcode", "PAUSE")
        assertEquals("PAUSE", LayerAction.command(LayerAction.Kind.PAUSE, klipper, ""))
        assertNull(LayerAction.command(LayerAction.Kind.FILAMENT, klipper, ""))
        val m600Profile = JSONObject().put("gcode_flavor", "klipper").put("machine_pause_gcode", "M600")
        assertEquals("M600", LayerAction.command(LayerAction.Kind.FILAMENT, m600Profile, ""))
        assertNull(LayerAction.command(LayerAction.Kind.CUSTOM, klipper, ";Z:2.3\nM117 Fixture"))
        assertEquals("M117 Fixture", LayerAction.command(LayerAction.Kind.CUSTOM, klipper, "M117 Fixture"))
    }

    @Test fun actionsStayOnTheirPlateAndRequireReviewAfterReopen() = runBlocking {
        assumeNotNull(SliceEngine.locateEngine())
        val root = Files.createTempDirectory("nia-layer-action-workflow").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(root.resolve("config"), root.resolve("data"), root.resolve("cache")).ensure(), scope)
        try {
            withContext(Dispatchers.Main) {
                val prepare = app.prepare
                val repo = generateSequence(File("").absoluteFile) { it.parentFile }
                    .first { File(it, "settings.gradle.kts").isFile }
                val mesh = MeshIO.readStl(repo.resolve("site-src/assets/test-cube-20mm.stl").readBytes())
                prepare.items += PrepItem(1, "First plate", mesh, 105f, 105f)
                prepare.addPlate()
                prepare.items += PrepItem(2, "Second plate", mesh, 135f, 135f, plate = 1)
                prepare.showPlate(0)
                prepare.changed()
                suspend fun slice(): SliceState.Done {
                    prepare.slice(scope)
                    withTimeout(120_000) { while (prepare.slice is SliceState.Running) delay(25) }
                    return prepare.slice as? SliceState.Done ?: error(prepare.slice.toString())
                }
                fun cursor(done: SliceState.Done) = requireNotNull(previewLayerCursor(requireNotNull(done.preview), 5))
                val first = cursor(slice())
                val firstPlate = prepare.plateIds[0]
                assertTrue(prepare.setLayerAction(firstPlate, first.index + 1, first.z.toDouble(), LayerAction.Kind.PAUSE, ""))
                val original = prepare.layerActions.getValue(firstPlate).single()
                val paused = slice()
                assertNull(LayerAction.verifyExport(paused.result.gcode, listOf(original), mapOf(original.id to "M600")))
                assertTrue(paused.result.gcode.readText().contains(";NIA_LAYER_ACTION ${original.id}\nM600"))
                val point = cursor(paused)
                assertTrue(prepare.setLayerAction(firstPlate, point.index + 1, point.z.toDouble(), LayerAction.Kind.CUSTOM, "M117 Layer fixture"))
                val edited = prepare.layerActions.getValue(firstPlate).single()
                assertEquals(original.id, edited.id)
                prepare.history.undo()
                assertEquals(original, prepare.layerActions.getValue(firstPlate).single())
                prepare.history.redo()
                assertEquals(edited, prepare.layerActions.getValue(firstPlate).single())
                val saved = root.resolve("layer-actions.3mf")
                saved.writeBytes(ThreeMf.write(prepare.toProject()))
                prepare.open(saved)
                assertFalse(prepare.layerActionsReviewed)
                prepare.slice(scope)
                assertTrue(prepare.slice is SliceState.Failed)
                val oldTarget = prepare.layerActionTarget()
                prepare.reviewLayerActions()
                val oldNozzle = prepare.nozzle
                prepare.nozzle = "0.6"
                assertFalse(prepare.layerActionsReviewed)
                prepare.reviewLayerActions(oldTarget)
                assertFalse(prepare.layerActionsReviewed)
                prepare.sliceAll(scope)
                assertTrue(prepare.slice is SliceState.Failed)
                prepare.nozzle = oldNozzle
                prepare.reviewLayerActions()
                val reviewedProject = prepare.toProject(forSlice = true)
                val changedMachine = SliceRequest(reviewedProject, prepare.profileDir(), QualityPreset.STANDARD,
                    false, 15, emptyList(), layerActionMachine = "{}")
                val incompatible = runCatching {
                    SliceEngine.nativeRequest(changedMachine, root, root.resolve("unexpected.gcode"))
                }.exceptionOrNull()
                assertTrue(incompatible?.message.orEmpty().contains("changed after review"))
                val restored = slice()
                assertTrue(restored.result.gcode.readText().contains(";NIA_LAYER_ACTION ${edited.id}\nM117 Layer fixture"))
                prepare.showPlate(1)
                assertFalse(slice().result.gcode.readText().contains("NIA_LAYER_ACTION"))
                prepare.showPlate(0)
                val beforeRecovery = prepare.toProject()
                val manifest = requireNotNull(beforeRecovery.manifest)
                val broken = manifest.plates[1].let {
                    it.copy(unknown = JSONObject(it.unknown.toString()).put("nia_layer_actions", "future data").put("future_plate_key", 17))
                }
                val recoveryFile = root.resolve("unreadable-actions.3mf")
                recoveryFile.writeBytes(ThreeMf.write(beforeRecovery.copy(manifest = manifest.copy(
                    plates = listOf(manifest.plates[0], broken),
                ))))
                prepare.open(recoveryFile)
                assertNotNull(prepare.layerActionProblem)
                assertEquals(edited, prepare.layerActions.getValue(firstPlate).single())
                assertEquals("future data", prepare.toProject().manifest!!.plates[1].unknown.getString("nia_layer_actions"))
                prepare.discardUnreadableLayerActions()
                assertNull(prepare.layerActionProblem)
                assertFalse(prepare.toProject().manifest!!.plates[1].unknown.has("nia_layer_actions"))
                assertEquals(17, prepare.toProject().manifest!!.plates[1].unknown.getInt("future_plate_key"))
                prepare.history.undo()
                assertNotNull(prepare.layerActionProblem)
                prepare.history.redo()
                assertNull(prepare.layerActionProblem)
                prepare.showPlate(0)
                prepare.removeLayerAction(firstPlate, edited.id)
                assertFalse(slice().result.gcode.readText().contains("NIA_LAYER_ACTION"))
            }
        } finally {
            scope.cancel()
            app.fleet.shutdown()
            root.deleteRecursively()
        }
    }
}
