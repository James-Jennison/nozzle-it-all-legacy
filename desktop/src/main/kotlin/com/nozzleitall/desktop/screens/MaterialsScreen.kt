package com.nozzleitall.desktop.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.desktop.prepare.BundledFilamentCatalog
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*

/** Common U1 material types and finishes, offered as quick picks; any text is still accepted. */
val materialTypes = listOf("PLA", "PETG", "ABS", "ASA", "TPU", "PVA", "PA", "PC")
val materialFinishes = listOf("Basic", "Matte", "Silk", "SnapSpeed", "Support", "HF", "95A")

/** Workshop entry point for bundled presets and material information printers currently report. */
@Composable
fun WorkshopMaterialsScreen(state: AppState) {
    val c = Nz.colors
    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeader("Materials", "Filament presets by brand, plus what your printers report loaded.")
        val slicingSetup = state.fleet.settings.value.slicingSetup
        Card(Modifier.fillMaxWidth(), raised = true) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("Installed filament library", Nz.type.title)
                    Txt(
                        slicingSetup?.let { setup ->
                            "${state.prepare.slicingMachineLabel} · ${state.prepare.nozzleToolheadLabel} · " +
                                "${setup.installedFilamentProfiles.size} compatible preset${if (setup.installedFilamentProfiles.size == 1) "" else "s"} installed"
                        } ?: "Finish slicer setup to choose the machine and compatible filament presets you use.",
                        Nz.type.bodySmall,
                        c.textMuted,
                    )
                }
                NzButton(
                    if (slicingSetup == null) "Set up slicer" else "Manage presets",
                    state::reopenFirstRun,
                    kind = ButtonKind.PRIMARY,
                    testTag = "materials-manage-presets",
                )
            }
        }
        FilamentCatalogBrowser()
        Txt("Loaded on printers", Nz.type.headline)
        val entries = state.fleet.order.value.mapNotNull { id -> state.fleet.printers[id]?.let { id to it } }
        if (entries.isEmpty()) Card(Modifier.fillMaxWidth()) {
            Txt("No printers added", Nz.type.title)
            Txt("Add a printer to see its reported material.", Nz.type.bodySmall, c.textMuted)
            NzButton("Open Printers", { state.destination = Destination.FLEET }, kind = ButtonKind.PRIMARY)
        }
        entries.forEach { (id, entry) ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(entry.config.identity.displayName, Nz.type.title, modifier = Modifier.weight(1f))
                    NzButton("Open printer materials", { state.selectedPrinter = id; state.destination = Destination.PRINTER_MATERIALS }, kind = ButtonKind.SECONDARY)
                }
                val heads = entry.status.value.toolheads
                if (heads.isEmpty()) Txt("No toolhead material reported.", Nz.type.bodySmall, c.textMuted)
                heads.forEach { head ->
                    Txt("Toolhead ${head.index + 1}: " + if (!head.loaded) "empty" else head.material?.label ?: "loaded, material unknown",
                        Nz.type.bodySmall, c.textMuted)
                }
            }
        }
    }
}

/** Case-insensitive brand grouping with one human-friendly label, matching the setup wizard and mainstream slicers. */
internal fun catalogVendorGroups(products: List<BundledFilamentCatalog.Product>): List<Pair<String, List<BundledFilamentCatalog.Product>>> =
    products.groupBy { it.vendor.trim().ifBlank { "Other" }.lowercase() }
        .map { (_, rows) ->
            val labels = rows.map { it.vendor.trim().ifBlank { "Other" } }.distinct()
            val label = labels.firstOrNull { it.any(Char::isUpperCase) && it.any(Char::isLowerCase) } ?: labels.first()
            label to rows
        }
        .sortedWith(compareBy<Pair<String, List<BundledFilamentCatalog.Product>>> {
            when (it.first.lowercase()) { "generic" -> 1; "other" -> 2; else -> 0 }
        }.thenBy { it.first.lowercase() })

internal data class CatalogMaterialTypeGroup(
    val key: String,
    val materialType: String,
    val products: List<BundledFilamentCatalog.Product>,
)

private fun catalogMaterialTypeLabel(value: String): String = value.trim().ifBlank { "Other" }

/** The second level of the catalog browser: Vendor -> Material type -> Material. */
internal fun catalogMaterialTypeGroups(products: List<BundledFilamentCatalog.Product>): List<CatalogMaterialTypeGroup> =
    products.groupBy { catalogMaterialTypeLabel(it.baseMaterial).lowercase() }
        .map { (key, rows) ->
            val labels = rows.map { catalogMaterialTypeLabel(it.baseMaterial) }.distinct()
            val label = labels.firstOrNull { it.any(Char::isUpperCase) } ?: labels.first()
            CatalogMaterialTypeGroup(key, label, rows.sortedBy { it.name.lowercase() })
        }
        .sortedWith(compareBy<CatalogMaterialTypeGroup> { if (it.key == "other") 1 else 0 }
            .thenBy { it.materialType.lowercase() })

/** The complete normalized product list; machine selectors show the compatible subset. */
@Composable
private fun FilamentCatalogBrowser() {
    val c = Nz.colors
    val all = remember { BundledFilamentCatalog.all() }
    var query by remember { mutableStateOf("") }
    val filtered = remember(all, query) {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) all else all.filter { product ->
            val haystack = listOf(product.vendor, product.name, product.material, product.baseMaterial,
                product.sources.joinToString(" ") { it.catalog }).joinToString(" ").lowercase()
            words.all(haystack::contains)
        }
    }
    val groups = remember(filtered) { catalogVendorGroups(filtered) }
    var selectedVendor by remember { mutableStateOf<String?>(null) }
    var selectedMaterialType by remember { mutableStateOf<String?>(null) }
    val active = groups.firstOrNull { it.first == selectedVendor } ?: groups.firstOrNull()
    val materialTypeGroups = remember(active) { active?.let { catalogMaterialTypeGroups(it.second) }.orEmpty() }
    val activeMaterialType = materialTypeGroups.firstOrNull { it.key == selectedMaterialType }
        ?: materialTypeGroups.firstOrNull()
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Txt("Filament presets", Nz.type.title)
                Txt("${all.size} products grouped by vendor, material type and material. A machine picker shows only products NIA can derive from " +
                    "that machine's compatible material presets.", Nz.type.bodySmall, c.textMuted)
            }
            Txt("${groups.size} brands", Nz.type.metricSmall, c.textMuted)
        }
        Field("Search brands, products or materials", query, { query = it }, placeholder = "Polymaker PLA")
        if (active == null) Txt("No matching filament products.", Nz.type.bodySmall, c.textMuted)
        else Row(Modifier.fillMaxWidth().height(360.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                groups.forEach { (vendor, products) ->
                    NzButton("$vendor (${products.size})", { selectedVendor = vendor; selectedMaterialType = null }, Modifier.fillMaxWidth(),
                        kind = if (vendor == active.first) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                }
            }
            Column(Modifier.width(150.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                materialTypeGroups.forEach { group ->
                    NzButton("${group.materialType} (${group.products.size})", { selectedMaterialType = group.key }, Modifier.fillMaxWidth(),
                        kind = if (group.key == activeMaterialType?.key) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                activeMaterialType?.products.orEmpty().forEach { product ->
                    Row(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(7.dp)).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt(product.name, Nz.type.body)
                            if (!product.material.equals(activeMaterialType?.materialType, true))
                                Txt(product.material, Nz.type.bodySmall, c.textMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MaterialsScreen(state: AppState) {
    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val entry = PrinterHeader(state, com.nozzleitall.desktop.Destination.PRINTER_MATERIALS, "What's loaded in each toolhead or material slot, as the printer reports it.") ?: run { NoPrinterYet(state, "what's loaded in each toolhead"); return@Column }
        val status = entry.status.value
        val caps = entry.capabilities.value
        val flow = rememberActionFlow(entry)
        ActionFlowUi(flow, entry)
        if (caps?.materialState == false && status.toolheads.none { it.material != null }) Banner(
            "${entry.config.identity.displayName} doesn't report what material is loaded, so Nozzle It All can't show it. Set materials in Prepare instead.", BannerKind.INFO)
        if (status.toolheads.isEmpty()) {
            Card(Modifier.fillMaxWidth()) { EmptyState(NzIcon.TOOLHEAD, "No toolhead information yet",
                if (status.state == PrinterState.OFFLINE) "${entry.config.identity.displayName} is offline. Toolheads appear once it's connected." else "This printer hasn't reported its toolheads.") }
            return@Column
        }
        var editing by remember(entry) { mutableStateOf<Int?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            status.toolheads.forEach { t -> ToolheadCard(t, caps, status, flow, Modifier.weight(1f)) { editing = t.index } }
        }
        editing?.let { index -> MaterialEditor(entry, status.toolheads.first { it.index == index }, flow) { editing = null } }
        Card(Modifier.fillMaxWidth()) {
            Txt("How materials reach your prints", Nz.type.title)
            Txt("When you prepare a project for this printer, Nozzle matches each colour in your project to the toolhead loaded with the closest material. " +
                "Tag-read spools are shown as reported; you can override them when the printer allows it.", Nz.type.body, Nz.colors.textMuted)
        }
    }
}

@Composable
private fun ToolheadCard(t: Toolhead, caps: Capabilities?, status: PrinterStatus, flow: ActionFlow, modifier: Modifier, onEdit: () -> Unit) {
    val c = Nz.colors
    val color = parseHex(t.material?.colorHex)
    Card(modifier, raised = t.active) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(NzIcon.TOOLHEAD, if (t.active) c.accent else c.textMuted, 22.dp)
            Txt("Toolhead ${t.index + 1}", Nz.type.title, modifier = Modifier.weight(1f))
            if (t.active) Txt("Active", Nz.type.label, c.accent)
        }
        Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(color ?: c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Loaded colour ${t.material?.colorHex ?: "unknown"}" }, contentAlignment = Alignment.Center) {
            if (!t.loaded) Txt("Empty", Nz.type.body, c.textMuted)
        }
        Txt(if (t.loaded) t.material?.label ?: "Unknown material" else "No material loaded", Nz.type.body)
        if (t.material?.fromTag == true) Txt("Read from the spool's tag", Nz.type.bodySmall, c.textMuted)
        Txt("Nozzle ${temp(t.nozzleTemperature)}${t.nozzleDiameterMm?.let { " · %.1f mm".format(it) } ?: ""}", Nz.type.metricSmall, c.textMuted)
        val blocked = flow.busy
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (caps?.materialEdit == true) NzButton("Set material", onEdit, kind = ButtonKind.SECONDARY, enabled = !blocked && status.state in PrinterAction.SetMaterialInfo(t.index, Material()).allowedStates)
            if (caps?.loadUnload == true) {
                NzButton("Load", { flow.request(PrinterAction.LoadMaterial(t.index)) }, kind = ButtonKind.QUIET, enabled = !blocked && status.state in PrinterAction.LoadMaterial(0).allowedStates)
                NzButton("Unload", { flow.request(PrinterAction.UnloadMaterial(t.index)) }, kind = ButtonKind.QUIET, enabled = !blocked && t.loaded && status.state in PrinterAction.UnloadMaterial(0).allowedStates)
            }
        }
    }
}

@Composable
private fun MaterialEditor(entry: PrinterEntry, t: Toolhead, flow: ActionFlow, onClose: () -> Unit) {
    val c = Nz.colors
    var vendor by remember(t) { mutableStateOf(t.material?.vendor ?: "") }
    var type by remember(t) { mutableStateOf(t.material?.type ?: "PLA") }
    var finish by remember(t) { mutableStateOf(t.material?.subType ?: "Basic") }
    var color by remember(t) { mutableStateOf(t.material?.colorHex ?: "#FFFFFF") }
    val valid = parseHex(color) != null
    Card(Modifier.fillMaxWidth(), raised = true) {
        Txt("Set material for toolhead ${t.index + 1}", Nz.type.title)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Brand", vendor, { vendor = it }, Modifier.weight(1f), placeholder = "Polymaker")
            Field("Colour", color, { color = it.take(7) }, Modifier.width(140.dp), placeholder = "#RRGGBB", error = if (valid) null else "Use #RRGGBB")
            Box(Modifier.padding(top = 26.dp).size(40.dp).clip(CircleShape).background(parseHex(color) ?: c.surfaceSunken).border(1.dp, c.lineStrong, CircleShape))
        }
        Txt("Type", Nz.type.label)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { materialTypes.forEach { m -> NzButton(m, { type = m }, kind = if (type == m) ButtonKind.PRIMARY else ButtonKind.SECONDARY) } }
        Txt("Finish", Nz.type.label)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { materialFinishes.forEach { m -> NzButton(m, { finish = m }, kind = if (finish == m) ButtonKind.PRIMARY else ButtonKind.SECONDARY) } }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NzButton("Save to printer", { flow.request(PrinterAction.SetMaterialInfo(t.index, Material(vendor.ifBlank { null }, type, finish, color.uppercase(), false))); onClose() },
                kind = ButtonKind.PRIMARY, enabled = valid)
            NzButton("Cancel", onClose, kind = ButtonKind.QUIET)
        }
    }
}
