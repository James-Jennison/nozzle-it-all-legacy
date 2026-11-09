package com.nozzleitall.desktop.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.desktop.PrinterTransferBusyException
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
    val problem = mutableStateOf<String?>(null)
    val expectedStart = mutableStateOf<PrinterAction?>(null)
    val resolveStart = mutableStateOf<String?>(null)
    val resolutionHold = mutableStateOf<QueueItem?>(null)
    var dispatchedHold: QueueItem? = null
    val reviewStart = mutableStateOf<String?>(null)
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
fun QueuePanel(state: AppState) {
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
        items.forEachIndexed { i, item -> key(item.id) { QueueRow(state, item, first = i == 0, last = i == items.lastIndex) } }
    }
}

@Composable
private fun QueueRow(state: AppState, item: QueueItem, first: Boolean, last: Boolean) {
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
            ?: available.firstOrNull { it.config.identity.profileId == null } ?: available.firstOrNull()
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
        ui.reviewedConnection = null
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
                Txt("For " + (target?.config?.identity?.displayName ?: "any printer") + when {
                    candidate == null -> if (item.printerId != null) " (printer not found)" else " · no free printer yet"
                    !candidate.isFree() -> " · busy"
                    else -> " · printer available"
                }, Nz.type.bodySmall, c.textMuted)
                NzButton(if (detailsOpen) "Hide details" else "Details", { detailsOpen = !detailsOpen },
                    modifier = Modifier.semantics { stateDescription = if (detailsOpen) "Expanded" else "Collapsed" },
                    kind = ButtonKind.QUIET, testTag = "queue-details-${item.id}")
            }
    }
    val actions: @Composable () -> Unit = {
            // Cycle the target through: any, then each printer.
            NzButton("Change printer", {
                val options = listOf<String?>(null) + ids
                val next = options[(options.indexOf(item.printerId) + 1) % options.size]
                fleet.retargetQueued(item.id, next)
                pinnedPrinterId = null
            }, kind = ButtonKind.QUIET, enabled = !item.startUncertain && uploading == null && expectedStart == null && flow?.pending == null && flow?.busy != true)
            NzButton("↑", { fleet.moveInQueue(item.id, -1) }, kind = ButtonKind.QUIET, enabled = !first && uploading == null && expectedStart == null && flow?.pending == null && flow?.busy != true)
            NzButton("↓", { fleet.moveInQueue(item.id, 1) }, kind = ButtonKind.QUIET, enabled = !last && uploading == null && expectedStart == null && flow?.pending == null && flow?.busy != true)
            NzButton("Remove", { fleet.dequeue(item.id) }, kind = ButtonKind.QUIET, enabled = !item.pendingLink && !item.startUncertain && uploading == null && expectedStart == null && flow?.pending == null && flow?.busy != true)
            if (item.pendingLink) Txt("Local queue records are being recovered; Start is unavailable.", Nz.type.bodySmall, c.textMuted)
            if (item.startUncertain) {
                NzButton("Confirm it started", { resolutionHold = item; resolveStart = "choose" }, kind = ButtonKind.QUIET)
                NzButton("Confirm it did not start", { resolutionHold = item; resolveStart = "not-started" }, kind = ButtonKind.QUIET)
            }
            if (candidate?.guard?.needsReconcile == true) {
                Txt("The last printer command has an unknown outcome. Check the printer before starting another job.", Nz.type.bodySmall, c.danger)
                if (flow != null) NzButton("Check printer", { state.scope.launch(Dispatchers.Main) { flow.reconcile() } }, kind = ButtonKind.SECONDARY)
            }
            if (item.sha256 == null) Txt("This older queued file has no integrity digest. Requeue it before starting.", Nz.type.bodySmall, c.textMuted)
            if (profileMismatch) Txt("This job was sliced for ${item.targetProfileId}, not this printer's ${candidate?.config?.identity?.profileId}. Choose a matching printer or slice again.", Nz.type.bodySmall, c.danger)
            materialChecks.filter { it.blocks }.forEach { Banner(it.message, BannerKind.DANGER) }
            if (candidate != null && flow != null) NzButton(
                if (uploading != null) "Sending…" else "Review and Start",
                {
                    val session = candidate.session
                    val guard = candidate.guard
                    val capabilities = candidate.capabilities.value
                    if (session == null || guard == null || capabilities == null || candidate.retired) {
                        problem = "The printer is not connected. Review again when it reconnects."
                        return@NzButton
                    }
                    ui.reviewedConnection = ReviewedQueueConnection(candidate, session, guard, capabilities,
                        candidate.status.value.toolheads.map { it.preflightObservation() })
                    pinnedPrinterId = candidate.config.identity.id
                    reviewStart = candidate.config.identity.id
                },
                kind = ButtonKind.PRIMARY, icon = NzIcon.PLAY, enabled = queuedSourcePresent && !profileMismatch && !materialConflict && !item.pendingLink && !item.startUncertain && item.sha256 != null &&
                    candidate.isFree() && candidate.canTakeJobs(item) && candidate.session != null &&
                    candidate.guard?.needsReconcile == false && uploading == null && expectedStart == null && flow.pending == null && !flow.busy,
                testTag = "queue-review-${item.id}")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        summary()
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
            val reviewBody = buildString {
                append("Prepared file: ${item.name}. Printer: ${candidate.config.identity.displayName}. ")
                append("Prepared for profile: ${item.targetProfileId ?: "not recorded"}. Printer reports: ${candidate.config.identity.profileId ?: "unknown"}. ")
                append("Toolhead assignments: ${item.toolheadMap.takeIf { it.isNotEmpty() }?.joinToString() ?: "not recorded"}. ")
                if (item.sourceJobId != null && item.toolheadMap.isEmpty() && candidate.capabilities.value?.multiMaterial != false)
                    append("This reprint has no retained toolhead mapping; verify each material and toolhead on the printer before Start. ")
                if (item.engineBuild == null || item.inputDigest == null)
                    append("The file's slicer inputs or engine are not recorded, so printer compatibility and material use cannot be verified here. ")
                if (item.targetProfileId == null) append("The prepared file has no recorded target printer profile. ")
                if (unknownPrinterProfile) append("The printer did not report a profile; confirm that it matches the prepared file. ")
                materialChecks.forEach { append(it.message).append(' ') }
                append("Check the plate and loaded materials before Start.")
            }
            ConfirmDialog(title = "Review ${item.name} for ${candidate.config.identity.displayName}",
                body = reviewBody,
                confirmLabel = "Continue to Start", destructive = false,
                detail = "Continuing may transfer the file to the printer. A separate Start confirmation follows.",
                acknowledgement = if (item.engineBuild == null || item.inputDigest == null || item.targetProfileId == null || unknownPrinterProfile ||
                    materialChecks.any { !it.blocks } ||
                    (item.sourceJobId != null && item.toolheadMap.isEmpty() && candidate.capabilities.value?.multiMaterial != false))
                    "I checked that this file, printer profile, and loaded materials are compatible." else null,
                confirmEnabled = !materialConflict,
                onConfirm = {
                    val reviewedId = reviewStart
                    val reviewedConnection = ui.reviewedConnection
                    reviewStart = null
                    if (reviewedId != candidate.config.identity.id || fleet.printers[reviewedId] !== candidate ||
                        reviewedConnection == null || !reviewedConnection.isCurrent(candidate) ||
                        fleet.queue.value.none { it == item } || !candidate.isFree() || !candidate.canTakeJobs(item) ||
                        candidate.guard?.needsReconcile == true ||
                        item.pendingLink || item.startUncertain || item.sha256 == null || profileMismatch || materialConflict) {
                        problem = "The queued job or printer changed. Review it again."
                        clearReview()
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
                        if (expectedStart == null) { problem = flow.message?.first ?: "Start could not be prepared. Review the printer before retrying."; clearReview() }
                    }
                    else scope.launch(Dispatchers.Main) {
                        uploading = 0f
                        val r = try { fleet.withQueuedTransfer(item.id, candidate.config.identity.id) {
                            check(fleet.printers[candidate.config.identity.id] === candidate && reviewedConnection.isCurrent(candidate)) {
                                "The printer connection changed. Review the queued job again."
                            }
                            check(candidate.guard?.needsReconcile != true) { "The last printer command needs checking before another transfer." }
                            withContext(Dispatchers.IO) {
                                val verified = fleet.queuedFile(item)
                                reviewedConnection.session.upload(verified, remote) { s, t -> uploading = if (t > 0) s.toFloat() / t else null }
                            }
                        } }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                problem = if (e is PrinterTransferBusyException) e.message else
                                    "Transfer failed: ${e.message ?: "unknown error"}. Check the printer before retrying."
                                clearReview()
                                return@launch
                            }
                            finally { uploading = null }
                        when (r) {
                            is UploadResult.Uploaded -> {
                                if (fleet.printers[candidate.config.identity.id] !== candidate || !reviewedConnection.isCurrent(candidate) ||
                                    fleet.queue.value.none { it == item } || !candidate.isFree() || !candidate.canTakeJobs(item) ||
                                    candidate.guard?.needsReconcile == true ||
                                    (item.targetProfileId != null && candidate.config.identity.profileId != null &&
                                        item.targetProfileId != candidate.config.identity.profileId) ||
                                    runCatching { fleet.queuedFile(item) }.isFailure) {
                                    problem = "The queued job or printer changed during transfer. Review it again before Start."
                                    expectedStart = null
                                    clearReview()
                                    return@launch
                                }
                                val action = PrinterAction.StartJob(r.remotePath, item.toolheadMap)
                                flow.request(action)
                                expectedStart = action.takeIf { flow.pending?.action == action }
                                if (expectedStart == null) { problem = flow.message?.first ?: "Start could not be prepared. Review the printer before retrying."; clearReview() }
                            }
                            is UploadResult.Interrupted -> { problem = "The transfer was interrupted: ${r.reason} The printer file may be incomplete; inspect it before retrying."; expectedStart = null; clearReview() }
                            is UploadResult.Failed -> { problem = r.reason; expectedStart = null; clearReview() }
                        }
                    }
                }, onDismiss = { reviewStart = null; clearReview() }, cancelLabel = "Cancel")
        }
        uploading?.let { ProgressBar(it, label = "Sending to printer") }
        problem?.let { Banner(it, BannerKind.WARNING) }
        if (candidate != null && flow != null) ActionFlowUi(flow, candidate, onDismiss = { expectedStart = null; clearReview() },
            beforeExecute = { prepared ->
                check(prepared.action == expectedStart) { "The queued Start changed; review it again." }
                check(fleet.printers[candidate.config.identity.id] === candidate && ui.reviewedConnection?.isCurrent(candidate) == true &&
                    fleet.queue.value.any { it == item } && candidate.isFree() && candidate.canTakeJobs(item) &&
                    candidate.guard?.needsReconcile != true &&
                    (item.targetProfileId == null || candidate.config.identity.profileId == null ||
                        item.targetProfileId == candidate.config.identity.profileId)) {
                    "The queued job or printer changed; review it again."
                }
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
                            .onFailure { problem = "Print started; its local record needs recovery: ${it.message}" }
                        is ActionOutcome.Rejected -> {
                            if (dispatchedHold != null)
                                runCatching { fleet.clearQueuedStartUncertain(dispatchedHold, printerId) }
                                    .onFailure { problem = "Start was not sent; the local hold needs review: ${it.message}" }
                            clearReview()
                        }
                        is ActionOutcome.Unknown -> problem = "Start outcome is unknown. Inspect the printer before resolving this queued job."
                    }
                }
                expectedStart = null
                clearReview()
            }, executionGate = { execute ->
                if (expectedStart is PrinterAction.UploadAndStart)
                    fleet.withQueuedTransfer(item.id, candidate.config.identity.id) { execute() }
                else execute()
            }, executionScope = state.scope)
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
