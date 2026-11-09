package com.nozzleitall.desktop.projects

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import com.nozzleitall.desktop.LocalFileDurability
import java.util.zip.ZipFile

/** Lossless local copies of Android .nozzleproj exports, pending a separately reviewed conversion. */
class AndroidArchiveCopies(private val dir: File) {
    data class Copy(val sourceSha256: String, val file: File)

    fun import(source: File): Copy {
        require(source.isFile && source.extension.equals("nozzleproj", ignoreCase = true)) { "Select an Android .nozzleproj archive." }
        require(source.length() > 0)
        ZipFile(source).use { zip ->
            val manifest = requireNotNull(zip.getEntry("project.json")) { "Android project manifest is missing." }
            require(!manifest.isDirectory && manifest.size <= 1_048_576L) { "Android project manifest is too large." }
            val bytes = zip.getInputStream(manifest).use { input -> input.readNBytes(1_048_577) }
            require(bytes.isNotEmpty() && bytes.size <= 1_048_576) { "Android project manifest is missing or too large." }
            val json = try { org.json.JSONObject(String(bytes, Charsets.UTF_8)) }
                catch (e: org.json.JSONException) { throw IllegalArgumentException("Android project manifest is unreadable.", e) }
            require(json.optInt("format", -1) == 1) { "Unsupported Android project archive format." }
        }
        dir.mkdirs()
        val tmp = File(dir, ".${UUID.randomUUID()}.part")
        try {
            source.copyTo(tmp, overwrite = false)
            java.io.RandomAccessFile(tmp, "rw").use { it.fd.sync() }
            val sha = digest(tmp)
            val destination = File(dir, "$sha.nozzleproj")
            if (destination.exists()) {
                check(destination.isFile && destination.length() == tmp.length() && digest(destination) == sha) { "Existing Android archive copy differs." }
            } else {
                try { Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                catch (e: java.nio.file.FileAlreadyExistsException) {
                    check(destination.isFile && destination.length() == tmp.length() && digest(destination) == sha) { "Existing Android archive copy differs." }
                }
                LocalFileDurability.syncDirectory(dir)
            }
            return Copy(sha, destination)
        } finally { tmp.delete() }
    }

    fun verify(copy: Copy): Boolean = copy.file.isFile && copy.file.parentFile.canonicalFile == dir.canonicalFile &&
        copy.file.name == "${copy.sourceSha256}.nozzleproj" && digest(copy.file) == copy.sourceSha256

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) {
            val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n)
        } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
