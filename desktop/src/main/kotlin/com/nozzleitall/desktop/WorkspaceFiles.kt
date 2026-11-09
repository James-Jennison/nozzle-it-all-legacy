package com.nozzleitall.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.nozzleitall.desktop.prepare.GcodePreview
import com.nozzleitall.desktop.prepare.LayerSlider
import com.nozzleitall.desktop.prepare.PlateViewer
import com.nozzleitall.desktop.prepare.PreviewView
import com.nozzleitall.desktop.prepare.ViewCamera
import com.nozzleitall.desktop.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

internal fun workspaceDropTarget(window: java.awt.Window, receive: (List<File>) -> Unit): DropTarget =
    DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
        override fun drop(event: DropTargetDropEvent) {
            if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                event.rejectDrop()
                return
            }
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val files = runCatching {
                (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                    .orEmpty().filterIsInstance<File>()
            }.getOrDefault(emptyList())
            event.dropComplete(files.isNotEmpty())
            if (files.isNotEmpty()) receive(files)
        }
    })

@Composable
internal fun WorkspaceFiles(state: AppState) {
    val dropped = state.droppedProject
    if (dropped != null) {
        ConfirmDialog(
            title = "Open or add this 3MF?",
            body = dropped.name,
            detail = "Open project loads its plates and compatible settings. Add as model brings geometry into the current project. The source file is preserved.",
            confirmLabel = "Open project",
            destructive = false,
            onConfirm = {
                state.droppedProject = null
                state.guardProjectReplacement { state.openWorkspaceProject(dropped) }
            },
            onDismiss = { state.droppedProject = null },
            alternativeAction = {
                NzButton("Add as model", {
                    state.droppedProject = null
                    state.importWorkspaceModels(listOf(dropped))
                })
            },
        )
    }
    state.gcodeInspection?.let { file ->
        InspectGcode(file, state.prepare.bed) { state.gcodeInspection = null }
    }
}

@Composable
private fun InspectGcode(file: File, bed: Pair<Float, Float>, close: () -> Unit) {
    val result by produceState<Result<GcodePreview>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                check(file.isFile && file.length() in 1..(64L * 1024 * 1024)) {
                    "Choose a readable G-code file up to 64 MiB."
                }
                GcodePreview.parse(file).also { check(it.layers.isNotEmpty()) { "No toolpaths could be read from this file." } }
            }
        }
    }
    DialogWindow(onCloseRequest = close, title = "Inspect G-code",
        state = rememberDialogState(width = 850.dp, height = 650.dp),
        onPreviewKeyEvent = {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { close(); true } else false
        }) {
        val c = Nz.colors
        Column(Modifier.fillMaxSize().background(c.surface).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(file.name, Nz.type.title)
            Txt("Read-only toolpaths on the current workspace grid. Printer compatibility has not been checked.", Nz.type.bodySmall, c.textMuted)
            val preview = result?.getOrNull()
            if (preview == null) {
                val problem = result?.exceptionOrNull()
                if (problem == null) Txt("Reading toolpaths…", Nz.type.bodySmall)
                else Banner(problem.message ?: "The G-code could not be opened.", BannerKind.WARNING)
                Spacer(Modifier.weight(1f))
            } else {
                val camera = remember(file) { ViewCamera() }
                var layer by remember(preview) { mutableIntStateOf(preview.layers.lastIndex) }
                Txt("Layer ${layer + 1} of ${preview.layers.size} · Z ${"%.2f".format(preview.layers[layer].z)} mm", Nz.type.bodySmall)
                if (preview.truncated) Banner("Only part of the toolpath is shown.", BannerKind.WARNING)
                if (preview.discardedPreamble) Txt("Moves before the first layer marker are hidden.", Nz.type.bodySmall, c.textMuted)
                PlateViewer(bed.first, bed.second, emptyList(), camera, c.surfaceRaised, c.lineStrong,
                    Modifier.fillMaxWidth().weight(1f), preview = preview, previewLayer = layer,
                    previewView = PreviewView.LINE_TYPE, accent = c.accent, backgroundColor = c.surfaceSunken)
                LayerSlider(layer, preview.layers.size, Modifier.fillMaxWidth()) { layer = it }
            }
            NzButton("Close", close)
        }
    }
}
