package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The menu bar's Plate and View menus and the items added to File, Edit and Help. */
class MenusTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())

    private fun app(): AppState {
        val tmp = Files.createTempDirectory("menus").toFile()
        return AppState(AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
    private fun AppState.item(menu: String, label: String) = appMenus(this) {}.first { it.title == menu }.items.filterNotNull().first { it.label == label }

    @Test fun theBarHasPlateCalibrationAndViewMenusBetweenEditAndGo() {
        assertEquals(listOf("File", "Edit", "Plate", "Calibration", "View", "Go", "Help"), appMenus(app()) {}.map { it.title })
    }

    @Test fun plateMenuAddsMovesBetweenAndDeletesPlates() {
        val a = app(); val p = a.prepare
        assertFalse(a.item("Plate", "Delete this plate").enabled); assertFalse(a.item("Plate", "Next plate").enabled)
        a.item("Plate", "Add plate").run()
        assertEquals(2, p.plateNames.size); assertEquals(1, p.currentPlate)
        assertTrue(a.item("Plate", "Previous plate").enabled); assertFalse(a.item("Plate", "Next plate").enabled)
        a.item("Plate", "Previous plate").run(); assertEquals(0, p.currentPlate)
        a.item("Plate", "Delete this plate").run(); assertEquals(1, p.plateNames.size)
    }

    @Test fun duplicateAndReorderKeepPlateIdentityAndUndo() {
        val a = app()
        val p = a.prepare
        val original = p.plateIds.single()
        p.items += PrepItem(1, "cube", cube, 50f, 60f, rotZ = 15f)
        p.items += PrepItem(2, "instance", cube, 90f, 60f).also { it.instanceOf = 1 }
        p.history.reset()
        a.item("Plate", "Duplicate plate").run()
        val copy = p.plateIds.last()
        assertNotEquals(original, copy)
        val (duplicate, instance) = p.plateItems()
        assertNotEquals(1, duplicate.id)
        assertNotSame(cube, duplicate.mesh)
        assertEquals(duplicate.id, instance.instanceOf)
        p.selectObject(duplicate.id)
        assertEquals(60f, duplicate.y, 0f)
        assertEquals(15f, duplicate.rotZ, 0f)
        a.item("Plate", "Move plate left").run()
        assertEquals(listOf(copy, original), p.plateIds.toList())
        assertEquals(duplicate.id, p.plateItems().first().id)
        assertEquals(duplicate.id, p.selected)
        p.history.undo()
        assertEquals(listOf(original, copy), p.plateIds.toList())
        p.history.undo()
        assertEquals(listOf(original), p.plateIds.toList())
        assertEquals(listOf(1, 2), p.plateItems().map { it.id })
    }

    @Test fun commandShortcutPersistsRejectsConflictsAndRestoresDefault() {
        val a = app()
        val file = File(a.paths.config, "test-shortcuts.json")
        val shortcuts = CommandShortcuts(file)
        val menus = appMenus(a) {}
        assertNotNull(shortcuts.assign("arrange", "K", menus))
        assertNull(shortcuts.assign("arrange", "J", menus))
        assertNotNull(shortcuts.assign("orient", "J", menus))
        val reopened = CommandShortcuts(file)
        assertEquals("Ctrl+Shift+J", reopened.applyTo(menus).first { it.title == "Edit" }
            .items.filterNotNull().first { it.commandId == "arrange" }.shortcut?.text)
        assertNull(reopened.assign("arrange", null, menus))
        assertNull(CommandShortcuts(file).letter("arrange"))
    }

    @Test fun viewMenuSetsTheCameraAndIsometricRestoresIt() {
        val a = app(); val c = a.prepare.camera
        a.item("View", "Top view").run(); assertEquals(0f, c.pitch, 0f)
        a.item("View", "Side view").run(); assertEquals(89f, c.pitch, 0f)
        val yaw = c.yaw; a.item("View", "Rotate view right").run(); assertEquals(yaw + 90f, c.yaw, 0f)
        a.item("View", "Isometric view").run(); assertEquals(-35f, c.yaw, 0f); assertEquals(55f, c.pitch, 0f)
    }

    @Test fun deleteAllClearsThePlateAndReloadNeedsASavedFile() {
        val a = app(); val p = a.prepare
        assertFalse(a.item("Edit", "Delete all").enabled); assertFalse(a.item("File", "Reload from disk").enabled)
        p.items += PrepItem(1, "cube", cube, 50f, 50f); p.items += PrepItem(2, "cube 2", cube, 90f, 50f)
        a.item("Edit", "Delete all").run()
        // The project has not been saved, so there is nothing to ask about; it just empties.
        assertTrue(p.items.isEmpty()); assertNull(p.selected)
    }

    @Test fun fileMenuListsTheNewestProjectsFirstAndOpensOne() {
        val a = app()
        assertTrue(appMenus(a) {}.first { it.title == "File" }.items.filterNotNull().none { it.label.startsWith("Recent: ") })
        val old = File(a.library.dir, "Old.3mf"); val new = File(a.library.dir, "New.3mf")
        ThreeMf.writeAtomically(Project3mf(listOf(ModelObject(1, "cube", cube))), old); old.setLastModified(1_000_000)
        ThreeMf.writeAtomically(Project3mf(listOf(ModelObject(1, "cube", cube))), new); new.setLastModified(2_000_000)
        val recent = appMenus(a) {}.first { it.title == "File" }.items.filterNotNull().filter { it.label.startsWith("Recent: ") }
        assertEquals(listOf("Recent: New", "Recent: Old"), recent.map { it.label })
    }

    @Test fun dirtyEmptyProjectCanCancelOrSaveBeforeNewProject() {
        val a = app()
        val p = a.prepare
        p.addPlate()
        val plates = p.plateIds.toList()
        a.item("File", "New project").run()
        assertNotNull(a.pendingProjectAction)
        a.cancelProjectReplacement()
        assertEquals(plates, p.plateIds.toList())
        assertTrue(p.dirty)

        a.item("File", "New project").run()
        a.continueProjectReplacement(save = true)
        assertNull(a.pendingProjectAction)
        assertFalse(p.dirty)
        assertEquals(1, p.plateIds.size)
        val saved = a.library.dir.listFiles()!!.single { it.extension == "3mf" }
        assertEquals(plates, ThreeMf.read(saved).manifest!!.plates.map { it.id })
    }

    @Test fun quitWaitsAndFailedSaveKeepsTheEditorOpen() {
        val a = app()
        val p = a.prepare
        p.addPlate()
        val saved = p.save()
        p.addPlate()
        ThreeMf.writeAtomically(Project3mf(listOf(ModelObject(1, "cube", cube))), saved)
        var quitCount = 0
        val quit = appMenus(a) { a.guardProjectReplacement { quitCount++ } }
            .first { it.title == "File" }.items.filterNotNull().single { it.label == "Quit" }
        quit.run()
        a.continueProjectReplacement(save = true)
        assertEquals(0, quitCount)
        assertNotNull(a.projectSaveProblem)
        assertNotNull(a.pendingProjectAction)
        assertTrue(p.dirty)
        a.continueProjectReplacement(save = false)
        assertEquals(1, quitCount)
    }

    @Test fun savingEditedExternalProjectLeavesItsSourceIntact() {
        val a = app()
        val external = File(a.paths.config, "Foreign.3mf")
        ThreeMf.writeAtomically(Project3mf(listOf(ModelObject(1, "cube", cube))), external)
        val original = external.readBytes()
        a.prepare.open(external)
        a.prepare.addPlate()
        val saved = a.prepare.save()
        assertEquals(a.library.dir.canonicalFile, saved.canonicalFile.parentFile)
        assertArrayEquals(original, external.readBytes())
        assertEquals(2, ThreeMf.read(saved).manifest!!.plates.size)
        assertFalse(a.prepare.dirty)
    }

    @Test fun dropping3mfOrGcodeLeavesCurrentProjectInPlaceUntilIntentIsChosen() {
        val a = app()
        a.prepare.addPlate()
        val plateIds = a.prepare.plateIds.toList()
        val dropped = File(a.paths.config, "model.3mf")
        a.receiveWorkspaceDrop(listOf(dropped))
        assertEquals(dropped, a.droppedProject)
        assertEquals(plateIds, a.prepare.plateIds.toList())
        assertTrue(a.prepare.dirty)

        a.droppedProject = null
        val gcode = File(a.paths.config, "fixture.gcode")
        a.receiveWorkspaceDrop(listOf(gcode))
        assertEquals(gcode, a.gcodeInspection)
        assertEquals(plateIds, a.prepare.plateIds.toList())
        assertTrue(a.fleet.queue.value.isEmpty())
    }

    @Test fun calibrationMenuStartsATestPrintWithItsSettingsAndPostProcessing() {
        val a = app(); val p = a.prepare
        assertEquals(net.jamesjennison.klippercompanion.CalibrationKind.entries.map { it.label }, appMenus(a) {}.first { it.title == "Calibration" }.items.filterNotNull().map { it.label })
        a.item("Calibration", "Temperature tower").run()
        assertEquals(net.jamesjennison.klippercompanion.CalibrationKind.TEMPERATURE, p.calibration?.kind)
        val item = p.items.single()
        // Closed: every edge is shared by exactly two triangles once equal corners are welded.
        val edges = HashMap<Pair<Int, Int>, Int>()
        for (t in 0 until item.mesh.triangles.size / 3) for (k in 0 until 3) {
            val u = item.mesh.triangles[t * 3 + k]; val v = item.mesh.triangles[t * 3 + (k + 1) % 3]
            edges.merge(minOf(u, v) to maxOf(u, v), 1, Int::plus)
        }
        assertTrue("open or non-manifold edges", edges.values.all { it % 2 == 0 })
        assertEquals(p.calibration!!.startTemp.toString(), p.overrides["nozzle_temperature"])
        p.newProject(); assertNull(p.calibration); assertTrue(p.overrides.isEmpty())
    }
}
