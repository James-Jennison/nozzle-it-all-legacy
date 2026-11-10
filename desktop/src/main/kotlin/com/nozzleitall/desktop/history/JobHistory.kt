package com.nozzleitall.desktop.history

import com.nozzleitall.desktop.PrinterStore
import com.nozzleitall.printer.PrinterState
import com.nozzleitall.printer.PrinterStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID

/** One print that ran to its end on one of your printers. */
data class JobRecord(
    val id: String,
    val printerId: String,
    val printerName: String,
    val fileName: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    /** "finished", "cancelled", "failed", or an observed but unresolved "unknown". */
    val outcome: String,
    val elapsedSeconds: Double?,
    val totalLayers: Int?,
    /** Distinguishes this app's observed machine event from imported legacy history during recovery. */
    val currentObservation: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("printerId", printerId).put("printerName", printerName).put("fileName", fileName)
        .put("startedAt", startedAtMillis).put("endedAt", endedAtMillis).put("outcome", outcome)
        .apply { elapsedSeconds?.let { put("elapsedSeconds", it) }; totalLayers?.let { put("totalLayers", it) }
            if (currentObservation) put("currentObservation", true) }

    companion object {
        fun fromJson(j: JSONObject) = JobRecord(j.getString("id"), j.getString("printerId"), j.optString("printerName"), j.getString("fileName"),
            j.getLong("startedAt"), j.getLong("endedAt"), j.getString("outcome").also {
                require(it in setOf("finished", "cancelled", "failed", "unknown")) { "Unknown machine outcome." }
            },
            if (j.has("elapsedSeconds")) j.getDouble("elapsedSeconds") else null, if (j.has("totalLayers")) j.getInt("totalLayers") else null,
            j.optBoolean("currentObservation", false))
    }
}

/**
 * Notices when a print starts and when it ends, from the status readings alone, so it works for every printer whatever its adapter.
 * A job counts only if this app saw it running; a printer that already says "finished" when the app starts is not a new result.
 * Losing the connection mid-print ends nothing: the job stays open until the printer says how it ended.
 */
class JobTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private class Open(val fileName: String, val startedAt: Long, var fraction: Float, var elapsed: Double?, var layers: Int?)
    private val open = HashMap<String, Open>()

    private fun startedAt(now: Long, elapsedSeconds: Double?): Long {
        val boundedNow = now.coerceAtLeast(0)
        val elapsed = elapsedSeconds?.takeIf { it.isFinite() && it >= 0.0 }?.times(1000.0)?.toLong() ?: 0L
        return boundedNow - elapsed.coerceIn(0, boundedNow)
    }

    fun observe(printerId: String, printerName: String, status: PrinterStatus): JobRecord? {
        val now = clock()
        val state = status.state
        val job = status.job
        if (state.isActiveJob && job != null) {
            val o = open[printerId]
            if (o == null || o.fileName != job.fileName) {
                // A filename switch is not evidence of completion; retain the previous attempt as unresolved.
                open[printerId] = Open(job.fileName, startedAt(now, job.elapsedSeconds), job.fraction, job.elapsedSeconds, job.totalLayers)
                if (o != null) return JobRecord(UUID.randomUUID().toString(), printerId, printerName, o.fileName,
                    o.startedAt, now.coerceAtLeast(o.startedAt), "unknown", o.elapsed, o.layers)
            } else { o.fraction = job.fraction; o.elapsed = job.elapsedSeconds ?: o.elapsed; o.layers = job.totalLayers ?: o.layers }
            return null
        }
        val o = open[printerId] ?: return null
        val outcome = when (state) {
            PrinterState.FINISHED -> if ((status.completedFileName ?: job?.fileName)?.takeIf { it.isNotBlank() }
                ?.let { it.substringAfterLast('/').substringAfterLast('\\') != o.fileName.substringAfterLast('/').substringAfterLast('\\') } == true)
                "unknown" else "finished"
            PrinterState.CANCELLED -> "cancelled"
            PrinterState.ERROR -> "failed"
            // READY alone does not say whether the print completed or was stopped.
            PrinterState.READY -> "unknown"
            else -> return null   // offline, connecting, unknown: still waiting for the printer to say
        }
        open.remove(printerId)
        return JobRecord(UUID.randomUUID().toString(), printerId, printerName, o.fileName, o.startedAt,
            now.coerceAtLeast(o.startedAt), outcome, o.elapsed, o.layers)
    }
}

/** The finished prints, newest first, kept in one file next to the other app data. */
class JobHistoryStore(private val file: File, private val limit: Int = 500) {
    companion object { private val processLock = Any() }
    fun load(): List<JobRecord> {
        if (!file.exists()) return emptyList()
        val a = JSONArray(file.readText())
        return (0 until a.length()).map { index ->
            val row = a.getJSONObject(index)
            require(listOf("id", "printerId", "fileName", "startedAt", "endedAt", "outcome").all(row::has)) {
                "Saved history row $index is incomplete. The source was not changed."
            }
            require(row.getLong("startedAt") >= 0 && row.getLong("endedAt") >= row.getLong("startedAt") &&
                row.getString("outcome") in setOf("finished", "cancelled", "failed", "unknown")) {
                "Saved history row $index has invalid machine evidence. The source was not changed."
            }
            require(listOf("id", "printerId", "fileName").all { row.getString(it).isNotBlank() }) {
                "Saved history row $index has no usable identity. The source was not changed."
            }
            JobRecord.fromJson(row)
        }
    }

    fun save(records: List<JobRecord>) {
        synchronized(processLock) {
            file.parentFile.mkdirs()
            FileChannel.open(File(file.parentFile, ".history.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val existing = load()
                    check(existing.isEmpty() || existing == records.take(limit)) {
                        "Saved history has changed; use preserving operations instead of replacing it."
                    }
                    PrinterStore.atomicWrite(file, JSONArray(records.take(limit).map { it.toJson() }).toString(2), private = false)
                }
            }
        }
    }

    /** Appends without pruning legacy rows, even when their migration is delayed or fails. */
    fun prependPreserving(record: JobRecord): List<JobRecord> = synchronized(processLock) {
        file.parentFile.mkdirs()
        FileChannel.open(File(file.parentFile, ".history.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val existing = load()
                val prior = existing.firstOrNull { it.id == record.id }
                check(prior == null || prior == record) { "History identity already has different evidence." }
                val updated = if (prior == null) listOf(record) + existing else existing
                PrinterStore.atomicWrite(file, JSONArray(updated.map { it.toJson() }).toString(2), private = false)
                updated
            }
        }
    }

    /** Removes only exact retry-outbox records after their SQLite commit; concurrent additions survive. */
    fun removeExact(records: Collection<JobRecord>): List<JobRecord> = synchronized(processLock) {
        file.parentFile.mkdirs()
        FileChannel.open(File(file.parentFile, ".history.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val before = load()
                val kept = before.filterNot { it in records }
                if (kept.size != before.size)
                    PrinterStore.atomicWrite(file, JSONArray(kept.map { it.toJson() }).toString(2), private = false)
                kept
            }
        }
    }
}

/** What to tell the user about an ended print. */
fun JobRecord.notificationTitle(): String = when (outcome) {
    "finished" -> "Print finished on $printerName"
    "cancelled" -> "Print stopped on $printerName"
    "failed" -> "Print failed on $printerName"
    else -> "Print outcome unknown on $printerName"
}

fun JobRecord.notificationBody(): String = buildString {
    append(fileName.substringAfterLast('/'))
    elapsedSeconds?.takeIf { it > 0 }?.let { s -> val m = (s / 60).toLong(); append(" · "); append(if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min") }
}

/** Shows a message outside the window: the system tray where the desktop has one, `notify-send` on Linux otherwise. */
object DesktopNotifier {
    fun notify(title: String, body: String) {
        runCatching {
            if (System.getProperty("os.name").contains("Linux", ignoreCase = true)) {
                ProcessBuilder("notify-send", "--app-name=Nozzle It All", title, body).redirectErrorStream(true).start()
                return
            }
            if (java.awt.SystemTray.isSupported()) {
                val tray = java.awt.SystemTray.getSystemTray()
                val icon = java.awt.TrayIcon(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB), "Nozzle It All")
                tray.add(icon); icon.displayMessage(title, body, java.awt.TrayIcon.MessageType.INFO)
                Thread { Thread.sleep(8000); tray.remove(icon) }.apply { isDaemon = true; start() }
            }
        }
    }
}
