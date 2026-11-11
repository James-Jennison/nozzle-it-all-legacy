package com.nozzleitall.desktop.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.MonitorHandoffState
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.desktop.PrinterTransferBusyException
import com.nozzleitall.desktop.printerProfileMismatch
import com.nozzleitall.desktop.settings.Choice
import com.nozzleitall.desktop.settings.DenseSelect
import com.nozzleitall.desktop.queue.QueueItem
import com.nozzleitall.desktop.prepare.preflightObservation
import com.nozzleitall.desktop.prepare.preparedMaterialChecks
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun PrinterEntry.isFree() = status.value.state in setOf(PrinterState.READY, PrinterState.FINISHED, PrinterState.CANCELLED)
private fun PrinterEntry.canTakeJobs(item: QueueItem) = capabilities.value?.let {
    item.output in it.acceptedOutputs && (it.uploadAndStart || (it.uploadJob && it.startPrint))
} == true
internal fun queuedRemoteName(item: QueueItem): String = item.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(12) +
    "-" + item.id + "." + item.output

/** A transfer can outlive the Jobs composition; keep its pending confirmation until the app closes. */
class QueueRowUiState {
    val detailsOpen = mutableStateOf(false)
    val pinnedPrinterId = mutableStateOf<String?>(null)
    val uploading = mutableStateOf<Float?>(null)
    var transferActive by mutableStateOf(false)
    val problem = mutableStateOf<String?>(null)
    val expectedStart = mutableStateOf<PrinterAction?>(null)
    internal var approvedDirectStart by mutableStateOf<PreparedAction?>(null)
    val resolveStart = mutableStateOf<String?>(null)
    val resolutionHold = mutableStateOf<QueueItem?>(null)
    var dispatchedHold: QueueItem? = null
    val reviewStart = mutableStateOf<String?>(null)
    // A confirmation may outlive the drawer. Keep the predicate from the review that created it
    // so opening the standalone queue cannot turn a stale workspace confirmation into a valid one.
    internal var reviewContext: (() -> Boolean)? = null
    internal var reviewedConnection: ReviewedQueueConnection? = null
    val legacyRemotePath = mutableStateOf("")
    var flowEntry: PrinterEntry? = null
    var flow: ActionFlow? = null
    fun flowFor(entry: PrinterEntry?): ActionFlow? {
        if (entry == null) return null
        if (flowEntry !== entry) {
            flowEntry = entry
            flow = ActionFlow(entry)
        }
        return flow
    }
}

internal data class ReviewedQueueConnection(
    val entry: PrinterEntry, val session: PrinterSession, val guard: ActionGuard, val capabilities: Capabilities,
    val tools: List<Toolhead>,
) {
    fun isCurrent(candidate: PrinterEntry) = entry === candidate && !candidate.retired &&
        candidate.session === session && candidate.guard === guard && candidate.capabilities.value == capabilities &&
        candidate.status.value.toolheads.map { it.preflightObservation() } == tools
}

/**
 * The prints waiting for a printer. Nothing starts by itself: "Start" asks, like every other command, and only a person can say the
 * plate is clear. A job for one printer waits for that printer; a job for "any" is offered the first free printer that can take it.
 */
@Composable
fun QueuePanel(state: AppState, query: String = "") {
    val items = state.fleet.queue.value
    val recovery = state.fleet.queueRecoveryProblems.value
    val rechecking = state.fleet.queueRechecking.value
    LaunchedEffect(items.map { it.id }) { state.queueRowUi.keys.retainAll(items.map { it.id }.toSet()) }
    if (items.isEmpty() && recovery.isEmpty() && !rechecking) return
    val c = Nz.colors
    Card(Modifier.fillMaxWidth()) {
        Txt("Print queue (${items.size})", Nz.type.title)
        Txt("Waiting for a printer. Starting one asks first; nothing runs on its own.", Nz.type.bodySmall, c.textMuted)
        recovery.forEach { problem -> Banner(problem, BannerKind.WARNING) }
        if (recovery.isNotEmpty() || rechecking)
            NzButton(if (rechecking) "Rechecking queue…" else "Recheck queue",
                { state.scope.launch(Dispatchers.Main) { state.fleet.recheckQueueFromUi() } },
                kind = ButtonKind.SECONDARY, enabled = !rechecking)
        val matching = items.withIndex().filter { (_, item) ->
            query.isBlank() || listOfNotNull(item.name, item.fileName,
                item.printerId?.let { state.fleet.printers[it]?.config?.identity?.displayName })
                .any { it.contains(query, ignoreCase = true) } ||
                state.queueRowUi[item.id]?.let { it.uploading.value != null || it.flow?.busy == true } == true
        }
        if (items.isNotEmpty() && matching.isEmpty()) Txt("No queued jobs match this search.", Nz.type.bodySmall, c.textMuted)
        matching.forEach { (i, item) -> key(item.id) { QueueRow(state, item, first = i == 0, last = i == items.lastIndex) } }
    }
}

@Composable
internal fun DirectPrintStart(
    state: AppState,
    item: QueueItem,
    stillCurrent: () -> Boolean,
    plate: com.nozzleitall.desktop.prepare.SlicedPlateContext?,
    onReviewProblem: (String) -> Unit,
    onHandoff: (String, MonitorHandoffState) -> Unit,
) {
    DisposableEffect(item.id) {
        onDispose {
            val ui = state.queueRowUi[item.id]
            if (state.fleet.queue.value.any { it == item } && !item.startUncertain && !item.pendingLink &&
                ui?.transferActive != true && ui?.flow?.busy != true) {
                ui?.flow?.pending = null
                ui?.reviewStart?.value = null
                ui?.approvedDirectStart = null
                if (item.directDraft) runCatching { state.fleet.discardUntransferredDirectDraft(item) }
                    .onFailure { state.prepare.notice = "The local print record needs attention in Queue & history: ${it.message}" }
            }
        }
    }
    val current = state.fleet.queue.value.firstOrNull { it.id == item.id } ?: return
    if (current != item && !current.startUncertain) {
        Banner("This prepared print changed. Review it in Queue & history.", BannerKind.WARNING)
        NzButton("Open queue", { state.destination = com.nozzleitall.desktop.Destination.JOBS }, kind = ButtonKind.SECONDARY)
        return
    }
    QueueRow(state, current, first = true, last = true, stillCurrent = stillCurrent, onHandoff = onHandoff, onReviewProblem = onReviewProblem, plate = plate)
}

@Composable
private fun QueueRow(
    state: AppState, item: QueueItem, first: Boolean, last: Boolean,
    stillCurrent: () -> Boolean = { true },
    onHandoff: ((String, MonitorHandoffState) -> Unit)? = null,
    onReviewProblem: ((String) -> Unit)? = null,
    plate: com.nozzleitall.desktop.prepare.SlicedPlateContext? = null,
) {
    val c = Nz.colors
    val fleet = state.fleet
    val scope = state.scope
    val ids = fleet.order.value
    val ui = state.queueRowUi.getOrPut(item.id) { QueueRowUiState() }
    var detailsOpen by ui.detailsOpen
    val queuedSourcePresent = fleet.queuedSourcePresent(item)
    var pinnedPrinterId by ui.pinnedPrinterId
    val target: PrinterEntry? = item.printerId?.let { fleet.printers[it] }
    // The printer this row would run on now: its own, or for "any" the first free one that can take a job.
    val available = ids.mapNotNull { fleet.printers[it] }.filter { it.isFree() && it.canTakeJobs(item) }
    val anyCandidate = if (item.targetProfileId == null) available.firstOrNull() else
        available.firstOrNull { it.config.identity.profileId == item.targetProfileId }
            ?: available.firstOrNull { it.config.identity.profileId == null }
    val candidate = when {
        pinnedPrinterId != null -> fleet.printers[pinnedPrinterId]
        item.printerId != null -> target
        else -> anyCandidate
    }
    val previousEntry = ui.flowEntry
    val previousFlow = ui.flow
    val candidateChanged = previousEntry != null && previousEntry !== candidate
    // A command already executing on the old connection must finish before this row offers another confirmation.
    val flow = if (candidateChanged && previousFlow?.busy == true) null else ui.flowFor(candidate)
    val profileMismatch = item.targetProfileId != null && candidate?.config?.identity?.profileId != null &&
        item.targetProfileId != candidate.config.identity.profileId
    val unknownPrinterProfile = item.targetProfileId != null && candidate?.config?.identity?.profileId == null
    val materialChecks = candidate?.let { entry ->
        entry.capabilities.value?.let { preparedMaterialChecks(item.materialPlan, entry.status.value, it) }
    }.orEmpty()
    val materialConflict = materialChecks.any { it.blocks }
    var uploading by ui.uploading
    var problem by ui.problem
    var expectedStart by ui.expectedStart
    var resolveStart by ui.resolveStart
    var resolutionHold by ui.resolutionHold
    var reviewStart by ui.reviewStart
    var legacyRemotePath by ui.legacyRemotePath
    fun clearReview() {
        ui.reviewContext = null
        ui.reviewedConnection = null
        ui.approvedDirectStart = null
        pinnedPrinterId = null
    }
    LaunchedEffect(candidate, previousEntry) {
        if (candidateChanged && (previousFlow?.pending != null || reviewStart != null)) {
            previousFlow?.pending = null
            expectedStart = null
            reviewStart = null
            clearReview()
            problem = "The printer connection changed. Review this queued job again before Start."
        } else if (candidateChanged && previousFlow?.busy == true) {
            problem = "The printer connection changed during Start. Wait for the result and inspect the queue."
        }
    }
    LaunchedEffect(reviewStart, candidate?.config?.identity?.id) {
        if (reviewStart != null && reviewStart != candidate?.config?.identity?.id) {
            reviewStart = null
            clearReview()
            problem = "The selected printer changed. Review the queued job again."
        }
    }
    val summary: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth()) {
                Txt(item.name, Nz.type.body, maxLines = Int.MAX_VALUE)
                Txt("For " + (target?.config?.identity?.displayName ?: "any compatible printer") + when {
                    candidate == null -> if (item.printerId != null) " (printer not found)" else " · no free printer yet"
                    !candidate.isFree() -> " · busy"
                    else -> " · printer available"
                }, Nz.type.bodySmall, c.textMuted)
                NzButton(if (detailsOpen) "Hide details" else "Details", { detailsOpen = !detailsOpen },
                    modifier = Modifier.semantics { stateDescription = if (detailsOpen) "Expanded" else "Collapsed" },
                    kind = ButtonKind.QUIET, testTag = "queue-details-${item.id}")
            }
    }
    fun dismissReview() {
        reviewStart = null
        expectedStart = null
        clearReview()
        if (onHandoff != null && item.directDraft && !item.startUncertain && !item.pendingLink && !ui.transferActive && flow?.busy != true)
            runCatching { fleet.discardUntransferredDirectDraft(item) }
                .onFailure { problem = "The local print record could not be removed: ${it.message}" }
    }
    fun requestReview(entry: PrinterEntry) {
        val session = entry.session
        val guard = entry.guard
        val capabilities = entry.capabilities.value
        if (session == null || guard == null || capabilities == null || entry.retired) {
            problem = "The printer is not connected. Review again when it reconnects."
            return
        }
        ui.reviewContext = stillCurrent
        ui.reviewedConnection = ReviewedQueueConnection(entry, session, guard, capabilities,
            entry.status.value.toolheads.map { it.preflightObservation() })
        pinnedPrinterId = entry.config.identity.id
        reviewStart = entry.config.identity.id
    }
    LaunchedEffect(item.id) {
        if (onHandoff != null && candidate != null && !materialConflict && !profileMismatch && stillCurrent() && !item.startUncertain &&
            !item.pendingLink && reviewStart == null && expectedStart == null && uploading == null)
            requestReview(candidate)
    }
    val actions: @Composable () -> Unit = {
            if (onHandoff == null) {
                val anyPrinter = ""
                val selectedId = item.printerId ?: anyPrinter
                val targets = ids.mapNotNull { fleet.printers[it] }.filter { entry ->
                    val identity = entry.config.identity
                    identity.id == item.printerId || (!printerProfileMismatch(item.targetProfileId, identity.profileId) &&
                        entry.canTakeJobs(item))
                }
                val choices = buildList {
                    add(Choice(anyPrinter, "Any compatible printer"))
                    targets.forEach { add(Choice(it.config.identity.id, it.config.identity.displayName)) }
                    if (item.printerId != null && target == null) add(Choice(item.printerId, "Saved printer unavailable"))
                }
                DenseSelect("Job destination", choices, selectedId, Modifier.width(240.dp),
                    enabled = !item.pendingLink && !item.startUncertain && uploading == null &&
                        expectedStart == null && reviewStart == null && flow?.pending == null && flow?.busy != true) { choice ->
                    if (choice != selectedId) runCatching {
                        fleet.retargetQueued(item.id, choice.takeUnless(String::isEmpty), expectedItem = item)
                    }.onSuccess {
                        clearReview()
                        problem = null
                    }.onFailure { problem = it.message ?: "The queued job could not be retargeted." }
                }
                NzButton("↑", { fleet.moveInQueue(item.id, -1) }, kind = ButtonKind.QUIET, enabled = !first && uploading == null && expectedStart == null && flow?.pending == null && flow?.busy != true)
                NzButton("↓", { fleet.moveInQueue(item.id, 1) }, kind = ButtonKind.QUIET, enabled = !last && uploading == null && expectedStart == null && flow?.pending == null && flow?.busy != true)
                NzButton("Remove", { fleet.dequeue(item.id) }, kind = ButtonKind.QUIET, enabled = !item.pendingLink && !item.startUncertain && uploading == null && expectedStart == null && flow?.pending == null && flow?.busy != true)
            }
            if (item.pendingLink) Txt("Local queue records are being recovered; Start is unavailable.", Nz.type.bodySmall, c.textMuted)
            if (item.startUncertain) {
                val canResolve = flow?.busy != true && previousFlow?.busy != true
                NzButton("Confirm it started", { resolutionHold = item; resolveStart = "choose" },
                    kind = ButtonKind.QUIET, enabled = canResolve)
                NzButton("Confirm it did not start", { resolutionHold = item; resolveStart = "not-started" },
                    kind = ButtonKind.QUIET, enabled = canResolve)
            }
            if (candidate?.guard?.needsReconcile == true) {
                Txt("The last printer command has an unknown outcome. Check the printer before starting another job.", Nz.type.bodySmall, c.danger)
                if (flow != null) NzButton("Check printer", { state.scope.launch(Dispatchers.Main) { flow.reconcile() } }, kind = ButtonKind.SECONDARY)
            }
            if (item.sha256 == null) Txt("This older queued file has no integrity digest. Requeue it before starting.", Nz.type.bodySmall, c.textMuted)
            if (unknownPrinterProfile && candidate != null) AssociatePrinterProfile(state, candidate, item.targetProfileId)
            if (profileMismatch) Txt("This job was sliced for ${item.targetProfileId}, not this printer's ${candidate?.config?.identity?.profileId}. Choose a matching printer or slice again.", Nz.type.bodySmall, c.danger)
            materialChecks.filter { it.blocks }.forEach { Banner(it.message, BannerKind.DANGER) }
            if (candidate != null && flow != null) NzButton(
                if (uploading != null) "Sending…" else "Review and Start",
                { requestReview(candidate) },
                kind = ButtonKind.PRIMARY, icon = NzIcon.PLAY, enabled = queuedSourcePresent && !profileMismatch && !materialConflict && !item.pendingLink && !item.startUncertain && item.sha256 != null &&
                    candidate.isFree() && candidate.canTakeJobs(item) && candidate.session != null &&
                    candidate.guard?.needsReconcile == false && uploading == null && expectedStart == null && flow.pending == null && !flow.busy,
                testTag = "queue-review-${item.id}")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (onHandoff == null) summary()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
        if (detailsOpen) Column(Modifier.fillMaxWidth().padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt("Prepared job details", Nz.type.label)
            Txt("Job name: ${item.name}", Nz.type.bodySmall, c.textMuted)
            Txt("Queued file: ${item.fileName}", Nz.type.bodySmall, c.textMuted)
            Txt("Queue ID: ${item.id}", Nz.type.bodySmall, c.textMuted)
            Txt("Job ID: ${item.jobId ?: "not linked"}", Nz.type.bodySmall, c.textMuted)
            Txt(item.projectId?.let { id -> "Saved project: $id" + (item.projectRevision?.let { " · revision $it" } ?: "") }
                ?: "Saved project: none linked", Nz.type.bodySmall, c.textMuted)
            when {
                item.plateId != null -> Txt("Saved plate ID: ${item.plateId}", Nz.type.bodySmall, c.textMuted)
                item.draftPlateId != null -> Txt("Draft plate ID: ${item.draftPlateId}", Nz.type.bodySmall, c.textMuted)
                else -> Txt("Plate: no saved plate linked", Nz.type.bodySmall, c.textMuted)
            }
            Txt("Target profile: ${item.targetProfileId ?: "not recorded"}", Nz.type.bodySmall, c.textMuted)
            Txt("Recorded file SHA-256 (not checked by Details): ${item.sha256 ?: "not recorded"}", Nz.type.bodySmall, c.textMuted)
            item.sourceJobId?.let { Txt("Reprint of job: $it", Nz.type.bodySmall, c.textMuted) }
        }
        if (reviewStart != null && candidate != null && flow != null) {
            val direct = onHandoff != null
            val reviewBody = buildList {
                add(item.name)
                plate?.let { add("${it.plateName} · ${it.nozzles} · ${it.bed}") }
                add("Printer: ${candidate.config.identity.displayName}")
                val tools = item.materialPlan?.tools.orEmpty().joinToString { tool ->
                    "T${tool.index + 1} ${tool.type ?: "material unknown"}"
                }
                if (tools.isNotEmpty()) add("Materials: $tools")
                else if (item.toolheadMap.isNotEmpty()) add("Toolhead assignments: ${item.toolheadMap.joinToString()}")
                if (item.sourceJobId != null && item.toolheadMap.isEmpty() && candidate.capabilities.value?.multiMaterial != false)
                    add("The reprint has no recorded toolhead mapping. Check the loaded materials.")
                if (item.engineBuild == null || item.inputDigest == null)
                    add("Check compatibility. Slicer input details are missing.")
                if (item.targetProfileId == null) add("The file has no recorded target printer profile.")
                if (unknownPrinterProfile) add("The printer profile could not be verified. Check that it matches the file.")
                materialChecks.forEach { add(it.message) }
            }.joinToString("\n")
            ConfirmDialog(title = if (direct) "Start printing?" else "Review ${item.name} for ${candidate.config.identity.displayName}",
                body = reviewBody,
                confirmLabel = if (direct) "Start printing" else "Continue to Start", destructive = false,
                reviewSize = if (direct) DpSize(560.dp, if (Nz.textScale >= 1.5f) 520.dp else 420.dp) else null,
                detail = if (direct) "Check the build plate is clear. Nozzle will recheck the printer before starting."
                    else "Continuing may transfer the file to the printer. A separate Start confirmation follows.",
                acknowledgement = if (item.engineBuild == null || item.inputDigest == null || item.targetProfileId == null || unknownPrinterProfile ||
                    materialChecks.any { !it.blocks } ||
                    (item.sourceJobId != null && item.toolheadMap.isEmpty() && candidate.capabilities.value?.multiMaterial != false))
                    "I checked that this file, printer profile, and loaded materials are compatible." else null,
                confirmEnabled = !materialConflict,
                onConfirm = {
                    val reviewedId = reviewStart
                    val reviewedConnection = ui.reviewedConnection
                    reviewStart = null
                    if (ui.reviewContext?.invoke() != true || reviewedId != candidate.config.identity.id || fleet.printers[reviewedId] !== candidate ||
                        reviewedConnection == null || !reviewedConnection.isCurrent(candidate) ||
                        fleet.queue.value.none { it == item } || !candidate.isFree() || !candidate.canTakeJobs(item) ||
                        candidate.guard?.needsReconcile == true ||
                        item.pendingLink || item.startUncertain || item.sha256 == null || profileMismatch || materialConflict) {
                        dismissReview()
                        problem = if (onHandoff == null) "The queued job or printer changed. Review it again."
                            else "The prepared print or printer changed. Review it again."
                        problem?.let { onReviewProblem?.invoke(it) }
                        return@ConfirmDialog
                    }
                    problem = null
                    val remote = queuedRemoteName(item)
                    if (reviewedConnection.capabilities.uploadAndStart) {
                        val file = runCatching { fleet.queuedFile(item) }.getOrElse {
                            problem = it.message ?: "Queued file cannot be verified."
                            clearReview()
                            return@ConfirmDialog
                        }
                        if (item.output == com.nozzleitall.desktop.jobs.BAMBU_BUNDLE) {
                            val block = com.nozzleitall.desktop.jobs.bambuLanProblem(file)
                            if (block != null) {
                                problem = block
                                clearReview()
                                return@ConfirmDialog
                            }
                        }
                        val action = PrinterAction.UploadAndStart(file.absolutePath, remote)
                        flow.request(action)
                        expectedStart = action.takeIf { flow.pending?.action == action }
                        if (direct && expectedStart != null) ui.approvedDirectStart = flow.pending
                        if (expectedStart == null) { problem = flow.message?.first ?: "Start could not be prepared. Review the printer before retrying."; clearReview() }
                    }
                    else scope.launch(Dispatchers.Main) {
                        ui.transferActive = true
                        uploading = 0f
                        val r = try { fleet.withQueuedTransfer(item.id, candidate.config.identity.id) {
                            check(ui.reviewContext?.invoke() == true && fleet.printers[candidate.config.identity.id] === candidate && reviewedConnection.isCurrent(candidate)) {
                                "The printer connection changed. Review the queued job again."
                            }
                            check(candidate.guard?.needsReconcile != true) { "The last printer command needs checking before another transfer." }
                            withContext(Dispatchers.IO) {
                                val verified = fleet.queuedFile(item)
                                val printerId = candidate.config.identity.id
                                fleet.recordDirectUpload(item, printerId, remote)
                                val result = reviewedConnection.session.upload(verified, remote) { s, t ->
                                    uploading = if (t > 0) s.toFloat() / t else null
                                }
                                if (result is UploadResult.Uploaded)
                                    fleet.recordDirectUpload(item, printerId, result.remotePath, uploaded = true)
                                result
                            }
                        } }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                UploadResult.Failed(if (e is PrinterTransferBusyException) e.message ?: "Printer transfer busy."
                                    else "Transfer failed: ${e.message ?: "unknown error"}. Check the printer before retrying.")
                            }
                            finally { uploading = null; ui.transferActive = false }
                        if (onHandoff != null && !stillCurrent()) {
                            dismissReview()
                            state.prepare.notice = "The print review closed during upload. A file may remain on ${candidate.config.identity.displayName}; no Start was sent."
                            return@launch
                        }
                        when (r) {
                            is UploadResult.Uploaded -> {
                                if (ui.reviewContext?.invoke() != true || fleet.printers[candidate.config.identity.id] !== candidate || !reviewedConnection.isCurrent(candidate) ||
                                    fleet.queue.value.none { it == item } || !candidate.isFree() || !candidate.canTakeJobs(item) ||
                                    candidate.guard?.needsReconcile == true ||
                                    (item.targetProfileId != null && candidate.config.identity.profileId != null &&
                                        item.targetProfileId != candidate.config.identity.profileId) ||
                                    runCatching { fleet.queuedFile(item) }.isFailure) {
                                    problem = "The queued job or printer changed during transfer. Review it again before Start."
                                    problem?.let { onReviewProblem?.invoke(it) }
                                    dismissReview()
                                    return@launch
                                }
                                val action = PrinterAction.StartJob(r.remotePath, item.toolheadMap)
                                flow.request(action)
                                expectedStart = action.takeIf { flow.pending?.action == action }
                                if (direct && expectedStart != null) ui.approvedDirectStart = flow.pending
                                if (expectedStart == null) { problem = flow.message?.first ?: "Start could not be prepared. Review the printer before retrying."; clearReview() }
                            }
                            is UploadResult.Interrupted -> { problem = "The transfer was interrupted: ${r.reason} The printer file may be incomplete; inspect it before retrying."; expectedStart = null; clearReview() }
                            is UploadResult.Failed -> { problem = r.reason; expectedStart = null; clearReview() }
                        }
                    }
                }, onDismiss = ::dismissReview, cancelLabel = if (direct) "Don't start" else "Cancel")
        }
        uploading?.let { ProgressBar(it, label = "Sending to printer") }
        problem?.let { Banner(it, BannerKind.WARNING) }
        if (candidate != null && flow != null) ActionFlowUi(flow, candidate, onDismiss = ::dismissReview,
            beforeExecute = { prepared ->
                check(prepared.action == expectedStart && (onHandoff == null || prepared === ui.approvedDirectStart)) {
                    "The queued Start changed; review it again."
                }
                check(ui.reviewContext?.invoke() == true && fleet.printers[candidate.config.identity.id] === candidate && ui.reviewedConnection?.isCurrent(candidate) == true &&
                    fleet.queue.value.any { it == item } && candidate.isFree() && candidate.canTakeJobs(item) &&
                    candidate.guard?.needsReconcile != true &&
                    (item.targetProfileId == null || candidate.config.identity.profileId == null ||
                        item.targetProfileId == candidate.config.identity.profileId)) {
                    when {
                        onHandoff == null -> "The queued job or printer changed; review it again."
                        candidate.status.value.state == PrinterState.OFFLINE ->
                            "${candidate.config.identity.displayName} is offline. Reconnect and review the print again."
                        else -> "The prepared print or printer changed. Review it again."
                    }
                }
                fleet.queuedFile(item)
                val remotePath = when (val action = prepared.action) {
                    is PrinterAction.StartJob -> action.remotePath
                    is PrinterAction.UploadAndStart -> action.remoteName
                    else -> error("The confirmed action was not a queued Start.")
                }
                ui.dispatchedHold = fleet.markQueuedStartUncertain(item, candidate.config.identity.id, remotePath)
            }, onResult = { prepared, outcome ->
                val dispatchedHold = ui.dispatchedHold
                ui.dispatchedHold = null
                if (prepared.action == expectedStart) {
                    val printerId = candidate.config.identity.id
                    when (outcome) {
                        ActionOutcome.Accepted -> runCatching {
                            val startedPath = when (val action = prepared.action) {
                                is PrinterAction.StartJob -> action.remotePath
                                is PrinterAction.UploadAndStart -> action.remoteName
                                else -> error("The confirmed action was not a queued Start.")
                            }
                            fleet.noteQueuedStarted(requireNotNull(dispatchedHold), printerId, startedPath)
                        }
                            .onSuccess { dispatchedHold?.startRemotePath?.let { onHandoff?.invoke(it, MonitorHandoffState.STARTED) } }
                            .onFailure {
                                dispatchedHold?.startRemotePath?.let { onHandoff?.invoke(it, MonitorHandoffState.STARTED_RECORD_PENDING) }
                                problem = "Print started; its local record needs recovery: ${it.message}" }
                        is ActionOutcome.Rejected -> {
                            if (dispatchedHold != null)
                                runCatching { fleet.clearQueuedStartUncertain(dispatchedHold, printerId) }
                                    .onFailure { problem = "Start was not sent; the local hold needs review: ${it.message}" }
                            clearReview()
                        }
                        is ActionOutcome.Unknown -> {
                            problem = "Start outcome is unknown. Inspect the printer before resolving this queued job."
                            dispatchedHold?.startRemotePath?.let { onHandoff?.invoke(it, MonitorHandoffState.START_UNCERTAIN) }
                        }
                    }
                }
                expectedStart = null
                clearReview()
            }, executionGate = { execute ->
                if (expectedStart is PrinterAction.UploadAndStart)
                    fleet.withQueuedTransfer(item.id, candidate.config.identity.id) { execute() }
                else execute()
            }, executionScope = state.scope, reviewedStart = ui.approvedDirectStart)
        val reviewedHold = resolutionHold
        if (resolveStart == "choose" && reviewedHold != null) {
            val printerId = reviewedHold.startPrinterId
            val pathToRecord = reviewedHold.startRemotePath ?: legacyRemotePath.trim()
            val pathValid = pathToRecord.isNotBlank() && pathToRecord.length <= 512 && pathToRecord.none(Char::isISOControl)
            ConfirmDialog(title = "Inspect ${printerId ?: "the printer"} before resolving Start",
                body = "A previous Start may have reached the printer. Check its current and recent jobs for ${reviewedHold.startRemotePath ?: "the prepared file"} before choosing whether this queued print started.",
                confirmLabel = "It started", destructive = false,
                detail = if (reviewedHold.startRemotePath == null)
                    "This older Start hold has no recorded remote path. Enter the file path shown by the printer before binding this job."
                else "This records the accepted attempt with its saved remote path and removes its queue row; it sends no printer command.",
                acknowledgement = "I checked this printer's current and recent jobs and confirmed this print started.",
                extraContent = if (reviewedHold.startRemotePath == null) {
                    { Field("Printer-reported file path", legacyRemotePath, { legacyRemotePath = it },
                        hint = "Use the exact path shown by the printer.",
                        error = if (legacyRemotePath.isNotBlank() && !pathValid) "Enter a valid path of at most 512 characters." else null) }
                } else null,
                confirmEnabled = pathValid,
                onConfirm = {
                    resolveStart = null
                    resolutionHold = null
                    runCatching { fleet.noteQueuedStarted(reviewedHold, requireNotNull(printerId),
                        pathToRecord.also { check(pathValid) { "Enter the printer-reported file path." } },
                        legacyPathObserved = reviewedHold.startRemotePath == null) }
                        .onFailure { problem = "The started attempt could not be recorded: ${it.message}" }
                }, onDismiss = { resolveStart = null; resolutionHold = null }, cancelLabel = "Cancel")
        } else if (resolveStart == "not-started" && reviewedHold != null) {
            ConfirmDialog(title = "Confirm no print started on ${reviewedHold.startPrinterId ?: "the printer"}",
                body = "Clear the local Start hold only after checking that this print did not start. The queued file will become available to Start again.",
                confirmLabel = "No print started", destructive = false,
                detail = "This only updates the local queue; it sends no printer command.",
                onConfirm = {
                    resolveStart = null
                    resolutionHold = null
                    runCatching { fleet.clearQueuedStartUncertain(reviewedHold, requireNotNull(reviewedHold.startPrinterId)) }
                        .onFailure { problem = "The Start hold could not be cleared: ${it.message}" }
                }, onDismiss = { resolveStart = null; resolutionHold = null }, cancelLabel = "Cancel")
        }
    }
}
