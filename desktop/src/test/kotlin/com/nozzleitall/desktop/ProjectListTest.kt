package com.nozzleitall.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.nozzleitall.project.ThreeMf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

class ProjectListTest {
    @Test fun blankImportedTitleFallsBackToVisibleFilenameInListAndEditor() {
        val repository = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val root = Files.createTempDirectory("blank-project-name").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val source = File(repository, "schemas/fixtures/kotlin-written.3mf")
        val copy = File(paths.projects, "Painted_Locomotive.3mf")
        val project = ThreeMf.read(source)
        copy.writeBytes(ThreeMf.write(project.copy(manifest = project.manifest!!.copy(name = ""),
            metadata = project.metadata + ("Title" to ""))))
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertEquals("Painted_Locomotive", app.library.list().single().name)
        app.prepare.open(copy)
        assertEquals("Painted_Locomotive", app.prepare.name)
    }

    @Test fun malformedSavedPresetIsMarkedForReviewInProjectList() {
        val repository = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val root = Files.createTempDirectory("invalid-project-preset").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val project = ThreeMf.read(File(repository, "schemas/fixtures/kotlin-written.3mf"))
        val manifest = project.manifest!!
        val extensions = JSONObject(manifest.extensions.toString())
            .put("desktop", JSONObject().put("presetSnapshot", JSONObject()
                .put("profileId", "snapmaker_u1").put("directoryName", "..")
                .put("files", JSONObject()).put("filamentProfiles", JSONObject())))
        val saved = File(paths.projects, "NeedsReview.3mf")
        saved.writeBytes(ThreeMf.write(project.copy(manifest = manifest.copy(extensions = extensions))))
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val summary = app.library.list().single()
        assertNotNull(summary.problem)
        assertTrue(summary.problem!!.contains("Saved preset needs review"))
        app.prepare.open(saved)
        assertNotNull(app.prepare.presetSnapshotProblem)

        val wrongType = File(paths.projects, "WrongDesktopType.3mf")
        wrongType.writeBytes(ThreeMf.write(project.copy(manifest = manifest.copy(
            extensions = JSONObject(manifest.extensions.toString()).put("desktop", "unexpected value")))))
        val wrongTypeSummary = app.library.list().single { it.file == wrongType }
        assertTrue(wrongTypeSummary.problem!!.contains("Saved preset needs review"))
        app.prepare.open(wrongType)
        assertNotNull(app.prepare.presetSnapshotProblem)
    }
}
