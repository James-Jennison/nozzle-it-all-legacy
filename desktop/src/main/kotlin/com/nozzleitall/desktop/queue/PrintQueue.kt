package com.nozzleitall.desktop.queue

import org.json.JSONArray
import org.json.JSONObject
import com.nozzleitall.desktop.jobs.preparedOutput
import com.nozzleitall.desktop.jobs.preparedOutputs
import java.io.File
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import com.nozzleitall.desktop.LocalFileDurability
import com.nozzleitall.desktop.prepare.PreparedMaterialPlan

/** A sliced file waiting for a printer. It keeps its own copy of the file, so it survives the project being edited or the cache being cleared. */
data class QueueItem(
    val id: String,
    val name: String,
    /** The file's name inside the queue folder. */
    val fileName: String,
    /** The printer it is meant for, or null for "whichever is free". */
    val printerId: String?,
    /** Which toolhead feeds each of the file's filaments; empty for a single-material printer. */
    val toolheadMap: List<Int>,
    val addedAtMillis: Long,
    val sha256: String? = null,
    val jobId: String? = null,
    val artifactId: String? = null,
    val pendingLink: Boolean = false,
    val projectId: String? = null,
    val projectRevision: Long? = null,
    val plateId: String? = null,
    val targetProfileId: String? = null,
    val engineBuild: String? = null,
    val inputDigest: String? = null,
    /** Persisted before a confirmed Start can reach the printer; only explicit reconciliation clears it. */
    val startUncertain: Boolean = false,
    val startPrinterId: String? = null,
    /** Exact path supplied to Start, retained for recovery if its outcome is unknown. */
    val startRemotePath: String? = null,
    /** Immutable source job when this queued item is a reprint draft. */
    val sourceJobId: String? = null,
    /** Identity of a plate in an unsaved editor session; separate from saved project plate lineage. */
    val draftPlateId: String? = null,
    val materialPlan: PreparedMaterialPlan? = null,
    val startHoldId: String? = null,
) {
    internal val output: String get() = preparedOutput(fileName)
    init { require(startRemotePath == null || (startUncertain && startRemotePath.isNotBlank())) }
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("fileName", fileName).put("addedAt", addedAtMillis)
        .put("toolheadMap", JSONArray(toolheadMap)).apply {
            printerId?.let { put("printerId", it) }; sha256?.let { put("sha256", it) }
            jobId?.let { put("jobId", it) }; artifactId?.let { put("artifactId", it) }
            if (pendingLink) put("pendingLink", true)
            projectId?.let { put("projectId", it) }; projectRevision?.let { put("projectRevision", it) }
            plateId?.let { put("plateId", it) }; targetProfileId?.let { put("targetProfileId", it) }
            engineBuild?.let { put("engineBuild", it) }; inputDigest?.let { put("inputDigest", it) }
            sourceJobId?.let { put("sourceJobId", it) }
            draftPlateId?.let { put("draftPlateId", it) }
            materialPlan?.let { put("materialPlan", it.toJson()) }
            if (startUncertain) put("startUncertain", true)
            startPrinterId?.let { put("startPrinterId", it) }
            startRemotePath?.let { put("startRemotePath", it) }
            startHoldId?.let { put("startHoldId", it) }
        }

    companion object {
        fun fromJson(j: JSONObject) = QueueItem(j.getString("id"), j.optString("name"), j.getString("fileName"), j.optString("printerId").ifBlank { null },
            j.optJSONArray("toolheadMap")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(), j.optLong("addedAt"),
            j.optString("sha256").ifBlank { null }, j.optString("jobId").ifBlank { null },
            j.optString("artifactId").ifBlank { null }, j.optBoolean("pendingLink", false),
            j.optString("projectId").ifBlank { null }, if (j.has("projectRevision")) j.getLong("projectRevision") else null,
            j.optString("plateId").ifBlank { null }, j.optString("targetProfileId").ifBlank { null },
            j.optString("engineBuild").ifBlank { null }, j.optString("inputDigest").ifBlank { null },
            j.optBoolean("startUncertain", false), j.optString("startPrinterId").ifBlank { null },
            j.optString("startRemotePath").ifBlank { null }, j.optString("sourceJobId").ifBlank { null },
            j.optString("draftPlateId").ifBlank { null },
            if (j.has("materialPlan")) PreparedMaterialPlan.fromJson(j.getJSONObject("materialPlan")) else null,
            j.optString("startHoldId").ifBlank { null })
    }
}

/**
 * The print queue: an ordered list of sliced files, kept in [dir] with a small index. It never starts anything: whoever shows it asks the
 * user before a file goes to a printer, because only a person can tell the plate is clear.
 */
class PrintQueue(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    companion object { private val processLock = Any() }
    private val index get() = File(dir, "queue.json")
    private val unreadableIndexMessage = "Queue index cannot be read. Restore or repair queue.json, then Recheck queue. Queued jobs are unavailable until it can be read."
    private val unreadableFilesMessage = "Queue files could not be checked. Verify local storage, then Recheck queue."
    private var loadProblem: String? = null
    private var list: MutableList<QueueItem> = runCatching { load().toMutableList() }.getOrElse {
        loadProblem = unreadableIndexMessage; mutableListOf()
    }

    private fun <T> withQueueLock(action: () -> T): T = synchronized(processLock) {
        dir.mkdirs()
        FileChannel.open(File(dir, ".queue.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                list = try { load().toMutableList().also { loadProblem = null } }
                    catch (e: Exception) { loadProblem = unreadableIndexMessage; throw IllegalStateException(unreadableIndexMessage, e) }
                action()
            }
        }
    }

    /** Damaged and missing records stay visible and are never silently dropped. */
    fun problems(): List<String> = try { withQueueLock { buildList {
        for (item in list) {
            if (item.pendingLink) add("Queued job awaits local record recovery: ${item.id}")
            if (item.startUncertain) add("Start outcome needs printer inspection for queued job ${item.id}")
            if (item.sha256 == null) add("Unverified legacy queued file for ${item.id}; requeue it before starting.")
            val stored = file(item)
            if (!stored.isFile) add("Missing queued file for ${item.id}. Restore the file or remove this queued job; Start is unavailable.")
            else if (item.sha256 != null && digest(stored) != item.sha256) add("Queued file digest differs for ${item.id}")
        }
        dir.listFiles()?.filter { it.isFile && preparedOutputs.any { output -> it.name.endsWith(".$output") } && list.none { entry -> entry.fileName == it.name } }
            ?.forEach { add("Unindexed queued file: ${it.name}") }
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }
            ?.forEach { add("Interrupted queue write: ${it.name}") }
    } } } catch (e: Exception) { listOf(loadProblem ?: unreadableFilesMessage) }

    private fun load(): List<QueueItem> {
        if (!index.exists()) return emptyList()
        val a = JSONArray(index.readText())
        return (0 until a.length()).map { QueueItem.fromJson(a.getJSONObject(it)) }.also { items ->
            check(items.map { it.id }.distinct().size == items.size) { "Queue contains duplicate identities." }
            items.forEach { requireSafeName(it) }
        }
    }

    private fun requireSafeName(item: QueueItem) {
        require(runCatching { UUID.fromString(item.id) }.isSuccess && item.fileName == "${item.id}.${item.output}") { "Unsafe queue entry." }
        require(item.sha256 == null || Regex("[0-9a-f]{64}").matches(item.sha256)) { "Invalid queue digest." }
        require(item.jobId == null || runCatching { UUID.fromString(item.jobId) }.isSuccess)
        require(item.artifactId == null || runCatching { UUID.fromString(item.artifactId) }.isSuccess)
        require((item.projectId == null) == (item.projectRevision == null))
        require(item.projectRevision == null || item.projectRevision > 0)
        require(item.plateId == null || item.projectId != null)
        require(item.draftPlateId == null || (item.projectId == null && item.plateId == null &&
            runCatching { UUID.fromString(item.draftPlateId) }.isSuccess)) { "Invalid draft plate identity." }
        require(!item.pendingLink || (item.jobId == null && item.artifactId == null))
        require(!item.startUncertain || (!item.pendingLink && item.jobId != null && item.startPrinterId != null))
        require(item.startPrinterId == null || item.startUncertain)
        require(item.startHoldId == null || (item.startUncertain && runCatching { UUID.fromString(item.startHoldId) }.isSuccess))
        require(item.startRemotePath == null || (item.startUncertain && item.startRemotePath.isNotBlank()))
        require(item.inputDigest == null || Regex("[0-9a-f]{64}").matches(item.inputDigest))
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) {
            val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n)
        } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun save(items: List<QueueItem>) {
        dir.mkdirs()
        val tmp = File(dir, ".queue-${UUID.randomUUID()}.part")
        try {
            tmp.writeText(JSONArray(items.map { it.toJson() }).toString(2))
            java.io.RandomAccessFile(tmp, "rw").use { it.fd.sync() }
            Files.move(tmp.toPath(), index.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            LocalFileDurability.syncDirectory(dir)
        } finally { tmp.delete() }
        list = items.toMutableList()
    }

    fun items(): List<QueueItem> = try { withQueueLock { list.toList() } } catch (_: Exception) { emptyList() }
    fun file(item: QueueItem): File = File(dir, item.fileName)
    fun verifiedFile(id: String, allowPending: Boolean = false, expectedItem: QueueItem? = null): File = withQueueLock {
        val item = list.firstOrNull { it.id == id } ?: error("Queued job is no longer present.")
        check(expectedItem == null || item == expectedItem) { "The queued job changed on disk; review it again." }
        check(allowPending || !item.pendingLink) { "Queued job records are still being recovered." }
        check(allowPending || !item.startUncertain) { "A prior Start may have reached the printer; inspect it before retrying." }
        check(item.sha256 != null) { "Legacy queued file has no integrity digest; requeue it before starting." }
        val stored = file(item)
        check(stored.isFile && digest(stored) == item.sha256) { "Queued file is missing or changed." }
        stored
    }
    fun link(id: String, jobId: String, artifactId: String): QueueItem = withQueueLock {
        val index = list.indexOfFirst { it.id == id }
        check(index >= 0) { "Pending queued job is missing." }
        val current = list[index]
        if (!current.pendingLink) {
            check(current.jobId == jobId && current.artifactId == artifactId) { "Queued job is linked to different records." }
            current
        } else {
            val linked = current.copy(jobId = jobId, artifactId = artifactId, pendingLink = false)
            requireSafeName(linked)
            save(list.toMutableList().also { it[index] = linked })
            linked
        }
    }

    fun add(name: String, source: File, printerId: String?, toolheadMap: List<Int> = emptyList(),
            jobId: String? = null, artifactId: String? = null, pendingLink: Boolean = false,
            projectId: String? = null, projectRevision: Long? = null, plateId: String? = null,
            targetProfileId: String? = null, engineBuild: String? = null, inputDigest: String? = null,
            sourceJobId: String? = null, expectedSha256: String? = null,
            draftPlateId: String? = null, materialPlan: PreparedMaterialPlan? = null): QueueItem = withQueueLock {
        dir.mkdirs()
        require(source.isFile) { "Queued source file is missing." }
        require(expectedSha256 == null || Regex("[0-9a-f]{64}").matches(expectedSha256)) { "Invalid expected prepared digest." }
        val id = UUID.randomUUID().toString()
        val stored = "$id.${preparedOutput(source.name)}"
        val tmp = File(dir, ".$stored.part")
        try {
            source.copyTo(tmp, overwrite = false)
            java.io.RandomAccessFile(tmp, "rw").use { it.fd.sync() }
            val hash = digest(tmp)
            check(expectedSha256 == null || hash == expectedSha256) {
                "The prepared file changed after review. Slice or review it again."
            }
            val item = QueueItem(id, name, stored, printerId, toolheadMap, clock(), hash, jobId, artifactId, pendingLink,
                projectId, projectRevision, plateId, targetProfileId, engineBuild, inputDigest,
                sourceJobId = sourceJobId, draftPlateId = draftPlateId, materialPlan = materialPlan)
            requireSafeName(item)
            Files.move(tmp.toPath(), File(dir, stored).toPath(), StandardCopyOption.ATOMIC_MOVE)
            LocalFileDurability.syncDirectory(dir)
            save(list + item)
            item
        } finally { tmp.delete() }
    }

    fun remove(id: String, confirmedStarted: Boolean = false) = withQueueLock {
        val item = list.firstOrNull { it.id == id } ?: return@withQueueLock
        check(!item.pendingLink) { "Recover this queued job before removing it." }
        check(!item.startUncertain || confirmedStarted) { "Inspect the printer and resolve the uncertain Start before removing this queued job." }
        save(list - item)
        // An interrupted cleanup leaves an explicit orphan reported by problems(); it cannot erase an indexed job.
        File(dir, item.fileName).delete()
    }

    /** Moves an item [by] places (negative = earlier), staying inside the list. */
    fun move(id: String, by: Int) = withQueueLock {
        val from = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return@withQueueLock
        val to = (from + by).coerceIn(0, list.lastIndex)
        if (to == from) return@withQueueLock
        val changed = list.toMutableList(); changed.add(to, changed.removeAt(from)); save(changed)
    }

    /** Points an item at another printer, or at any. */
    fun retarget(id: String, printerId: String?) = withQueueLock {
        val i = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return@withQueueLock
        check(!list[i].startUncertain) { "Inspect the printer before changing an uncertain queued Start." }
        save(list.toMutableList().also { it[i] = it[i].copy(printerId = printerId) })
    }

    fun markStartUncertain(id: String, printerId: String, remotePath: String? = null, expectedItem: QueueItem? = null,
                           canStart: () -> Boolean = { true }): QueueItem = withQueueLock {
        val i = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: error("Queued job is missing.")
        val item = list[i]
        check(expectedItem == null || item == expectedItem) { "The queued job changed on disk; review it again." }
        check(!item.pendingLink && item.jobId != null && !item.startUncertain) { "Queued job cannot start while recovery is pending." }
        check(item.printerId == null || item.printerId == printerId) {
            "Queued job now targets another printer; review it before starting."
        }
        check(list.none { it.id != id && it.startUncertain && it.startPrinterId == printerId }) {
            "Another queued Start is awaiting resolution on this printer."
        }
        check(canStart()) { "A queued attempt is still active on this printer; inspect it before starting another." }
        verifiedFileUnderLock(item)
        val changed = item.copy(startUncertain = true, startPrinterId = printerId, startRemotePath = remotePath,
            startHoldId = UUID.randomUUID().toString())
        save(list.toMutableList().also { it[i] = changed })
        changed
    }

    /** Bind the accepted Start while the durable queue lock still prevents another hold. */
    fun confirmStarted(id: String, printerId: String, jobId: String, remotePath: String? = null,
                       legacyPathObserved: Boolean = false, expectedHold: QueueItem? = null, bind: () -> Unit) = withQueueLock {
        val item = list.firstOrNull { it.id == id } ?: error("Queued Start evidence is missing.")
        check(expectedHold == null || item == expectedHold) { "The held Start changed; inspect it again." }
        check(item.startUncertain && item.startPrinterId == printerId && item.jobId == jobId) {
            "Queued Start was held for a different printer or job; inspect the queue before binding."
        }
        check(remotePath != null && remotePath.isNotBlank() &&
            (item.startRemotePath == remotePath || (item.startRemotePath == null && legacyPathObserved))) {
            "The recorded remote path differs from this Start; inspect the printer before binding."
        }
        bind()
        save(list - item)
        File(dir, item.fileName).delete()
    }

    fun clearStartUncertain(id: String, printerId: String, expectedHold: QueueItem? = null) = withQueueLock {
        val i = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: error("Queued job is missing.")
        val item = list[i]
        check(expectedHold == null || item == expectedHold) { "The held Start changed; inspect it again." }
        check(item.startUncertain && item.startPrinterId == printerId) { "Queued Start state changed; inspect it again." }
        save(list.toMutableList().also { it[i] = item.copy(startUncertain = false, startPrinterId = null, startRemotePath = null, startHoldId = null) })
    }

    private fun verifiedFileUnderLock(item: QueueItem) {
        check(item.sha256 != null && file(item).isFile && digest(file(item)) == item.sha256) { "Queued file is missing or changed." }
    }

    /** The first item that may run on [printerId]: one meant for it, or one meant for any printer. */
    fun nextFor(printerId: String): QueueItem? = withQueueLock { list.firstOrNull {
        !it.pendingLink && !it.startUncertain && it.sha256 != null && (it.printerId == null || it.printerId == printerId) && file(it).isFile &&
            digest(file(it)) == it.sha256
    } }

    /** Items whose printer was removed fall back to "any". */
    fun forgetPrinter(printerId: String) = withQueueLock {
        val changed = list.map { if (it.printerId == printerId) it.copy(printerId = null) else it }
        if (changed != list) save(changed)
    }
}
