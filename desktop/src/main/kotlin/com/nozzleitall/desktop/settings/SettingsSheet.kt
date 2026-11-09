@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.nozzleitall.desktop.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.nozzleitall.desktop.prepare.PreflightGuidance
import com.nozzleitall.desktop.prepare.PrepareState
import com.nozzleitall.desktop.prepare.chooseFiles
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import com.nozzleitall.desktop.ui.*

/**
 * Prepare's settings, dense: one row per setting (label, value with units, reset when changed, help on hover), in tabs
 * of Nozzle's groups for the print, a material or the printer. The Advanced switch shows everything; off, only the
 * essentials (plus anything already changed). Search looks across the whole scope.
 */
@Composable
fun SettingsSheet(catalog: SettingsCatalog, profile: ProfileValues, p: PrepareState, modifier: Modifier = Modifier) {
    val c = Nz.colors
    val scope = p.settingsScope
    val tabs = catalog.tabs[scope].orEmpty()
    var tabId by remember(scope) { mutableStateOf(tabs.firstOrNull()?.id) }
    var searching by remember { mutableStateOf(false) }
    var query by remember(scope) { mutableStateOf("") }
    var saveOpen by remember(scope) { mutableStateOf(false) }
    var deleteOpen by remember(scope) { mutableStateOf(false) }
    var compareOpen by remember(scope) { mutableStateOf(false) }
    var guidanceOpen by remember(scope) { mutableStateOf(false) }
    fun shown(s: SettingDef) = p.advancedSettings || s.mode == "simple" ||
        s.key == "belt_printer" || s.key == "is_imex" || p.isChanged(s.key, profile)
    // Orca's settings rules for this configuration, from the engine: greyed-out and hidden rows, the Setting Overrides page, the IDEX layout choices.
    LaunchedEffect(p.profileId, p.nozzle, p.processId) { p.refreshRules() }
    val rules = p.rules

    Card(modifier, compact = true) {
        // Header: what these settings are for, the preset, search and the Advanced switch.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (scope != Scope.PROCESS) Txt("‹", Nz.type.title, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "Back to process settings") { p.settingsScope = Scope.PROCESS }.padding(horizontal = 6.dp))
                if (scope != Scope.PROCESS) Txt(
                    when (scope) { Scope.PROCESS -> "Process"; Scope.FILAMENT -> "Material settings"; Scope.PRINTER -> "Printer settings" },
                    Nz.type.label,
                    maxLines = 1,
                )
                DenseSelect("${scopeActionName(scope)} preset", p.presetChoices(scope), p.selectedPresetId(scope), Modifier.weight(1f)) {
                    p.selectSettingsPreset(scope, it)
                }
                IconToggle(NzIcon.SAVE, "Save ${scopeActionName(scope)} preset", false) { saveOpen = true }
                if (p.selectedUserPreset(scope) != null)
                    IconToggle(NzIcon.DELETE, "Delete ${scopeActionName(scope)} user preset", false) { deleteOpen = true }
                IconToggle(NzIcon.COMPARE, "Compare ${scopeActionName(scope)} presets", false) { compareOpen = true }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                IconToggle(NzIcon.SEARCH, "Find a setting", searching) { searching = !searching; if (!searching) query = "" }
                Txt("Advanced", Nz.type.label, c.textMuted, maxLines = 1)
                MiniSwitch(p.advancedSettings, "Show every setting") { p.advancedSettings = it }
            }
        }
        if (p.changedSettings(scope).isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val changed = p.changedSettings(scope).size
            if (changed > 0) {
                Txt("$changed changed", Nz.type.bodySmall, c.accent)
                Txt("Reset", Nz.type.label, c.textMuted, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { p.resetSettingsScope(scope) }.padding(4.dp))
            }
        }
        if (p.settingsLayoutSaveProblem) Txt("Group layout couldn't be saved. Select the group again to retry.", Nz.type.bodySmall, c.danger)
        // What Orca tells you about the current combination: a value it resets (OK) and a clash it asks about (pick one).
        p.ruleChecks?.let { checks -> RuleNotices(p, checks) }
        if (scope == Scope.PROCESS) NzButton("AI preflight guidance", { guidanceOpen = true },
            kind = ButtonKind.QUIET, testTag = "process-ai-preflight")
        if (searching) DenseInput(query, { query = it }, "Find a setting", Modifier.fillMaxWidth(), placeholder = "seam, brim, gyroid…")
        if (!searching && tabs.size > 1) TabStrip(tabs, tabId) { tabId = it }

        val rows: List<Pair<SettingGroup, List<SettingDef>>> = if (searching && query.isNotBlank())
            catalog.search(query).filter { it.second.scope == scope }.groupBy({ it.first }, { it.second }).toList()
        else tabs.firstOrNull { it.id == tabId }?.groups?.map { g -> g to g.settings.filter(::shown) }?.filter { it.second.isNotEmpty() } ?: emptyList()
        val listState = rememberLazyListState()
        LaunchedEffect(tabId, query, scope) { listState.scrollToItem(0) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state = listState) {
                if (rows.isEmpty()) item("empty") {
                    Txt(if (searching) "No setting here matches “$query”." else "Nothing essential here. Turn on Advanced to see everything.", Nz.type.bodySmall, c.textMuted,
                        modifier = Modifier.padding(vertical = 12.dp))
                }
                rows.forEach { (g, list) ->
                    item("h-${g.scope.name}:${g.id}") { GroupHeader(g, p.isSettingGroupCollapsed(g)) { p.toggleSettingGroup(g) } }
                    if (!p.isSettingGroupCollapsed(g) || query.isNotBlank()) items(list, key = { "${g.scope.name}:${g.id}:${it.key}" }) { s ->
                        val state = rules?.states?.get(s.key); val override = rules?.overrides?.get(s.key)
                        if (state?.visible == false || override?.visible == false) return@items
                        // A dropdown whose contents depend on other settings (the IDEX tool layout) takes the engine's choices.
                        val def = rules?.choices?.get(s.key)?.let { list -> s.copy(choices = list.map { Choice(it.first, it.second) }) } ?: s
                        DenseRow(def, p.setting(s.key, profile), p.baseSetting(s.key, profile), p.isChanged(s.key, profile),
                            enabled = state?.enabled != false && override?.checkboxEnabled != false,
                            inherited = override?.inherited?.takeIf { it.isNotBlank() && p.setting(s.key, profile).isNullOrBlank() }) { v -> p.setSetting(s.key, v, profile) }
                    }
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(listState),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }

    if (saveOpen) SaveUserPresetDialog(scope, p, profile, onDismiss = { saveOpen = false })
    if (deleteOpen) p.selectedUserPreset(scope)?.let { selected ->
        ConfirmDialog(
            title = "Delete ${scopeActionName(scope)} preset?",
            body = "Delete the user preset “${selected.name}”? Bundled, vendor, and imported presets cannot be deleted.",
            confirmLabel = "Delete preset",
            destructive = true,
            onConfirm = { p.deleteSelectedUserPreset(scope); deleteOpen = false },
            onDismiss = { deleteOpen = false },
        )
    } ?: run { deleteOpen = false }
    if (compareOpen) ComparePresetsDialog(catalog, scope, p, profile, onDismiss = { compareOpen = false })
    if (guidanceOpen) {
        val snapshot by remember(p, catalog, profile) {
            derivedStateOf { PreflightGuidance.capture(p, catalog, profile) }
        }
        LocalGuidanceDialog(snapshot, current = { PreflightGuidance.capture(p, catalog, profile) },
            onDismiss = { guidanceOpen = false })
    }
}

@Composable
internal fun LocalGuidanceDialog(
    snapshot: PreflightGuidance.Snapshot,
    current: () -> PreflightGuidance.Snapshot?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = Nz.colors
    var program by remember { mutableStateOf<File?>(null) }
    var draft by remember { mutableStateOf<Pair<PreflightGuidance.Snapshot, PreflightGuidance.Draft>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var showRequest by remember { mutableStateOf(false) }
    val title = if (snapshot.failure == null) "AI preflight guidance" else "AI failure diagnosis"
    DialogWindow(onCloseRequest = onDismiss, title = title,
        state = rememberDialogState(width = 620.dp, height = 620.dp)) {
        Column(Modifier.fillMaxSize().background(colors.surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("Review the data before analysis", Nz.type.title)
                Txt("The local program receives the reviewed data below. Guidance is read-only.",
                    Nz.type.bodySmall, colors.textMuted)
                val failure = snapshot.failure
                if (failure == null) {
                    Txt("Objects %d · Off plate %d · Open edges %d".format(snapshot.objects, snapshot.offPlate, snapshot.openEdges), Nz.type.body)
                    Txt(if (snapshot.machineConfigured) "Machine configured" else "Machine setup needed", Nz.type.body)
                    snapshot.settings.forEach { (definition, value) ->
                        Txt(definition.label + "  " + definition.compareValue(value), Nz.type.bodySmall)
                    }
                } else {
                    Txt(failure.kind.label, Nz.type.body)
                    failure.machineReportedFailure?.let { reported ->
                        Txt(if (reported) "Machine reported a failure" else "Physical assessment supplied the failure observation", Nz.type.bodySmall)
                    }
                    failure.physicalAssessment?.let { Txt("Physical assessment " + it.name.lowercase().replace('_', ' '), Nz.type.bodySmall) }
                    failure.elapsedSeconds?.let { Txt("Elapsed %.0f s".format(it), Nz.type.bodySmall) }
                    failure.totalLayers?.let { Txt("Total layers " + it, Nz.type.bodySmall) }
                }
                NzButton(program?.name ?: "Choose local program…", {
                    chooseFiles("Choose an executable guidance program", emptyList(), false).firstOrNull()?.let {
                        program = runCatching { it.canonicalFile }.getOrNull()
                        draft = null
                        problem = if (program == null) "The selected path could not be opened." else null
                    }
                }, enabled = !busy, kind = ButtonKind.SECONDARY)
                program?.let { Txt(it.path, Nz.type.bodySmall) }
                Txt("The local program runs in a networkless sandbox with the system runtime and its own executable.",
                    Nz.type.bodySmall, colors.textMuted)
                NzButton(if (showRequest) "Hide JSON request" else "Review JSON request", { showRequest = !showRequest },
                    kind = ButtonKind.QUIET)
                if (showRequest) Txt(snapshot.payload().toString(2), Nz.type.bodySmall)
                problem?.let { Txt(it, Nz.type.bodySmall, colors.danger) }
                draft?.let { (input, answer) ->
                    if (input != snapshot) Txt("The reviewed data changed. Run the analysis again.", Nz.type.bodySmall)
                    else {
                        Txt(answer.summary, Nz.type.body)
                        answer.suggestions.forEach { suggestion ->
                            val (definition, before) = suggestion.setting
                            Txt(definition.label + "  " + definition.compareValue(before) + " → " + definition.compareValue(suggestion.proposed),
                                Nz.type.label)
                            Txt(suggestion.reason, Nz.type.bodySmall, colors.textMuted)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                NzButton("Close", onDismiss, kind = ButtonKind.QUIET)
                NzButton(if (busy) "Analyzing…" else if (snapshot.failure == null) "Analyze preflight" else "Analyze failure", {
                    val selected = program ?: return@NzButton
                    val approved = snapshot
                    if (current() != approved) {
                        problem = "The reviewed data changed. Review the inputs again."
                        return@NzButton
                    }
                    busy = true
                    problem = null
                    draft = null
                    scope.launch {
                        try {
                            draft = approved to runInterruptible(Dispatchers.IO) { PreflightGuidance.analyze(selected, approved) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (isActive) problem = "The isolated local program could not return valid guidance."
                        } finally { if (isActive) busy = false }
                    }
                }, enabled = program != null && !busy, kind = ButtonKind.PRIMARY)
            }
        }
    }
}

private fun scopeActionName(scope: Scope) = when (scope) {
    Scope.PROCESS -> "Process"
    Scope.FILAMENT -> "Filament"
    Scope.PRINTER -> "Printer"
}

data class PresetDifference(val key: String, val label: String, val left: String, val right: String, val section: String)

private val numericPresetLabel = Regex("""[+-]?(?:\d+(?:\.\d*)?|\.\d+)\s*%?""")
private val overhangSpeedKey = Regex("""overhang_[1-4]_4_speed""")

fun presetDifferences(catalog: SettingsCatalog, scope: Scope, left: Map<String, String>, right: Map<String, String>): List<PresetDifference> {
    val tabs = catalog.tabs[scope].orEmpty()
    val groups = catalog.groups.filter { it.scope == scope }
    val groupByKey = groups.flatMap { group -> group.settings.map { it.key to group } }.toMap()
    val tabByGroup = tabs.flatMap { tab -> tab.groups.map { it.id to tab.title } }.toMap()
    val settings = catalog.all.filter { it.scope == scope }
    val byLabel = settings.groupBy { it.label.trim().lowercase() }
    val sectionOrder = tabs.map { it.title }.ifEmpty { groups.map { it.title } }
    val fallback = if (scope == Scope.PROCESS) "Others" else "Other settings"

    return settings.mapNotNull { setting ->
        val a = left[setting.key].orEmpty()
        val b = right[setting.key].orEmpty()
        if (a == b) return@mapNotNull null
        val group = groupByKey[setting.key]
        val section = group?.let { tabByGroup[it.id] ?: it.title } ?: fallback
        val shortLabel = setting.label.trim()
        val keyLabel = setting.key.replace('_', ' ').replaceFirstChar { it.uppercase() }
        val sameLabel = byLabel.getValue(shortLabel.lowercase())
        val label = when {
            overhangSpeedKey.matches(setting.key) && numericPresetLabel.matches(shortLabel) ->
                "Overhang speed ($shortLabel)"
            shortLabel.isEmpty() || numericPresetLabel.matches(shortLabel) -> keyLabel
            sameLabel.size == 1 -> shortLabel
            group != null && sameLabel.count { groupByKey[it.key]?.id == group.id } == 1 ->
                "$shortLabel · ${group.title}"
            else -> keyLabel
        }
        PresetDifference(setting.key, label, setting.compareValue(a), setting.compareValue(b), section)
    }.sortedWith(compareBy({ sectionOrder.indexOf(it.section).let { index -> if (index < 0) Int.MAX_VALUE else index } },
        { it.section }, { it.label.lowercase() }, { it.key }))
}

private fun SettingDef.compareValue(serialized: String): String {
    if (serialized.isBlank()) return "Not set"

    fun format(value: String): String {
        if (nullable && value == "nil") return "Not set"
        if (baseType == "bool") return when (value) {
            "1", "true" -> "On"
            "0", "false" -> "Off"
            else -> value
        }
        val text = choices.firstOrNull { it.value == value }?.label ?: value
        val unit = units?.removeSuffix(" or %") ?: if (baseType == "percent") "%" else return text
        return when {
            text.endsWith("%") || text.endsWith(unit) -> text
            unit in setOf("%", "°") -> "$text$unit"
            else -> "$text $unit"
        }
    }

    fun point(value: String): String {
        val axes = value.split(',', 'x')
        return if (axes.size == 2) "X: ${format(axes[0].trim())}, Y: ${format(axes[1].trim())}" else format(value)
    }

    if (type == "point") return point(serialized)
    val isAngleTemplate = key == "sparse_infill_rotate_template" || key == "solid_infill_rotate_template"
    if (!isList && !isAngleTemplate) return format(serialized)
    val values = when {
        type == "strings" && (serialized.trimStart().startsWith('"') || ';' in serialized) ->
            SettingDef.parseStrings(serialized)
        else -> serialized.split(',').map(String::trim)
    }
    return values.mapIndexed { index, value ->
        val formatted = when {
            type == "points" -> point(value)
            isAngleTemplate && value.toDoubleOrNull() == null -> value
            else -> format(value)
        }
        if (values.size == 1) formatted else "Value ${index + 1}: $formatted"
    }.joinToString("\n").ifEmpty { "Not set" }
}

@Composable
private fun SaveUserPresetDialog(scope: Scope, p: PrepareState, profile: ProfileValues, onDismiss: () -> Unit) {
    val c = Nz.colors
    val current = p.selectedUserPreset(scope)
    var name by remember(scope, current?.id) { mutableStateOf(current?.name.orEmpty()) }
    var overwrite by remember(scope, current?.id) { mutableStateOf(current != null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val height = 210.dp + (if (Nz.textScale >= 1.5f) 36.dp else 0.dp) + (if (current != null) 50.dp else 0.dp)
    DialogWindow(onCloseRequest = onDismiss, title = "Save ${scopeActionName(scope)} preset",
        state = rememberDialogState(width = 520.dp, height = height), resizable = false) {
        Column(Modifier.fillMaxSize().background(c.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("Save ${scopeActionName(scope)} preset", Nz.type.title)
                Txt("Save the current ${scopeActionName(scope).lowercase()} settings as a NIA user preset. System and imported presets remain unchanged.", Nz.type.bodySmall, c.textMuted)
                DenseInput(name, { name = it; problem = null }, "Preset name", Modifier.fillMaxWidth(),
                    placeholder = "e.g. My ${scopeActionName(scope).lowercase()} preset")
                if (current != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NzButton("New preset", { overwrite = false }, kind = if (!overwrite) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                        NzButton("Overwrite “${current.name}”", { overwrite = true }, kind = if (overwrite) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                    }
                }
                problem?.let { Txt(it, Nz.type.bodySmall, c.danger) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                NzButton("Cancel", onDismiss, kind = ButtonKind.QUIET)
                NzButton(if (overwrite) "Overwrite preset" else "Save preset", {
                    runCatching { p.saveUserPreset(scope, name, profile, overwrite) }
                        .onSuccess { onDismiss() }.onFailure { problem = it.message ?: "The preset could not be saved." }
                }, kind = ButtonKind.PRIMARY, enabled = name.trim().isNotEmpty())
            }
        }
    }
}

@Composable
private fun ComparePresetsDialog(catalog: SettingsCatalog, scope: Scope, p: PrepareState, profile: ProfileValues, onDismiss: () -> Unit) {
    val c = Nz.colors
    val snapshots = p.presetSnapshots(scope, profile)
    var leftId by remember(scope, snapshots) { mutableStateOf(p.selectedPresetId(scope).takeIf { id -> snapshots.any { it.id == id } } ?: snapshots.firstOrNull()?.id.orEmpty()) }
    var rightId by remember(scope, snapshots) { mutableStateOf(snapshots.firstOrNull { it.id != leftId }?.id ?: leftId) }
    val left = snapshots.firstOrNull { it.id == leftId }
    val right = snapshots.firstOrNull { it.id == rightId }
    val differences = if (left == null || right == null) emptyList() else presetDifferences(catalog, scope, left.values, right.values)
    DialogWindow(onCloseRequest = onDismiss, title = "Compare ${scopeActionName(scope)} presets",
        state = rememberDialogState(width = 860.dp, height = 640.dp), resizable = true) {
        Column(Modifier.fillMaxSize().background(c.surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt("Compare ${scopeActionName(scope)} presets", Nz.type.title)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DenseSelect("Left preset", snapshots.map { Choice(it.id, it.name) }, leftId, Modifier.weight(1f)) { leftId = it }
                DenseSelect("Right preset", snapshots.map { Choice(it.id, it.name) }, rightId, Modifier.weight(1f)) { rightId = it }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt("Setting", Nz.type.label, c.textMuted, Modifier.weight(1.1f))
                Txt(left?.name.orEmpty(), Nz.type.label, c.textMuted, Modifier.weight(1f))
                Txt(right?.name.orEmpty(), Nz.type.label, c.textMuted, Modifier.weight(1f))
            }
            if (differences.isEmpty()) Txt("These presets have no different ${scopeActionName(scope).lowercase()} settings.", Nz.type.body, c.textMuted,
                Modifier.weight(1f).padding(8.dp))
            else {
                val listState = rememberLazyListState()
                Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp))) {
                    LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state = listState) {
                        differences.groupBy { it.section }.forEach { (section, rows) ->
                            item("section-$section") {
                                Txt("$section (${rows.size})", Nz.type.label, c.text,
                                    Modifier.fillMaxWidth().background(c.surfaceSunken).padding(horizontal = 8.dp, vertical = 7.dp))
                            }
                            items(rows, key = { it.key }) { difference ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Txt(difference.label, Nz.type.bodySmall, modifier = Modifier.weight(1.1f))
                                    Txt(difference.left, Nz.type.bodySmall, c.textMuted, Modifier.weight(1f))
                                    Txt(difference.right, Nz.type.bodySmall, c.textMuted, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            Txt("${differences.size} different setting${if (differences.size == 1) "" else "s"}", Nz.type.bodySmall, c.textMuted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { NzButton("Close", onDismiss, kind = ButtonKind.PRIMARY) }
        }
    }
}

@Composable
private fun RuleNotices(p: PrepareState, checks: com.nozzleitall.desktop.prepare.OptionRules.Result) {
    val c = Nz.colors
    // The mixed sub-layer warning is shown beside the layer tools; the rest are the print-setting checks.
    val notices = checks.notices.filter { it.id != "mixed_sublayer_variable_layer" }
    if (notices.isEmpty() && checks.conflicts.isEmpty() && checks.notices.none { it.id == "mixed_sublayer_variable_layer" }) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        checks.notices.firstOrNull { it.id == "mixed_sublayer_variable_layer" }?.let { Txt(it.message, Nz.type.bodySmall, c.danger) }
        notices.forEach { n ->
            Txt(n.message, Nz.type.bodySmall, c.text)
            if (n.changes.isNotEmpty()) Txt("Apply: " + n.changes.entries.joinToString { "${it.key} = ${it.value}" }, Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                .clickable(onClickLabel = "Apply Orca's reset") { n.changes.forEach { (k, v) -> p.overrides[k] = v }; p.changed() }.padding(4.dp))
        }
        checks.conflicts.forEach { cf ->
            Txt(cf.message, Nz.type.bodySmall, c.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cf.choices.forEach { (label, changes) -> Txt(label, Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = label) { changes.forEach { (k, v) -> p.overrides[k] = v }; p.changed() }.padding(4.dp)) }
            }
        }
    }
}

@Composable
internal fun TabStrip(tabs: List<SettingTab>, selected: String?, onSelect: (String) -> Unit) {
    val c = Nz.colors
    BoxWithConstraints(Modifier.fillMaxWidth().height(38.dp)) {
        val fitsFamiliarSidebar = maxWidth >= if (Nz.textScale >= 1.5f) 540.dp else 330.dp
        if (fitsFamiliarSidebar) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                tabs.forEach { t -> CategoryTab(t, t.id == selected) { onSelect(t.id) } }
            }
        } else {
            val listState = rememberLazyListState()
            val hasOverflow by remember {
                derivedStateOf { listState.canScrollBackward || listState.canScrollForward }
            }
            LaunchedEffect(selected, tabs) {
                tabs.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.let { listState.animateScrollToItem(it) }
            }
            LazyRow(
                Modifier.fillMaxSize().padding(bottom = if (hasOverflow) 4.dp else 0.dp),
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tabs, key = { it.id }) { t ->
                    CategoryTab(t, t.id == selected) { onSelect(t.id) }
                }
            }
            if (hasOverflow) {
                HorizontalScrollbar(
                    rememberScrollbarAdapter(listState),
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).testTag("settings-category-scrollbar"),
                    style = ScrollbarStyle(
                        minimalHeight = 16.dp,
                        thickness = 3.dp,
                        shape = RoundedCornerShape(2.dp),
                        hoverDurationMillis = 200,
                        unhoverColor = c.lineStrong,
                        hoverColor = c.accent,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CategoryTab(tab: SettingTab, selected: Boolean, onSelect: () -> Unit) {
    val c = Nz.colors
    Column(
        Modifier.width(IntrinsicSize.Max).height(34.dp)
            .testTag("settings-category-${tab.id}")
            .clickable(role = Role.Tab, onClick = onSelect)
            .semantics {
                contentDescription = "${tab.title} settings"
                this.selected = selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Txt(tab.title, Nz.type.label, if (selected) c.accent else c.textMuted, maxLines = 1)
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) c.accent else Color.Transparent))
    }
}

@Composable
private fun GroupHeader(g: SettingGroup, collapsed: Boolean, onToggle: () -> Unit) {
    val c = Nz.colors
    TooltipArea(tooltip = { Tip(g.summary) }, delayMillis = 500) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NzButton((if (collapsed) "› " else "⌄ ") + g.title, onToggle,
                modifier = Modifier.semantics { stateDescription = if (collapsed) "Collapsed" else "Expanded" },
                kind = ButtonKind.QUIET, testTag = "settings-group-${g.scope.name}-${g.id}")
            Box(Modifier.weight(1f).height(1.dp).background(c.line))
        }
    }
}

/** One setting: label (help on hover), the value with its units, and a reset mark when it differs from the base. */
@Composable
internal fun DenseRow(s: SettingDef, value: String?, base: String?, changed: Boolean, enabled: Boolean = true, inherited: String? = null, onSet: (String?) -> Unit) {
    val c = Nz.colors
    // A setting Orca greys out is dimmed and ignores the pointer.
    val dim = if (enabled) Modifier else Modifier.alpha(0.45f).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } }
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(vertical = 1.dp).then(dim), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(if (changed) c.accent else c.surface))
        Spacer(Modifier.width(8.dp))
        TooltipArea(tooltip = { Tip(buildString { s.help?.let { append(it) }; if (base != null && changed) append("\n\nProfile: ${s.display(base).take(80)}") }) },
            delayMillis = 400, modifier = Modifier.weight(1f)) {
            Txt(s.label, Nz.type.bodySmall, if (changed) c.accent else c.text, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        if (inherited != null && value.isNullOrBlank()) Txt("Printer's: " + s.display(inherited).take(20), Nz.type.bodySmall, c.textMuted, maxLines = 1)
        DenseControl(s, value.orEmpty(), onSet)
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            if (changed) Txt("↺", Nz.type.label, c.textMuted, modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClickLabel = "Reset ${s.label}") { onSet(null) }.padding(2.dp))
        }
    }
}

@Composable
private fun DenseControl(s: SettingDef, serialized: String, onSet: (String?) -> Unit) {
    val width = 140.dp
    when {
        s.readonly -> Txt(s.display(serialized), Nz.type.bodySmall, Nz.colors.textMuted, modifier = Modifier.width(width), maxLines = 1)
        s.type == "bool" -> Box(Modifier.width(width)) { MiniCheck(serialized == "1", s.label) { onSet(if (it) "1" else "0") } }
        s.type == "enum" && !s.openChoices -> DenseSelect(s.label, s.choices, serialized, Modifier.width(width)) { onSet(it) }
        s.code || s.multiline -> CodeButton(s, serialized, Modifier.width(width), onSet)
        else -> {
            var text by remember(s.key, serialized) { mutableStateOf(s.display(serialized)) }
            val problem = s.problem(text)
            TooltipArea(tooltip = { problem?.let { Tip(it) } }, delayMillis = 0) {
                DenseInput(text, { t -> text = t; if (s.problem(t) == null) onSet(s.serialize(t)) }, s.label, Modifier.width(width), units = s.units, error = problem != null,
                    placeholder = if (s.nullable) "Printer's" else "")
            }
        }
    }
}

@Composable
fun DenseInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, units: String? = null, error: Boolean = false, placeholder: String = "") {
    val c = Nz.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Row(modifier.height(28.dp).clip(shape).background(c.surfaceSunken).border(1.dp, if (error) c.danger else if (focused) c.focus else c.line, shape).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) Txt(placeholder, Nz.type.bodySmall, c.textMuted, maxLines = 1)
            BasicTextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = label }, singleLine = true,
                textStyle = Nz.type.bodySmall.copy(color = c.text), cursorBrush = SolidColor(c.accent), interactionSource = interaction)
        }
        units?.let { Spacer(Modifier.width(4.dp)); Txt(it, Nz.type.bodySmall, c.textMuted, maxLines = 1) }
    }
}

@Composable
fun DenseSelect(label: String, choices: List<Choice>, value: String, modifier: Modifier, enabled: Boolean = true, onSelect: (String) -> Unit) {
    val c = Nz.colors
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val selectedLabel = choices.firstOrNull { it.value == value }?.label ?: value
    Box(modifier) {
        Row(Modifier.fillMaxWidth().height(28.dp).clip(shape).background(c.surfaceSunken).border(1.dp, if (open) c.focus else c.line, shape)
            .semantics {
                contentDescription = "$label, $selectedLabel"
                stateDescription = if (open) "Expanded" else "Collapsed"
                role = Role.DropdownList
            }
            .onPreviewKeyEvent { event ->
                if (enabled && event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                    open = !open
                    true
                } else false
            }
            .focusable(enabled)
            .clickable(enabled = enabled, onClickLabel = "Choose $label") { open = !open }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(selectedLabel, Nz.type.bodySmall, if (enabled) c.text else c.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
            Txt("▾", Nz.type.bodySmall, c.textMuted)
        }
        if (open && enabled) Popup(offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 30.dp.roundToPx() }),
            onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(240.dp).heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceRaised).border(1.dp, c.lineStrong, RoundedCornerShape(10.dp))
                .verticalScroll(rememberScrollState()).padding(4.dp)) {
                choices.forEach { ch ->
                    Txt(ch.label, Nz.type.bodySmall, if (ch.value == value) c.accent else c.text, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (ch.value == value) c.accent.copy(alpha = 0.14f) else c.surfaceRaised)
                            .semantics {
                                contentDescription = "$label option ${ch.label}"
                                role = Role.Button
                                selected = ch.value == value
                            }
                            .clickable { onSelect(ch.value); open = false }.padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
        }
    }
}

/** G-code and other long text: edited in a larger box that opens from the row. */
@Composable
private fun CodeButton(s: SettingDef, serialized: String, modifier: Modifier, onSet: (String?) -> Unit) {
    val c = Nz.colors
    var open by remember { mutableStateOf(false) }
    val lines = s.display(serialized).lines().count { it.isNotBlank() }
    Box(modifier) {
        Txt(if (lines == 0) "Empty · Edit" else "$lines lines · Edit", Nz.type.bodySmall, c.accent, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 6.dp))
        if (open) Popup(onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            var text by remember { mutableStateOf(s.display(serialized)) }
            Column(Modifier.width(520.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceRaised).border(1.dp, c.lineStrong, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(s.label, Nz.type.label)
                Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    BasicTextField(text, { text = it }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).semantics { contentDescription = s.label },
                        textStyle = Nz.type.metricSmall.copy(color = c.text), cursorBrush = SolidColor(c.accent))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    NzButton("Cancel", { open = false }, kind = ButtonKind.QUIET)
                    NzButton("Done", { onSet(s.serialize(text)); open = false }, kind = ButtonKind.PRIMARY)
                }
            }
        }
    }
}

@Composable
fun MiniSwitch(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val c = Nz.colors
    Box(Modifier.size(34.dp, 18.dp).clip(RoundedCornerShape(9.dp)).background(if (on) c.accent else c.surfaceSunken).border(1.dp, c.lineStrong, RoundedCornerShape(9.dp))
        .clickable(onClickLabel = label) { onChange(!on) }.semantics { contentDescription = "$label: ${if (on) "on" else "off"}" }) {
        Box(Modifier.padding(2.dp).size(14.dp).align(if (on) Alignment.CenterEnd else Alignment.CenterStart).clip(RoundedCornerShape(7.dp)).background(if (on) c.onAccent else c.textMuted))
    }
}

@Composable
private fun MiniCheck(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val c = Nz.colors
    Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(if (on) c.accent else c.surfaceSunken).border(1.dp, if (on) c.accent else c.lineStrong, RoundedCornerShape(4.dp))
        .clickable(onClickLabel = label) { onChange(!on) }.semantics { contentDescription = "$label: ${if (on) "on" else "off"}" }, contentAlignment = Alignment.Center) {
        if (on) Txt("✓", Nz.type.label, c.onAccent)
    }
}

@Composable
fun IconToggle(icon: NzIcon, label: String, on: Boolean, onClick: () -> Unit) {
    val c = Nz.colors
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(if (on) c.accent.copy(alpha = 0.16f) else c.surface).clickable(onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { Icon(icon, if (on) c.accent else c.textMuted, 16.dp) }
}

@Composable
private fun Tip(text: String) {
    if (text.isBlank()) return
    val c = Nz.colors
    Box(Modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceRaised).border(1.dp, c.lineStrong, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Txt(text, Nz.type.bodySmall, c.text)
    }
}

/** A choice from a list too long for buttons. Keyboard: Enter or Space opens it; the list is scrollable. */
@Composable
fun NzSelect(label: String, choices: List<Choice>, value: String, onSelect: (String) -> Unit) {
    val c = Nz.colors
    var open by remember { mutableStateOf(false) }
    Box {
        NzButton((choices.firstOrNull { it.value == value }?.label ?: value.ifBlank { "Choose…" }) + "  ▾", { open = !open }, kind = ButtonKind.SECONDARY)
        if (open) Popup(onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(280.dp).heightIn(max = 340.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceRaised).border(1.dp, c.lineStrong, RoundedCornerShape(10.dp))
                .verticalScroll(rememberScrollState()).padding(4.dp).semantics { contentDescription = "Choices for $label" }) {
                choices.forEach { ch ->
                    Txt(ch.label, Nz.type.body, if (ch.value == value) c.accent else c.text,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (ch.value == value) c.accent.copy(alpha = 0.14f) else c.surfaceRaised)
                            .clickable { onSelect(ch.value); open = false }.padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
        }
    }
}
