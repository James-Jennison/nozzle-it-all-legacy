package com.nozzleitall.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The familiar slicer journey, expressed in NIA's own shell rather than Orca's window chrome. */
enum class PrintWorkflowStage(val label: String, val icon: NzIcon) {
    PREPARE("Prepare", NzIcon.PREPARE),
    PREVIEW("Preview", NzIcon.LAYERS),
    SEND("Send", NzIcon.SEND),
    MONITOR("Monitor", NzIcon.MONITOR),
}

internal fun usesCompactNavigation(width: Dp, textScale: Float): Boolean =
    width < if (textScale >= 1.5f) 1120.dp else 850.dp

internal fun printWorkflowStageEnabled(
    stage: PrintWorkflowStage,
    hasPreview: Boolean,
    hasPrinter: Boolean,
): Boolean = when (stage) {
    PrintWorkflowStage.PREPARE -> true
    PrintWorkflowStage.PREVIEW, PrintWorkflowStage.SEND -> hasPreview
    PrintWorkflowStage.MONITOR -> hasPrinter
}

internal fun printWorkflowStageDescription(
    stage: PrintWorkflowStage,
    active: PrintWorkflowStage,
    hasPreview: Boolean,
    hasPrinter: Boolean,
): String {
    val status = when {
        stage == active -> "Current stage."
        printWorkflowStageEnabled(stage, hasPreview, hasPrinter) -> "Available."
        stage == PrintWorkflowStage.PREVIEW -> "Unavailable until the current plate is sliced."
        stage == PrintWorkflowStage.SEND -> "Unavailable until the current plate is sliced."
        else -> "Unavailable until a printer is selected."
    }
    return "${stage.label}. $status"
}

/**
 * A persistent, ordered print workflow. Disabled later stages remain visible so
 * people can understand where slicing is going without hunting through pages.
 */
@Composable
fun PrintWorkflowBar(
    active: PrintWorkflowStage,
    hasPreview: Boolean,
    hasPrinter: Boolean,
    onSelect: (PrintWorkflowStage) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier.semantics {
            contentDescription = "Print workflow. Current stage: ${active.label}"
            testTag = "print-workflow"
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrintWorkflowStage.entries.forEachIndexed { index, stage ->
            val enabled = printWorkflowStageEnabled(stage, hasPreview, hasPrinter)
            val description = printWorkflowStageDescription(stage, active, hasPreview, hasPrinter)
            val tag = "workflow-${stage.name.lowercase()}"
            if (compact) {
                NzIconButton(stage.icon, stage.label, { onSelect(stage) },
                    enabled = enabled, selected = stage == active, testTag = tag, tooltip = description)
            } else {
                NzButton(stage.label, { onSelect(stage) },
                    kind = if (stage == active) ButtonKind.PRIMARY else ButtonKind.QUIET,
                    icon = stage.icon, enabled = enabled, testTag = tag, accessibilityDescription = description)
                if (index != PrintWorkflowStage.entries.lastIndex) {
                    Txt("→", Nz.type.label, Nz.colors.textMuted)
                }
            }
        }
    }
}
