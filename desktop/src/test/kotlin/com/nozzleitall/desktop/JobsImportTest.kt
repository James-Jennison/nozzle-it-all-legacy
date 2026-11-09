package com.nozzleitall.desktop

import com.nozzleitall.project.Mesh
import com.nozzleitall.project.MeshIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JobsImportTest {
    @Test fun laterInvalidModelDoesNotReplaceOpenDraft() {
        val root = Files.createTempDirectory("jobs-import").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "c"), File(root, "d"), File(root, "k")).ensure(), scope)
        try {
            val valid = MeshIO.Loaded(Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)))
            app.prepare.importLoadedModel(File("original.stl"), valid)
            val before = app.prepare.items.single()
            assertEquals(setOf(before.id), app.prepare.selectedIds)
            assertEquals(before.id, app.prepare.selected)
            val invalid = MeshIO.Loaded(Mesh(floatArrayOf(1f), intArrayOf()))
            assertThrows(IndexOutOfBoundsException::class.java) {
                app.prepare.newProjectWithLoadedModels(listOf(File("new.stl") to valid, File("bad.stl") to invalid))
            }
            assertEquals("original", app.prepare.name)
            assertEquals(listOf(before), app.prepare.items.toList())
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }

    @Test fun emptyProjectImportIsRejectedBeforeReplacingTheDraft() {
        val root = Files.createTempDirectory("jobs-import-empty").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "c"), File(root, "d"), File(root, "k")).ensure(), scope)
        try {
            assertThrows(IllegalArgumentException::class.java) { app.prepare.newProjectWithLoadedModels(emptyList()) }
            assertEquals("Untitled project", app.prepare.name)
            assertTrue(app.prepare.items.isEmpty())
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }

    @Test fun multiModelProjectSelectsEveryImportedObjectAndFocusesTheLast() {
        val root = Files.createTempDirectory("jobs-import-multi").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "c"), File(root, "d"), File(root, "k")).ensure(), scope)
        try {
            val mesh = MeshIO.Loaded(Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)))
            app.prepare.newProjectWithLoadedModels(listOf(File("one.stl") to mesh, File("two.stl") to mesh))
            assertEquals(setOf(1, 2), app.prepare.selectedIds)
            assertEquals(2, app.prepare.selected)
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }
}
