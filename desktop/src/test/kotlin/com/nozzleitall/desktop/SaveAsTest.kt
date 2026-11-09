package com.nozzleitall.desktop

import com.nozzleitall.project.ThreeMf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SaveAsTest {
    @Test fun saveAsCreatesANewLibraryIdentityWithoutChangingTheSource() {
        val repository = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val source = File(repository, "schemas/fixtures/kotlin-written.3mf")
        val before = source.readBytes()
        val root = Files.createTempDirectory("save-as-project").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        app.prepare.open(source)
        val originalId = app.prepare.manifest!!.projectId
        val target = File(paths.projects, "A second project.3mf")
        assertEquals(target, app.prepare.saveAs(target))
        assertArrayEquals(before, source.readBytes())
        assertEquals(target, app.prepare.file)
        assertEquals("A second project", app.prepare.name)
        assertNotEquals(originalId, app.prepare.manifest!!.projectId)
        assertEquals(1L, app.prepare.manifest!!.revision)
        val sourceRecord = app.prepare.manifest!!.extensions.getJSONObject("desktop").getJSONObject("saveAsSource")
        assertEquals(originalId, sourceRecord.getString("projectId"))
        assertEquals(ThreeMf.read(source).manifest!!.revision, sourceRecord.getLong("revision"))
        app.prepare.name = "Updated copy"
        app.prepare.changed()
        app.prepare.save()
        assertEquals(2L, ThreeMf.read(target).manifest!!.revision)
        assertThrows(IllegalStateException::class.java) { app.prepare.saveAs(source) }
        val nestedTarget = File(paths.projects, "nested/copied.3mf")
        assertThrows(IllegalStateException::class.java) { app.prepare.saveAs(nestedTarget) }
        assertFalse(nestedTarget.exists())
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun outsideCopyHasValidManifestAndExclusivePublishDoesNotReplaceAnExistingFile() {
        val repository = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val source = File(repository, "schemas/fixtures/kotlin-written.3mf")
        val root = Files.createTempDirectory("save-as-outside").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        app.prepare.open(source)
        val sourceBytes = source.readBytes()
        val target = File(root, "external-copy.3mf")
        app.prepare.saveAs(target)
        assertEquals(source, app.prepare.file)
        assertArrayEquals(sourceBytes, source.readBytes())
        val opened = ThreeMf.read(target)
        assertNotEquals(opened.manifest!!.projectId, app.prepare.manifest!!.projectId)
        assertEquals(1L, opened.manifest!!.revision)
        val sourceRecord = opened.manifest!!.extensions.getJSONObject("desktop").getJSONObject("saveAsSource")
        assertEquals(app.prepare.manifest!!.projectId, sourceRecord.getString("projectId"))
        assertEquals(app.prepare.manifest!!.revision, sourceRecord.getLong("revision"))
        assertEquals(ThreeMf.sha256(opened.manifest!!.canonicalText().toByteArray()),
            opened.metadata[com.nozzleitall.project.ProjectManifest.META_MANIFEST_SHA256])
        val before = target.readBytes()
        assertThrows(IllegalStateException::class.java) { app.prepare.saveAs(target) }
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            ThreeMf.writeAtomicallyNew(app.prepare.toProject(), target)
        }
        assertArrayEquals(before, target.readBytes())
    }
}
