package com.nozzleitall.desktop.prepare

import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.desktop.AppState
import com.nozzleitall.project.Mesh
import com.nozzleitall.project.ProjectManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ObjectTreeStateTest {
    private fun app(): AppState {
        val root = Files.createTempDirectory("object-tree-state").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        return AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    private fun objectWithPart() = PrepItem(
        1,
        "Object with part",
        Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)),
        50f,
        50f,
    ).also {
        it.parts += ProjectManifest.ObjectPart("modifier", "cylinder", listOf(0.0, 0.0, 0.0), listOf(1.0, 1.0, 1.0))
    }

    @Test
    fun `hiding selected object preserves object selection and clears part selection`() {
        val prepare = app().prepare
        prepare.items += objectWithPart()
        prepare.selectPart(1, 0)
        val dirtyBefore = prepare.dirty

        prepare.toggleObjectVisibility(1)

        assertFalse(prepare.objectVisible(1))
        assertEquals(1, prepare.selected)
        assertEquals(-1, prepare.selectedPart)
        assertEquals(dirtyBefore, prepare.dirty)
        prepare.toggleObjectVisibility(1)
        assertTrue(prepare.objectVisible(1))
        assertEquals(1, prepare.selected)
    }

    @Test
    fun `new and opened projects clear transient object tree state`() {
        val prepare = app().prepare
        prepare.items += objectWithPart()
        prepare.selectPart(1, 0)
        val saved = prepare.save()
        prepare.toggleObjectVisibility(1)
        assertFalse(prepare.objectVisible(1))

        prepare.newProject()
        assertTrue(prepare.objectVisible(1))
        assertEquals(-1, prepare.selectedPart)

        prepare.items += objectWithPart()
        prepare.selectPart(1, 0)
        prepare.toggleObjectVisibility(1)
        prepare.open(saved)
        assertTrue(prepare.objectVisible(1))
        assertEquals(-1, prepare.selectedPart)
    }
}
