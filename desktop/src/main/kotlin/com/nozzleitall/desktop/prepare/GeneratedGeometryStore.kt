package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Mesh
import com.nozzleitall.project.MeshIO
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal class GeneratedGeometryStore(private val root: File) {
    data class Pending(val receipt: String, val taskId: String?)
    class Asset(val taskId: String, val bytes: ByteArray, val credits: Int?)
    private val pendingFile get() = File(root, "pending.json")

    @Synchronized
    fun pending(): Pending? {
        val file = pendingFile
        return if (file.exists()) readReceipt(file) else null
    }

    private fun readReceipt(file: File): Pending {
        val bytes = file.inputStream().use { it.readNBytes(1025) }
        require(bytes.size <= 1024) { "The local Meshy request receipt is invalid." }
        val json = JSONObject(bytes.decodeToString())
        return Pending(MeshyProvider.taskId(json.opt("receipt")),
            json.opt("taskId")?.takeUnless { it == JSONObject.NULL }?.let { MeshyProvider.taskId(it) })
    }

    fun begin(): Pending = withReceiptLock {
        if (pending() != null) throw GeometryGenerationError("Resume the saved Meshy request first.")
        val value = Pending(UUID.randomUUID().toString(), null)
        publish(pendingFile, replace = false) { it.writeText(pendingJson(value).toString()) }
        value
    }

    fun attach(expected: Pending, taskId: String): Pending = withReceiptLock {
        if (expected.taskId != null || pending() != expected) throw GeometryGenerationError("The local Meshy request changed.")
        val value = expected.copy(taskId = MeshyProvider.taskId(taskId))
        publish(pendingFile, replace = true) { it.writeText(pendingJson(value).toString()) }
        value
    }

    fun clear(expected: Pending, reason: String) = withReceiptLock {
        if (pending() != expected) throw GeometryGenerationError("The local Meshy request changed.")
        val archived = File(root, "receipt-" + expected.receipt + ".json")
        if (archived.exists()) {
            if (readReceipt(archived) != expected)
                throw GeometryGenerationError("The archived Meshy receipt conflicts with this task.")
        } else publish(archived, replace = false) {
            it.writeText(pendingJson(expected).put("closedReason", reason).toString())
        }
        Files.delete(pendingFile.toPath())
    }

    private fun <T> withReceiptLock(block: () -> T): T = synchronized(receiptLock) {
        Files.createDirectories(root.toPath())
        FileChannel.open(File(root, "receipt.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            .use { channel ->
                val lock = channel.tryLock() ?: throw GeometryGenerationError("The local Meshy request is in use.")
                lock.use { block() }
            }
    }

    @Synchronized
    fun cache(asset: Asset) {
        require(asset.bytes.size in 1..MeshyProvider.MAX_ASSET)
        val file = assetFile(asset.taskId)
        if (file.isFile) {
            val stored = read(asset.taskId)
            check(stored != null && digest(stored.bytes) == digest(asset.bytes)) { "The existing Meshy asset has different bytes." }
            return
        }
        val metadata = JSONObject().put("provider", "Meshy").put("model", "meshy-6").put("mode", "preview")
            .put("format", "stl").put("taskId", asset.taskId).put("sha256", digest(asset.bytes))
            .putOpt("consumedCredits", asset.credits)
        publish(file, replace = false) { stage ->
            ZipOutputStream(stage.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("provenance.json"))
                zip.write(metadata.toString().encodeToByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("source.stl"))
                zip.write(asset.bytes)
                zip.closeEntry()
            }
        }
    }

    @Synchronized
    fun read(taskId: String): Asset? {
        val file = assetFile(taskId)
        if (!file.isFile) return null
        return ZipInputStream(file.inputStream().buffered()).use { zip ->
            require(zip.nextEntry?.name == "provenance.json") { "The local Meshy receipt is invalid." }
            val metadataBytes = zip.readNBytes(4097)
            require(metadataBytes.size <= 4096) { "The local Meshy receipt is invalid." }
            val metadata = JSONObject(metadataBytes.decodeToString())
            require(metadata.optString("taskId") == taskId && metadata.optString("provider") == "Meshy" &&
                metadata.optString("model") == "meshy-6" && metadata.optString("mode") == "preview") {
                "The local Meshy receipt does not match this task."
            }
            require(zip.nextEntry?.name == "source.stl") { "The local Meshy source is missing." }
            val bytes = zip.readNBytes(MeshyProvider.MAX_ASSET + 1)
            require(bytes.size <= MeshyProvider.MAX_ASSET && digest(bytes) == metadata.optString("sha256") &&
                zip.nextEntry == null) { "The local Meshy source does not match its receipt." }
            Asset(taskId, bytes, (metadata.opt("consumedCredits") as? Int)?.takeIf { it >= 0 })
        }
    }

    private fun assetFile(id: String) = File(root, MeshyProvider.taskId(id) + ".zip")
    private fun pendingJson(value: Pending) = JSONObject().put("receipt", value.receipt).putOpt("taskId", value.taskId)

    private fun publish(destination: File, replace: Boolean, write: (File) -> Unit) {
        Files.createDirectories(root.toPath())
        val stage = Files.createTempFile(root.toPath(), ".meshy-", ".tmp")
        try {
            write(stage.toFile())
            FileChannel.open(stage, StandardOpenOption.WRITE).use { it.force(true) }
            if (replace) Files.move(stage, destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else Files.createLink(destination.toPath(), stage)
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally { Files.deleteIfExists(stage) }
    }

    companion object {
        private val receiptLock = Any()

        private fun digest(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun scaledModel(bytes: ByteArray, sizeMm: Double): Mesh {
            require(bytes.size in 1..MeshyProvider.MAX_ASSET && sizeMm.isFinite() && sizeMm in 1.0..500.0) {
                "Choose a model size from 1 to 500 mm."
            }
            val source = try { MeshIO.readStl(bytes) }
            catch (_: Exception) { throw GeometryGenerationError("The downloaded STL is invalid.") }
            val extent = validatedExtent(source)
            val bounds = source.bounds()
            val scaled = FloatArray(source.vertices.size) { i ->
                val axis = i % 3
                ((source.vertices[i].toDouble() - bounds[axis]) / extent * sizeMm).toFloat()
            }
            return Mesh(scaled, source.triangles)
        }

        fun validateLocalMesh(mesh: Mesh, sizeMm: Double) {
            require(validatedExtent(mesh) <= sizeMm + 0.01) { "The local mesh exceeds the reviewed size." }
        }

        private fun validatedExtent(mesh: Mesh): Double {
            val vertices = mesh.vertices
            val triangles = mesh.triangles
            require(mesh.triangleCount in 4..600_000 && vertices.size % 3 == 0 &&
                triangles.size % 3 == 0 && vertices.all { it.isFinite() } &&
                triangles.all { it in 0 until vertices.size / 3 }) {
                "The STL does not contain a usable mesh."
            }
            require((0 until mesh.triangleCount).any { face ->
                val a = triangles[face * 3] * 3
                val b = triangles[face * 3 + 1] * 3
                val c = triangles[face * 3 + 2] * 3
                val ux = vertices[b].toDouble() - vertices[a]
                val uy = vertices[b + 1].toDouble() - vertices[a + 1]
                val uz = vertices[b + 2].toDouble() - vertices[a + 2]
                val vx = vertices[c].toDouble() - vertices[a]
                val vy = vertices[c + 1].toDouble() - vertices[a + 1]
                val vz = vertices[c + 2].toDouble() - vertices[a + 2]
                uy * vz != uz * vy || uz * vx != ux * vz || ux * vy != uy * vx
            }) { "The STL has no non-degenerate triangles." }
            val bounds = mesh.bounds()
            val extent = (0..2).maxOf { bounds[it + 3].toDouble() - bounds[it] }
            require(extent.isFinite() && extent > 0) { "The STL has no measurable size." }
            return extent
        }
    }
}
