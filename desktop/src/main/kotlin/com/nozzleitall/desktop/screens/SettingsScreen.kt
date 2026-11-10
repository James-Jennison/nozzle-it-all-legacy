package com.nozzleitall.desktop.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.migration.LegacySettingsMigration
import com.nozzleitall.desktop.settings.Choice
import com.nozzleitall.desktop.settings.NzSelect
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.Glossary
import com.nozzleitall.printer.external.AdapterAccount
import com.nozzleitall.printer.external.AdapterAccountState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(state: AppState) {
    val c = Nz.colors
    val fleet = state.fleet
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeader("Settings")
        if (fleet.settingsLoadProblem.value) Banner(
            "Saved settings could not be read. Defaults are shown and the original file is preserved. Repair or restore the settings file before changing choices.",
            BannerKind.WARNING)
        var tab by remember { mutableStateOf(if (state.meshySettingsReturn) SettingsTab.INTEGRATIONS else SettingsTab.GENERAL) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsTab.entries.forEach { t -> NzButton(t.label, { tab = t }, kind = if (t == tab) ButtonKind.PRIMARY else ButtonKind.SECONDARY, testTag = "settings-tab-${t.name.lowercase()}") }
        }
        if (tab == SettingsTab.GENERAL) {
        Card(Modifier.fillMaxWidth()) {
            Txt("Appearance", Nz.type.title)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "Match system", "dark" to "Dark", "light" to "Light").forEach { (k, label) ->
                    NzButton(label, { fleet.updateTheme(k) },
                        kind = if (fleet.settings.value.theme == k) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                }
            }
            Txt("Text size", Nz.type.label)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f, 1.25f, 1.5f, 2f).forEach { scale ->
                    NzButton("${(scale * 100).toInt()}%", { fleet.updateTextSize(scale) },
                        modifier = Modifier.semantics { contentDescription = "Text size ${(scale * 100).toInt()} percent" },
                        kind = if (fleet.settings.value.textScale == scale) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                }
            }
            if (fleet.textSizeSaveProblem.value) Banner("Text size could not be saved. The previous size is still active; check this computer's storage and try again.", BannerKind.DANGER)
            if (fleet.themeSaveProblem.value) Banner("Appearance could not be saved. The previous choice is still active; check this computer's storage and try again.", BannerKind.DANGER)
        }
        Card(Modifier.fillMaxWidth()) {
            Txt("Notifications", Nz.type.title)
            Toggle("Print milestones", fleet.settings.value.printMilestones, { fleet.updatePrintMilestones(it) },
                "Notify when a print starts, reaches 25%, 50%, or 75%, or the printer confirms it finished.")
            Toggle("Queue and problem notifications", fleet.settings.value.notifications, { fleet.updateNotifications(it) },
                "Notify when a printer with queued work is free or a print is stopped or fails.")
            if (fleet.notificationsSaveProblem.value) Banner("Notifications could not be saved. The previous choice is still active; check this computer's storage and try again.", BannerKind.DANGER)
        }
        Card(Modifier.fillMaxWidth()) {
            Txt("Slicer setup", Nz.type.title)
            val setup = fleet.settings.value.slicingSetup
            Txt(setup?.let { "${state.prepare.slicingMachineLabel} · ${state.prepare.nozzleToolheadLabel}" }
                ?: "No machine setup has been finished.", Nz.type.body, c.textMuted)
            NzButton("Run setup wizard", state::reopenFirstRun, kind = ButtonKind.SECONDARY, testTag = "settings-setup-wizard")
        }
        }
        if (tab == SettingsTab.CONNECTIONS) {
        Card(Modifier.fillMaxWidth()) {
            Txt("Away from home", Nz.type.title)
            Txt(Glossary.explanations.getValue("remote-access"), Nz.type.body, c.textMuted)
        }
        Card(Modifier.fillMaxWidth()) {
            Txt("Stock U1 support (optional)", Nz.type.title)
            Txt(Glossary.explanations.getValue("stock-u1"), Nz.type.body, c.textMuted)
            val installed = fleet.stockHelperInstalled
            if (!installed) Banner("Stock U1 support isn't installed on this computer. PAXX printers don't need it.", BannerKind.INFO)
            Toggle("Use Stock U1 support", fleet.settings.value.stockU1Enabled, { fleet.setStockEnabled(it) },
                if (installed) "Starts only when a Stock U1 printer is opened." else "Install nozzle-stock-u1-adapter to turn this on.")
            if (fleet.stockSaveProblem.value) Banner("Stock U1 support could not be saved. The previous choice is still active; check this computer's storage and try again.", BannerKind.DANGER)
            if (fleet.settings.value.stockU1Enabled && installed) StockAccountPanel(state)
        }
        FilamentProfilesPanel(state)
        ConnectorPanel(state)
        }
        if (tab == SettingsTab.INTEGRATIONS) MeshyIntegrationSettings(state)
        if (tab == SettingsTab.DATA) {
        Card(Modifier.fillMaxWidth()) {
            Txt("Your data", Nz.type.title)
            Txt(Glossary.explanations.getValue("cloud"), Nz.type.body, c.textMuted)
            listOf("Settings and printers" to state.paths.config, "Projects" to state.paths.projects, "Logs" to state.paths.logs)
                .forEach { (label, dir) -> Row { Txt(label, Nz.type.body, modifier = Modifier.width(220.dp)); Txt(dir.absolutePath, Nz.type.metricSmall, c.textMuted) } }
            Txt("Nozzle It All keeps its own folders. It reads another slicer's settings only after you choose an import source, and never changes that source.", Nz.type.bodySmall, c.textMuted)
        }
        LegacySettingsPanel(state)
        ArchivedProcessPanel(state)
        Card(Modifier.fillMaxWidth()) {
            Txt("NIA updates", Nz.type.title)
            Txt(com.nozzleitall.desktop.updates.NiaUpdatePolicy.status, Nz.type.body, c.textMuted)
        }
        Card(Modifier.fillMaxWidth()) {
            Txt("About", Nz.type.title)
            Txt("${Glossary.PRODUCT_DESKTOP} ${state.version}", Nz.type.body)
            Txt("Free software under the GNU AGPL 3.0 or later. Detailed credits and dependency licences are available from the About window and Licences link.", Nz.type.bodySmall, c.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NzButton("Source code", { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://github.com/James-Jennison/nozzle-it-all")) } }, kind = ButtonKind.QUIET)
                NzButton("Licences", { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://nozzleitall.com/open-source/")) } }, kind = ButtonKind.QUIET)
            }
        }
        }
    }
}

@Composable
private fun MeshyIntegrationSettings(state: AppState) {
    val keychain = remember { com.nozzleitall.desktop.prepare.MeshySecretService() }
    val scope = rememberCoroutineScope()
    var keyAvailable by remember { mutableStateOf<Boolean?>(null) }
    var keyInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun changeKey(action: () -> Unit) {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            try {
                keyAvailable = runInterruptible(Dispatchers.IO) {
                    action()
                    keychain.hasAvailableKey()
                }
                editing = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                problem = "The system keychain could not confirm the change."
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(keychain) {
        keyAvailable = runInterruptible(Dispatchers.IO) { keychain.hasAvailableKey() }
    }
    Card(Modifier.fillMaxWidth()) {
        Txt("Meshy", Nz.type.title)
        Txt("Meshy text-to-3D uses your API key and credits. The key is stored in the Linux system keychain.", Nz.type.bodySmall)
        Txt(when (keyAvailable) {
            true -> "Meshy key is saved."
            false -> "No Meshy key is available."
            null -> "Checking the system keychain…"
        }, Nz.type.bodySmall)
        problem?.let { Banner(it, BannerKind.WARNING) }
        if (editing) {
            Field("New Meshy API key", keyInput, { keyInput = it.take(512) }, Modifier.fillMaxWidth(), secret = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NzButton("Save key", {
                    val input = keyInput.toCharArray()
                    keyInput = ""
                    changeKey {
                        try { keychain.save(input) } finally { input.fill('\u0000') }
                    }
                }, enabled = !busy && keyInput.isNotBlank(), kind = ButtonKind.PRIMARY)
                NzButton("Cancel", { keyInput = ""; editing = false }, enabled = !busy, kind = ButtonKind.QUIET)
            }
        } else if (keyAvailable != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NzButton(if (keyAvailable == true) "Replace key" else "Add key", { editing = true }, enabled = !busy)
                if (keyAvailable == true) NzButton("Remove key", { remove = true }, enabled = !busy, kind = ButtonKind.QUIET)
            }
        }
        if (state.meshySettingsReturn) NzButton("Return to Meshy review", state::returnToMeshyReview,
            enabled = !busy, kind = ButtonKind.SECONDARY, testTag = "meshy-return-review")
    }
    if (remove) ConfirmDialog("Remove Meshy key?", "Remove the saved key from this computer’s system keychain.",
        "Remove key", destructive = true, onDismiss = { remove = false }, onConfirm = {
            remove = false
            changeKey(keychain::remove)
        })
}

@Composable
private fun LegacySettingsPanel(state: AppState) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<com.nozzleitall.desktop.migration.LegacySettingsMigration.Source>?>(null) }
    var prepared by remember { mutableStateOf<com.nozzleitall.desktop.migration.LegacySettingsMigration.Prepared?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Txt("Import existing slicer settings", Nz.type.title)
        Txt("Copy compatible filament and process presets, recent projects and appearance from NIA Desktop, Hybrid Orca, Snapmaker Orca or OrcaSlicer. " +
            "Credentials, updater state, caches, source configuration and unrecognized files are excluded and listed in the migration report. A process preset is activated only when its inheritance resolves to one bundled NIA printer. Sanitized compatible presets and verified recent projects are archived; the source is never changed.",
            Nz.type.body, c.textMuted)
        notice?.let { Banner(it, if (it.startsWith("Import failed") || it.startsWith("Couldn't")) BannerKind.WARNING else BannerKind.INFO) }
        if (sources == null) NzButton(if (busy) "Looking…" else "Find existing settings", {
            busy = true; notice = null
            scope.launch {
                sources = withContext(Dispatchers.IO) { state.legacySettings.discover() }
                busy = false
                if (sources!!.isEmpty()) notice = "No importable Orca-family settings were found."
            }
        }, kind = ButtonKind.SECONDARY, enabled = !busy)
        sources?.forEach { source ->
            NzButton("${source.label} — ${source.root.absolutePath}", {
                busy = true; notice = null; prepared = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { state.legacySettings.prepare(source) } }
                        .onSuccess { prepared = it }
                        .onFailure { notice = "Couldn't review this source: ${it.message}" }
                    busy = false
                }
            }, kind = ButtonKind.QUIET, enabled = !busy)
        }
        prepared?.let { review ->
            Txt("Review: ${review.filaments.size} filament preset${if (review.filaments.size == 1) "" else "s"}; " +
                "${review.profiles.count { it.category == "process" }} process and ${review.profiles.count { it.category == "machine" }} machine preset${if (review.profiles.count { it.category == "machine" } == 1) "" else "s"}; " +
                "${review.projects.size} recent project${if (review.projects.size == 1) "" else "s"}; ${if (review.theme == null) "no compatible appearance preference" else "${review.theme} appearance"}; " +
                "${review.sanitizedCount} sanitized; ${review.skipped.size} skipped.", Nz.type.bodySmall)
            if (review.sanitizedCount > 0 || review.skipped.isNotEmpty())
                Banner("Some source files require sanitizing or cannot be imported. The migration report records every skipped item before anything is activated.", BannerKind.WARNING)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NzButton(if (busy) "Importing…" else "Import compatible data", {
                    busy = true; notice = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { state.legacySettings.apply(review) } }
                            .onSuccess { result ->
                                notice = "Imported ${result.importedFilaments} filament, ${result.importedProcesses} process preset${if (result.importedProcesses == 1) "" else "s"}, " +
                                    "${result.importedProjects} project${if (result.importedProjects == 1) "" else "s"} and ${result.importedPreferences} preference${if (result.importedPreferences == 1) "" else "s"}. " +
                                    "${result.preservedProfiles} unresolved printer/process preset${if (result.preservedProfiles == 1) "" else "s"} preserved. " +
                                    "Migration report: ${result.report.absolutePath}"
                                prepared = null
                            }
                            .onFailure { notice = "Import failed without changing the source: ${it.message}" }
                        busy = false
                    }
                }, kind = ButtonKind.PRIMARY, enabled = !busy && (review.profiles.isNotEmpty() || review.projects.isNotEmpty() || review.theme != null))
                NzButton("Cancel", { prepared = null }, kind = ButtonKind.QUIET, enabled = !busy)
            }
        }
    }
}

@Composable
private fun ArchivedProcessPanel(state: AppState) {
    val scope = rememberCoroutineScope()
    val profileId = state.prepare.profileId
    val machine = state.prepare.machineVariant()
    val library = state.prepare.library()
    val bases = machine?.processes.orEmpty().mapNotNull { id -> library?.processes?.get(id) }
    var candidates by remember { mutableStateOf<List<LegacySettingsMigration.ArchivedProcess>?>(null) }
    var selected by remember { mutableStateOf("") }
    var base by remember(profileId, machine?.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Txt("Link a preserved Process preset", Nz.type.title)
        Txt("Choose the target printer and nozzle in Prepare. Linking creates a Process preset for that setup and keeps the original archived settings.",
            Nz.type.body, Nz.colors.textMuted)
        notice?.let { Banner(it, if (it.startsWith("Couldn't")) BannerKind.WARNING else BannerKind.INFO) }
        if (candidates == null) NzButton(if (busy) "Looking…" else "Find preserved Process presets", {
            busy = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { state.legacySettings.archivedProcesses() } }
                    .onSuccess {
                        candidates = it
                        notice = if (it.isEmpty()) "No unresolved Process presets were found in completed migrations." else null
                    }
                    .onFailure { notice = "Couldn't read preserved Process presets: ${it.message}" }
                busy = false
            }
        }, enabled = !busy, kind = ButtonKind.SECONDARY)
        val available = candidates.orEmpty()
        if (available.isNotEmpty()) {
            Txt("Preserved preset", Nz.type.label)
            NzSelect("Preserved Process preset", available.map { Choice(it.sha256, "${it.name} · ${it.source}") }, selected) {
                if (!busy) selected = it
            }
            if (machine == null || bases.isEmpty()) {
                Txt("Choose a printer and nozzle with a bundled Process library in Prepare.", Nz.type.body, Nz.colors.textMuted)
            } else {
                Txt("Base Process for ${machine.name}", Nz.type.label)
                NzSelect("Base Process", bases.map { Choice(it.id, it.displayLabel) }, base) { if (!busy) base = it }
                val archived = available.firstOrNull { it.sha256 == selected }
                NzButton(if (busy) "Linking…" else "Link Process preset", {
                    if (archived != null) {
                        val targetBase = base
                        busy = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    state.legacySettings.linkArchivedProcess(archived, profileId, machine.id, targetBase)
                                }
                            }.onSuccess {
                                notice = "Linked ${it.name}. Applied ${it.applied} settings and skipped ${it.skipped}. Select the preset under Process in Prepare."
                            }.onFailure { notice = "Couldn't link this Process preset: ${it.message}" }
                            busy = false
                        }
                    }
                }, enabled = !busy && archived != null && bases.any { it.id == base }, kind = ButtonKind.PRIMARY)
            }
        }
    }
}

@Composable
private fun FilamentProfilesPanel(state: AppState) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    val catalog = state.filamentCatalog
    var key by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf<Boolean?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(catalog) { configured = withContext(Dispatchers.IO) { catalog.hasCredential() } }
    Card(Modifier.fillMaxWidth()) {
        Txt("3D Filament Profiles", Nz.type.title)
        Txt("Search 3dfilamentprofiles.com from a filament slot and keep the actual product brand in your NIA preset. " +
            "The API key is stored by this computer's Secret Service and is never written to NIA settings or projects.", Nz.type.body, c.textMuted)
        if (!catalog.credentials.available) Banner("This computer's native Secret Service is unavailable. Catalog credentials cannot be saved here.", BannerKind.WARNING)
        notice?.let { Banner(it, if (it.startsWith("Couldn't")) BannerKind.WARNING else BannerKind.INFO) }
        Field("API key", key, { key = it }, secret = true,
            placeholder = if (configured == true) "Saved in the system keychain" else "Paste API key")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NzButton(if (configured == true) "Replace key" else "Save key", {
                val candidate = key
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { catalog.saveCredential(candidate) }
                    key = ""; configured = withContext(Dispatchers.IO) { catalog.hasCredential() }
                    notice = if (saved && configured == true) "API key saved in the system keychain." else "Couldn't save the API key in the system keychain."
                }
            }, kind = ButtonKind.SECONDARY, enabled = catalog.credentials.available && key.isNotBlank())
            if (configured == true) NzButton("Remove key", {
                scope.launch {
                    val removed = withContext(Dispatchers.IO) { catalog.clearCredential() }
                    configured = withContext(Dispatchers.IO) { catalog.hasCredential() }
                    notice = if (removed && configured == false) "API key removed from the system keychain." else "Couldn't remove the API key from the system keychain."
                }
            }, kind = ButtonKind.QUIET)
        }
    }
}

/** The groups on the Settings page. */
private enum class SettingsTab(val label: String) { GENERAL("General"), CONNECTIONS("Connections"), INTEGRATIONS("Integrations"), DATA("Your data & about") }

@Composable
private fun ConnectorPanel(state: AppState) {
    val c = Nz.colors
    var on by remember { mutableStateOf(state.connector.running) }
    var code by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Txt("Nozzle It All Web on this computer", Nz.type.title)
        Txt("Browsers limit what a web page can reach on your network. Turn this on to let Nozzle It All Web, open in a browser on this computer, " +
            "use the printers you've added here. It only listens on this computer, only answers Nozzle's own web app, and only after you pair the browser.", Nz.type.body, c.textMuted)
        problem?.let { Banner(it, BannerKind.WARNING) }
        Toggle("Allow Nozzle It All Web to use these printers", on, { v ->
            problem = null
            if (v) runCatching { state.connector.start() }.onFailure { problem = "Couldn't start: ${it.message}. Another program may be using port ${state.connector.port}." }
            else { state.connector.stop(); code = null }
            on = state.connector.running
        })
        if (on) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NzButton("Pair a browser", { code = state.connector.newPairingCode() }, kind = ButtonKind.SECONDARY)
            NzButton("Forget paired browsers (${state.connector.pairedCount})", { state.connector.forgetAll(); code = null }, kind = ButtonKind.QUIET)
        }
        code?.let { Txt("Pairing code: $it", Nz.type.metric); Txt("Enter it in Nozzle It All Web under Settings within two minutes. It works once.", Nz.type.bodySmall, c.textMuted) }
    }
}

@Composable
private fun StockAccountPanel(state: AppState) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf<AdapterAccount?>(null) }
    var pasted by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val client = state.fleet.stockAccount
    LaunchedEffect(client) { account = client?.let { a -> withContext(Dispatchers.IO) { runCatching { a.account() }.onFailure { problem = it.message }.getOrNull() } } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("Snapmaker account", Nz.type.label)
        problem?.let { Banner("Stock U1 support isn't responding: $it. PAXX printers are unaffected.", BannerKind.WARNING) }
        val a = account
        when (a?.state) {
            AdapterAccountState.SIGNED_IN -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(a.detail, Nz.type.body, modifier = Modifier.weight(1f))
                NzButton("Sign out", { scope.launch { account = withContext(Dispatchers.IO) { client?.signOut() } } }, kind = ButtonKind.SECONDARY)
            }
            null -> Txt("Checking…", Nz.type.body, c.textMuted)
            else -> {
                Txt(a.detail.ifBlank { "Not signed in." }, Nz.type.body, c.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NzButton("Sign in with your browser", { scope.launch {
                        account = withContext(Dispatchers.IO) { client?.beginSignIn() }
                        account?.signInUrl?.let { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(it)) } }
                    } }, kind = ButtonKind.SECONDARY)
                }
                if (a.state == AdapterAccountState.SIGNING_IN || a.state == AdapterAccountState.EXPIRED) {
                    Field("Sign-in token from Snapmaker's page", pasted, { pasted = it }, secret = true,
                        hint = "After signing in, Snapmaker shows a page with your sign-in details. Copy all of it and paste it here.")
                    NzButton("Finish signing in", { scope.launch { account = withContext(Dispatchers.IO) { client?.completeSignIn(pasted) }; pasted = "" } },
                        kind = ButtonKind.PRIMARY, enabled = pasted.isNotBlank())
                }
            }
        }
    }
}
