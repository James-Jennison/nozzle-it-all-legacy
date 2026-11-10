package com.nozzleitall.desktop

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.nozzleitall.printer.*
import com.nozzleitall.printer.external.AccountAdapter
import com.nozzleitall.printer.external.ExternalAdapterClient
import kotlinx.coroutines.*
import com.nozzleitall.desktop.history.notificationBody
import com.nozzleitall.desktop.history.notificationTitle
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PrinterTransferBusyException(message: String) : IllegalStateException(message)

data class PlatePrintProgress(val machineCompleted: Boolean, val physicallyAccepted: Boolean)
data class ReprintCandidate(val sourceJobId: String, val file: File, val fileName: String,
                            val projectId: String?, val projectRevision: Long?, val plateId: String?,
                            val targetProfileId: String?, val engineBuild: String?, val inputDigest: String?)

/** Local app settings. Optional Stock U1 support is off unless the user turns it on. */
data class SlicingSetup(val profileId: String, val name: String, val nozzle: String? = null,
                        val processId: String? = null, val toolheads: Int = 1,
                        val filamentProfiles: Map<Int, String> = emptyMap(), val customOffline: Boolean = false,
                        val bedWidthMm: Float? = null, val bedDepthMm: Float? = null, val heightMm: Float? = null,
                        val installedFilamentProfiles: Set<String> = emptySet(), val gcodeFlavor: String = "klipper") {
    fun isValid(): Boolean = profileId.length in 1..256 && name.length in 1..256 && toolheads in 1..32 &&
        filamentProfiles.all { (slot, id) -> slot in 1..toolheads && id.length in 1..256 } &&
        installedFilamentProfiles.size <= 4096 && installedFilamentProfiles.all { it.length in 1..256 } &&
        filamentProfiles.values.all { it in installedFilamentProfiles || installedFilamentProfiles.isEmpty() } &&
        (!customOffline || profileId == "generic_klipper" && gcodeFlavor in GCODE_FLAVORS &&
            listOf(bedWidthMm, bedDepthMm, heightMm).all { it != null && it.isFinite() && it in 10f..2000f } &&
            nozzle?.toFloatOrNull()?.let { it in 0.1f..2f } == true)
    fun toJson() = JSONObject().put("profileId", profileId).put("name", name)
        .put("nozzle", nozzle).put("processId", processId).put("toolheads", toolheads)
        .put("customOffline", customOffline).put("bedWidthMm", bedWidthMm).put("bedDepthMm", bedDepthMm)
        .put("heightMm", heightMm).put("gcodeFlavor", gcodeFlavor).put("filamentProfiles", JSONObject().also { out ->
            filamentProfiles.toSortedMap().forEach { (slot, id) -> out.put(slot.toString(), id) }
        }).put("installedFilamentProfiles", JSONArray(installedFilamentProfiles.sorted()))
    companion object {
        fun fromJson(value: JSONObject?): SlicingSetup? = value?.let { o ->
            val profileId = o.optString("profileId").takeIf { it.isNotBlank() } ?: return@let null
            val toolheads = o.optInt("toolheads", 1).coerceIn(1, 32)
            val filaments = o.optJSONObject("filamentProfiles")?.let { rows ->
                rows.keySet().mapNotNull { key -> key.toIntOrNull()?.takeIf { it in 1..toolheads }?.let { it to rows.optString(key) } }
                    .filter { it.second.isNotBlank() }.toMap()
            }.orEmpty()
            val installed = if (o.has("installedFilamentProfiles")) {
                o.optJSONArray("installedFilamentProfiles")?.let { rows ->
                    (0 until rows.length()).mapNotNull { rows.optString(it).takeIf(String::isNotBlank) }.toSet()
                }.orEmpty()
            } else filaments.values.toSet()
            fun dimension(key: String) = o.optDouble(key, Double.NaN).toFloat().takeIf { it.isFinite() && it in 10f..2000f }
            SlicingSetup(profileId, o.optString("name").ifBlank { profileId },
                o.optString("nozzle").ifBlank { null }, o.optString("processId").ifBlank { null },
                toolheads, filaments, o.optBoolean("customOffline"),
                dimension("bedWidthMm"), dimension("bedDepthMm"), dimension("heightMm"), installed,
                o.optString("gcodeFlavor", "klipper").ifBlank { "klipper" })
        }

        val GCODE_FLAVORS = setOf("klipper", "marlin2", "marlin", "reprapfirmware", "repetier")
    }
}

data class DesktopSettings(val stockU1Enabled: Boolean = false, val theme: String = "system",
                           val notifications: Boolean = true, val textScale: Float = 1f,
                           val collapsedSettingGroups: Set<String> = emptySet(),
                           val firstRunComplete: Boolean = false, val slicingSetup: SlicingSetup? = null,
                           val rfidFilamentProfiles: Map<String, String> = emptyMap(),
                           val externalSpoolProfiles: Map<String, String> = emptyMap()) {
    fun toJson() = JSONObject().put("stockU1Enabled", stockU1Enabled).put("theme", theme)
        .put("notifications", notifications).put("textScale", textScale)
        .put("collapsedSettingGroups", JSONArray(collapsedSettingGroups.sorted()))
        .put("firstRunComplete", firstRunComplete)
        .put("rfidFilamentProfiles", JSONObject().also { out ->
            rfidFilamentProfiles.toSortedMap().forEach { (spoolId, profileId) -> out.put(spoolId, profileId) }
        }).put("externalSpoolProfiles", JSONObject(externalSpoolProfiles.toSortedMap()))
        .apply { slicingSetup?.let { put("slicingSetup", it.toJson()) } }
    companion object {
        private fun fromJson(it: JSONObject): DesktopSettings {
            val scale = it.optDouble("textScale", 1.0).toFloat().takeIf { value -> value.isFinite() && value in 1f..2f } ?: 1f
            val collapsed = it.optJSONArray("collapsedSettingGroups")?.let { rows ->
                (0 until rows.length().coerceAtMost(1024)).asSequence().mapNotNull { index ->
                    rows.optString(index).takeIf { key -> key.length in 1..128 && ':' in key }
                }.distinct().take(256).toSet()
            }.orEmpty()
            val theme = it.optString("theme", "system").takeIf { value -> value in setOf("system", "dark", "light") } ?: "system"
            fun profiles(field: String) = it.optJSONObject(field)?.let { rows ->
                rows.keySet().asSequence().filter { key -> key.length in 1..256 }
                    .mapNotNull { key -> rows.optString(key).takeIf { value -> value.length in 1..256 }?.let { key to it } }
                    .take(2048).toMap()
            }.orEmpty()
            return DesktopSettings(it.optBoolean("stockU1Enabled"), theme,
                it.optBoolean("notifications", true), scale, collapsed,
                it.optBoolean("firstRunComplete", false), SlicingSetup.fromJson(it.optJSONObject("slicingSetup")),
                profiles("rfidFilamentProfiles"), profiles("externalSpoolProfiles"))
        }
        fun load(file: File): DesktopSettings = runCatching { loadExisting(file) }.getOrDefault(DesktopSettings())
        /** A save must fail closed on a damaged existing file instead of replacing it with defaults. */
        fun loadExisting(file: File): DesktopSettings {
            val source = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(file.readBytes())).toString()
            require(source.none { it < ' ' && it != '\t' && it != '\r' && it != '\n' }) {
                "Settings file contains a non-JSON control character."
            }
            val parser = JSONTokener(source)
            val parsed = JSONObject(parser)
            require(parser.nextClean() == '\u0000') { "Settings file has trailing data." }
            return fromJson(parsed)
        }
    }
}

/** Finds the separately installed Stock U1 helper. Nothing here links against it. */
object StockHelperLocator {
    fun find(env: Map<String, String> = System.getenv()): List<String>? {
        env["NOZZLE_STOCK_ADAPTER"]?.let { p -> if (File(p).canExecute()) return listOf(p) }
        val install = System.getProperty("compose.application.resources.dir")?.let { File(it).parentFile?.parentFile }
        val candidates = listOfNotNull(
            install?.let { File(it, "lib/nozzle-stock-u1-adapter/bin/nozzle-stock-u1-adapter") },
            File("/opt/nozzle-stock-u1-adapter/bin/nozzle-stock-u1-adapter"),
            File("/usr/lib/nozzle-stock-u1-adapter/bin/nozzle-stock-u1-adapter"),
        )
        return candidates.firstOrNull { it.canExecute() }?.let { listOf(it.absolutePath) }
    }
}

/** Everything the UI knows about one saved printer. */
class PrinterEntry(val config: PrinterConfig) {
    val status = mutableStateOf(PrinterStatus(PrinterState.CONNECTING, routeFor(runCatching { java.net.URI(config.identity.address.let { if ("://" in it) it else "http://$it" }).host ?: "" }.getOrDefault(""))))
    val capabilities = mutableStateOf<Capabilities?>(null)
    val cameras = mutableStateOf<List<CameraEndpoint>>(emptyList())
    val problem = mutableStateOf<String?>(null)
    @Volatile var session: PrinterSession? = null
    @Volatile var guard: ActionGuard? = null
    @Volatile var retired: Boolean = false
}

const val EXPORT_ONLY = "export-only"

class Fleet(private val paths: AppPaths, private val scope: CoroutineScope, private val log: (String) -> Unit = {},
            /** Finds the separately installed Stock U1 helper; tests pass their own so the machine's installs don't matter. */
            private val stockHelper: () -> List<String>? = { StockHelperLocator.find() }) {
    companion object { private val settingsProcessLock = Any() }
    private val store = PrinterStore(paths)
    /** Built-in adapters are whatever adapter modules this installation ships (ServiceLoader); none is named here. */
    val registry = AdapterRegistry().apply {
        java.util.ServiceLoader.load(DeviceAdapterProvider::class.java).forEach { p ->
            runCatching { registerBuiltIn(p.create()) }.onFailure { log("adapter not loaded: ${it.message}") }
        }
    }
    val printers = mutableStateMapOf<String, PrinterEntry>()
    val order = mutableStateOf<List<String>>(emptyList())
    private val initialSettings = if (paths.settings.exists()) runCatching { DesktopSettings.loadExisting(paths.settings) }
        else Result.success(DesktopSettings())
    val settings = mutableStateOf(initialSettings.getOrDefault(DesktopSettings()))
    val settingsLoadProblem = mutableStateOf(initialSettings.isFailure)
    /** Re-read under a file lock so cooperating app instances cannot lose a saved choice. */
    private fun saveSettings(change: (DesktopSettings) -> DesktopSettings): Boolean = synchronized(settingsProcessLock) {
        runCatching {
            paths.settings.parentFile.mkdirs()
            // Keep this sentinel file: deleting it could let another process lock a new inode.
            FileChannel.open(File(paths.settings.parentFile, ".settings.lock").toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                var fileLock: FileLock? = null
                while (fileLock == null) {
                    fileLock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (fileLock == null) {
                        if (System.nanoTime() >= deadline) throw IOException("Settings file is busy")
                        Thread.sleep(20)
                    }
                }
                fileLock.use {
                    val persisted = paths.settings.exists()
                    val current = if (persisted) DesktopSettings.loadExisting(paths.settings) else settings.value
                    val next = change(current)
                    if (!persisted || next != current)
                        PrinterStore.atomicWrite(paths.settings, next.toJson().toString(2), private = false)
                    settings.value = next
                    settingsLoadProblem.value = false
                }
            }
        }.isSuccess
    }
    /** Retain a failed text-size save notice when the user leaves and returns to Settings. */
    val textSizeSaveProblem = mutableStateOf(false)
    val themeSaveProblem = mutableStateOf(false)
    val notificationsSaveProblem = mutableStateOf(false)
    val stockSaveProblem = mutableStateOf(false)
    fun updateTextSize(scale: Float): Boolean {
        if (!scale.isFinite() || scale !in 1f..2f) return false
        val saved = saveSettings { it.copy(textScale = scale) }
        textSizeSaveProblem.value = !saved
        return saved
    }
    fun updateSettingGroupCollapsed(key: String, collapsed: Boolean): Boolean {
        if (key.length !in 1..128 || ':' !in key) return false
        return saveSettings { current ->
            val groups = current.collapsedSettingGroups
            require(!collapsed || key in groups || groups.size < 256) { "Too many saved setting groups." }
            current.copy(collapsedSettingGroups = if (collapsed) groups + key else groups - key)
        }
    }
    fun updateTheme(theme: String): Boolean {
        if (theme !in setOf("system", "dark", "light")) return false
        val saved = saveSettings { it.copy(theme = theme) }
        themeSaveProblem.value = !saved
        return saved
    }
    /** Applies only the directly compatible Orca light/dark preference during an explicit migration. */
    fun importLegacyTheme(theme: String): Boolean {
        if (theme !in setOf("dark", "light")) return false
        val saved = saveSettings { it.copy(theme = theme) }
        themeSaveProblem.value = !saved
        return saved
    }
    fun updateNotifications(enabled: Boolean): Boolean {
        val saved = saveSettings { it.copy(notifications = enabled) }
        notificationsSaveProblem.value = !saved
        return saved
    }
    fun completeFirstRun(): Boolean = saveSettings { it.copy(firstRunComplete = true) }
    fun saveSlicingSetup(setup: SlicingSetup): Boolean {
        if (!setup.isValid()) return false
        return saveSettings { it.copy(slicingSetup = setup, firstRunComplete = true) }
    }
    fun rfidFilamentProfile(spoolId: String?): String? = spoolId?.let { settings.value.rfidFilamentProfiles[it] }
    /** Remembers the user's explicit scanned-spool/profile choice without modifying the RFID tag or printer. */
    fun saveRfidFilamentProfile(spoolId: String, filamentProfileId: String?): Boolean {
        if (spoolId.length !in 1..256 || filamentProfileId != null && filamentProfileId.length !in 1..256) return false
        return saveSettings { current ->
            val next = current.rfidFilamentProfiles.toMutableMap()
            if (filamentProfileId == null) next.remove(spoolId) else {
                require(spoolId in next || next.size < 2048) { "Too many saved RFID filament mappings." }
                next[spoolId] = filamentProfileId
            }
            current.copy(rfidFilamentProfiles = next)
        }
    }
    fun externalSpoolProfile(tag: String): String? = settings.value.externalSpoolProfiles[tag]

    fun saveExternalSpoolProfile(tag: String, profile: String): Boolean = saveSettings { current ->
        require(tag.length in 1..256 && profile.length in 1..256)
        require(tag in current.externalSpoolProfiles || current.externalSpoolProfiles.size < 2048)
        current.copy(externalSpoolProfiles = current.externalSpoolProfiles + (tag to profile))
    }

    private var stockClient: ExternalAdapterClient? = null
    /** Notices when each printer's job ends, keeps the results (newest first) and tells the user outside the window. */
    val tracker = com.nozzleitall.desktop.history.JobTracker()
    private val historyStore = com.nozzleitall.desktop.history.JobHistoryStore(File(paths.data, "history.json"))
    private val pendingHistoryStore = com.nozzleitall.desktop.history.JobHistoryStore(File(paths.data, "history-pending.json"))
    private val currentObservationOutbox = com.nozzleitall.desktop.history.JobHistoryStore(
        File(paths.data, "current-observation-retry.json"), Int.MAX_VALUE)
    private val localJobs by lazy { com.nozzleitall.desktop.jobs.LocalJobStore(File(paths.data, "jobs.sqlite")) }
    fun historyAssessment(record: com.nozzleitall.desktop.history.JobRecord):
        com.nozzleitall.desktop.jobs.LocalJobStore.ObservationReview? = localJobs.reviewForObservation(record)

    fun assessHistoryObservation(record: com.nozzleitall.desktop.history.JobRecord, expectedRevision: Long,
                                 assessment: com.nozzleitall.desktop.jobs.LocalJobStore.Assessment, notes: String):
        com.nozzleitall.desktop.jobs.LocalJobStore.ObservationReview {
        val review = localJobs.reviewForObservation(record) ?: error("This observation is not yet saved in local job history.")
        // The store compares this revision inside its write transaction; a UI-side read cannot arbitrate races.
        localJobs.assess(review.attemptId, expectedRevision, assessment, notes)
        return requireNotNull(localJobs.reviewForObservation(record))
    }
    /** Evidence-backed project overview; a machine completion and an accepted physical result are distinct. */
    fun projectPlateProgress(projectId: String, projectRevision: Long): Map<String, PlatePrintProgress> =
        localJobs.jobs().filter { it.projectId == projectId && it.projectRevision == projectRevision && it.plateId != null }
            .groupBy { requireNotNull(it.plateId) }.mapValues { (_, jobs) ->
                val attempts = jobs.flatMap { localJobs.attempts(it.id) }
                PlatePrintProgress(
                    machineCompleted = attempts.any { attempt -> localJobs.machineEvents(attempt.id).any { it.state == com.nozzleitall.desktop.jobs.LocalJobStore.MachineState.MACHINE_COMPLETED } },
                    physicallyAccepted = attempts.any { attempt -> localJobs.outcomes(attempt.id).lastOrNull()?.assessment == com.nozzleitall.desktop.jobs.LocalJobStore.Assessment.SUCCESS },
                )
            }
    val historyMigrationProblem = mutableStateOf<String?>(null)
    val currentObservationProblem = mutableStateOf<String?>(null)
    private val savedHistory = runCatching { historyStore.load() }.getOrElse {
        historyMigrationProblem.value = "Saved history cannot be read: ${it.message}"; emptyList()
    }
    private val pendingHistory = runCatching { pendingHistoryStore.load() }.getOrElse {
        historyMigrationProblem.value = "Pending history cannot be read: ${it.message}"; emptyList()
    }
    val history = mutableStateOf((pendingHistory + savedHistory).distinct())
    private val historyOnlyObservations = mutableMapOf<String, com.nozzleitall.desktop.history.JobRecord>()
    private var historyOnlyInitialized = false
    val historyConflictProblem = mutableStateOf<String?>(if (history.value.groupBy { it.id }.any { (_, rows) -> rows.size > 1 })
        "History files contain conflicting records with the same identity; review both sources." else null)
    private fun refreshHistoryConflict() {
        historyConflictProblem.value = if (history.value.groupBy { it.id }.any { (_, rows) -> rows.distinct().size > 1 })
            "History files contain conflicting records with the same identity; review both sources." else null
    }
    var notifier: (String, String) -> Unit = com.nozzleitall.desktop.history.DesktopNotifier::notify
    /** Sliced files waiting for a printer. Nothing here starts by itself: the user confirms each one (see QueuePanel). */
    private val queueStore = com.nozzleitall.desktop.queue.PrintQueue(File(paths.data, "queue"))
    private val artifactStore by lazy { com.nozzleitall.desktop.jobs.PreparedArtifactStore(File(paths.data, "prepared")) }
    val queue = mutableStateOf(queueStore.items())
    val queueRecoveryProblems = mutableStateOf(queueStore.problems())
    val queueRechecking = mutableStateOf(false)
    private val queueRecheckActive = AtomicBoolean(false)
    private val printerTransfers = ConcurrentHashMap<String, AtomicBoolean>()
    private val queuedTransfers = mutableSetOf<String>()
    /** Prevent two job files from being transferred to the same printer at once, across Send and the queue. */
    suspend fun <T> withPrinterTransfer(printerId: String, operation: suspend () -> T): T {
        val active = printerTransfers.computeIfAbsent(printerId) { AtomicBoolean(false) }
        if (!active.compareAndSet(false, true))
            throw PrinterTransferBusyException("A file transfer to this printer is already running. Wait for it to finish.")
        try { return operation() } finally { active.set(false) }
    }
    /** Keep a queued file's target and row stable while its transfer is in flight. */
    suspend fun <T> withQueuedTransfer(itemId: String, printerId: String, operation: suspend () -> T): T {
        synchronized(this) {
            check(queueStore.items().any { it.id == itemId }) { "Queued job was removed before transfer." }
            check(queuedTransfers.add(itemId)) { "This queued file is already being transferred." }
        }
        try { return withPrinterTransfer(printerId, operation) }
        finally { synchronized(this) { queuedTransfers.remove(itemId) } }
    }
    private fun refreshQueue() { queue.value = queueStore.items() }
    /** Re-read the queue and retry local record links after repair; this never dispatches a printer command. */
    fun retryQueueRecovery() { retryPendingQueueLinks() }
    /** The user-triggered recheck may hash large files, so keep it off the UI thread. */
    suspend fun recheckQueueFromUi() {
        if (!queueRecheckActive.compareAndSet(false, true)) return
        queueRechecking.value = true
        try {
            val recovered = withContext(Dispatchers.IO) { recoverQueueData() }
            queueRecoveryProblems.value = recovered.problems
            queue.value = recovered.items
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            queueRecoveryProblems.value = (queueRecoveryProblems.value +
                "Queue recheck failed. Verify local storage and try again.").distinct()
        } finally {
            queueRecheckActive.set(false)
            queueRechecking.value = false
        }
    }
    private fun reprintArtifactFor(original: com.nozzleitall.desktop.jobs.LocalJobStore.Job):
        com.nozzleitall.desktop.jobs.PreparedArtifactStore.Record? {
        val digest = original.artifactSha256 ?: return null
        val matches = artifactStore.recordsByDigest(digest).filter {
            it.projectId == original.projectId && it.projectRevision == original.projectRevision &&
                it.plateId == original.plateId
        }
        val provenance = matches.map { listOf(it.targetProfileId, it.engineBuild, it.inputDigest, it.output) }.distinct()
        check(provenance.size <= 1) { "Several slices share this file but have different provenance. Reprint from the project instead." }
        return matches.firstOrNull()
    }
    fun enqueue(name: String, file: File, printerId: String?, toolheadMap: List<Int>,
                projectId: String? = null, projectRevision: Long? = null, plateId: String? = null,
                targetProfileId: String? = null, engineBuild: String? = null,
                inputDigest: String? = null, sourceJobId: String? = null,
                expectedSha256: String? = null, draftPlateId: String? = null,
                materialPlan: com.nozzleitall.desktop.prepare.PreparedMaterialPlan? = null,
                directDraft: Boolean = false): com.nozzleitall.desktop.queue.QueueItem {
        if (printerId != null) {
            check(!printerProfileMismatch(targetProfileId, printers[printerId]?.config?.identity?.profileId)) {
                "This file was sliced for a different machine profile. Choose a matching printer or slice again."
            }
        }
        val sourceDigest = sourceJobId?.let { sourceId ->
            val source = localJobs.job(sourceId) ?: error("The original job is missing.")
            val indexed = reprintArtifactFor(source) ?: error("The original prepared file or its provenance is missing.")
            check(projectId == source.projectId && projectRevision == source.projectRevision && plateId == source.plateId &&
                targetProfileId == indexed.targetProfileId && engineBuild == indexed.engineBuild && inputDigest == indexed.inputDigest) {
                "The reprint setup differs from the original prepared file. Review it again."
            }
            check(file.canonicalFile == artifactStore.verifiedFile(indexed).canonicalFile) {
                "The reprint file is no longer the verified original. Review it again."
            }
            indexed.sha256
        }
        check(sourceDigest == null || expectedSha256 == null || sourceDigest == expectedSha256) {
            "The reprint file digest differs from the prepared file. Review it again."
        }
        val pending = queueStore.add(name, file, printerId, toolheadMap, pendingLink = true,
            projectId = projectId, projectRevision = projectRevision, plateId = plateId,
            targetProfileId = targetProfileId, engineBuild = engineBuild, inputDigest = inputDigest,
            sourceJobId = sourceJobId, expectedSha256 = sourceDigest ?: expectedSha256,
            draftPlateId = draftPlateId, materialPlan = materialPlan, directDraft = directDraft)
        refreshQueue()
        return try { finishPendingQueueItem(pending).also { refreshQueue() } }
        catch (e: Exception) {
            queueRecoveryProblems.value = queueStore.problems()
            throw e
        }
    }
    private fun queueRecordId(prefix: String, queueId: String): String =
        UUID.nameUUIDFromBytes("$prefix:$queueId".toByteArray(StandardCharsets.UTF_8)).toString()

    private fun finishPendingQueueItem(item: com.nozzleitall.desktop.queue.QueueItem): com.nozzleitall.desktop.queue.QueueItem {
        val source = queueStore.verifiedFile(item.id, allowPending = true)
        item.sourceJobId?.let { sourceId ->
            val original = localJobs.job(sourceId) ?: error("The original job is missing.")
            check(original.artifactSha256 == item.sha256) {
                "The queued reprint differs from its original prepared file. Remove and review it again."
            }
            val indexed = reprintArtifactFor(original) ?: error("The original prepared file or its provenance is missing.")
            artifactStore.verifiedFile(indexed)
            check(item.projectId == original.projectId && item.projectRevision == original.projectRevision && item.plateId == original.plateId &&
                item.targetProfileId == indexed.targetProfileId && item.engineBuild == indexed.engineBuild && item.inputDigest == indexed.inputDigest && item.output == indexed.output) {
                "The queued reprint provenance differs from its original prepared file."
            }
        }
        val artifact = artifactStore.prepare(source, item.projectId, item.projectRevision, item.plateId,
            item.targetProfileId, item.engineBuild, item.inputDigest,
            nowMillis = item.addedAtMillis, id = queueRecordId("artifact", item.id))
        check(artifact.sha256 == item.sha256 && artifact.output == item.output) { "Queued file changed during record creation." }
        val job = localJobs.createJob(com.nozzleitall.desktop.jobs.LocalJobStore.Origin.APP,
            item.projectId, item.projectRevision, item.plateId, artifact.sha256, printerId = null,
            sourceJobId = item.sourceJobId,
            nowMillis = item.addedAtMillis, id = queueRecordId("job", item.id))
        return queueStore.link(item.id, job.id, artifact.id)
    }

    /** Reprint is possible only from a locally retained and digest-verified prepared artifact. */
    fun reprintCandidate(record: com.nozzleitall.desktop.history.JobRecord): ReprintCandidate? {
        val sourceJob = localJobs.jobForObservation(record) ?: return null
        val artifact = reprintArtifactFor(sourceJob) ?: return null
        val file = artifactStore.verifiedFile(artifact)
        return ReprintCandidate(sourceJob.id, file, record.fileName.replace('\\', '/').substringAfterLast('/'),
            sourceJob.projectId, sourceJob.projectRevision, sourceJob.plateId,
            artifact.targetProfileId, artifact.engineBuild, artifact.inputDigest)
    }

    private data class QueueRecovery(val failures: List<String>, val problems: List<String>,
                                     val items: List<com.nozzleitall.desktop.queue.QueueItem>)
    /** Retry incomplete data-only queue links after an interrupted write. Nothing is sent to a printer. */
    private fun recoverQueueData(): QueueRecovery {
        val failures = queueStore.items().filter { it.pendingLink }.mapNotNull { item ->
            runCatching { finishPendingQueueItem(item) }.exceptionOrNull()?.let { "${item.id}: ${it.message}" }
        }
        return QueueRecovery(failures, failures + queueStore.problems(), queueStore.items())
    }
    fun retryPendingQueueLinks(): List<String> {
        val recovered = recoverQueueData()
        queueRecoveryProblems.value = recovered.problems
        queue.value = recovered.items
        return recovered.failures
    }
    /** An accepted Start may be recorded just before a crash, leaving its queue row behind. */
    private fun reconcileStartedQueueEntries() {
        val result = runCatching {
            val active = localJobs.activeQueuedAttempts()
            queueStore.items().filter { item -> !item.pendingLink && item.startUncertain && item.jobId != null && active.any {
                it.jobId == item.jobId && it.printerId == item.startPrinterId
            } }
                .forEach { queueStore.remove(it.id, confirmedStarted = true) }
        }
        result.onFailure { queueRecoveryProblems.value = queueStore.problems() +
            "A started queued job still needs queue cleanup: ${it.message}" }
        refreshQueue()
    }
    fun dequeue(id: String, expectedItem: com.nozzleitall.desktop.queue.QueueItem? = null) = synchronized(this) {
        check(id !in queuedTransfers) { "Wait for the queued file transfer to finish before removing it." }
        queueStore.remove(id, expectedItem = expectedItem); refreshQueue()
    }
    fun moveInQueue(id: String, by: Int) = synchronized(this) {
        check(id !in queuedTransfers) { "Wait for the queued file transfer to finish before reordering it." }
        queueStore.move(id, by); refreshQueue()
    }
    fun retargetQueued(id: String, printerId: String?, expectedItem: com.nozzleitall.desktop.queue.QueueItem? = null) = synchronized(this) {
        check(id !in queuedTransfers) { "Wait for the queued file transfer to finish before changing its printer." }
        val reviewedItem = expectedItem ?: queueStore.items().firstOrNull { it.id == id } ?: return@synchronized
        if (printerId != null) {
            val printer = printers[printerId]?.takeUnless { it.retired } ?: error("The selected printer is unavailable.")
            check(!printerProfileMismatch(reviewedItem.targetProfileId, printer.config.identity.profileId)) {
                "This job was sliced for a different machine profile. Choose a matching printer or slice again."
            }
            val caps = printer.capabilities.value ?: error("The selected printer has not reported its capabilities.")
            check(reviewedItem.output in caps.acceptedOutputs && (caps.uploadAndStart || (caps.uploadJob && caps.startPrint))) {
                "The selected printer cannot start this file type."
            }
        }
        queueStore.retarget(id, printerId, reviewedItem)
        refreshQueue()
    }
    fun queuedFile(item: com.nozzleitall.desktop.queue.QueueItem): File {
        queueStore.verifiedFile(item.id, expectedItem = item)
        val artifact = item.artifactId?.let { artifactStore.record(it) }
            ?: error("Queued job has no prepared artifact record.")
        check(artifact.sha256 == item.sha256 && artifact.output == item.output) { "Queued job and prepared artifact differ." }
        return artifactStore.verifiedFile(artifact)
    }
    /** Presentation hint only; verifiedFile remains the final gate before transfer or Start. */
    fun queuedSourcePresent(item: com.nozzleitall.desktop.queue.QueueItem): Boolean = queueStore.file(item).isFile
    @Synchronized
    fun markQueuedStartUncertain(item: com.nozzleitall.desktop.queue.QueueItem, printerId: String,
                                 remotePath: String): com.nozzleitall.desktop.queue.QueueItem {
        queuedFile(item)
        val hold = queueStore.markStartUncertain(item.id, printerId, remotePath, expectedItem = item) {
            localJobs.activeQueuedAttempt(printerId) == null
        }
        refreshQueue()
        queueRecoveryProblems.value = queueStore.problems()
        return hold
    }
    fun clearQueuedStartUncertain(item: com.nozzleitall.desktop.queue.QueueItem, printerId: String) {
        check(item.startUncertain) { "Review the held Start before clearing it." }
        queueStore.clearStartUncertain(item.id, printerId, expectedHold = item)
        refreshQueue()
        queueRecoveryProblems.value = queueStore.problems()
    }
    @Synchronized
    fun noteQueuedStarted(item: com.nozzleitall.desktop.queue.QueueItem, printerId: String, remoteName: String,
                         legacyPathObserved: Boolean = false) {
        check(item.startUncertain && item.jobId != null) { "Review the linked held Start before recording it." }
        queueStore.confirmStarted(item.id, printerId, item.jobId, remoteName, legacyPathObserved, expectedHold = item) {
            localJobs.bindQueuedStart(printerId, item.jobId, remoteName)
        }
        refreshQueue()
        queueRecoveryProblems.value = queueStore.problems()
    }
    private val wasBusy = HashMap<String, Boolean>()
    /** Last-resort process-local retry when every durable destination is temporarily unavailable. */
    private val unpersistedObservations = LinkedHashMap<String, com.nozzleitall.desktop.history.JobRecord>()
    private val pollers = HashMap<String, Job>()

    val stockHelperInstalled: Boolean get() = stockHelper() != null
    val stockAccount: AccountAdapter? get() = stockClient

    init {
        retryHistoryOnlyObservations()
        importLegacyHistory()
        retryCurrentObservationOutbox()
        reconcileCurrentObservations()
        retryPendingQueueLinks()
        runCatching { queueStore.discardAbandonedDirectDrafts() }
            .onSuccess { refreshQueue() }
            .onFailure { queueRecoveryProblems.value += "Abandoned print reviews could not be cleared: ${it.message}" }
        reconcileStartedQueueEntries()
        applyStockSetting()
        store.load().forEach { add(it, persist = false) }
    }

    private fun importLegacyHistory() {
        runCatching { localJobs.importLegacyHistory(File(paths.data, "history.json")) }
            .onSuccess {
                if (historyMigrationProblem.value?.startsWith("Legacy history import failed:") == true)
                    historyMigrationProblem.value = null
            }
            .onFailure { historyMigrationProblem.value = "Legacy history import failed: ${it.message}"; log("legacy history import failed: ${it.message}") }
    }

    /** A JSON history write may succeed while both SQLite and its retry outbox fail. */
    private fun retryHistoryOnlyObservations() {
        if (!historyOnlyInitialized) {
            val conflicts = history.value.groupBy { it.id }.filterValues { rows -> rows.distinct().size > 1 }.keys
            history.value.filter { it.currentObservation && it.id !in conflicts }
                .forEach { historyOnlyObservations[it.id] = it }
            historyOnlyInitialized = true
        }
        val recovered = historyOnlyObservations.values.filter { record ->
            runCatching { localJobs.recordCurrentObservation(record) }.isSuccess
        }
        recovered.forEach { historyOnlyObservations.remove(it.id) }
        if (historyOnlyObservations.isNotEmpty()) currentObservationProblem.value =
            "${historyOnlyObservations.size} saved machine observation(s) still need local job database recovery."
        else if (currentObservationProblem.value?.matches(Regex("\\d+ saved machine observation\\(s\\) still need local job database recovery\\.")) == true)
            currentObservationProblem.value = null
    }

    private fun reconcileCurrentObservations() {
        runCatching { localJobs.currentObservedRecords() }.onSuccess { records ->
            records.forEach { record ->
                val existing = history.value.filter { it.id == record.id }
                if (existing.isNotEmpty()) {
                    if (existing.any { it != record }) currentObservationProblem.value =
                        "Saved History conflicts with a committed machine observation: ${record.id}"
                    return@forEach
                }
                val result = runCatching { historyStore.prependPreserving(record) }
                    .recoverCatching { pendingHistoryStore.prependPreserving(record) }
                result.onSuccess { history.value = (listOf(record) + history.value).distinct() }
                    .onFailure { currentObservationProblem.value = "A committed machine observation could not be restored to History: ${it.message}" }
                refreshHistoryConflict()
            }
        }.onFailure { currentObservationProblem.value = "Committed machine observations could not be read: ${it.message}" }
    }

    /** Retry a machine observation whose SQLite write failed after the tracker emitted it. */
    private fun clearResolvedOutboxProblem() {
        if (currentObservationProblem.value?.let { problem ->
            problem.startsWith("Current machine observation is saved for database retry:") ||
                problem.startsWith("Current machine observation could not be saved for database retry:") ||
                problem.matches(Regex("\\d+ machine observation\\(s\\) still need local database recovery\\.")) ||
                problem.startsWith("Machine observation retry file cannot be read:")
        } == true) currentObservationProblem.value = null
    }

    private fun retryCurrentObservationOutbox() {
        runCatching { currentObservationOutbox.load() }.onSuccess { pending ->
            if (pending.isEmpty()) return@onSuccess
            history.value = (pending + history.value).distinct()
            refreshHistoryConflict()
            val succeeded = pending.filter { record ->
                runCatching { localJobs.recordCurrentObservation(record) }.isSuccess
            }
            val remaining = pending - succeeded.toSet()
            runCatching { currentObservationOutbox.removeExact(succeeded) }
                .onFailure { currentObservationProblem.value = "Machine observation retry could not be updated: ${it.message}" }
            if (remaining.isNotEmpty()) currentObservationProblem.value =
                "${remaining.size} machine observation(s) still need local database recovery."
            else {
                clearResolvedOutboxProblem()
                importLegacyHistory()
            }
        }.onFailure { currentObservationProblem.value = "Machine observation retry file cannot be read: ${it.message}" }
    }

    /** Registers the Stock helper as an optional, lazily started adapter only when the user enabled it. */
    private fun applyStockSetting() {
        if (!settings.value.stockU1Enabled) return
        val cmd = stockHelper() ?: return
        val client = ExternalAdapterClient(cmd, log = { log("stock-u1: $it") })
        stockClient = client
        registry.registerOptional("stock-u1") { client }
    }

    fun setStockEnabled(enabled: Boolean): Boolean {
        val saved = saveSettings { it.copy(stockU1Enabled = enabled) }
        stockSaveProblem.value = !saved
        if (!saved) return false
        if (enabled) applyStockSetting() else { stockClient?.close(); stockClient = null }
        // Stock printers reconnect (or go offline) according to the new setting; PAXX printers are untouched.
        printers.values.filter { it.config.adapterId == "stock-u1" }.forEach { restart(it.config.identity.id) }
        return true
    }

    /** Adds a printer only after its durable store accepted the new fleet. */
    fun add(config: PrinterConfig, persist: Boolean = true): Boolean {
        val nextOrder = order.value.filter { it != config.identity.id } + config.identity.id
        if (persist) {
            val nextPrinters = nextOrder.mapNotNull { id -> if (id == config.identity.id) config else printers[id]?.config }
            if (runCatching { store.save(nextPrinters) }.onFailure { log("printer not saved: ${it.message}") }.isFailure) return false
        }
        val entry = PrinterEntry(config)
        printers[config.identity.id] = entry
        order.value = nextOrder
        startPolling(entry)
        return true
    }

    /** Replaces a saved printer's config (same id), keeping its place on the Printers page, and reconnects with the new details. */
    fun update(config: PrinterConfig) {
        val id = config.identity.id
        val place = order.value.indexOf(id)
        remove(id, persist = false, forgetQueuedTargets = false); add(config, persist = false)
        if (place >= 0) order.value = order.value.filter { it != id }.toMutableList().also { it.add(place.coerceAtMost(it.size), id) }
        store.save(order.value.mapNotNull { printers[it]?.config })
    }

    internal fun canAssociatePrinterProfile(entry: PrinterEntry): Boolean =
        entry.config.identity.profileId == null && !entry.retired &&
            entry.status.value.state in PrinterAction.StartJob("profile review").allowedStates &&
            entry.guard?.needsReconcile != true &&
            queue.value.none { it.startUncertain && (it.printerId == null || it.printerId == entry.config.identity.id) }

    /** Persist the association before replacing the connection and invalidating its reviews. */
    internal suspend fun associatePrinterProfile(entry: PrinterEntry, profileId: String) {
        val id = entry.config.identity.id
        withPrinterTransfer(id) {
            check(printers[id] === entry && !entry.retired) { "The saved printer changed. Review it again." }
            check(canAssociatePrinterProfile(entry)) {
                "Wait for the printer to be idle and resolve any held Start before associating a profile."
            }
            requireNotNull(com.nozzleitall.desktop.prepare.ProfileCatalog.byId(profileId)) { "Choose an available machine profile." }
            val config = entry.config.copy(identity = entry.config.identity.copy(profileId = profileId))
            val savedOrder = order.value
            store.save(savedOrder.mapNotNull { if (it == id) config else printers[it]?.config })
            remove(id, persist = false, forgetQueuedTargets = false)
            add(config, persist = false)
            order.value = savedOrder
        }
    }

    /** Removes a printer only after its durable fleet record accepted the change. */
    fun remove(id: String, persist: Boolean = true, forgetQueuedTargets: Boolean = true): Boolean {
        if (id !in printers) return false
        val nextOrder = order.value.filter { it != id }
        if (persist) {
            val nextPrinters = nextOrder.mapNotNull { printers[it]?.config }
            if (runCatching { store.save(nextPrinters) }.onFailure { log("printer not removed: ${it.message}") }.isFailure) return false
        }
        printers[id]?.retired = true
        pollers.remove(id)?.cancel()
        wasBusy.remove(id)
        if (forgetQueuedTargets) { queueStore.forgetPrinter(id); refreshQueue() }
        printers.remove(id)?.session?.let { s -> scope.launch(Dispatchers.IO) { runCatching { s.close() } } }
        order.value = nextOrder
        return true
    }

    private fun restart(id: String) { printers[id]?.let { pollers.remove(id)?.cancel(); it.session = null; it.guard = null; startPolling(it) } }

    private fun startPolling(entry: PrinterEntry) {
        // An export-only printer is a slicing target with no connection: nothing to poll, nothing to control.
        if (entry.config.adapterId == EXPORT_ONLY) {
            entry.capabilities.value = Capabilities.EXPORT_ONLY
            entry.status.value = PrinterStatus(PrinterState.UNKNOWN, ConnectionRoute.NONE, message = "Nozzle slices for this printer but doesn't connect to it.")
            return
        }
        pollers[entry.config.identity.id] = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (entry.session == null) {
                    try {
                        val s = registry.open(entry.config)
                        entry.session = s; entry.guard = ActionGuard(s)
                        entry.capabilities.value = s.capabilities; entry.problem.value = null
                        entry.cameras.value = if (s.capabilities.camera) runCatching { s.cameras() }.getOrDefault(emptyList()) else emptyList()
                    } catch (e: Exception) {
                        entry.problem.value = when {
                            entry.config.adapterId == "stock-u1" && !settings.value.stockU1Enabled -> "Stock U1 support is turned off. Turn it on in Settings to use this printer."
                            entry.config.adapterId == "stock-u1" && !stockHelperInstalled -> "Stock U1 support isn't installed. Install nozzle-stock-u1-adapter to use this printer."
                            else -> e.message ?: "This printer can't be opened."
                        }
                        entry.status.value = entry.status.value.copy(state = PrinterState.OFFLINE, message = entry.problem.value)
                        delay(15_000); continue
                    }
                }
                val s = entry.session ?: continue
                val reading = s.status()
                // An edited printer may replace this entry while the old poll is in flight.
                if (!isActive || entry.retired || printers[entry.config.identity.id] !== entry) break
                entry.status.value = reading
                recordEnd(entry, reading)
                if (reading.state != PrinterState.OFFLINE && entry.cameras.value.isEmpty() && s.capabilities.camera)
                    entry.cameras.value = runCatching { s.cameras() }.getOrDefault(emptyList())
                delay(if (reading.state.isActiveJob) 2_000 else 4_000)
            }
        }
    }

    /** A printer that was working and is now free, with something waiting for it, is worth telling the user about. */
    internal fun noteFree(entry: PrinterEntry, reading: PrinterStatus) {
        val id = entry.config.identity.id
        val busy = reading.state.isActiveJob
        val free = reading.state == PrinterState.READY || reading.state == PrinterState.FINISHED || reading.state == PrinterState.CANCELLED
        val before = wasBusy[id]
        if (reading.state != PrinterState.OFFLINE && reading.state != PrinterState.UNKNOWN && reading.state != PrinterState.CONNECTING) wasBusy[id] = busy
        if (before == true && free) queueStore.nextFor(id)?.let { next ->
            if (settings.value.notifications) notifier("${entry.config.identity.displayName.ifBlank { entry.config.identity.address }} is free", "Next in the queue: ${next.name}. Clear the plate, then start it from the Printers page.")
        }
    }

    @Synchronized
    internal fun recordEnd(entry: PrinterEntry, reading: PrinterStatus) {
        if (entry.retired) return
        retryHistoryOnlyObservations()
        retryUnpersistedObservations()
        retryCurrentObservationOutbox()
        noteFree(entry, reading)
        val printerId = entry.config.identity.id
        val printerName = entry.config.identity.displayName.ifBlank { entry.config.identity.address }
        val observed = tracker.observe(printerId, printerName, reading) ?: run {
            val outcome = when (reading.state) {
                PrinterState.FINISHED -> "finished"
                PrinterState.CANCELLED -> "cancelled"
                PrinterState.ERROR -> "failed"
                // A restarted app may first see READY after the prior attempt ended out of view.
                // READY does not establish success or cancellation; release the attempt as unknown.
                PrinterState.READY -> "unknown"
                else -> null
            } ?: return
            val active = runCatching { localJobs.activeQueuedAttempt(printerId) }.getOrNull() ?: return
            val reportedFile = reading.job?.fileName?.takeIf { it.isNotBlank() }
            if (reportedFile == null && reading.state != PrinterState.READY) {
                currentObservationProblem.value = "Printer $printerName reported ${reading.state} without a file name; the queued attempt remains open for review."
                return
            }
            val sameFile = reportedFile == null || reportedFile.substringAfterLast('/').substringAfterLast('\\') ==
                active.fileName.substringAfterLast('/').substringAfterLast('\\')
            val observedName = if (sameFile) active.fileName else requireNotNull(reportedFile)
            if (!sameFile && (history.value.asSequence() + unpersistedObservations.values.asSequence()).any {
                    it.printerId == printerId && it.fileName == observedName &&
                        it.outcome == outcome && it.endedAtMillis >= active.startedAtMillis }) return
            val observationId = UUID.nameUUIDFromBytes(
                "queued-end:${active.attemptId}:$observedName:$outcome".toByteArray()).toString()
            val endedAt = System.currentTimeMillis().coerceAtLeast(active.startedAtMillis)
            val observedStart = if (sameFile) active.startedAtMillis else {
                val elapsed = reading.job?.elapsedSeconds?.takeIf { it.isFinite() && it >= 0.0 }
                    ?.times(1000.0)?.toLong() ?: 0L
                endedAt - elapsed.coerceIn(0, endedAt)
            }
            unpersistedObservations[observationId] ?: history.value.firstOrNull { it.id == observationId } ?:
                com.nozzleitall.desktop.history.JobRecord(
                observationId, printerId, printerName, observedName, observedStart,
                endedAt, outcome, reading.job?.elapsedSeconds, reading.job?.totalLayers)
        }
        val rec = observed.copy(currentObservation = true)
        val firstObservation = history.value.none { it.id == rec.id }
        var durable = false
        var committedToLocalJobs = false
        runCatching { localJobs.recordCurrentObservation(rec) }
            .onSuccess {
                durable = true
                committedToLocalJobs = true
                if (unpersistedObservations.isEmpty() && runCatching { currentObservationOutbox.load().isEmpty() }.getOrDefault(false))
                    clearResolvedOutboxProblem()
            }
            .onFailure {
                val pending = runCatching { currentObservationOutbox.prependPreserving(rec) }
                if (pending.isSuccess) durable = true
                currentObservationProblem.value = if (pending.isSuccess)
                    "Current machine observation is saved for database retry: ${it.message}"
                else "Current machine observation could not be saved for database retry: ${pending.exceptionOrNull()?.message}"
                log("current job evidence not recorded: ${it.message}")
            }
        runCatching { historyStore.prependPreserving(rec) }
            .onSuccess { saved ->
                durable = true
                history.value = (history.value + saved).distinct()
                importLegacyHistory()
            }
            .onFailure { error ->
                runCatching { pendingHistoryStore.prependPreserving(rec) }
                    .onSuccess { durable = true; historyMigrationProblem.value = "Saved history needs repair; new observations are in history-pending.json. ${error.message}" }
                    .onFailure { pendingError ->
                        historyMigrationProblem.value = "History and pending history could not be saved: ${pendingError.message}"
                        log("history and pending history not saved: ${pendingError.message}")
                    }
            }
        if (durable && !committedToLocalJobs)
            historyOnlyObservations[rec.id] = rec
        if (durable) {
            history.value = (listOf(rec) + history.value).distinct()
            refreshHistoryConflict()
        }
        else {
            unpersistedObservations[rec.id] = rec
            currentObservationProblem.value = "Machine observation could not be saved in any local store; keep the app running until storage recovers: ${rec.id}"
        }
        if (durable && firstObservation && settings.value.notifications) notifier(rec.notificationTitle(), rec.notificationBody())
    }

    private fun retryUnpersistedObservations() {
        if (unpersistedObservations.isEmpty()) return
        val saved = unpersistedObservations.values.filter { record ->
            val committed = runCatching { localJobs.recordCurrentObservation(record) }.isSuccess
            val held = if (committed) false else runCatching { currentObservationOutbox.prependPreserving(record) }.isSuccess
            committed || held
        }
        saved.forEach { unpersistedObservations.remove(it.id) }
        if (saved.isNotEmpty()) {
            history.value = (saved + history.value).distinct()
            refreshHistoryConflict()
        }
        if (unpersistedObservations.isEmpty() && saved.isNotEmpty() && currentObservationProblem.value?.let { problem ->
            problem.startsWith("Machine observation could not be saved in any local store;") ||
                problem.matches(Regex("\\d+ machine observation\\(s\\) still have no durable local copy;.*"))
        } == true) currentObservationProblem.value = null
        else if (unpersistedObservations.isNotEmpty()) currentObservationProblem.value =
            "${unpersistedObservations.size} machine observation(s) still have no durable local copy; keep the app running until storage recovers."
    }

    /** Probes an address with built-in adapters only; the Stock helper is never started by a scan. */
    suspend fun probe(address: String): List<DiscoveredPrinter> = withContext(Dispatchers.IO) { registry.probeBuiltIn(address) }

    fun shutdown() {
        printers.values.forEach { it.retired = true }
        pollers.values.forEach { it.cancel() }
        printers.values.forEach { e -> runCatching { e.session?.close() } }
        stockClient?.close()
    }
}


internal fun printerProfileMismatch(prepared: String?, associated: String?): Boolean =
    prepared != null && associated != null && prepared != associated
