package com.nozzleitall.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.EXPORT_ONLY
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.desktop.PrinterStore
import com.nozzleitall.desktop.discovery.LanScan
import com.nozzleitall.desktop.prepare.ProfileCatalog
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What each family asks for. Everything else about a printer comes from its adapter's reported capabilities. */
private data class FamilyForm(val needsAddress: Boolean = true, val canProbe: Boolean = false, val secretLabel: String? = null, val secretRequired: Boolean = false,
                              val needsSerial: Boolean = false, val note: String? = null, val defaultProfile: String? = null)

private fun formFor(f: PrinterFamily) = when (f) {
    PrinterFamily.PAXX_U1, PrinterFamily.STOCK_U1 -> FamilyForm(canProbe = true, secretLabel = "API key (only if your printer requires one)", defaultProfile = "snapmaker_u1")
    PrinterFamily.KLIPPER -> FamilyForm(canProbe = true, secretLabel = "API key (only if Moonraker requires one)", defaultProfile = "generic_klipper")
    PrinterFamily.OCTOPRINT -> FamilyForm(secretLabel = "OctoPrint API key", secretRequired = true, note = "Create an API key in OctoPrint's settings (Application Keys) and paste it here.")
    PrinterFamily.PRUSA -> FamilyForm(secretLabel = "PrusaLink password", secretRequired = true, defaultProfile = "prusa_generic",
        note = "Find the PrusaLink password on the printer's screen under Settings → Network → PrusaLink.")
    PrinterFamily.BAMBU_LAB -> FamilyForm(secretLabel = "Access code", secretRequired = true, needsSerial = true, defaultProfile = "bambu_generic",
        note = "Turn on LAN mode on the printer (and Developer Mode on current firmware), then copy its access code and serial number from the printer's screen.")
    PrinterFamily.ELEGOO -> FamilyForm(canProbe = true, secretLabel = "Access code (Centauri Carbon 2 only, if you set one)",
        note = "Enter the printer's local address from its network settings. A Centauri Carbon needs nothing else; a Centauri Carbon 2 uses its access code if one is set.")
    PrinterFamily.EXPORT_ONLY -> FamilyForm(needsAddress = false)
    else -> FamilyForm()
}

internal fun compatibilityProfileForDiscoveredPrinter(explicitProfileId: String?, model: String): String? =
    explicitProfileId ?: ProfileCatalog.search(model).firstOrNull()?.id

@Composable
fun AddPrinterDialog(state: AppState, autoScan: Boolean = false, compatibilityProfileId: String? = null,
                     onDone: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val fleet = state.fleet
    // This dialog connects physical devices. Offline slicing configurations belong to the setup wizard, not the fleet.
    val families = remember { (fleet.registry.builtInIds.flatMap { fleet.registry.adapter(it).families } + PrinterFamily.STOCK_U1 + PrinterFamily.EXPORT_ONLY)
        .distinct().filter { it != PrinterFamily.EXPORT_ONLY }
        .sortedBy { PrinterFamily.known.indexOf(it).let { i -> if (i < 0) 99 else i } } }
    var family by remember { mutableStateOf(families.first()) }
    val form = formFor(family)
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var compatibleProfileId by remember(family, compatibilityProfileId) {
        mutableStateOf(compatibilityProfileId ?: formFor(family).defaultProfile)
    }
    var step by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<DiscoveredPrinter?>(null) }
    var checked by remember { mutableStateOf<PrinterStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Printers found by looking at this network; the ones already added are left out.
    val nearby = remember { mutableStateListOf<DiscoveredPrinter>() }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var scanDone by remember { mutableStateOf(false) }
    fun hostOf(a: String) = runCatching { java.net.URI(if ("://" in a) a else "http://$a").host }.getOrNull() ?: a
    fun scanNetwork() {
        if (scanning) return
        scanning = true; scanDone = false; nearby.clear(); scanned = null
        scope.launch {
            val have = fleet.printers.values.map { hostOf(it.config.identity.address) }.toSet()
            // What the saved printers are, so one that answers on another address (Wi-Fi and cable) isn't offered as new.
            val saved = withContext(Dispatchers.IO) { fleet.printers.values.mapNotNull { LanScan.fingerprint(it.config.identity.address) }.toSet() }
            val seen = mutableSetOf<String>()
            LanScan.scan(LanScan.localHosts(), probe = { fleet.probe(it) }, onFound = { d ->
                val host = hostOf(d.address)
                val print = LanScan.fingerprint(d.address)
                if (host !in have && nearby.none { hostOf(it.address) == host } && (print == null || (print !in saved && seen.add(print)))) nearby += d
            }, onProgress = { n, total -> scanned = n to total })
            scanning = false; scanDone = true
        }
    }
    LaunchedEffect(Unit) { if (autoScan) scanNetwork() }

    fun adapterId(f: PrinterFamily) = if (f == PrinterFamily.STOCK_U1) "stock-u1" else if (f == PrinterFamily.EXPORT_ONLY) EXPORT_ONLY
        else fleet.registry.builtInIds.firstOrNull { f in fleet.registry.adapter(it).families } ?: ""

    fun draft() = PrinterConfig(PrinterIdentity(PrinterStore.newId(), name.ifBlank { found?.model ?: family.label },
        model.ifBlank { found?.model ?: family.label }, found?.suggestedFamily ?: family, found?.address ?: address.trim(), compatibleProfileId),
        adapterId(found?.suggestedFamily ?: family), secret.trim(), if (serial.isNotBlank()) mapOf("serial" to serial.trim()) else emptyMap())

    fun check() {
        if (form.needsAddress && address.isBlank()) { error = "Enter the printer's address."; return }
        busy = true; error = null; found = null; checked = null
        scope.launch {
            if (form.canProbe) {
                val r = runCatching { fleet.probe(address.trim()) }.getOrDefault(emptyList()).firstOrNull()
                if (r == null) error = "No printer answered at ${address.trim()}. Check the address, that the printer is on, and that this computer is on the same network (or your private network)."
                else {
                    found = r; family = r.suggestedFamily
                    compatibleProfileId = compatibilityProfileForDiscoveredPrinter(compatibilityProfileId, r.model)
                    if (name.isBlank()) name = r.model
                    if (model.isBlank()) model = r.model
                }
            } else {
                // No discovery for this family: open a session with what the user typed and read its status once. Read-only.
                val cfg = draft()
                checked = withContext(Dispatchers.IO) { runCatching { fleet.registry.open(cfg).use { it.status() } }.getOrElse { PrinterStatus(PrinterState.OFFLINE, ConnectionRoute.LAN, message = it.message) } }
                if (checked!!.state == PrinterState.OFFLINE || checked!!.state == PrinterState.ERROR) error = checked!!.message ?: "The printer didn't answer."
            }
            busy = false
        }
    }

    DialogWindow(onCloseRequest = { onDone(null) }, title = "Add printer", state = rememberDialogState(width = 1040.dp, height = 720.dp),
        onPreviewKeyEvent = { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDone(null); true } else false }) {
        NozzleTheme(fleet.settings.value.theme, fleet.settings.value.textScale) {
            val c = Nz.colors
            Column(Modifier.fillMaxSize().background(c.surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("Add printer", Nz.type.headline)
                Txt(when {
                    step == 1 -> "Step 1 of 2 · Find or choose the printer."
                    else -> "Step 2 of 2 · Confirm the physical printer connection."
                }, Nz.type.body, c.textMuted)
                if (step == 1) {
                    // Choosing a manual printer includes identifying its network endpoint. The next page only confirms
                    // its user-facing identity and any read-only compatibility association.
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Card(Modifier.fillMaxWidth(), raised = true) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Txt("Available printers", Nz.type.title)
                                    Txt(when {
                                        scanning -> scanned?.let { (n, t) -> "Looked at $n of $t addresses…" } ?: "Starting…"
                                        scanDone && nearby.isEmpty() -> "No new printer answered. Add it by address."
                                        scanDone -> "Choose a printer below."
                                        else -> "Search the local network."
                                    }, Nz.type.bodySmall, c.textMuted)
                                }
                                NzButton(if (scanning) "Looking…" else if (scanDone) "Refresh" else "Search", ::scanNetwork, kind = ButtonKind.SECONDARY, enabled = !scanning, testTag = "scan-network")
                            }
                            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                nearby.forEach { d ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(NzIcon.CHECK, Nz.status.ready, 18.dp)
                                        Column(Modifier.weight(1f)) { Txt(d.model, Nz.type.body); Txt(hostOf(d.address), Nz.type.bodySmall, c.textMuted) }
                                        NzButton("Select", {
                                            family = d.suggestedFamily; address = hostOf(d.address); found = d; checked = null; error = null
                                            if (name.isBlank()) name = d.model
                                            if (model.isBlank()) model = d.model
                                            compatibleProfileId = compatibilityProfileForDiscoveredPrinter(compatibilityProfileId, d.model)
                                        }, kind = if (found?.address == d.address) ButtonKind.PRIMARY else ButtonKind.QUIET, testTag = "use-found")
                                    }
                                }
                            }
                        }
                        Txt("Choose a printer type", Nz.type.title)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            families.chunked(4).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { f -> NzButton(f.label, {
                                        family = f; found = null; checked = null; error = null
                                        compatibleProfileId = compatibilityProfileId ?: formFor(f).defaultProfile
                                    }, kind = if (family == f) ButtonKind.PRIMARY else ButtonKind.SECONDARY) }
                                }
                            }
                        }
                        Txt(Glossary.terms[family.glossaryId]?.description ?: "", Nz.type.bodySmall, c.textMuted)
                        form.note?.let { Banner(it, BannerKind.INFO) }
                        if (family == PrinterFamily.STOCK_U1) Banner(
                            if (!fleet.stockHelperInstalled) "Stock U1 support is not installed. You can save the printer, but it stays offline until support is installed and enabled."
                            else if (!fleet.settings.value.stockU1Enabled) "Turn on optional Stock U1 support in Settings to connect."
                            else Glossary.explanations.getValue("stock-u1"), BannerKind.INFO)
                        if (form.needsAddress) {
                            Field("Printer address", address, { address = it; found = null; checked = null; error = null }, placeholder = "192.168.1.40 or printer.your-tailnet.ts.net",
                                hint = "Local or private-network address.", error = error, onSubmit = ::check)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (form.needsSerial) Field("Serial number", serial, { serial = it }, Modifier.weight(1f))
                                form.secretLabel?.let { Field(it, secret, { v -> secret = v }, Modifier.weight(1f), secret = true, hint = "Stored only on this computer.") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NzButton(if (busy) "Checking…" else if (form.canProbe) "Find printer" else "Check connection", ::check, kind = ButtonKind.SECONDARY,
                                    enabled = !busy && (!form.secretRequired || secret.isNotBlank()) && (!form.needsSerial || serial.isNotBlank()), testTag = "probe")
                                found?.let { RouteBadge(it.route) }
                                checked?.takeIf { it.state != PrinterState.OFFLINE && it.state != PrinterState.ERROR }?.let { Txt("Connected: ${it.state.label}", Nz.type.bodySmall, Nz.status.ready) }
                            }
                            found?.let { f -> Card(Modifier.fillMaxWidth(), raised = true) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(NzIcon.CHECK, Nz.status.ready, 20.dp); Txt("Found ${f.model}", Nz.type.title) }
                                Txt(f.evidence, Nz.type.bodySmall, c.textMuted)
                            } }
                        } else {
                            Banner("This printer is an export target and does not need a network connection.", BannerKind.INFO)
                            error?.let { Banner(it, BannerKind.DANGER) }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Txt(family.label, Nz.type.title)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (form.needsAddress) Field("Model", model, { model = it }, Modifier.weight(1f), placeholder = "Printer model")
                            Field("Name", name, { name = it }, Modifier.weight(1f), placeholder = "Workshop printer")
                        }
                        compatibleProfileId?.let { association -> Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Txt("Compatibility association", Nz.type.label)
                                    Txt(ProfileCatalog.byId(association)?.name ?: association, Nz.type.body, c.textMuted)
                                    Txt("Used only to check whether a sliced file matches this printer. It does not install or change your slicing setup.", Nz.type.bodySmall, c.textMuted)
                                }
                            }
                        } }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NzButton("Cancel", { onDone(null) }, kind = ButtonKind.QUIET)
                    Spacer(Modifier.weight(1f))
                    if (step == 1) {
                        val printerChosen = when {
                            family == PrinterFamily.EXPORT_ONLY -> true
                            form.canProbe -> found != null || family == PrinterFamily.STOCK_U1 && address.isNotBlank()
                            else -> address.isNotBlank() && (!form.secretRequired || secret.isNotBlank()) && (!form.needsSerial || serial.isNotBlank())
                        }
                        NzButton("Next", { step = 2 }, kind = ButtonKind.PRIMARY, enabled = printerChosen, testTag = "printer-wizard-next")
                    } else {
                        NzButton("Back", { step = 1; error = null }, kind = ButtonKind.SECONDARY, testTag = "printer-wizard-back")
                        val ready = when {
                            family == PrinterFamily.EXPORT_ONLY -> true
                            form.canProbe -> found != null || family == PrinterFamily.STOCK_U1 && address.isNotBlank()
                            else -> address.isNotBlank() && (!form.secretRequired || secret.isNotBlank()) && (!form.needsSerial || serial.isNotBlank())
                        }
                        NzButton("Add printer", {
                            val cfg = draft()
                            if (fleet.add(cfg)) {
                                if (state.prepare.printerId == null) state.requestWorkspacePrinter(cfg.identity.id)
                                onDone(cfg.identity.id)
                            } else error = "This printer could not be saved. Check that your Nozzle It All settings folder is writable, then try again."
                        }, kind = ButtonKind.PRIMARY, enabled = ready, testTag = "save-printer")
                    }
                }
            }
        }
    }
}

/**
 * Changes a saved printer's address, name, model, credentials and serial number. The printer keeps its id, so projects, the
 * compatibility association and the order on the Printers page stay as they are. A blank credential field keeps the saved one.
 */
@Composable
fun EditPrinterDialog(state: AppState, entry: PrinterEntry, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val fleet = state.fleet
    val old = entry.config
    val form = formFor(old.identity.family)
    var address by remember { mutableStateOf(old.identity.address) }
    var name by remember { mutableStateOf(old.identity.displayName) }
    var model by remember { mutableStateOf(old.identity.model) }
    var secret by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf(old.extras["serial"].orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf<PrinterStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun edited() = old.copy(
        identity = old.identity.copy(displayName = name.trim().ifBlank { old.identity.displayName }, model = model.trim().ifBlank { old.identity.model }, address = address.trim()),
        secret = secret.trim().ifEmpty { old.secret },
        extras = if (serial.isNotBlank()) old.extras + ("serial" to serial.trim()) else old.extras - "serial")

    fun check() {
        if (form.needsAddress && address.isBlank()) { error = "Enter the printer's address."; return }
        busy = true; error = null; checked = null
        scope.launch {
            val cfg = edited()
            checked = withContext(Dispatchers.IO) { runCatching { fleet.registry.open(cfg).use { it.status() } }.getOrElse { PrinterStatus(PrinterState.OFFLINE, ConnectionRoute.LAN, message = it.message) } }
            if (checked!!.state == PrinterState.OFFLINE || checked!!.state == PrinterState.ERROR) error = checked!!.message ?: "The printer didn't answer."
            busy = false
        }
    }

    DialogWindow(onCloseRequest = onDone, title = "Edit printer", state = rememberDialogState(width = 600.dp, height = 640.dp),
        onPreviewKeyEvent = { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDone(); true } else false }) {
        NozzleTheme(fleet.settings.value.theme, fleet.settings.value.textScale) {
            val c = Nz.colors
            Column(Modifier.fillMaxSize().background(c.surface).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Txt("Edit printer", Nz.type.headline)
                Txt("${old.identity.family.label}. Its projects and slicing profile stay as they are.", Nz.type.body, c.textMuted)
                form.note?.let { Banner(it, BannerKind.INFO) }
                if (form.needsAddress) {
                    Field("Printer address", address, { address = it; checked = null; error = null }, placeholder = "192.168.1.40 or printer.your-tailnet.ts.net",
                        hint = "At home, the printer's local address. Away from home, its address on your private network (for example Tailscale).", error = error, onSubmit = ::check)
                    if (form.needsSerial) Field("Serial number", serial, { serial = it; checked = null })
                    form.secretLabel?.let { Field(it, secret, { v -> secret = v; checked = null }, secret = true, placeholder = if (old.secret.isNotEmpty()) "Saved - leave blank to keep it" else "",
                        hint = "Stored only on this computer, in a file only you can read.") }
                    NzButton(if (busy) "Checking…" else "Check connection", ::check, kind = ButtonKind.SECONDARY, enabled = !busy, testTag = "edit-probe")
                    checked?.takeIf { it.state != PrinterState.OFFLINE && it.state != PrinterState.ERROR }?.let { s -> Banner("Connected: ${s.state.label}.", BannerKind.SUCCESS) }
                    Field("Model", model, { model = it }, placeholder = "Snapmaker U1, Prusa MK4S, Bambu Lab P1S…")
                }
                Field("Name", name, { name = it }, placeholder = "Workshop printer")
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    NzButton("Cancel", onDone, kind = ButtonKind.QUIET)
                    // Saving does not need the printer to answer: the address may be one the printer will have later.
                    NzButton("Save", { fleet.update(edited()); onDone() }, kind = ButtonKind.PRIMARY, enabled = !busy && (!form.needsAddress || address.isNotBlank()), testTag = "save-edited-printer")
                }
            }
        }
    }
}


/** The operator explicitly links a saved physical printer to the machine preset under review. */
@Composable
internal fun AssociatePrinterProfile(state: AppState, entry: PrinterEntry, profileId: String?) {
    val profile = ProfileCatalog.byId(profileId) ?: return
    if (entry.config.identity.profileId != null) return
    val scope = rememberCoroutineScope()
    var reviewing by remember(entry, profile.id) { mutableStateOf(false) }
    var problem by remember(entry, profile.id) { mutableStateOf<String?>(null) }
    NzButton("Link machine profile…", { reviewing = true }, enabled = state.fleet.canAssociatePrinterProfile(entry), testTag = "link-machine-profile")
    problem?.let { Banner(it, BannerKind.WARNING) }
    if (reviewing) ConfirmDialog(
        title = "Link machine profile?",
        body = "Link ${entry.config.identity.displayName} to ${profile.vendor} · ${profile.model}.",
        detail = "Check that this is the physical printer and configuration you intend to use. The association is used when reviewing prepared files.",
        acknowledgement = "I verified the printer matches this machine profile.",
        confirmLabel = "Link profile",
        destructive = false,
        onConfirm = {
            reviewing = false
            scope.launch {
                try {
                    state.fleet.associatePrinterProfile(entry, profile.id)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    problem = error.message ?: "The machine profile could not be linked."
                }
            }
        },
        onDismiss = { reviewing = false },
    )
}
