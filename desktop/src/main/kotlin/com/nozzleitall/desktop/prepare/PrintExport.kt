package com.nozzleitall.desktop.prepare

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.swing.JFileChooser

internal object PrintExport {
    data class Entry(val file: File, val sha256: String, val name: String)

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(bytes)
                if (size < 0) break
                digest.update(bytes, 0, size)
            }
        }
        return hex(digest.digest())
    }

    fun safeName(name: String, fallback: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim('.', ' ').take(80).trimEnd('.', ' ').ifBlank { fallback }

    fun initialDirectory(last: File?, project: File?, home: File): File? =
        listOfNotNull(last, project, home.resolve("Documents"), home).firstOrNull { it.isDirectory && it.canWrite() }

    fun requireOutsideCache(target: File, parent: File, cache: File) {
        val privatePath = cache.canonicalFile.toPath()
        require(listOf(target, parent).none { it.canonicalFile.toPath().startsWith(privatePath) }) {
            "Choose a destination outside the slice cache."
        }
    }

    fun chooseDirectory(initial: File?): File? {
        val chooser = JFileChooser(initial).apply {
            dialogTitle = "Choose a folder for the sliced plates"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showDialog(null, "Export here") == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    fun single(entry: Entry, destination: File, stillCurrent: () -> Boolean) {
        val target = destination.absoluteFile
        val parent = requireNotNull(target.parentFile)
        require(parent.isDirectory) { "Choose an existing export folder." }
        require(entry.file.canonicalFile != target.canonicalFile) { "Choose a destination outside the slice cache." }
        val stage = Files.createTempFile(parent.toPath(), ".nia-export-", ".part").toFile()
        try {
            copyVerified(entry, stage)
            check(stillCurrent()) { "The prepared slice changed. Slice again before exporting." }
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            stage.delete()
        }
    }

    fun batch(entries: List<Entry>, parent: File, projectName: String, stillCurrent: () -> Boolean): File {
        require(entries.isNotEmpty() && entries.map { it.name }.distinct().size == entries.size &&
            entries.all { File(it.name).name == it.name && it.name != "." && it.name != ".." }) {
            "Slice all populated plates before exporting."
        }
        require(parent.isDirectory) { "Choose an existing export folder." }
        val stage = Files.createTempDirectory(parent.toPath(), ".nia-export-").toFile()
        try {
            for (entry in entries) copyVerified(entry, stage.resolve(entry.name))
            check(stillCurrent()) { "The prepared plates changed. Slice all plates again before exporting." }
            val name = safeName(projectName, "Project")
            val destination = parent.resolve("$name - sliced plates - ${UUID.randomUUID().toString().take(8)}")
            Files.move(stage.toPath(), destination.toPath())
            return destination
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun copyVerified(entry: Entry, destination: File) {
        check(entry.file.isFile && entry.file.length() > 0) { "A prepared plate file is missing. Slice it again." }
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(entry.file.inputStream().buffered(), digest).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        check(hex(digest.digest()) == entry.sha256) { "A prepared plate file changed. Slice it again before exporting." }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
