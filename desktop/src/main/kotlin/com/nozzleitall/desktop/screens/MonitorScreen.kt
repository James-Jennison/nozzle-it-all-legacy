@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.nozzleitall.desktop.screens

import com.nozzleitall.desktop.history.displayJobPath
import com.nozzleitall.desktop.history.reportedJobPath
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.MonitorHandoff
import com.nozzleitall.desktop.prepare.SliceState
import com.nozzleitall.desktop.settings.Choice
import com.nozzleitall.desktop.settings.DenseSelect
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Picks the printer a screen is about; shared by Monitor and Materials. */
@Composable
fun PrinterPicker(state: AppState): PrinterEntry? {
    val ids = state.fleet.order.value
    val current = state.selectedPrinter?.takeIf { it in ids } ?: ids.firstOrNull()
    if (ids.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = "Choose printer" }) {
        ids.forEach { id -> state.fleet.printers[id]?.let { e ->
            NzButton(e.config.identity.displayName, { state.selectPrinter(id) }, kind = if (id == current) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
        } }
    }
    return current?.let { state.fleet.printers[it] }
}

/**
 * The top of one printer's page: back to the printers, the printer to look at, and its two tabs (the live Monitor and the
 * Materials & toolheads it has loaded). Returns the printer, or null when there are none.
 */
@Composable
fun PrinterHeader(state: AppState, on: com.nozzleitall.desktop.Destination, what: String): PrinterEntry? {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NzButton("Printers", { state.destination = com.nozzleitall.desktop.Destination.FLEET }, kind = ButtonKind.QUIET, icon = NzIcon.FLEET)
        Spacer(Modifier.weight(1f))
        listOf(com.nozzleitall.desktop.Destination.MONITOR to "Monitor", com.nozzleitall.desktop.Destination.PRINTER_MATERIALS to "Materials & toolheads", com.nozzleitall.desktop.Destination.HISTORY to "History").forEach { (d, label) ->
            NzButton(label, { state.destination = d }, kind = if (d == on) ButtonKind.PRIMARY else ButtonKind.SECONDARY, testTag = "printer-tab-${d.name.lowercase()}")
        }
    }
    val entry = PrinterPicker(state)
    // The printer's name is on its own card below, so the heading here is only what the page is for.
    Txt(what, Nz.type.bodySmall, Nz.colors.textMuted)
    return entry
}

@Composable
fun NoPrinterYet(state: AppState, what: String) = Card(Modifier.fillMaxWidth()) {
    EmptyState(NzIcon.FLEET, "No printers yet", "Add a printer to see $what.") {
        NzButton("Go to Printers", { state.destination = com.nozzleitall.desktop.Destination.FLEET }, kind = ButtonKind.PRIMARY)
    }
}

@Composable
fun MonitorScreen(state: AppState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val ids = state.fleet.order.value
        val current = state.selectedPrinter?.takeIf { it in ids } ?: ids.firstOrNull()
        val entry = current?.let { state.fleet.printers[it] }
        val hasPreview = state.prepare.slice is SliceState.Done
        FlowRow(Modifier.fillMaxWidth().semantics { testTag = "monitor-toolbar" },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (entry != null) {
                val choices = ids.mapNotNull { id -> state.fleet.printers[id]?.let { printer ->
                    Choice(id, printer.config.identity.displayName)
                } }
                DenseSelect("Monitor printer", choices, entry.config.identity.id, Modifier.width(174.dp),
                    enabled = choices.size > 1, onSelect = state::openPrinter)
            }
            listOf(Destination.MONITOR to "Monitor", Destination.PRINTER_MATERIALS to "Materials & toolheads",
                Destination.HISTORY to "History").forEach { (destination, label) ->
                NzButton(label, { state.destination = destination },
                    kind = if (destination == Destination.MONITOR) ButtonKind.PRIMARY else ButtonKind.QUIET,
                    testTag = "printer-tab-${destination.name.lowercase()}")
            }
            NzButton("Queue & history", { state.destination = Destination.JOBS },
                kind = ButtonKind.SECONDARY, testTag = "monitor-queue-history")
            NzButton(if (hasPreview) "Back to Preview" else "Back to Prepare", { state.returnToWorkspace(preview = hasPreview) },
                kind = ButtonKind.QUIET, icon = NzIcon.PREPARE, testTag = if (hasPreview) "monitor-back-preview" else "monitor-back-project")
        }
        if (entry == null) { NoPrinterYet(state, "live status, cameras and controls"); return@Column }
        val status = entry.status.value
        val handoff = state.monitorHandoff?.takeIf { it.printerId == entry.config.identity.id }
        val handoffSuperseded = if (handoff?.state in recoveryHandoffs)
            state.fleet.queue.value.none {
                it.startUncertain && it.startPrinterId == handoff?.printerId &&
                    (handoff?.remotePath == null || reportedJobPath(it.startRemotePath) == reportedJobPath(handoff.remotePath))
            }
        else monitorHandoffSuperseded(status)
        val showHandoff = handoff != null && !handoffSuperseded
        LaunchedEffect(handoff, handoffSuperseded) {
            if (handoff != null) state.reconcileMonitorHandoff(handoff, handoffSuperseded)
        }
        val caps = entry.capabilities.value
        val flow = rememberActionFlow(entry)
        ActionFlowUi(flow, entry)
        entry.problem.value?.let { Banner(it, BannerKind.WARNING) }
        val cameraAvailable = entry.cameras.value.isNotEmpty()
        val jobContext = state.monitorJobContext?.takeIf { it.printerId == entry.config.identity.id }
        val reportedActivePath = reportedJobPath(status.job?.fileName)
        val completionPath = reportedJobPath(status.completedFileName)
        var observedContextJob by remember(jobContext) { mutableStateOf(false) }
        LaunchedEffect(jobContext, status.state, reportedActivePath, completionPath) {
            val contextPath = reportedJobPath(jobContext?.remotePath)
            if (jobContext == null || contextPath == null) return@LaunchedEffect
            if (status.state.isActiveJob && reportedActivePath != null) {
                if (reportedActivePath == contextPath) observedContextJob = true
                else state.clearMonitorJobContext(jobContext)
            } else if (status.state in setOf(PrinterState.FINISHED, PrinterState.CANCELLED, PrinterState.ERROR) &&
                (observedContextJob || completionPath == contextPath)) {
                state.clearMonitorJobContext(jobContext)
            }
        }
        if (cameraAvailable) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 900.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.weight(1.5f)) { CameraPanel(entry) }
                        Column(Modifier.weight(1f)) { JobHero(entry, status, caps, flow, jobContext) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        CameraPanel(entry)
                        JobHero(entry, status, caps, flow, jobContext)
                    }
                }
            }
        } else {
            JobHero(entry, status, caps, flow, jobContext)
            Txt(if (caps?.camera == false) "No supported camera for this printer." else "No camera found on this printer.",
                Nz.type.bodySmall, Nz.colors.textMuted)
        }
        handoff?.takeIf { showHandoff }?.let {
            Card(Modifier.fillMaxWidth(), raised = true) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(NzIcon.SEND, Nz.colors.accent, 20.dp)
                    Column(Modifier.weight(1f)) {
                        Txt(it.projectName, Nz.type.title)
                        Txt("${it.state.label} · ${it.fileName}", Nz.type.bodySmall, Nz.colors.textMuted)
                        it.plate?.let { plate ->
                            Txt("${plate.plateName} · ${plate.machine} · ${plate.nozzles} · ${plate.bed}", Nz.type.bodySmall, Nz.colors.textMuted)
                        }
                    }
                    if (it.state in recoveryHandoffs)
                        NzButton("Open queue", { state.destination = com.nozzleitall.desktop.Destination.JOBS }, kind = ButtonKind.PRIMARY)
                }
            }
        }
        if (caps != null) ControlsPanel(entry, status, caps, flow)
        TemperaturePanel(status)
    }
}

private val recoveryHandoffs = setOf(
    com.nozzleitall.desktop.MonitorHandoffState.START_UNCERTAIN,
    com.nozzleitall.desktop.MonitorHandoffState.STARTED_RECORD_PENDING,
)

/** A printer-reported job or terminal outcome supersedes the optimistic queue/upload handoff card. */
internal fun monitorHandoffSuperseded(status: PrinterStatus): Boolean =
    status.job != null || status.state.isActiveJob ||
        status.state in setOf(PrinterState.FINISHED, PrinterState.CANCELLED, PrinterState.ERROR)

/**
 * The top of the Monitor: which printer, its state, and the job in one place. A running job shows its progress, the time left and when it
 * should end; Pause, Resume and Cancel sit beside it, and only the ones the printer can do right now appear.
 */
@Composable
fun JobHero(entry: PrinterEntry, status: PrinterStatus, caps: Capabilities?, flow: ActionFlow, context: MonitorHandoff? = null) {
    val c = Nz.colors
    val blocked = entry.guard?.needsReconcile == true || flow.busy
    val reconnecting = status.state in com.nozzleitall.desktop.history.MonitorPrintProgress.reconnectingStates
    val reportedCompletion = reportedJobPath(status.completedFileName)
    val trackedJob = entry.printProgress.active.value
    val unrelatedCompletion = status.state == PrinterState.FINISHED && reportedCompletion != null &&
        trackedJob != null && reportedCompletion != reportedJobPath(trackedJob.fileName)
    val confirmed = entry.printProgress.finished.value?.takeIf {
        status.state == PrinterState.FINISHED && reportedJobPath(it) == reportedCompletion
    }
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(entry.config.identity.displayName, Nz.type.title, modifier = Modifier.weight(1f))
            RouteBadge(status.route)
            if (reconnecting && entry.printProgress.active.value != null) Txt("Reconnecting", Nz.type.label, c.textMuted)
            else if (status.state == PrinterState.FINISHED && (reportedCompletion == null || unrelatedCompletion))
                Txt("Completion unverified", Nz.type.label, c.textMuted)
            else StatusPill(status.state)
        }
        val job = when {
            reconnecting -> entry.printProgress.active.value
            status.state.isActiveJob -> status.job
            else -> null
        }
        if (job == null) Txt(when {
            confirmed != null -> "${displayJobPath(confirmed)} finished."
            unrelatedCompletion && reportedCompletion != null && trackedJob != null ->
                "The printer reported ${displayJobPath(reportedCompletion)} finished. Completion of ${displayJobPath(trackedJob.fileName)} is still unverified."
            status.state == PrinterState.FINISHED && reportedCompletion != null ->
                "Printer reports ${displayJobPath(reportedCompletion)} finished."
            status.state == PrinterState.FINISHED -> "The printer reported completion without a job name."
            else -> status.message ?: "Nothing is printing."
        }, Nz.type.body, c.textMuted)
        else {
            val reported = job.remainingSeconds?.takeIf { it.isFinite() && it >= 0 }
            val left = if (reconnecting) null else reported ?: JobEta.remainingSeconds(job.fraction, job.elapsedSeconds)
            val matching = context?.takeIf { reportedJobPath(it.remotePath) == reportedJobPath(job.fileName) }
            if (matching == null) Txt(displayJobPath(job.fileName), Nz.type.headline)
            else {
                Txt(listOfNotNull(matching.projectName, matching.plate?.plateName).joinToString(" · "), Nz.type.headline)
                var showFile by remember(job.fileName) { mutableStateOf(false) }
                NzButton(if (showFile) "Hide job details" else "Job details", { showFile = !showFile }, kind = ButtonKind.QUIET)
                if (showFile) Txt("File: ${displayJobPath(job.fileName)}", Nz.type.bodySmall, c.textMuted)
            }
            ProgressBar(job.fraction, Nz.status.forState(status.state.glossaryId), label = "Print progress ${(job.fraction * 100).toInt()} percent")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(if (reconnecting) "Last reported" else "Done", "${(job.fraction * 100).toInt()}%")
                left?.let {
                    Metric(if (reported != null) "Printer ETA" else "Estimated time left", formatDuration(it))
                    Metric("Finishes about", JobEta.finishesAt(System.currentTimeMillis(), it))
                }
                Metric("Elapsed", formatDuration(job.elapsedSeconds))
                job.totalLayers?.let { Metric("Layer", "${job.currentLayer ?: 0} of $it") }
            }
            status.message?.let { Txt(it, Nz.type.bodySmall, c.textMuted) }
        }
        if (status.toolheads.isNotEmpty()) ToolheadStrip(status.toolheads)
        if (caps != null) {
            fun offer(a: PrinterAction) = !blocked && status.state in a.allowedStates
            val show = listOfNotNull(
                PrinterAction.Pause.takeIf { caps.pausePrint && offer(it) }, PrinterAction.Resume.takeIf { caps.resumePrint && offer(it) }, PrinterAction.Cancel.takeIf { caps.cancelPrint && offer(it) })
            if (entry.guard?.needsReconcile == true) Txt("Commands are paused until Nozzle has checked the printer.", Nz.type.bodySmall, Nz.status.paused)
            if (show.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                show.forEach { a -> when (a) {
                    PrinterAction.Pause -> NzButton("Pause", { flow.request(a) }, icon = NzIcon.PAUSE, testTag = "job-pause")
                    PrinterAction.Resume -> NzButton("Resume", { flow.request(a) }, icon = NzIcon.PLAY, kind = ButtonKind.PRIMARY, testTag = "job-resume")
                    else -> NzButton("Cancel print", { flow.request(a) }, kind = ButtonKind.DANGER, icon = NzIcon.STOP, testTag = "job-cancel")
                } }
            }
        }
    }
}

fun ago(millis: Long): String {
    val s = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return if (s < 5) "just now" else if (s < 90) "$s s ago" else "${s / 60} min ago"
}

@Composable
fun CameraPanel(entry: PrinterEntry) {
    val c = Nz.colors
    val cam = com.nozzleitall.desktop.camera.rememberLiveCamera(entry)
    val camera = cam.camera; val image = cam.image; val problem = cam.problem; val live = cam.live
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(NzIcon.CAMERA, c.textMuted, 18.dp); Spacer(Modifier.width(8.dp))
            Txt(camera?.name?.replaceFirstChar { it.uppercase() } ?: "Camera", Nz.type.title, modifier = Modifier.weight(1f))
            if (live) Pill("Live", c.accent)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(c.surfaceSunken), contentAlignment = Alignment.Center) {
            when {
                camera == null -> Txt(if (entry.capabilities.value?.camera == false) "Nozzle It All can't show this printer's camera." else "No camera found on this printer.", Nz.type.body, c.textMuted)
                image != null -> {
                    val label = when {
                        live -> "Live camera view"
                        problem != null -> "Last available camera image"
                        else -> "Camera image"
                    }
                    androidx.compose.foundation.Image(image, "$label of ${entry.config.identity.displayName}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                else -> Txt(problem ?: "Connecting to the camera…", Nz.type.body, c.textMuted)
            }
        }
        when {
            image != null && problem != null -> Txt("$problem Reconnecting…", Nz.type.bodySmall, c.textMuted)
            camera?.liveUrl == null && camera?.videoUrl == null && image != null ->
                Txt("This camera refreshes still images periodically.", Nz.type.bodySmall, c.textMuted)
        }
    }
}

@Composable
fun TemperaturePanel(status: PrinterStatus) {
    Card(Modifier.fillMaxWidth()) {
        Txt("Temperatures", Nz.type.title)
        status.bed?.let { Row { Txt("Bed", Nz.type.body, modifier = Modifier.weight(1f)); Txt("${temp(it.current)} → ${temp(it.target)}", Nz.type.metricSmall) } }
        status.toolheads.forEach { t ->
            Row { Txt("Toolhead ${t.index + 1}" + if (t.active) " (active)" else "", Nz.type.body, modifier = Modifier.weight(1f)); Txt("${temp(t.nozzleTemperature)} → ${temp(t.nozzleTarget)}", Nz.type.metricSmall) }
        }
        if (status.bed == null && status.toolheads.isEmpty()) Txt("No temperature readings yet.", Nz.type.body, Nz.colors.textMuted)
    }
}

@Composable
fun ControlsPanel(entry: PrinterEntry, status: PrinterStatus, caps: Capabilities, flow: ActionFlow) {
    val c = Nz.colors
    val blocked = entry.guard?.needsReconcile == true || flow.busy
    Card(Modifier.fillMaxWidth()) {
        Txt("Controls", Nz.type.title)
        fun offer(a: PrinterAction) = !blocked && status.state in a.allowedStates
        // Each control appears only if this printer's adapter reports it; nothing here depends on the printer's make.
        if (!caps.anyControl) Txt("${entry.config.identity.displayName} can be monitored from Nozzle It All but not controlled.", Nz.type.body, c.textMuted)
        if (caps.temperatures) {
            var nozzle by remember { mutableStateOf("") }
            var bed by remember { mutableStateOf("") }
            val active = status.toolheads.firstOrNull { it.active }?.index ?: 0
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.Bottom) {
                Field("Toolhead ${active + 1} °C", nozzle, { nozzle = it.filter(Char::isDigit).take(3) }, Modifier.width(120.dp), placeholder = "210")
                NzButton("Heat", { nozzle.toIntOrNull()?.let { flow.request(PrinterAction.SetNozzleTemperature(active, it)) } }, icon = NzIcon.HEAT, enabled = !blocked && nozzle.isNotBlank())
                Field("Bed °C", bed, { bed = it.filter(Char::isDigit).take(3) }, Modifier.width(100.dp), placeholder = "60")
                NzButton("Heat", { bed.toIntOrNull()?.let { flow.request(PrinterAction.SetBedTemperature(it)) } }, icon = NzIcon.HEAT, enabled = !blocked && bed.isNotBlank())
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NzButton("Toolhead ${active + 1} heater off", { flow.request(PrinterAction.SetNozzleTemperature(active, 0)) }, kind = ButtonKind.QUIET, enabled = !blocked)
                NzButton("Bed heater off", { flow.request(PrinterAction.SetBedTemperature(0)) }, kind = ButtonKind.QUIET, enabled = !blocked)
            }
        }
        // Wraps onto more lines in a narrow window rather than squeezing buttons.
        if (caps.motion) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NzButton("Home", { flow.request(PrinterAction.HomeAll) }, icon = NzIcon.HOME, enabled = offer(PrinterAction.HomeAll))
            listOf('X' to 10.0, 'X' to -10.0, 'Y' to 10.0, 'Y' to -10.0, 'Z' to 1.0, 'Z' to -1.0).forEach { (axis, mm) ->
                val a = PrinterAction.Jog(axis, mm)
                NzButton("$axis${if (mm > 0) "+" else "−"}${kotlin.math.abs(mm).let { if (it == it.toInt().toDouble()) it.toInt() else it }}", { flow.request(a) }, kind = ButtonKind.QUIET, enabled = offer(a))
            }
        }
        Txt("Physical results of commands are the printer's to confirm; Nozzle shows only what the printer reports.", Nz.type.bodySmall, c.textMuted)
    }
}
