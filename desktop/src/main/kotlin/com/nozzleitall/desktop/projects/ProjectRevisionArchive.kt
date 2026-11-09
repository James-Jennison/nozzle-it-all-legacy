package com.nozzleitall.desktop.projects

import com.nozzleitall.project.ProjectManifest
import com.nozzleitall.project.ReadLimits
import com.nozzleitall.project.ThreeMf
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** Immutable copies of replaced project heads, stored beside (but outside) the library's visible 3MF files. */
internal class ProjectRevisionArchive(private val projects: File) {
    data class Revision(val projectId: String, val revision: Long, val contentSha256: String, val file: File,
                        val problem: String? = null)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun folder(projectId: String): File = File(File(projects, ".revisions"),
        ThreeMf.sha256(projectId.toByteArray(Charsets.UTF_8)))

    private fun syncDirectory(directory: File) {
        com.nozzleitall.desktop.LocalFileDurability.syncDirectory(directory)
    }

    fun current(head: File, manifest: ProjectManifest): Revision =
        Revision(manifest.projectId, manifest.revision, sha256(head), head)

    fun capture(head: File, manifest: ProjectManifest): Revision {
        val parent = folder(manifest.projectId).apply { mkdirs() }
        // Persist newly created path components before allowing the head to be replaced.
        syncDirectory(projects)
        syncDirectory(requireNotNull(parent.parentFile))
        val digest = sha256(head)
        val archived = File(parent, "${manifest.revision}-$digest.3mf")
        if (archived.exists()) {
            check(sha256(archived) == digest) { "An archived project revision differs from its recorded digest." }
            syncDirectory(parent)
            return Revision(manifest.projectId, manifest.revision, digest, archived)
        }
        val temporary = File(parent, ".${UUID.randomUUID()}.part")
        try {
            Files.copy(head.toPath(), temporary.toPath())
            FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            check(sha256(temporary) == digest) { "The project changed while its previous revision was being archived." }
            Files.move(temporary.toPath(), archived.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(parent)
            return Revision(manifest.projectId, manifest.revision, digest, archived)
        } finally { temporary.delete() }
    }

    fun archived(projectId: String): List<Revision> = (folder(projectId).listFiles { f -> f.isFile && f.name.endsWith(".3mf") }
        ?: emptyArray()).map { file ->
        runCatching {
            val match = Regex("^([0-9]+)-([0-9a-f]{64})\\.3mf$").matchEntire(file.name)
                ?: error("An archived project revision has an unexpected filename: ${file.name}")
            val digest = match.groupValues[2]
            check(sha256(file) == digest) { "An archived project revision differs from its recorded digest: ${file.name}" }
            val revision = match.groupValues[1].toLong()
            val manifest = ThreeMf.read(file, ReadLimits(maxTriangles = 50_000_000)).manifest
            check(manifest?.projectId == projectId && manifest.revision == revision) { "An archived project revision has the wrong identity or revision: ${file.name}" }
            Revision(projectId, revision, digest, file)
        }.getOrElse { Revision(projectId, 0, "", file, it.message ?: "This archived revision cannot be verified.") }
    }.sortedBy { it.revision }
}
