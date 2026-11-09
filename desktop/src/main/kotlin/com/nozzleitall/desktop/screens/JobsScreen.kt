package com.nozzleitall.desktop.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.prepare.chooseFiles
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.PrinterState
import com.nozzleitall.printer.label
import com.nozzleitall.project.MeshIO

/** Local job landing page. Queue records are data only until a person reviews and confirms Start. */
@Composable
fun JobsScreen(state: AppState) {
    val c = Nz.colors
    val fleet = state.fleet
    var creating by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun preservingCurrentProject(action: () -> Unit) {
        if (state.prepare.dirty) state.pendingDiscard = action else action()
    }
    val active = fleet.order.value.mapNotNull { id -> fleet.printers[id]?.let { id to it } }
        .filter { (_, entry) -> entry.status.value.job != null && entry.status.value.state in setOf(PrinterState.PRINTING, PrinterState.PAUSED) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeader("Jobs", "Active prints, prepared jobs and recent machine results.") {
            NzButton("New job", { creating = !creating }, kind = ButtonKind.PRIMARY, icon = NzIcon.ADD, testTag = "new-job")
        }
        state.historySavePending.keys.forEach { pending ->
            Banner("Saving assessment for ${historyNoticeLabel(pending)}.", BannerKind.INFO)
        }
        state.historySaveNotices.forEach { (record, notice) ->
            Banner(notice.message, if (notice.isError) BannerKind.WARNING else BannerKind.INFO,
                "Dismiss" to { dismissHistorySaveNotice(state, record) })
        }
        state.historyDrafts.keys.filter { state.fleet.history.value.none { listed -> listed == it } }.forEach { record ->
            Banner("An unsaved assessment for ${historyNoticeLabel(record)} belongs to an earlier print result.",
                BannerKind.WARNING, "View original draft" to { state.openHistoryRecord(record) })
        }
        if (creating) Card(Modifier.fillMaxWidth()) {
            Txt("Start a job", Nz.type.title)
            Txt("Choose a saved project, import a model, or add an already prepared G-code file.", Nz.type.bodySmall, c.textMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NzButton("Existing project", { creating = false; state.destination = Destination.PROJECTS })
                NzButton("Import model", {
                    preservingCurrentProject {
                        val files = chooseFiles("Import a model", listOf("stl", "3mf", "obj"))
                        if (files.isNotEmpty()) {
                            val failures = mutableListOf<String>()
                            val loaded = files.mapNotNull { file ->
                                val parsed = runCatching { file to MeshIO.load(file) }
                                parsed.onFailure {
                                    failures += "${file.name}: ${it.message ?: "could not read model"}"
                                }
                                parsed.getOrNull()
                            }
                            if (loaded.isEmpty()) problem = "No model was imported. Your current project is unchanged. ${failures.joinToString("; ")}"
                            else {
                                runCatching { state.prepare.newProjectWithLoadedModels(loaded) }
                                    .onSuccess {
                                        if (failures.isNotEmpty()) state.prepare.notice = "Some models could not be imported: ${failures.joinToString("; ")}"
                                        creating = false
                                        state.destination = Destination.PREPARE
                                    }
                                    .onFailure { problem = "Models could not be prepared: ${it.message}. Check the open project before retrying." }
                            }
                        }
                    }
                })
                NzButton("Prepared file", {
                    creating = false
                    chooseFiles("Choose a prepared print file", listOf("gcode"), multiple = false).firstOrNull()?.let { file ->
                        runCatching { fleet.enqueue(file.nameWithoutExtension, file, null, emptyList()) }
                            .onFailure { problem = "Couldn't add ${file.name}: ${it.message}" }
                    }
                })
            }
        }
        problem?.let { Banner(it, BannerKind.WARNING, "Dismiss" to { problem = null }) }
        Card(Modifier.fillMaxWidth()) {
            Txt("Active prints (${active.size})", Nz.type.title)
            if (active.isEmpty()) Txt("No printer reports an active print.", Nz.type.bodySmall, c.textMuted)
            active.forEach { (id, entry) ->
                val job = entry.status.value.job ?: return@forEach
                val status = entry.status.value
                val remaining = JobEta.remainingSeconds(job.fraction, job.elapsedSeconds)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Txt(entry.config.identity.displayName, Nz.type.label)
                    Txt(job.fileName, Nz.type.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Txt(status.state.label, Nz.type.bodySmall)
                        Txt("${(job.fraction.coerceIn(0f, 1f) * 100).toInt()}%", Nz.type.bodySmall)
                        Txt(remaining?.let { "${formatDuration(it)} left" } ?: "Time left unknown", Nz.type.bodySmall, c.textMuted)
                    }
                    NzButton("Monitor", { state.openPrinter(id) }, kind = ButtonKind.QUIET)
                }
            }
        }
        if (fleet.queue.value.isEmpty() && fleet.queueRecoveryProblems.value.isEmpty()) Card(Modifier.fillMaxWidth()) {
            Txt("Prepared jobs (0)", Nz.type.title)
            Txt("A sliced file can be added here, then reviewed before Start.", Nz.type.bodySmall, c.textMuted)
        } else QueuePanel(state)
        Card(Modifier.fillMaxWidth()) {
            Txt("Recent machine results", Nz.type.title)
            val recent = fleet.history.value.sortedByDescending { it.endedAtMillis }.take(5)
            if (recent.isEmpty()) Txt("No completed or stopped prints have been recorded yet.", Nz.type.bodySmall, c.textMuted)
            recent.forEachIndexed { index, record ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Txt(record.fileName.replace('\\', '/').substringAfterLast('/'), Nz.type.bodySmall)
                    Txt("${record.printerName} · ${record.outcome}", Nz.type.bodySmall, c.textMuted)
                    NzButton("Details", { state.openHistoryRecord(record) }, kind = ButtonKind.QUIET,
                        testTag = "jobs-result-${record.id}-$index")
                }
            }
        }
    }
}
