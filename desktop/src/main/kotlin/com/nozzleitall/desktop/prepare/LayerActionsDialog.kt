package com.nozzleitall.desktop.prepare

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.settings.Choice
import com.nozzleitall.desktop.settings.NzSelect
import com.nozzleitall.desktop.ui.*
import java.util.Locale
import org.json.JSONObject

@Composable
internal fun LayerActionsDialog(state: AppState, cursor: PreviewLayerCursor?, dismiss: () -> Unit) {
    val prepare = state.prepare
    val c = Nz.colors
    if (cursor == null) {
        if (prepare.layerActionProblem != null) {
            ConfirmDialog(
                "Remove unreadable layer actions?",
                "This removes only layer actions this version could not read. Undo can restore them before closing the project.",
                "Remove unreadable actions", destructive = true, onDismiss = dismiss,
                onConfirm = { prepare.discardUnreadableLayerActions(); dismiss() },
            )
            return
        }
        val reviewed = prepare.layerActions.toMap()
        val target = prepare.layerActionTarget()
        val needsReview = !prepare.layerActionsReviewed
        val machine = runCatching { JSONObject(target.machine) }.getOrDefault(JSONObject())
        ConfirmDialog(
            if (needsReview) "Review saved layer actions" else "Manage layer actions",
            "These actions change printer instructions at a specific height. Remove an action to choose a new layer after slicing again.",
            if (needsReview) "Use saved actions" else "Done", destructive = false,
            onDismiss = dismiss, onConfirm = {
                if (prepare.layerActions.toMap() == reviewed) prepare.reviewLayerActions(target)
                dismiss()
            },
            acknowledgement = "I reviewed these actions and their printer instructions.".takeIf { needsReview },
            extraContent = {
                reviewed.forEach { (id, actions) ->
                    val plate = prepare.plateIds.indexOf(id)
                    Txt(prepare.plateNames.getOrNull(plate) ?: "Saved plate", Nz.type.label)
                    actions.forEach { action ->
                        Txt("Layer ${action.layer} · Z ${height(action.z)} mm · ${action.kind.label}", Nz.type.body)
                        val code = LayerAction.command(action.kind, machine, action.text)
                        Txt(code ?: "Unavailable for the selected printer profile.", Nz.type.bodySmall, c.textMuted)
                        NzButton("Remove layer ${action.layer} action", { prepare.removeLayerAction(id, action.id) },
                            kind = ButtonKind.QUIET)
                    }
                }
            },
        )
        return
    }
    val plateId = remember(cursor) { prepare.plateIds.getOrNull(prepare.currentPlate) } ?: return
    val preview = remember(cursor) { prepare.slice }
    val target = remember(cursor) { prepare.layerActionTarget() }
    val existing = remember(cursor, plateId) {
        prepare.layerActions[plateId].orEmpty().firstOrNull { it.layer == cursor.index + 1 }
    }
    fun matchesPreview() = prepare.slice === preview && prepare.plateIds.getOrNull(prepare.currentPlate) == plateId &&
        prepare.layerActionTarget() == target
    val stillCurrent = matchesPreview()
    var kind by remember(plateId, cursor.index, existing) { mutableStateOf(existing?.kind ?: LayerAction.Kind.PAUSE) }
    var commands by remember(plateId, cursor.index, existing) { mutableStateOf(existing?.text.orEmpty()) }
    val machine = runCatching { JSONObject(target.machine) }.getOrDefault(JSONObject())
    val code = LayerAction.command(kind, machine, commands)
    val types = LayerAction.Kind.entries.map { Choice(it.name, it.label) }
    ConfirmDialog(
        "Layer ${cursor.index + 1} action",
        "Add one action before printing this layer at Z ${height(cursor.z.toDouble())} mm. Nozzle will slice again and check the resulting instructions.",
        if (existing == null) "Add and slice" else "Update and slice", destructive = false,
        onDismiss = dismiss, onConfirm = {
            if (matchesPreview() && prepare.setLayerAction(plateId, cursor.index + 1, cursor.z.toDouble(), kind, commands)) prepare.slice(state.scope)
            else prepare.notice = "The selected layer or printer setup changed. Review the action again."
            dismiss()
        },
        confirmEnabled = stillCurrent && code != null,
        alternativeAction = if (existing == null || !stillCurrent) null else {
            {
                NzButton("Remove action", {
                    if (matchesPreview()) {
                        prepare.removeLayerAction(plateId, existing.id)
                        prepare.slice(state.scope)
                    }
                    dismiss()
                }, kind = ButtonKind.SECONDARY)
            }
        },
        extraContent = {
            if (!stillCurrent) Banner("The preview or printer setup changed. Reopen the layer action from the current preview.", BannerKind.WARNING)
            Txt("Action", Nz.type.label)
            NzSelect("Layer action", types, kind.name) { kind = LayerAction.Kind.valueOf(it) }
            if (kind == LayerAction.Kind.CUSTOM) {
                Txt("Custom G-code", Nz.type.label)
                BasicTextField(commands, { commands = it.take(2048) },
                    Modifier.fillMaxWidth().heightIn(min = 74.dp).border(1.dp, c.lineStrong).background(c.surfaceSunken)
                        .padding(8.dp).semantics { contentDescription = "Custom layer G-code" },
                    textStyle = Nz.type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent))
                Txt("Use printer-compatible commands. The code is included as entered.", Nz.type.bodySmall, c.textMuted)
                if (commands.isNotBlank() && code == null)
                    Txt("Use printable ASCII without layer or action markers.", Nz.type.bodySmall, c.danger)
            } else if (code == null) {
                Banner("This action is unavailable for the selected printer profile.", BannerKind.WARNING)
            } else {
                Txt(code, Nz.type.bodySmall, c.textMuted)
            }
        },
    )
}

private fun height(value: Double): String = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
