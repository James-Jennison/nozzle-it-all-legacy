package com.nozzleitall.desktop

import com.nozzleitall.desktop.projects.AndroidArchiveCopies
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AndroidArchiveCopiesTest {
    @Test fun importedAndroidArchiveIsByteIdenticalAndSourceIsUntouched() {
        val root = Files.createTempDirectory("nia-android-copy").toFile()
        val source = File(root, "sample.nozzleproj")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("project.json")); zip.write("{\"format\":1,\"name\":\"A\",\"plates\":[],\"objects\":[]}".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("models/part.stl")); zip.write("solid x\nendsolid x\n".toByteArray()); zip.closeEntry()
        }
        val original = source.readBytes()
        val store = AndroidArchiveCopies(File(root, "copies"))
        val copy = store.import(source)
        assertArrayEquals(original, source.readBytes())
        assertArrayEquals(original, copy.file.readBytes())
        assertEquals(copy, store.import(source))
        assertTrue(store.verify(copy))
        copy.file.appendText("changed")
        assertFalse(store.verify(copy))
        assertThrows(IllegalStateException::class.java) { store.import(source) }
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun malformedArchiveIsRejectedWithoutACopy() {
        val root = Files.createTempDirectory("nia-android-bad").toFile()
        val source = File(root, "bad.nozzleproj").apply { writeText("not a zip") }
        val store = AndroidArchiveCopies(File(root, "copies"))
        assertThrows(Exception::class.java) { store.import(source) }
        assertTrue(File(root, "copies").listFiles().isNullOrEmpty())
    }

    @Test fun unreadableManifestIsRejectedWithoutACopy() {
        val root = Files.createTempDirectory("nia-android-manifest").toFile()
        val source = File(root, "bad.nozzleproj")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("project.json")); zip.write("not json".toByteArray()); zip.closeEntry()
        }
        val original = source.readBytes()
        val store = AndroidArchiveCopies(File(root, "copies"))
        assertThrows(IllegalArgumentException::class.java) { store.import(source) }
        assertArrayEquals(original, source.readBytes())
        assertTrue(File(root, "copies").listFiles().isNullOrEmpty())
    }
}
