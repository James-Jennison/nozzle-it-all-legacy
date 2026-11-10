package com.nozzleitall.desktop.prepare

import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.desktop.AppState
import com.nozzleitall.project.MeshIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PrintExportTest {
    @Test fun staleOrChangedOutputLeavesPriorFilesAndNoPartialExports() {
        val root = Files.createTempDirectory("nia-export-controls").toFile()
        try {
            val file = root.resolve("prepared.gcode").apply { writeText("G1 X1") }
            val entry = PrintExport.Entry(file, PrintExport.sha256(file), "1 - First.gcode")
            val target = root.resolve("existing.gcode").apply { writeText("Previously exported") }
            assertTrue(runCatching { PrintExport.single(entry, target) { false } }.isFailure)
            assertEquals("Previously exported", target.readText())
            file.writeText("G1 X2")
            assertTrue(runCatching { PrintExport.single(entry, target) { true } }.isFailure)
            assertTrue(runCatching { PrintExport.batch(listOf(entry), root, "Project") { true } }.isFailure)
            assertEquals("Previously exported", target.readText())
            assertEquals(setOf("prepared.gcode", "existing.gcode"), root.list().orEmpty().toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun initialDestinationPrefersLastWritableExportThenProjectAndDocuments() {
        val root = Files.createTempDirectory("nia-export-destination").toFile()
        try {
            val docs = root.resolve("Documents").apply { mkdir() }
            val project = root.resolve("Project").apply { mkdir() }
            val last = root.resolve("Previous").apply { mkdir() }
            assertEquals(last, PrintExport.initialDirectory(last, project, root))
            last.delete()
            assertEquals(project, PrintExport.initialDirectory(last, project, root))
            project.delete()
            assertEquals(docs, PrintExport.initialDirectory(last, project, root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun exportsExactPopulatedPlateFilesAndInvalidatesThemAfterEdit() = runBlocking {
        assumeNotNull(SliceEngine.locateEngine())
        val root = Files.createTempDirectory("nia-three-plate-export").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(root.resolve("config"), root.resolve("data"), root.resolve("cache")).ensure(), scope)
        try {
            withContext(Dispatchers.Main) {
                val prepare = app.prepare
                val repo = generateSequence(File("").absoluteFile) { it.parentFile }
                    .first { File(it, "settings.gradle.kts").isFile }
                val mesh = MeshIO.readStl(repo.resolve("site-src/assets/test-cube-20mm.stl").readBytes())
                prepare.items += PrepItem(1, "First cube", mesh, 90f, 90f)
                prepare.addPlate()
                prepare.addPlate()
                prepare.items += PrepItem(2, "Third cube", mesh, 135f, 135f, plate = 2).apply { sz = 0.5f }
                prepare.changed()
                prepare.sliceAll(scope)
                withTimeout(120_000) { while (prepare.slice is SliceState.Running) delay(25) }
                val entries = prepare.batchExportEntries()
                assertEquals(listOf("1 - Plate 1.gcode", "3 - Plate 3.gcode"), entries.map { it.name })
                assertNotEquals(entries[0].sha256, entries[1].sha256)
                val destination = root.resolve("export").apply { mkdir() }
                val output = PrintExport.batch(entries, destination, "Three plates") { true }
                assertEquals(entries.map { it.name }.toSet(), output.list().orEmpty().toSet())
                entries.forEach { entry -> assertEquals(entry.sha256, PrintExport.sha256(output.resolve(entry.name))) }
                prepare.items[0].x += 1f
                prepare.changed()
                assertTrue(prepare.batchExportEntries().isEmpty())
            }
        } finally {
            scope.cancel()
            app.fleet.shutdown()
            root.deleteRecursively()
        }
    }
}
