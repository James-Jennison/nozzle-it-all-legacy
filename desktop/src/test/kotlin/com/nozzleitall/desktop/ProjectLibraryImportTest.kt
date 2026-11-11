package com.nozzleitall.desktop

import com.nozzleitall.desktop.projects.ProjectLibrary
import com.nozzleitall.project.Mesh
import com.nozzleitall.project.ModelObject
import com.nozzleitall.project.Project3mf
import com.nozzleitall.project.ProjectManifest
import com.nozzleitall.project.ThreeMf
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectLibraryImportTest {
    private fun fixture(): Project3mf {
        val producer = ProjectManifest.Producer("Nozzle It All", "desktop", "test")
        val manifest = ProjectManifest("stable-project-id", 4, "Test project", producer, producer, 1L)
        val mesh = Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2))
        return Project3mf(listOf(ModelObject(1, "Triangle", mesh)), mapOf("Title" to "Test project"), manifest)
    }

    private fun library(root: File) = ProjectLibrary(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure())

    private fun changedManifest(original: ByteArray, replacement: ByteArray?): ByteArray {
        val entries = ThreeMf.readEntries(ByteArrayInputStream(original)).toMutableMap()
        if (replacement == null) entries.remove(ProjectManifest.ARCHIVE_PATH)
        else entries[ProjectManifest.ARCHIVE_PATH] = replacement
        return ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        } }.toByteArray()
    }

    @Test fun duplicateIdentityIsRejectedWithoutChangingEitherCopy() {
        val root = Files.createTempDirectory("project-import-duplicate").toFile()
        val source = File(root, "source.3mf").apply { writeBytes(ThreeMf.write(fixture())) }
        val library = library(root)
        val imported = library.import(source, "test")
        val importedBytes = imported.readBytes()
        val sourceBytes = source.readBytes()
        val error = assertThrows(IllegalStateException::class.java) { library.import(source, "test") }
        assertTrue(error.message!!.contains("already exists"))
        assertArrayEquals(sourceBytes, source.readBytes())
        assertArrayEquals(importedBytes, imported.readBytes())
        assertEquals(listOf("stable-project-id"), library.list().map { it.projectId })
        assertEquals(4L, ThreeMf.read(imported).manifest!!.revision)
    }

    @Test fun damagedOrMismatchedNozzleDetailsAreNotSilentlyReplaced() {
        val root = Files.createTempDirectory("project-import-damaged").toFile()
        val library = library(root)
        val valid = ThreeMf.write(fixture())
        val damaged = File(root, "damaged.3mf").apply { writeBytes(changedManifest(valid, "{bad json".toByteArray())) }
        val changed = File(root, "changed.3mf").apply {
            writeBytes(changedManifest(valid, fixture().manifest!!.copy(revision = 5).canonicalText().toByteArray()))
        }
        val missing = File(root, "missing.3mf").apply { writeBytes(changedManifest(valid, null)) }
        val zeroRevision = File(root, "zero.3mf").apply { writeBytes(ThreeMf.write(fixture().copy(manifest = fixture().manifest!!.copy(revision = 0)))) }
        for (source in listOf(damaged, changed, missing, zeroRevision)) {
            val before = source.readBytes()
            assertThrows(IllegalStateException::class.java) { library.import(source, "test") }
            assertArrayEquals(before, source.readBytes())
        }
        assertTrue(library.list().isEmpty())
    }

    @Test fun foreign3mfGetsANewIdentityAndReopens() {
        val root = Files.createTempDirectory("project-import-foreign").toFile()
        val source = File(root, "foreign.3mf").apply { writeBytes(ThreeMf.write(fixture().copy(manifest = null))) }
        val sourceBytes = source.readBytes()
        val imported = library(root).import(source, "test")
        val reopened = ThreeMf.read(imported)
        assertNotNull(reopened.manifest?.projectId)
        assertNotEquals("stable-project-id", reopened.manifest!!.projectId)
        assertEquals(1L, reopened.manifest!!.revision)
        assertEquals(1, reopened.objects.size)
        assertArrayEquals(sourceBytes, source.readBytes())
    }

    @Test fun foreignMultiPlateImportKeepsPlateAndMaterialAssignments() {
        val root = Files.createTempDirectory("project-import-multi-plate").toFile()
        val first = fixture().objects.single()
        val foreign = fixture().copy(manifest = null, objects = listOf(
            first.copy(id = 1, name = "First", filament = 2, settings = mapOf("wall_loops" to "4"), plate = 0),
            first.copy(id = 2, name = "Second", filament = 3, plate = 1)),
            plateNames = listOf("Front", "Back"))
        val source = File(root, "multi.3mf").apply { writeBytes(ThreeMf.write(foreign)) }
        val sourceBytes = source.readBytes()
        val library = library(root)
        val review = assertThrows(IllegalStateException::class.java) { library.import(source, "test") }
        assertTrue(review.message.orEmpty().contains("needs conversion review"))
        val imported = ThreeMf.read(library.importReviewed(source, "test", ThreeMf.read(source)))
        assertEquals(listOf("Front", "Back"), imported.manifest!!.plates.map { it.name })
        assertEquals(2, imported.manifest!!.plates.mapNotNull { it.id }.toSet().size)
        assertEquals(listOf(listOf(1), listOf(2)), imported.manifest!!.plates.map { plate -> plate.objects.map { it.objectId } })
        assertEquals(listOf(2, 3), imported.manifest!!.plates.flatMap { plate -> plate.objects.map { it.materialSlot } })
        assertEquals("4", imported.manifest!!.plates.first().objects.single().settings["wall_loops"])
        assertArrayEquals(sourceBytes, source.readBytes())
    }

    @Test fun trashKeepsIdentityReservedUntilRestore() {
        val root = Files.createTempDirectory("project-import-trash").toFile()
        val source = File(root, "part-with-dash.3mf").apply { writeBytes(ThreeMf.write(fixture())) }
        val library = library(root)
        val original = library.import(source, "test")
        val originalBytes = original.readBytes()
        val trashed = library.moveToTrash(original)
        assertThrows(IllegalStateException::class.java) { library.import(source, "test") }
        assertArrayEquals(originalBytes, trashed.readBytes())
        val restored = library.restore(trashed)
        assertEquals("part-with-dash.3mf", restored.name)
        assertEquals("stable-project-id", ThreeMf.read(restored).manifest!!.projectId)
        assertArrayEquals(originalBytes, restored.readBytes())
    }

    @Test fun legacyTrashNameRestoresWithoutDroppingFilenameDashes() {
        val root = Files.createTempDirectory("project-import-legacy-trash").toFile()
        val library = library(root)
        val trash = File(library.dir, ".trash").apply { mkdirs() }
        val oldEntry = File(trash, "123-part-with-dash.3mf").apply { writeBytes(ThreeMf.write(fixture())) }
        val restored = library.restore(oldEntry)
        assertEquals("part-with-dash.3mf", restored.name)
        assertEquals("stable-project-id", ThreeMf.read(restored).manifest!!.projectId)
    }

    @Test fun duplicateLegacyTrashIdentitiesCannotBeRestoredSilently() {
        val root = Files.createTempDirectory("project-import-duplicate-trash").toFile()
        val library = library(root)
        val trash = File(library.dir, ".trash").apply { mkdirs() }
        val bytes = ThreeMf.write(fixture())
        val first = File(trash, "123-first.3mf").apply { writeBytes(bytes) }
        val second = File(trash, "124-second.3mf").apply { writeBytes(bytes) }
        assertThrows(IllegalStateException::class.java) { library.restore(first) }
        assertArrayEquals(bytes, first.readBytes())
        assertArrayEquals(bytes, second.readBytes())
        assertTrue(library.list().isEmpty())
    }

    @Test fun trashOperationsCannotMoveAnUnrelatedSourceFile() {
        val root = Files.createTempDirectory("project-import-trash-boundary").toFile()
        val library = library(root)
        val outside = File(root, "outside.3mf").apply { writeBytes(ThreeMf.write(fixture())) }
        val original = outside.readBytes()
        assertThrows(IllegalStateException::class.java) { library.moveToTrash(outside) }
        assertThrows(IllegalStateException::class.java) { library.restore(outside) }
        assertArrayEquals(original, outside.readBytes())
        assertTrue(library.list().isEmpty())
    }

    @Test fun unreadableLibraryFileStopsImportWithoutReplacingIt() {
        val root = Files.createTempDirectory("project-import-unreadable").toFile()
        val library = library(root)
        val existing = File(library.dir, "unreadable.3mf").apply { writeBytes("broken 3MF".toByteArray()) }
        val source = File(root, "valid.3mf").apply { writeBytes(ThreeMf.write(fixture())) }
        val existingBytes = existing.readBytes()
        val sourceBytes = source.readBytes()
        assertThrows(IllegalStateException::class.java) { library.import(source, "test") }
        assertArrayEquals(existingBytes, existing.readBytes())
        assertArrayEquals(sourceBytes, source.readBytes())
        assertEquals(setOf("unreadable.3mf"), library.dir.listFiles()!!.filter { it.name.endsWith(".3mf") }.map { it.name }.toSet())
    }

    @Test fun unknownNozzleMetadataWithoutAManifestIsNotReidentified() {
        val root = Files.createTempDirectory("project-import-future-metadata").toFile()
        val source = File(root, "future.3mf").apply {
            writeBytes(ThreeMf.write(fixture().copy(manifest = null, metadata = mapOf("nozzle:FutureIdentity" to "future-id"))))
        }
        val sourceBytes = source.readBytes()
        assertThrows(IllegalStateException::class.java) { library(root).import(source, "test") }
        assertArrayEquals(sourceBytes, source.readBytes())
        assertTrue(library(root).list().isEmpty())
    }

    @Test fun simultaneousLibraryInstancesCannotCreateTheSameIdentity() {
        val root = Files.createTempDirectory("project-import-race").toFile()
        val sources = (1..2).map { i -> File(root, "copy-$i.3mf").apply { writeBytes(ThreeMf.write(fixture())) } }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = sources.map { source -> pool.submit<Boolean> {
                start.await()
                runCatching { library(root).import(source, "test") }.isSuccess
            } }
            start.countDown()
            assertEquals(listOf(false, true), results.map { it.get() }.sorted())
            assertEquals(1, library(root).list().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun simultaneousNewSavesWithSameNameGetSeparateFiles() {
        val root = Files.createTempDirectory("project-save-new-race").toFile()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { i -> pool.submit<File> {
                start.await()
                val project = fixture().let { it.copy(manifest = it.manifest!!.copy(projectId = "new-$i", revision = 1)) }
                library(root).saveNew(project, "Same name")
            } }
            start.countDown()
            val saved = results.map { it.get() }
            assertEquals(2, saved.toSet().size)
            assertEquals(setOf("new-1", "new-2"), saved.map { ThreeMf.read(it).manifest!!.projectId }.toSet())
        } finally { pool.shutdownNow() }
    }

    @Test fun savesCannotEscapeTheLibraryOrWriteIntoTrash() {
        val root = Files.createTempDirectory("project-save-boundary").toFile()
        val library = library(root)
        val outside = File(root, "outside.3mf")
        val trash = File(library.dir, ".trash").apply { mkdirs() }
        val insideTrash = File(trash, "project.3mf")
        val project = fixture().let { it.copy(manifest = it.manifest!!.copy(revision = 1)) }
        assertThrows(IllegalStateException::class.java) { library.save(project, outside) }
        assertThrows(IllegalStateException::class.java) { library.save(project, insideTrash) }
        assertFalse(outside.exists())
        assertFalse(insideTrash.exists())
        val saved = library.fileFor("Test project")
        library.save(project, saved)
        assertEquals(project.manifest!!.projectId, ThreeMf.read(saved).manifest!!.projectId)
    }

    @Test fun manifestWithoutItsRecordedDigestCannotEnterTheLibrary() {
        val root = Files.createTempDirectory("project-import-no-digest").toFile()
        val source = File(root, "missing-digest.3mf")
        val original = ThreeMf.write(fixture())
        val entries = ThreeMf.readEntries(ByteArrayInputStream(original)).toMutableMap()
        val model = "3D/3dmodel.model"
        val xml = String(entries.getValue(model), Charsets.UTF_8)
        entries[model] = xml.replace(Regex("\\s*<metadata name=\"${ProjectManifest.META_MANIFEST_SHA256}\">[^<]*</metadata>"), "").toByteArray()
        assertFalse(String(entries.getValue(model), Charsets.UTF_8).contains(ProjectManifest.META_MANIFEST_SHA256))
        source.writeBytes(ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        } }.toByteArray())
        val before = source.readBytes()
        assertThrows(IllegalStateException::class.java) { library(root).import(source, "test") }
        assertArrayEquals(before, source.readBytes())
        val library = library(root)
        File(library.dir, source.name).writeBytes(before)
        assertNotNull(library.list().single().problem)
    }

    @Test fun archivedIdentityRemainsReservedWhenHeadIsMissing() {
        val root = Files.createTempDirectory("project-import-archive-reservation").toFile()
        val library = library(root)
        val head = library.fileFor("Test project")
        val first = fixture().let { it.copy(manifest = it.manifest!!.copy(revision = 1)) }
        library.save(first, head)
        library.save(first.copy(manifest = first.manifest!!.copy(revision = 2)), head)
        assertTrue(head.delete())
        val source = File(root, "source.3mf").apply { writeBytes(ThreeMf.write(fixture())) }
        val sourceBytes = source.readBytes()
        assertThrows(IllegalStateException::class.java) { library.import(source, "test") }
        assertArrayEquals(sourceBytes, source.readBytes())
    }

    @Test fun pendingRecoveryIdentityRemainsReservedForImport() {
        val root = Files.createTempDirectory("project-import-recovery-reservation").toFile()
        val library = library(root)
        val pending = File(library.dir, ".Test project.3mf.123.part").apply { writeBytes(ThreeMf.write(fixture())) }
        val source = File(root, "source.3mf").apply { writeBytes(ThreeMf.write(fixture())) }
        val before = source.readBytes()
        assertThrows(IllegalStateException::class.java) { library.import(source, "test") }
        assertArrayEquals(before, source.readBytes())
        assertTrue(pending.exists())
    }

    @Test fun blankIdentityIsRejectedWithoutModifyingTheSource() {
        val root = Files.createTempDirectory("project-import-blank-id").toFile()
        val project = fixture().let { it.copy(manifest = it.manifest!!.copy(projectId = " ")) }
        val source = File(root, "blank.3mf").apply { writeBytes(ThreeMf.write(project)) }
        val original = source.readBytes()
        assertThrows(IllegalStateException::class.java) { library(root).import(source, "test") }
        assertArrayEquals(original, source.readBytes())
        assertTrue(library(root).list().isEmpty())
    }

    @Test fun pendingRecoveryIdentityAlsoReservesNewSaves() {
        val root = Files.createTempDirectory("project-save-pending-id").toFile()
        val library = library(root)
        val first = fixture().let { it.copy(manifest = it.manifest!!.copy(revision = 1)) }
        val pending = File(library.dir, ".Test project.3mf.123.part").apply { writeBytes(ThreeMf.write(first)) }
        assertThrows(IllegalStateException::class.java) { library.saveNew(first, "New project") }
        assertTrue(pending.exists())
        assertTrue(library.list().isEmpty())
    }

    @Test fun unrecognizedHyphenatedTrashNameIsNotSilentlyShortened() {
        val root = Files.createTempDirectory("project-trash-unknown-name").toFile()
        val library = library(root)
        val entry = File(library.dir, ".trash/My-Project.3mf").apply { parentFile.mkdirs(); writeBytes(ThreeMf.write(fixture())) }
        assertThrows(IllegalStateException::class.java) { library.restore(entry) }
        assertTrue(entry.exists())
        assertFalse(File(library.dir, "Project.3mf").exists())
    }
}
