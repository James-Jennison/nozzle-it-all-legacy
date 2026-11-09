package com.nozzleitall.desktop.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.HistoryAssessmentDraft
import com.nozzleitall.desktop.HistorySaveNotice
import com.nozzleitall.desktop.ReprintCandidate
import com.nozzleitall.desktop.history.JobRecord
import com.nozzleitall.desktop.prepare.PreflightGuidance
import com.nozzleitall.desktop.settings.LocalGuidanceDialog
import com.nozzleitall.desktop.jobs.LocalJobStore
import com.nozzleitall.desktop.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun resolveRequestedHistoryRecord(records: List<JobRecord>, requested: JobRecord?): JobRecord? =
    requested?.let { exact -> records.firstOrNull { it == exact } }

internal fun canApplyHistoryReview(tokenAtStart: Any, activeToken: Any, selected: JobRecord?,
                                   record: JobRecord, records: List<JobRecord>): Boolean =
    tokenAtStart === activeToken && selected == record && resolveRequestedHistoryRecord(records, record) == record

internal fun historyNoticeLabel(record: JobRecord): String =
    "${record.fileName.substringAfterLast('/')} on ${record.printerName}, " +
        "ended ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(record.endedAtMillis))} " +
        "(result ${record.id})"

/** Retain the write result even if a concurrent history refresh changed or removed its row. */
internal fun retainHistorySaveSuccess(state: AppState, record: JobRecord, updated: LocalJobStore.ObservationReview): Boolean {
    state.historyCompletedSaves[record] = updated
    state.historySaveProblems.remove(record)
    state.historySaveNotices.remove(record)
    val stillListed = resolveRequestedHistoryRecord(state.fleet.history.value, record) == record
    if (!stillListed) state.historySaveNotices[record] = HistorySaveNotice(
        "Assessment for ${historyNoticeLabel(record)} was saved, but its print result changed or left History.", false)
    else if (state.destination != Destination.HISTORY || state.selectedHistoryRecord != record)
        state.historySaveNotices[record] = HistorySaveNotice(
            "Assessment saved for ${historyNoticeLabel(record)} while you were viewing something else.", false)
    return stillListed
}

internal fun retainHistorySaveFailure(state: AppState, record: JobRecord, cause: Exception) {
    state.historySaveProblems[record] = "The result could not be saved: ${cause.message}"
    state.historySaveNotices.remove(record)
    if (resolveRequestedHistoryRecord(state.fleet.history.value, record) != record)
        state.historySaveNotices[record] = HistorySaveNotice(
            "Assessment for ${historyNoticeLabel(record)} could not be saved after its print result changed or left History.", true)
    else if (state.destination != Destination.HISTORY || state.selectedHistoryRecord != record)
        state.historySaveNotices[record] = HistorySaveNotice(
            "Assessment for ${historyNoticeLabel(record)} could not be saved. Open its result to review the error and retry.", true)
}

internal fun dismissHistorySaveNotice(state: AppState, record: JobRecord) {
    // The completion cache can still be needed if this exact row returns after a refresh.
    state.historySaveNotices.remove(record)
}

internal fun discardOriginalHistoryDraft(state: AppState, record: JobRecord) {
    state.historyDrafts.remove(record)
}

internal fun historyReviewAfterLoad(state: AppState, record: JobRecord,
                                   loaded: LocalJobStore.ObservationReview?): LocalJobStore.ObservationReview? =
    state.historyCompletedSaves.remove(record) ?: loaded

internal enum class HistoryLoadPhase { LOADING, READY, RETRYABLE_ERROR, UNAVAILABLE }

internal fun historyLoadFailure(cause: Exception): Pair<String, HistoryLoadPhase> {
    val retryable = cause is org.sqlite.SQLiteException &&
        (cause.resultCode.code and 0xFF) in setOf(5, 6)
    return if (retryable)
        "Local history is busy. Retry loading the assessment." to HistoryLoadPhase.RETRYABLE_ERROR
    else "This assessment cannot be read from local history. Close this detail; no assessment was changed." to HistoryLoadPhase.UNAVAILABLE
}

/** The prints this app has watched finish on one printer, newest first, each with a way to run it again. */
@Composable
fun HistoryScreen(state: AppState) {
    val c = Nz.colors
    var reprintReview by remember { mutableStateOf<Pair<JobRecord, ReprintCandidate>?>(null) }
    var reprintProblem by remember { mutableStateOf<String?>(null) }
    val requestedRecord = state.selectedHistoryRecord
    val selectedRecord = resolveRequestedHistoryRecord(state.fleet.history.value, requestedRecord)
    val selectionToken = remember(selectedRecord) { Any() }
    val activeSelectionToken = rememberUpdatedState(selectionToken)
    DisposableEffect(Unit) { onDispose { state.selectedHistoryRecord = null } }
    // Keep completed saves in a stable state slot: a save started on A can finish after A → B → A.
    var reviewState by remember { mutableStateOf<Pair<JobRecord, LocalJobStore.ObservationReview?>?>(null) }
    val selectedReview = reviewState?.takeIf { it.first == selectedRecord }?.second
    val draft = selectedRecord?.let { state.historyDrafts[it] } ?: HistoryAssessmentDraft()
    val assessment = draft.assessment
    val assessmentNotes = draft.notes
    var loadProblem by remember { mutableStateOf<Pair<JobRecord, String>?>(null) }
    var loadRequest by remember(selectedRecord) { mutableIntStateOf(0) }
    var loadPhase by remember(selectedRecord) { mutableStateOf(HistoryLoadPhase.LOADING) }
    val assessmentProblem = state.historySaveProblems[selectedRecord]
        ?: loadProblem?.takeIf { it.first == selectedRecord }?.second
    val assessmentBusy = selectedRecord != null && state.historySavePending.containsKey(selectedRecord)
    LaunchedEffect(selectedRecord, assessmentBusy, loadRequest) {
        loadProblem = null
        if (assessmentBusy) return@LaunchedEffect
        loadPhase = HistoryLoadPhase.LOADING
        val completed = selectedRecord?.let(state.historyCompletedSaves::remove)
        if (selectedRecord != null && completed != null) {
            // A successful write is authoritative even if it finished while another row was selected.
            reviewState = selectedRecord to completed
            loadPhase = HistoryLoadPhase.READY
            return@LaunchedEffect
        }
        reviewState = null
        selectedRecord?.let { record ->
            try {
                val review = withContext(Dispatchers.IO) { state.fleet.historyAssessment(record) }
                if (!state.historySavePending.containsKey(record) &&
                    canApplyHistoryReview(selectionToken, activeSelectionToken.value,
                        state.selectedHistoryRecord, record, state.fleet.history.value)) {
                    val current = historyReviewAfterLoad(state, record, review)
                    reviewState = record to current
                    if (current == null) {
                        loadProblem = record to "This observation is not yet saved in local job history."
                        loadPhase = HistoryLoadPhase.UNAVAILABLE
                    } else loadPhase = HistoryLoadPhase.READY
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!state.historySavePending.containsKey(record) &&
                    canApplyHistoryReview(selectionToken, activeSelectionToken.value,
                        state.selectedHistoryRecord, record, state.fleet.history.value)) {
                    val (message, phase) = historyLoadFailure(e)
                    loadProblem = record to message
                    loadPhase = phase
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        state.fleet.historyMigrationProblem.value?.let { Banner(it, BannerKind.WARNING) }
        state.fleet.historyConflictProblem.value?.let { Banner(it, BannerKind.WARNING) }
        state.fleet.currentObservationProblem.value?.let { Banner(it, BannerKind.WARNING) }
        state.historySavePending.keys.filter { it != selectedRecord }.forEach { pending ->
            Banner("Saving assessment for ${historyNoticeLabel(pending)} while you view another print.", BannerKind.INFO)
        }
        state.historySaveNotices.forEach { (record, notice) ->
            Banner(notice.message, if (notice.isError) BannerKind.WARNING else BannerKind.INFO,
                "Dismiss" to { dismissHistorySaveNotice(state, record) })
        }
        state.historyDrafts.keys.filter { it != requestedRecord && resolveRequestedHistoryRecord(state.fleet.history.value, it) == null }.forEach { record ->
            Banner("An unsaved assessment for ${historyNoticeLabel(record)} belongs to an earlier print result.",
                BannerKind.WARNING, "View original draft" to { state.openHistoryRecord(record) })
        }
        if (requestedRecord != null && selectedRecord == null) {
            Banner("That print result is no longer in local history. The Jobs overview may have changed.", BannerKind.WARNING)
            if (state.historySavePending.containsKey(requestedRecord))
                Banner("An assessment save for this print is still pending.", BannerKind.INFO)
            state.historySaveProblems[requestedRecord]?.let { Banner(it, BannerKind.WARNING) }
            if (state.historySaveProblems[requestedRecord] == null && state.historyCompletedSaves.containsKey(requestedRecord))
                Banner("The assessment was saved for the original print, but its result is no longer listed. Check Jobs for the latest history.", BannerKind.INFO)
            state.historyDrafts[requestedRecord]?.let { draft ->
                Banner("A summary of your unsaved assessment for the original print is shown below. It cannot be saved against the changed print result.", BannerKind.WARNING)
                Txt("Draft choice: ${draft.assessment?.name?.replace('_', ' ')?.lowercase() ?: "not selected"}", Nz.type.bodySmall)
                if (draft.notes.isNotEmpty()) Txt("Draft note: ${draft.notes}", Nz.type.bodySmall)
                NzButton("Discard original draft", { discardOriginalHistoryDraft(state, requestedRecord) },
                    kind = ButtonKind.QUIET, testTag = "history-discard-original-draft")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NzButton("Back to Jobs", { state.selectedHistoryRecord = null; state.destination = Destination.JOBS }, kind = ButtonKind.PRIMARY)
                NzButton("Show remaining history", { state.selectedHistoryRecord = null }, kind = ButtonKind.SECONDARY)
            }
        }
        val orphanedRecord = selectedRecord?.takeIf { it.printerId !in state.fleet.printers }
        val entry = if (orphanedRecord == null)
            PrinterHeader(state, Destination.HISTORY, "Prints that ended while Nozzle It All was watching.") else null
        if (entry == null && selectedRecord == null) {
            NoPrinterYet(state, "this printer's past prints")
            return@Column
        }
        if (orphanedRecord != null) {
            Txt("Past print for unavailable printer ${orphanedRecord.printerName}", Nz.type.title)
        }
        val records = if (orphanedRecord != null) listOf(orphanedRecord)
            else state.fleet.history.value.filter { it.printerId == entry?.config?.identity?.id }
        reprintProblem?.let { Banner(it, BannerKind.WARNING, "Dismiss" to { reprintProblem = null }) }
        selectedRecord?.let { record ->
            var diagnose by remember(record) { mutableStateOf(false) }
            fun currentFailure(): PreflightGuidance.Snapshot? {
                val latestRecord = resolveRequestedHistoryRecord(state.fleet.history.value, state.selectedHistoryRecord)
                if (latestRecord != record || state.historySavePending.containsKey(record)) return null
                val latestReview = state.historyCompletedSaves[record]
                    ?: reviewState?.takeIf { it.first == record }?.second
                return PreflightGuidance.jobFailure(record, latestReview?.latestOutcome?.assessment)
            }
            val failure = currentFailure()
            Card(Modifier.fillMaxWidth()) {
                Txt("Print result: ${record.fileName.substringAfterLast('/')}", Nz.type.title)
                Txt("Machine ${record.outcome}. Physical result is recorded separately.", Nz.type.bodySmall, c.textMuted)
                if (failure != null) NzButton("AI failure diagnosis", { diagnose = true },
                    kind = ButtonKind.QUIET, testTag = "history-ai-diagnosis")
                if (diagnose && failure != null) LocalGuidanceDialog(failure,
                    current = ::currentFailure,
                    onDismiss = { diagnose = false })
                if (assessmentBusy) Banner("Saving this result. You can view another print while it finishes.", BannerKind.INFO)
                assessmentProblem?.let { Banner(it, BannerKind.WARNING) }
                selectedReview?.let { review ->
                    Txt("Source: ${when (review.job.origin) {
                        LocalJobStore.Origin.APP -> "Nozzle It All job"
                        LocalJobStore.Origin.EXTERNAL -> "External print"
                        LocalJobStore.Origin.LEGACY_UNKNOWN -> "Imported history"
                        LocalJobStore.Origin.OBSERVED_UNLINKED -> "Observed print; project not linked"
                    }}", Nz.type.bodySmall)
                    Txt("Outcome: ${review.latestOutcome?.assessment?.name?.replace('_', ' ')?.lowercase() ?: "not reviewed"}", Nz.type.bodySmall)
                    review.latestOutcome?.notes?.takeIf { it.isNotBlank() }?.let { Txt("Latest note: $it", Nz.type.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LocalJobStore.Assessment.entries.forEach { choice ->
                            NzButton(choice.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercaseChar),
                                { state.historyDrafts[record] = (state.historyDrafts[record] ?: HistoryAssessmentDraft()).copy(assessment = choice) },
                                kind = if (assessment == choice) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                                enabled = !assessmentBusy)
                        }
                    }
                    Field("Optional result note", assessmentNotes, { text ->
                        state.historyDrafts[record] = (state.historyDrafts[record] ?: HistoryAssessmentDraft()).copy(notes = text.take(10_000))
                    },
                        modifier = Modifier.fillMaxWidth(), hint = "You can revise the assessment later; earlier entries remain in history.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NzButton("Save result", {
                            val draftAtSave = state.historyDrafts[record] ?: return@NzButton
                            val chosen = draftAtSave.assessment ?: return@NzButton
                            if (state.historySavePending.containsKey(record)) return@NzButton
                            val notesToSave = draftAtSave.notes
                            state.historySavePending[record] = Unit
                            state.historySaveProblems.remove(record)
                            state.historySaveNotices.remove(record)
                            state.scope.launch(Dispatchers.Main.immediate) {
                                try {
                                    val updated = withContext(Dispatchers.IO) {
                                        state.fleet.assessHistoryObservation(record, review.latestOutcome?.revision ?: 0L,
                                            chosen, notesToSave)
                                    }
                                    if (retainHistorySaveSuccess(state, record, updated)) {
                                        if (state.selectedHistoryRecord == record) { reviewState = record to updated; loadProblem = null }
                                    }
                                    if (state.historyDrafts[record] == draftAtSave) state.historyDrafts.remove(record)
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) {
                                    retainHistorySaveFailure(state, record, e)
                                } finally {
                                    state.historySavePending.remove(record)
                                }
                            }
                        }, kind = ButtonKind.PRIMARY, enabled = assessment != null && !assessmentBusy,
                            testTag = "history-save-outcome")
                        NzButton("Close", {
                            state.selectedHistoryRecord = null
                            if (entry == null) state.destination = Destination.JOBS
                        }, kind = ButtonKind.QUIET, enabled = !assessmentBusy, testTag = "history-close")
                    }
                }
                if (selectedReview == null && !assessmentBusy && loadPhase == HistoryLoadPhase.LOADING)
                    Banner("Loading the assessment…", BannerKind.INFO)
                if (selectedReview == null && !assessmentBusy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (loadPhase == HistoryLoadPhase.RETRYABLE_ERROR)
                            NzButton("Retry loading assessment", { loadRequest++ },
                                kind = ButtonKind.SECONDARY, testTag = "history-retry-load")
                        NzButton("Close", {
                            state.selectedHistoryRecord = null
                            if (entry == null) state.destination = Destination.JOBS
                        }, kind = ButtonKind.QUIET, testTag = "history-close")
                    }
                }
            }
        }
        // An orphaned observation is already open above. A printer-specific list cannot offer
        // a useful picker or reprint target, and duplicates the result in a cramped row.
        if (orphanedRecord != null) return@Column
        reprintReview?.takeIf { entry != null }?.let { (previous, candidate) ->
            Card(Modifier.fillMaxWidth()) {
                Txt("Review reprint", Nz.type.title)
                Txt("A new job will use the retained prepared file. Nothing starts until you review its queue entry and explicitly choose Start.", Nz.type.bodySmall, c.textMuted)
                Txt("Printer: ${previous.printerName} → ${entry!!.config.identity.displayName}", Nz.type.bodySmall)
                Txt("Project: ${candidate.projectId ?: "not recorded"} · revision ${candidate.projectRevision ?: "not recorded"}", Nz.type.bodySmall)
                Txt("Printer profile: ${candidate.targetProfileId ?: "not recorded"} → ${entry.config.identity.profileId ?: "not reported"}", Nz.type.bodySmall)
                Txt("Material, spool, and toolhead assignments: previous not recorded → check the printer's current setup before Start", Nz.type.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NzButton("Add reprint to queue", {
                        runCatching {
                            state.fleet.enqueue(candidate.fileName.substringBeforeLast('.', candidate.fileName), candidate.file,
                                entry.config.identity.id, emptyList(), candidate.projectId, candidate.projectRevision,
                                candidate.plateId, candidate.targetProfileId, candidate.engineBuild,
                                candidate.inputDigest, candidate.sourceJobId)
                        }.onSuccess { reprintReview = null; state.destination = Destination.JOBS }
                            .onFailure { reprintProblem = "Reprint could not be queued: ${it.message}" }
                    }, kind = ButtonKind.PRIMARY, testTag = "reprint-queue")
                    NzButton("Cancel review", { reprintReview = null }, kind = ButtonKind.QUIET)
                }
            }
        }
        if (records.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                EmptyState(NzIcon.LAYERS, "No prints recorded yet",
                    "When a print finishes, is stopped or fails while Nozzle It All is running, it is listed here, and you get a notification.")
            }
            return@Column
        }
        records.forEachIndexed { index, r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt(r.fileName.substringAfterLast('/'), Nz.type.title)
                    Txt(describe(r), Nz.type.bodySmall, c.textMuted)
                    Txt(if (selectedRecord == r) "Result details open above" else "Machine result · physical result available in details", Nz.type.bodySmall, c.textMuted)
                    Pill(when (r.outcome) { "finished" -> "Finished"; "cancelled" -> "Stopped"; "failed" -> "Failed"; else -> "Unknown" },
                        when (r.outcome) { "finished" -> Nz.status.finished; "cancelled", "unknown" -> c.textMuted; else -> c.danger })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        NzButton("Result details", { state.selectedHistoryRecord = r },
                            kind = ButtonKind.SECONDARY, testTag = "history-result-${r.id}-$index")
                        NzButton("Review reprint", {
                            runCatching { state.fleet.reprintCandidate(r) }
                                .onSuccess { candidate ->
                                    if (candidate == null) reprintProblem = "This print has no retained local prepared file. Prepare a new job from its project or import a file."
                                    else { reprintProblem = null; reprintReview = r to candidate }
                                }
                                .onFailure { reprintProblem = "The retained file could not be verified: ${it.message}" }
                        }, kind = ButtonKind.SECONDARY, icon = NzIcon.PLAY,
                            testTag = "reprint-${r.id}-$index")
                    }
                }
            }
        }
        Txt("Reprint creates a separate reviewed job when its prepared file is retained locally.", Nz.type.bodySmall, c.textMuted)
    }
}

private fun describe(r: JobRecord): String {
    val whenText = SimpleDateFormat("EEE d MMM, h:mm a").format(Date(r.endedAtMillis))
    val took = r.elapsedSeconds?.takeIf { it > 0 }?.let { " · " + formatDuration(it) }.orEmpty()
    return whenText + took + (r.totalLayers?.let { " · $it layers" } ?: "")
}
