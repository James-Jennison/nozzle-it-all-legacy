package com.nozzleitall.desktop.projects

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.prepare.chooseFiles
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.project.ReadLimits
import com.nozzleitall.project.Project3mf
import com.nozzleitall.project.ThreeMf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class RecoveryComparison(val saved: String, val newer: String, val settings: String,
                                       val remainingSettings: List<String> = emptyList(),
                                       val currentSession: String? = null,
                                       val currentVsSaved: String? = null,
                                       val currentVsRecovery: String? = null,
                                       val changedProjectFields: List<String> = emptyList(),
                                       val currentVsSavedFields: List<String> = emptyList(),
                                       val currentVsRecoveryFields: List<String> = emptyList(),
                                       val hasSavedHead: Boolean = true)

private const val MAX_PROJECT_PREVIEW_BYTES = 2 * 1024 * 1024
private val PROJECT_PREVIEW_NAMES = listOf("Metadata/plate_1_small.png", "Metadata/plate_1.png", "Metadata/thumbnail.png", "3D/Thumbnail.png")

internal fun safePreviewDimensions(bytes: ByteArray): Boolean {
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(png) ||
        !bytes.copyOfRange(12, 16).contentEquals("IHDR".toByteArray(Charsets.US_ASCII))) return false
    fun dimension(offset: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[offset + i].toLong() and 0xff) }
    val width = dimension(16)
    val height = dimension(20)
    return width in 1..2048 && height in 1..2048 && width * height <= 4_194_304
}

/** Read only a bounded embedded preview. A project without one remains identifiable in the list. */
internal fun projectPreview(file: File): ImageBitmap? = runCatching {
    ZipFile(file).use { archive ->
        for (name in PROJECT_PREVIEW_NAMES) {
            val entry = archive.getEntry(name) ?: continue
            if (entry.size > MAX_PROJECT_PREVIEW_BYTES) continue
            val image = runCatching {
                val bytes = archive.getInputStream(entry).use { it.readNBytes(MAX_PROJECT_PREVIEW_BYTES + 1) }
                if (bytes.size > MAX_PROJECT_PREVIEW_BYTES || !safePreviewDimensions(bytes)) null
                else org.jetbrains.skia.Image.makeFromEncoded(bytes).takeIf { image ->
                    image.width.toLong() * image.height <= 4_194_304 && image.width in 1..2048 && image.height in 1..2048
                }?.toComposeImageBitmap()
            }.getOrNull()
            if (image != null) return@use image
        }
        null
    }
}.getOrNull()

@Composable
private fun ProjectPreview(project: ProjectSummary) {
    val c = Nz.colors
    val preview by produceState<ImageBitmap?>(null, project.file, project.modifiedMillis) {
        value = withContext(Dispatchers.IO) { projectPreview(project.file) }
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    Box(Modifier.size(64.dp).clip(shape).background(c.surface), contentAlignment = Alignment.Center) {
        val image = preview
        if (image != null) Image(image, "Preview of ${project.name}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Txt("No preview", Nz.type.bodySmall, c.textMuted,
            modifier = Modifier.semantics { contentDescription = "No preview for ${project.name}" })
    }
}

/** Include printer, material, plate, object and unknown manifest fields in recovery inspection. */
private fun projectFields(project: Project3mf): Map<String, String> {
    val manifest = project.manifest?.toJson() ?: return emptyMap()
    val result = sortedMapOf<String, String>()
    fun visit(path: String, value: Any?) {
        when (value) {
            is JSONObject -> {
                if (value.length() == 0) result[path] = "{}"
                else value.keySet().sorted().forEach { key -> visit("$path.$key", value.get(key)) }
            }
            is JSONArray -> {
                if (value.length() == 0) result[path] = "[]"
                else (0 until value.length()).forEach { index -> visit("$path[$index]", value.get(index)) }
            }
            else -> result[path] = if (value == null || value == JSONObject.NULL) "not set" else value.toString()
        }
    }
    manifest.keySet().sorted().forEach { key -> visit(key, manifest.get(key)) }
    return result
}

private fun changedProjectFields(before: Project3mf?, after: Project3mf): List<String> {
    if (before == null) return projectFields(after).map { (key, value) -> "$key: $value" }
    val old = projectFields(before)
    val now = projectFields(after)
    return (old.keys + now.keys).sorted().filter { old[it] != now[it] }.map { key ->
        "$key: ${old[key] ?: "not set"} → ${now[key] ?: "not set"}"
    }
}

internal fun compareRecovery(candidate: ProjectRecoveryCandidate, currentSession: Project3mf? = null): RecoveryComparison {
    val limits = ReadLimits()
    val newer = ThreeMf.read(candidate.part, limits)
    val saved = candidate.target.takeIf { it.isFile }?.let { ThreeMf.read(it, limits) }
    fun describe(project: com.nozzleitall.project.Project3mf?): String = project?.let {
        val manifest = it.manifest
        "${manifest?.name ?: it.metadata["Title"] ?: "Unnamed"}, revision ${manifest?.revision ?: "unknown"}, " +
            "${it.objects.size} objects, ${manifest?.plates?.size ?: "unknown"} plates, " +
            "printer ${manifest?.printer?.model ?: "not recorded"}"
    } ?: "No saved head"
    val oldSettings = saved?.manifest?.settings
    val newSettings = newer.manifest?.settings
    val additional = mutableListOf<String>()
    val changed = if (saved == null) "No saved settings to compare. Inspect the recovery manifest fields below." else {
        val oldOverrides = oldSettings?.overrides.orEmpty()
        val newOverrides = newSettings?.overrides.orEmpty()
        val differences = (oldOverrides.keys + newOverrides.keys).sorted().filter { oldOverrides[it] != newOverrides[it] }
        fun describe(key: String) =
            "$key: ${oldOverrides[key] ?: "not set"} → ${newOverrides[key] ?: "not set"}"
        val details = differences.take(20).joinToString("; ", transform = ::describe)
        additional += differences.drop(20).map(::describe)
        "Preset ${oldSettings?.preset ?: "unknown"} → ${newSettings?.preset ?: "unknown"}; " +
            "${differences.size} changed overrides" +
            (if (details.isEmpty()) "." else ": $details" + if (differences.size > 20) "; ${differences.size - 20} more changes" else ".")
    }
    fun settingsDifference(left: Project3mf?, right: Project3mf): String {
        if (left == null) return "No saved project to compare."
        val before = left.manifest?.settings
        val after = right.manifest?.settings
        val old = before?.overrides.orEmpty()
        val now = after?.overrides.orEmpty()
        val keys = (old.keys + now.keys).sorted().filter { old[it] != now[it] }
        return "Preset ${before?.preset ?: "unknown"} → ${after?.preset ?: "unknown"}; " +
            "${keys.size} changed overrides" +
            (if (keys.isEmpty()) "." else ": " + keys.joinToString("; ") { key ->
                "$key: ${old[key] ?: "not set"} → ${now[key] ?: "not set"}"
            }) + "; ${changedProjectFields(left, right).size} changed project fields."
    }
    return RecoveryComparison(describe(saved), describe(newer), changed, additional,
        currentSession?.let { describe(it) }, currentSession?.let { settingsDifference(saved, it) },
        currentSession?.let { settingsDifference(newer, it) }, changedProjectFields(saved, newer),
        currentSession?.let { changedProjectFields(saved, it) }.orEmpty(),
        currentSession?.let { changedProjectFields(newer, it) }.orEmpty(), saved != null)
}

@Composable
fun ProjectsScreen(state: AppState) {
    val c = Nz.colors
    var refresh by remember { mutableStateOf(0) }
    var projects by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    var sessionDrafts by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    var libraryLoaded by remember { mutableStateOf(false) }
    var draftsLoaded by remember { mutableStateOf(false) }
    var recoveriesLoaded by remember { mutableStateOf(false) }
    var projectLoadProblem by remember { mutableStateOf<String?>(null) }
    var draftLoadProblem by remember { mutableStateOf<String?>(null) }
    var recoveryListProblem by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<Pair<String, File?>?>(null) }
    var recoveries by remember { mutableStateOf<List<ProjectRecoveryCandidate>?>(null) }
    var selectedRecovery by remember { mutableStateOf<ProjectRecoveryCandidate?>(null) }
    var recoveryComparison by remember { mutableStateOf<RecoveryComparison?>(null) }
    var comparisonLoading by remember { mutableStateOf(false) }
    var comparisonIncludesCurrentSession by remember { mutableStateOf(false) }
    var comparisonProblem by remember { mutableStateOf<String?>(null) }
    var comparisonRefresh by remember { mutableStateOf(0) }
    var recoveryProblem by remember { mutableStateOf<String?>(null) }
    var showAllRecoverySettings by remember(selectedRecovery) { mutableStateOf(false) }
    var showProjectFieldChanges by remember(selectedRecovery) { mutableStateOf(false) }
    var showCurrentVsSavedFields by remember(selectedRecovery) { mutableStateOf(false) }
    var showCurrentVsRecoveryFields by remember(selectedRecovery) { mutableStateOf(false) }
    var restoringRecovery by remember { mutableStateOf(false) }
    var keepingSavedVersion by remember { mutableStateOf(false) }
    var openingRecoveryCopy by remember { mutableStateOf(false) }
    var showAllSessionDrafts by remember { mutableStateOf(false) }
    var projectQuery by remember { mutableStateOf("") }
    LaunchedEffect(refresh) {
        libraryLoaded = false
        draftsLoaded = false
        recoveriesLoaded = false
        projectLoadProblem = null
        draftLoadProblem = null
        recoveryListProblem = null
        recoveryProblem = null
        projects = emptyList()
        sessionDrafts = emptyList()
        recoveries = null
        selectedRecovery = null
        try {
            projects = withContext(Dispatchers.IO) { state.library.list() }
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            projects = emptyList()
            projectLoadProblem = "Projects could not be listed: ${e.message}"
        }
        libraryLoaded = true
        try { sessionDrafts = withContext(Dispatchers.IO) { state.library.sessionDrafts() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            sessionDrafts = emptyList()
            draftLoadProblem = "Session drafts could not be listed: ${e.message}"
        }
        draftsLoaded = true
        try { recoveries = withContext(Dispatchers.IO) { state.library.pendingRecoveries() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            recoveries = null
            recoveryListProblem = "Recovery copies could not be listed: ${e.message}"
        }
        recoveriesLoaded = true
    }
    LaunchedEffect(selectedRecovery, comparisonRefresh, state.prepare.editorGeneration) {
        recoveryComparison = null
        comparisonProblem = null
        val candidate = selectedRecovery
        if (candidate == null || (candidate.problem != null && !candidate.canOpenAsCopy)) {
            comparisonLoading = false
            return@LaunchedEffect
        }
        comparisonLoading = true
        try {
            val comparisonGeneration = state.prepare.editorGeneration
            comparisonIncludesCurrentSession = state.prepare.dirty
            val current = if (comparisonIncludesCurrentSession) state.prepare.toProject() else null
            val compared = withContext(Dispatchers.IO) { compareRecovery(candidate, current) }
            if (state.prepare.editorGeneration == comparisonGeneration) recoveryComparison = compared
            else comparisonProblem = "The current project changed while comparing. Compare this recovery again."
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                comparisonProblem = "Recovery comparison could not be read: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        finally {
            if (currentCoroutineContext().isActive) comparisonLoading = false
        }
    }
    fun preservingCurrentProject(action: () -> Unit) {
        if (state.prepare.dirty) state.pendingDiscard = action else action()
    }
    fun openRecoveryCopy(candidate: ProjectRecoveryCandidate) {
        if (openingRecoveryCopy) return
        openingRecoveryCopy = true
        state.scope.launch(Dispatchers.Main) {
            try {
                val draft = if (state.prepare.dirty) state.prepare.toProject() else null
                val editorGeneration = state.prepare.editorGeneration
                val copy = withContext(Dispatchers.IO) {
                    draft?.let { state.library.preserveSessionDraft(it) }
                    state.library.openRecoveryAsCopy(candidate)
                }
                if (state.destination == Destination.PROJECTS && selectedRecovery?.part == candidate.part &&
                    state.prepare.editorGeneration == editorGeneration) {
                    runCatching { state.prepare.open(copy) }
                        .onSuccess { state.destination = Destination.PREPARE }
                        .onFailure { notice = "Recovery copy was saved, but could not be opened: ${it.message}" to null }
                } else notice = "Recovery copy was saved. Your current work stayed open; find the copy in Projects." to null
                refresh++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { recoveryProblem = "Recovery copy could not be opened: ${e.message}" }
            finally { openingRecoveryCopy = false }
        }
    }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeader("Projects", "Saved as standard 3MF files, so Android, the Web app and other slicers can open them.") {
            NzButton("Import", {
                chooseFiles("Import models or projects", listOf("stl", "3mf", "obj")).forEach { f ->
                    runCatching { state.library.import(f, state.version) }.onFailure { notice = "Couldn't import ${f.name}: ${it.message}" to null }
                }
                refresh++
            }, icon = NzIcon.IMPORT, enabled = !openingRecoveryCopy)
            NzButton("New project", { preservingCurrentProject { state.prepare.newProject(); state.destination = Destination.PREPARE } }, kind = ButtonKind.PRIMARY, icon = NzIcon.ADD, enabled = !openingRecoveryCopy)
        }
        notice?.let { (text, trashed) ->
            Banner(text, if (trashed != null) BannerKind.INFO else BannerKind.WARNING, if (trashed != null) "Undo" to {
                runCatching { state.library.restore(trashed) }; notice = null; refresh++ } else "Dismiss" to { notice = null })
        }
        recoveryProblem?.let { Banner(it, BannerKind.WARNING, "Dismiss" to { recoveryProblem = null }) }
        projectLoadProblem?.let { Banner(it, BannerKind.WARNING, "Retry" to { refresh++ }) }
        draftLoadProblem?.let { Banner(it, BannerKind.WARNING, "Retry" to { refresh++ }) }
        recoveryListProblem?.let { Banner(it, BannerKind.WARNING, "Retry" to { refresh++ }) }
        when {
            !draftsLoaded && !recoveriesLoaded -> Txt("Checking session drafts and recovery copies…", Nz.type.bodySmall, c.textMuted)
            !draftsLoaded -> Txt("Checking session drafts…", Nz.type.bodySmall, c.textMuted)
            !recoveriesLoaded -> Txt("Checking recovery copies…", Nz.type.bodySmall, c.textMuted)
        }
        recoveries?.takeIf { it.isNotEmpty() }?.let { pending ->
            Card(Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
                Txt("Project recovery copies (${pending.size})", Nz.type.title)
                Txt("These copies were left by interrupted saves. Your saved projects and recovery files remain unchanged until you choose an action.", Nz.type.bodySmall, c.textMuted)
                pending.forEach { candidate ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt("${candidate.target.name} · recovery revision ${candidate.revision ?: "unknown"}" +
                            if (candidate.keptSavedVersion) {
                                if (candidate.target.isFile) " · saved version kept" else " · recovery left un-restored"
                            } else "",
                            Nz.type.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                        NzButton(if (candidate.canOpenAsCopy) "Inspect" else "Compare", {
                            recoveryComparison = null
                            comparisonProblem = null
                            comparisonLoading = true
                            selectedRecovery = candidate
                            comparisonRefresh++
                            recoveryProblem = if (candidate.canOpenAsCopy) null else candidate.problem
                        },
                            kind = ButtonKind.SECONDARY, enabled = (candidate.problem == null || candidate.canOpenAsCopy) && !candidate.alreadyApplied &&
                                !restoringRecovery && !keepingSavedVersion && !openingRecoveryCopy)
                    }
                    if (candidate.canOpenAsCopy)
                        Txt("Inspect this recovery before opening it under a new project identity. The original file remains.",
                            Nz.type.bodySmall, c.textMuted)
                    if (candidate.previousKeepChoiceNeedsReview)
                        Txt("Previous Keep choice needs review", Nz.type.bodySmall, c.danger)
                    candidate.problem?.let { Txt(it, Nz.type.bodySmall, c.danger) }
                }
            }
        }
        selectedRecovery?.let { candidate ->
            Card(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                val missingSavedHead = recoveryComparison?.hasSavedHead == false ||
                    (recoveryComparison == null && !candidate.target.isFile)
                Txt(if (missingSavedHead) "Inspect recovery copy" else "Compare saved and recovery versions", Nz.type.title)
                if (comparisonLoading) Txt(when {
                    missingSavedHead && comparisonIncludesCurrentSession -> "Reading recovery and current session…"
                    missingSavedHead -> "Reading recovery copy…"
                    comparisonIncludesCurrentSession -> "Reading saved, recovery and current session…"
                    else -> "Reading saved and recovery copies…"
                }, Nz.type.bodySmall, c.textMuted)
                candidate.previousKeepChoiceReason?.let { reason ->
                    Txt(when (reason) {
                        KeepChoiceReviewReason.NO_HEAD_BECAME_SAVED -> "You previously left this recovery un-restored. A saved project now exists. Compare the current files before choosing again."
                        KeepChoiceReviewReason.SAVED_HEAD_CHANGED -> "You previously kept a different saved-project version. Compare the current files before choosing again."
                        KeepChoiceReviewReason.SAVED_HEAD_MISSING -> "You previously kept a saved project that is now missing. Inspect this recovery before choosing again."
                        KeepChoiceReviewReason.RECORD_UNVERIFIABLE -> "The previous Keep record cannot be verified. Compare the current files before choosing again."
                    }, Nz.type.bodySmall, c.danger)
                }
                comparisonProblem?.let { problem ->
                    Txt(problem, Nz.type.bodySmall, c.danger)
                    NzButton("Retry comparison", {
                        comparisonProblem = null
                        comparisonLoading = true
                        comparisonRefresh++
                    }, kind = ButtonKind.SECONDARY)
                }
                recoveryComparison?.let { comparison ->
                    Txt("Saved: ${comparison.saved}", Nz.type.bodySmall)
                    Txt("Recovery: ${comparison.newer}", Nz.type.bodySmall)
                }
                recoveryComparison?.currentSession?.let { session ->
                    Txt("Current unsaved session: $session", Nz.type.bodySmall)
                    Txt(if (recoveryComparison?.hasSavedHead == true)
                        "Current vs saved: ${recoveryComparison?.currentVsSaved}"
                    else "No saved project; inspect the current session fields below.",
                        Nz.type.bodySmall, c.textMuted)
                    Txt("Current vs recovery: ${recoveryComparison?.currentVsRecovery}", Nz.type.bodySmall, c.textMuted)
                    recoveryComparison?.currentVsSavedFields?.let { fields ->
                        Txt(if (recoveryComparison?.hasSavedHead == true)
                            "Current vs saved: ${fields.size} changed manifest fields"
                        else "No saved project; ${fields.size} current session manifest fields to inspect",
                            Nz.type.bodySmall, c.textMuted)
                        if (fields.isNotEmpty()) NzButton(if (recoveryComparison?.hasSavedHead == true)
                            (if (showCurrentVsSavedFields) "Hide current vs saved fields" else "Inspect current vs saved fields")
                        else (if (showCurrentVsSavedFields) "Hide current session fields" else "Inspect current session fields"), {
                            showCurrentVsSavedFields = !showCurrentVsSavedFields
                        }, kind = ButtonKind.QUIET)
                        if (showCurrentVsSavedFields) fields.forEach { Txt(it, Nz.type.bodySmall, c.textMuted) }
                    }
                    recoveryComparison?.currentVsRecoveryFields?.let { fields ->
                        Txt("Current vs recovery: ${fields.size} changed manifest fields", Nz.type.bodySmall, c.textMuted)
                        if (fields.isNotEmpty()) NzButton("Inspect current vs recovery fields", {
                            showCurrentVsRecoveryFields = !showCurrentVsRecoveryFields
                        }, kind = ButtonKind.QUIET)
                        if (showCurrentVsRecoveryFields) fields.forEach { Txt(it, Nz.type.bodySmall, c.textMuted) }
                    }
                    Txt("Restore will preserve this current session as a separate draft first.", Nz.type.bodySmall, c.textMuted)
                }
                recoveryComparison?.let { Txt(it.settings, Nz.type.bodySmall, c.textMuted) }
                recoveryComparison?.remainingSettings?.takeIf { it.isNotEmpty() }?.let { remaining ->
                    NzButton(if (showAllRecoverySettings) "Hide remaining changes" else "Show ${remaining.size} more changes",
                        { showAllRecoverySettings = !showAllRecoverySettings }, kind = ButtonKind.QUIET)
                    if (showAllRecoverySettings) remaining.forEach { Txt(it, Nz.type.bodySmall, c.textMuted) }
                }
                recoveryComparison?.changedProjectFields?.let { fields ->
                    Txt(if (recoveryComparison?.hasSavedHead == true)
                        "${fields.size} changed printer, material, plate, object or settings fields"
                    else "No saved project; ${fields.size} recovery manifest fields to inspect",
                        Nz.type.bodySmall, c.textMuted)
                    if (fields.isNotEmpty()) {
                        NzButton(if (showProjectFieldChanges) {
                            if (missingSavedHead) "Hide recovery fields" else "Hide all changed fields"
                        } else if (missingSavedHead) "Inspect recovery fields" else "Inspect all changed fields",
                            { showProjectFieldChanges = !showProjectFieldChanges }, kind = ButtonKind.QUIET)
                        if (showProjectFieldChanges) fields.forEach { Txt(it, Nz.type.bodySmall, c.textMuted) }
                    }
                }
                Txt(if (candidate.canOpenAsCopy)
                    "In-place Restore is unavailable for this recovery. Open as copy creates a new project identity, leaves the original recovery file in place, and preserves any unsaved session as a draft."
                else if (missingSavedHead)
                    "Restore creates the missing saved project and keeps the recovery copy. Keep recovery un-restored records your choice without removing it."
                else "Restore archives the saved head and keeps the recovery copy. Keep saved version leaves both files in place.",
                    Nz.type.bodySmall, c.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (candidate.canOpenAsCopy) NzButton("Open as copy", { openRecoveryCopy(candidate) },
                        kind = ButtonKind.PRIMARY, enabled = recoveryComparison != null && comparisonProblem == null &&
                            !comparisonLoading && !openingRecoveryCopy && !restoringRecovery && !keepingSavedVersion)
                    else NzButton("Restore recovery", {
                        restoringRecovery = true
                        state.scope.launch(Dispatchers.Main) {
                            try {
                                val draft = if (state.prepare.dirty) state.prepare.toProject() else null
                                val restored = withContext(Dispatchers.IO) {
                                    draft?.let { state.library.preserveSessionDraft(it) }
                                    state.library.recoverFromPart(candidate)
                                }
                                selectedRecovery = null; refresh++
                                if (state.destination == Destination.PROJECTS) {
                                    runCatching { state.prepare.open(restored) }
                                        .onSuccess { state.destination = Destination.PREPARE }
                                        .onFailure { notice = "Recovery was saved, but could not be opened: ${it.message}" to null }
                                } else notice = "Recovery was saved. Open it from Projects when ready." to null
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { recoveryProblem = "Recovery was not applied: ${e.message}" }
                            finally { restoringRecovery = false }
                        }
                    }, kind = ButtonKind.PRIMARY, enabled = recoveryComparison != null && candidate.problem == null &&
                        comparisonProblem == null && !comparisonLoading && !restoringRecovery && !keepingSavedVersion && !openingRecoveryCopy)
                    if (!candidate.canOpenAsCopy) NzButton(if (missingSavedHead) "Keep recovery un-restored" else "Keep saved version", {
                        keepingSavedVersion = true
                        state.scope.launch(Dispatchers.Main) {
                            try {
                                withContext(Dispatchers.IO) { state.library.keepSavedVersion(candidate) }
                                if (selectedRecovery?.part == candidate.part) selectedRecovery = null
                                refresh++
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { recoveryProblem = "Recovery choice could not be recorded: ${e.message}" }
                            finally { keepingSavedVersion = false }
                        }
                    }, kind = ButtonKind.SECONDARY, enabled = recoveryComparison != null && candidate.problem == null && comparisonProblem == null &&
                        !comparisonLoading && !keepingSavedVersion && !restoringRecovery && !openingRecoveryCopy)
                    NzButton("Defer", { selectedRecovery = null }, kind = ButtonKind.QUIET,
                        enabled = !restoringRecovery && !keepingSavedVersion && !openingRecoveryCopy)
                }
            }
        }
        if (selectedRecovery == null && sessionDrafts.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
            Txt("Preserved session drafts (${sessionDrafts.size})", Nz.type.title)
            Txt("Opening a draft creates a separate project copy; the preserved draft remains available.", Nz.type.bodySmall, c.textMuted)
            (if (showAllSessionDrafts) sessionDrafts else sessionDrafts.take(5)).forEach { draft ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt("${draft.name} · ${draft.objectCount} objects", Nz.type.bodySmall,
                        modifier = Modifier.weight(1f), maxLines = 1)
                    NzButton("Open as copy", { preservingCurrentProject {
                        state.scope.launch(Dispatchers.Main) {
                            try {
                                val file = withContext(Dispatchers.IO) { state.library.openSessionDraftAsCopy(draft.file) }
                                refresh++
                                runCatching { state.prepare.open(file) }
                                    .onSuccess { state.destination = Destination.PREPARE }
                                    .onFailure { notice = "Draft copy was saved, but could not be opened: ${it.message}" to null }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { notice = "Draft copy could not be opened: ${e.message}" to null }
                        }
                    } }, kind = ButtonKind.SECONDARY, enabled = draft.problem == null && !openingRecoveryCopy)
                }
                draft.problem?.let { Txt(it, Nz.type.bodySmall, c.danger) }
            }
            if (sessionDrafts.size > 5) NzButton(
                if (showAllSessionDrafts) "Show recent drafts" else "Show all ${sessionDrafts.size} drafts",
                { showAllSessionDrafts = !showAllSessionDrafts }, kind = ButtonKind.QUIET)
        }
        if (selectedRecovery == null && !libraryLoaded) Txt("Loading projects…", Nz.type.bodySmall, c.textMuted)
        else if (selectedRecovery == null && projectLoadProblem != null) Card(Modifier.fillMaxWidth()) {
            Txt("Project list unavailable. Retry after resolving the error above.", Nz.type.bodySmall, c.textMuted)
        } else if (selectedRecovery == null && projects.isEmpty()) Card(Modifier.fillMaxWidth()) {
            EmptyState(NzIcon.PROJECTS, "No projects yet", "Create or import a project.")
        } else if (selectedRecovery == null) {
            com.nozzleitall.desktop.settings.DenseInput(projectQuery, { projectQuery = it }, "Search projects",
                Modifier.fillMaxWidth(), placeholder = "Project name, printer or file")
            val query = projectQuery.trim()
            val visibleProjects = if (query.isEmpty()) projects else projects.filter { p ->
                listOfNotNull(p.name, p.printerModel, p.file.name, p.projectId).any { it.contains(query, ignoreCase = true) }
            }
            if (visibleProjects.isEmpty()) Card(Modifier.fillMaxWidth()) {
                Txt("No projects match “$query”.", Nz.type.bodySmall, c.textMuted)
            } else LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(visibleProjects, key = { it.file.absolutePath }) { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ProjectPreview(p)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(p.name, Nz.type.title, maxLines = 1)
                                Txt(if (p.problem == null) "Saved project" else "Needs review", Nz.type.bodySmall,
                                    if (p.problem == null) c.textMuted else Nz.status.paused)
                            }
                        }
                        Txt("${p.objectCount} object${if (p.objectCount == 1) "" else "s"}${p.printerModel?.let { " · for $it" } ?: ""}", Nz.type.bodySmall, c.textMuted)
                        Txt("Changed ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.modifiedMillis))}", Nz.type.bodySmall, c.textMuted)
                        p.problem?.let { Txt(it, Nz.type.bodySmall, Nz.status.paused) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NzButton("Open", { preservingCurrentProject {
                                runCatching { state.prepare.open(p.file) }.onSuccess { state.destination = Destination.PREPARE }.onFailure { notice = "Couldn't open ${p.name}: ${it.message}" to null }
                            } }, kind = ButtonKind.PRIMARY, enabled = p.objectCount > 0 && !openingRecoveryCopy)
                            NzButton("Move to trash", { runCatching { state.library.moveToTrash(p.file) }.onSuccess { notice = "${p.name} moved to the trash." to it }; refresh++ }, kind = ButtonKind.QUIET, enabled = !openingRecoveryCopy)
                        }
                    }
                }
            }
        }
    }
}
