package com.nozzleitall.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.nozzleitall.desktop.*
import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SetupStep(val label: String, val title: String, val description: String) {
    WELCOME(
        "Welcome",
        "Welcome to Nozzle It All",
        "Create a slicing setup, open an existing project, or bring compatible settings with you.",
    ),
    MACHINE(
        "Machine",
        "Choose your machine",
        "Install an offline slicing configuration, then choose its nozzle and toolheads. This does not connect a physical printer.",
    ),
    FILAMENT(
        "Filaments",
        "Choose compatible filaments",
        "Install the presets you use. Vendor, material type, and material stay grouped the way established slicers present them.",
    ),
    PROCESS(
        "Process",
        "Choose a process default",
        "Select the normal starting process for this machine. Advanced settings remain available in Prepare.",
    ),
    CONNECT(
        "Connect",
        "Connect a physical printer — optional",
        "Discover a printer or enter its address. Skip this step to slice and export completely offline.",
    ),
    REVIEW(
        "Review",
        "Review and finish",
        "Confirm the installed slicing setup and any optional physical-printer connection before saving.",
    ),
}

internal fun setupWizardSteps(): List<String> = SetupStep.entries.map { it.label }

internal fun setupWizardReachableSteps(current: SetupStep): List<String> =
    SetupStep.entries.filter { it.ordinal <= current.ordinal }.map { it.label }

@Composable
private fun SetupStepRail(current: SetupStep, onStep: (SetupStep) -> Unit) {
    val c = Nz.colors
    Column(
        Modifier.width(224.dp).fillMaxHeight().background(c.surfaceSunken).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.foundation.Image(painterResource("brand/mark-violet.svg"), null, Modifier.size(36.dp))
            Column {
                Txt(Glossary.PRODUCT_FAMILY, Nz.type.title)
                Txt("Setup assistant", Nz.type.bodySmall, c.textMuted)
            }
        }
        Spacer(Modifier.height(18.dp))
        SetupStep.entries.forEach { item ->
            val reached = item.ordinal <= current.ordinal
            val label = when {
                item == current -> "${item.ordinal + 1}. ${item.label}"
                item.ordinal < current.ordinal -> "✓  ${item.label}"
                else -> "${item.ordinal + 1}. ${item.label}"
            }
            NzButton(
                label,
                { onStep(item) },
                Modifier.fillMaxWidth(),
                kind = if (item == current) ButtonKind.PRIMARY else ButtonKind.QUIET,
                enabled = reached,
                accessibilityDescription = when {
                    item == current -> "${item.label}, current setup step"
                    item.ordinal < current.ordinal -> "${item.label}, completed setup step"
                    else -> "${item.label}, upcoming setup step"
                },
            )
        }
        Spacer(Modifier.weight(1f))
        Txt("You can close setup and return later from Settings.", Nz.type.bodySmall, c.textMuted)
    }
}

@Composable
private fun FilamentInstallRow(
    filament: FilamentLibrary.Entry,
    installed: Boolean,
    required: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val c = Nz.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (installed) c.surfaceRaised else c.surface)
            .border(1.dp, if (installed) c.accent else c.line, shape)
            .toggleable(installed, enabled = !required, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = "${filament.displayName}, ${if (installed) "installed" else "not installed"}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(20.dp).background(if (installed) c.accent else c.surfaceSunken, RoundedCornerShape(4.dp))
                .border(1.dp, if (installed) c.accent else c.lineStrong, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (installed) Icon(NzIcon.CHECK, c.onAccent, 14.dp)
        }
        Column(Modifier.weight(1f)) {
            Txt(filament.displayName, Nz.type.bodySmall)
            if (required) Txt("Starting preset", Nz.type.bodySmall, c.textMuted)
        }
    }
}

internal fun resizedFilamentProfiles(current: Map<Int, String>, toolheads: Int, defaultProfileId: String?): Map<Int, String> {
    val count = toolheads.coerceIn(1, 8)
    return (1..count).mapNotNull { slot ->
        (current[slot] ?: defaultProfileId)?.let { slot to it }
    }.toMap()
}

internal fun setupFilamentProfileKey(profileId: String, machine: PrinterLibrary.Machine?): String =
    machine?.let { "$profileId@${it.id}" } ?: profileId

private val vendorWhitespace = Regex("\\s+")

internal fun setupFilamentVendorKey(vendor: String): String =
    vendor.trim().replace(vendorWhitespace, " ").ifBlank { "Other" }.lowercase()

private fun setupFilamentVendorLabel(vendor: String): String =
    vendor.trim().replace(vendorWhitespace, " ").ifBlank { "Other" }.let { label ->
        when (setupFilamentVendorKey(label)) {
            "generic" -> "Generic"
            "other" -> "Other"
            else -> label
        }
    }

private fun setupFilamentVendorLabel(entries: List<FilamentLibrary.Entry>): String {
    val labels = entries.map { setupFilamentVendorLabel(it.vendor) }
    return labels.firstOrNull { label -> label.any(Char::isUpperCase) && label.any(Char::isLowerCase) }
        ?: labels.first()
}

/** Bundled machine presets plus NIA-owned/imported brand presets: machine default, brands, then generic fallbacks. */
internal fun setupCompatibleFilaments(
    profileId: String,
    machine: PrinterLibrary.Machine?,
    available: List<FilamentLibrary.Entry>? = null,
): List<FilamentLibrary.Entry> =
    (available ?: FilamentLibrary.forProfile(setupFilamentProfileKey(profileId, machine))).distinctBy { it.id }.sortedWith(
        compareBy<FilamentLibrary.Entry> {
            when {
                it.id == machine?.defaultFilament -> 0
                setupFilamentVendorKey(it.vendor) == "generic" -> 2
                else -> 1
            }
        }.thenBy { it.displayName.lowercase() },
    )

internal data class FilamentVendorGroup(val key: String, val vendor: String, val filaments: List<FilamentLibrary.Entry>)

internal data class FilamentMaterialTypeGroup(val key: String, val materialType: String, val filaments: List<FilamentLibrary.Entry>)

internal fun setupFilamentMaterialTypeGroups(filaments: List<FilamentLibrary.Entry>): List<FilamentMaterialTypeGroup> =
    filaments.groupBy { it.type.trim().ifBlank { "Other" }.lowercase() }
        .map { (key, entries) ->
            val labels = entries.map { it.type.trim().ifBlank { "Other" } }.distinct()
            val label = labels.firstOrNull { it.any(Char::isUpperCase) } ?: labels.first()
            FilamentMaterialTypeGroup(key, label, entries)
        }
        .sortedWith(compareBy<FilamentMaterialTypeGroup> { if (it.key == "other") 1 else 0 }
            .thenBy { it.materialType.lowercase() })

/** Groups the already-compatible presets like mainstream slicers: machine/default vendor, brands, then Generic. */
internal fun setupFilamentVendorGroups(filaments: List<FilamentLibrary.Entry>): List<FilamentVendorGroup> =
    filaments.groupBy { setupFilamentVendorKey(it.vendor) }
        .map { (key, entries) ->
            val label = setupFilamentVendorLabel(entries)
            FilamentVendorGroup(key, label, entries.map { entry ->
                if (entry.vendor == label) entry else entry.copy(vendor = label)
            })
        }

internal data class MachineVendorGroup(val key: String, val vendor: String, val machines: List<PrinterProfileInfo>)

/** Groups compatible machine presets by manufacturer, matching established slicers' setup assistants. */
internal fun setupMachineVendorGroups(profiles: List<PrinterProfileInfo>): List<MachineVendorGroup> =
    profiles.groupBy { setupFilamentVendorKey(it.vendor) }
        .map { (key, entries) ->
            val labels = entries.map { setupFilamentVendorLabel(it.vendor) }
            val vendor = labels.firstOrNull { it.any(Char::isUpperCase) && it.any(Char::isLowerCase) } ?: labels.first()
            MachineVendorGroup(key, vendor, entries.sortedBy { it.model.lowercase() })
        }

internal fun setupDefaultFilament(
    machine: PrinterLibrary.Machine?,
    compatibleFilaments: List<FilamentLibrary.Entry>,
): String? = machine?.defaultFilament ?: compatibleFilaments.firstOrNull()?.id

internal fun setupResolvedFilamentProfiles(
    current: Map<Int, String>,
    toolheads: Int,
    machine: PrinterLibrary.Machine?,
    compatibleFilaments: List<FilamentLibrary.Entry>,
): Map<Int, String> = resizedFilamentProfiles(current, toolheads, setupDefaultFilament(machine, compatibleFilaments))

internal fun setupInstalledFilamentIds(
    selected: Map<String, Boolean>,
    assignments: Map<Int, String>,
): Set<String> = selected.filterValues { it }.keys + assignments.values

internal data class FilamentCatalogRequest(val generation: Long, val slot: Int, val profileKey: String)

internal fun catalogRequestIsCurrent(
    request: FilamentCatalogRequest,
    generation: Long,
    slot: Int?,
    profileKey: String?,
): Boolean = request.generation == generation && request.slot == slot && request.profileKey == profileKey

internal suspend fun searchFilamentCatalog(
    query: String,
    search: (String) -> com.nozzleitall.desktop.materials.FilamentProfilesReply,
): com.nozzleitall.desktop.materials.FilamentProfilesReply = runCatching {
    withContext(Dispatchers.IO) { search(query) }
}.getOrElse { error ->
    com.nozzleitall.desktop.materials.FilamentProfilesReply(error = error.message ?: "The catalog search failed. Try again.")
}

internal data class SetupWizardDraft(
    val profileId: String,
    val setupName: String,
    val customOffline: Boolean,
    val nozzle: String?,
    val processId: String?,
    val customTools: Int,
    val customNozzle: String,
    val bedWidth: String,
    val bedDepth: String,
    val buildHeight: String,
    val gcodeFlavor: String,
    val filamentProfiles: Map<Int, String>,
    val installedFilamentProfiles: Set<String>,
)

/** Re-entry starts from the exact persisted slicing setup; defaults are applied only after an explicit machine change. */
internal fun setupWizardDraft(existing: SlicingSetup?): SetupWizardDraft = SetupWizardDraft(
    profileId = existing?.profileId ?: ProfileCatalog.DEFAULT_ID,
    setupName = existing?.name.orEmpty(),
    customOffline = existing?.customOffline ?: false,
    nozzle = existing?.nozzle,
    processId = existing?.processId,
    customTools = existing?.toolheads?.coerceIn(1, 8) ?: 1,
    customNozzle = existing?.nozzle ?: "0.4",
    bedWidth = existing?.bedWidthMm?.toString() ?: "250",
    bedDepth = existing?.bedDepthMm?.toString() ?: "250",
    buildHeight = existing?.heightMm?.toString() ?: "250",
    gcodeFlavor = existing?.gcodeFlavor ?: "klipper",
    filamentProfiles = existing?.filamentProfiles.orEmpty(),
    installedFilamentProfiles = existing?.installedFilamentProfiles.orEmpty(),
)

/** Installs a slicing configuration first. Connecting a physical printer is a later, optional step. */
@Composable
fun SetupWizardDialog(state: AppState) {
    val existing = state.fleet.settings.value.slicingSetup
    val initial = remember(existing) { setupWizardDraft(existing) }
    var step by remember { mutableStateOf(SetupStep.WELCOME) }
    var profileId by remember { mutableStateOf(initial.profileId) }
    var profileQuery by remember { mutableStateOf("") }
    var selectedMachineVendor by remember { mutableStateOf<String?>(null) }
    var setupName by remember { mutableStateOf(initial.setupName) }
    var customOffline by remember { mutableStateOf(initial.customOffline) }
    var nozzle by remember { mutableStateOf(initial.nozzle) }
    var processId by remember { mutableStateOf(initial.processId) }
    var customTools by remember { mutableStateOf(initial.customTools) }
    var customNozzle by remember { mutableStateOf(initial.customNozzle) }
    var bedWidth by remember { mutableStateOf(initial.bedWidth) }
    var bedDepth by remember { mutableStateOf(initial.bedDepth) }
    var buildHeight by remember { mutableStateOf(initial.buildHeight) }
    var gcodeFlavor by remember { mutableStateOf(initial.gcodeFlavor) }
    val filamentProfiles = remember { mutableStateMapOf<Int, String>().apply { putAll(initial.filamentProfiles) } }
    val installedFilaments = remember { mutableStateMapOf<String, Boolean>().apply {
        initial.installedFilamentProfiles.forEach { put(it, true) }
        initial.filamentProfiles.values.forEach { put(it, true) }
    } }
    val scope = rememberCoroutineScope()
    var importSources by remember { mutableStateOf<List<com.nozzleitall.desktop.migration.LegacySettingsMigration.Source>?>(null) }
    var importPrepared by remember { mutableStateOf<com.nozzleitall.desktop.migration.LegacySettingsMigration.Prepared?>(null) }
    var importCategories by remember {
        mutableStateOf(emptySet<com.nozzleitall.desktop.migration.LegacySettingsMigration.ImportCategory>())
    }
    var importBusy by remember { mutableStateOf(false) }
    var importNotice by remember { mutableStateOf<String?>(null) }
    var catalogSlot by remember { mutableStateOf<Int?>(null) }
    var catalogQuery by remember { mutableStateOf("") }
    var catalogResults by remember { mutableStateOf<List<com.nozzleitall.desktop.materials.FilamentProfileRecord>>(emptyList()) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var catalogBusy by remember { mutableStateOf(false) }
    var catalogImportBusy by remember { mutableStateOf(false) }
    var catalogGeneration by remember { mutableStateOf(0L) }
    var catalogContextKey by remember { mutableStateOf<String?>(null) }
    var catalogSearchJob by remember { mutableStateOf<Job?>(null) }
    var selectedFilamentVendor by remember { mutableStateOf<String?>(null) }
    var selectedFilamentType by remember { mutableStateOf<String?>(null) }
    var filamentQuery by remember { mutableStateOf("") }

    val profile = ProfileCatalog.byId(profileId) ?: ProfileCatalog.byId(ProfileCatalog.DEFAULT_ID)
    val machineVendorGroups = setupMachineVendorGroups(ProfileCatalog.search(profileQuery))
    val selectedMachineVendorGroup = machineVendorGroups.firstOrNull {
        it.key == (selectedMachineVendor ?: profile?.let { selected -> setupFilamentVendorKey(selected.vendor) })
    } ?: machineVendorGroups.firstOrNull()
    val library = PrinterLibrary.of(profileId)
    val machine = library?.machineFor(nozzle)
    val tools = (if (customOffline) customTools else profile?.tools ?: 1).coerceAtLeast(1)
    val filamentProfileKey = setupFilamentProfileKey(profileId, machine)
    val compatibleFilaments = setupCompatibleFilaments(profileId, machine)
    val compatibleProcesses = machine?.let { library.processesFor(it) }.orEmpty()
    val effectiveFilamentProfiles = setupResolvedFilamentProfiles(filamentProfiles, tools, machine, compatibleFilaments)

    fun resetCatalog() {
        catalogGeneration++
        catalogSearchJob?.cancel()
        catalogSearchJob = null
        catalogSlot = null
        catalogContextKey = null
        catalogQuery = ""
        catalogResults = emptyList()
        catalogError = null
        catalogBusy = false
        catalogImportBusy = false
    }

    fun selectProfile(nextProfileId: String) {
        if (nextProfileId == profileId) return
        resetCatalog()
        val nextLibrary = PrinterLibrary.of(nextProfileId)
        val nextMachine = nextLibrary?.machineFor(null)
        profileId = nextProfileId
        selectedMachineVendor = ProfileCatalog.byId(nextProfileId)?.let { setupFilamentVendorKey(it.vendor) }
        nozzle = nextMachine?.nozzle
        processId = nextMachine?.defaultProcess
        filamentProfiles.clear()
        installedFilaments.clear()
        nextMachine?.defaultFilament?.let { id ->
            installedFilaments[id] = true
            repeat((ProfileCatalog.byId(nextProfileId)?.tools ?: 1).coerceAtLeast(1)) { filamentProfiles[it + 1] = id }
        }
        if (!customOffline || setupName.isBlank()) setupName = ProfileCatalog.byId(nextProfileId)?.model.orEmpty()
    }
    fun resizeCustomToolheads(count: Int) {
        resetCatalog()
        customTools = count.coerceIn(1, 8)
        val selectedLibrary = PrinterLibrary.of(profileId)
        val selectedMachine = selectedLibrary?.machineFor(nozzle)
        val selectedFilaments = setupCompatibleFilaments(profileId, selectedMachine)
        val resized = setupResolvedFilamentProfiles(filamentProfiles, customTools, selectedMachine, selectedFilaments)
        filamentProfiles.clear()
        filamentProfiles.putAll(resized)
    }

    fun openProject() {
        val file = chooseFiles("Open a project", listOf("3mf"), multiple = false).firstOrNull() ?: return
        state.guardProjectReplacement {
            state.prepare.notice = null
            runCatching { state.prepare.open(file); state.returnToWorkspace(preview = false); state.completeFirstRun() }
                .onFailure { state.prepare.notice = it.message ?: "That project could not be opened." }
        }
    }
    fun setup() = SlicingSetup(profileId, setupName.ifBlank { profile?.model ?: "Offline machine" }, if (customOffline) customNozzle else nozzle,
        processId, tools, effectiveFilamentProfiles, customOffline,
        bedWidth.toFloatOrNull(), bedDepth.toFloatOrNull(), buildHeight.toFloatOrNull(),
        installedFilaments.filterValues { it }.keys + effectiveFilamentProfiles.values, gcodeFlavor)
    fun customDimensionsValid() = !customOffline ||
        (listOf(bedWidth, bedDepth, buildHeight).all { raw -> raw.toFloatOrNull()?.let { it in 10f..2000f } == true } &&
            customNozzle.toFloatOrNull()?.let { it in 0.1f..2f } == true)

    DialogWindow(onCloseRequest = state::dismissFirstRun, title = "Set up ${Glossary.PRODUCT_FAMILY}",
        state = rememberDialogState(width = 1180.dp, height = 800.dp), resizable = true,
        onPreviewKeyEvent = { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { state.dismissFirstRun(); true } else false }) {
        window.minimumSize = java.awt.Dimension(920, 640)
        val c = Nz.colors
        Row(Modifier.fillMaxSize().background(c.surface)) {
            SetupStepRail(step) { destination ->
                if (destination.ordinal <= step.ordinal) {
                    if (step == SetupStep.FILAMENT) resetCatalog()
                    step = destination
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Txt(step.title, Nz.type.headline)
                    Txt(step.description, Nz.type.body, c.textMuted)
                    Txt("Step ${step.ordinal + 1} of ${SetupStep.entries.size}", Nz.type.bodySmall, c.textMuted)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                state.prepare.notice?.let {
                    Banner(it, BannerKind.DANGER, modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp))
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                when (step) {
                    SetupStep.WELCOME -> {
                        Card(Modifier.fillMaxWidth(), raised = true) {
                            Txt("New slicing setup", Nz.type.title)
                            Txt("Choose a machine, nozzle, toolheads, compatible filament presets, and a starting process. Connecting a physical printer remains optional.", Nz.type.bodySmall, c.textMuted)
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Txt("Bring existing work", Nz.type.title)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NzButton("Open .3mf", ::openProject, kind = ButtonKind.SECONDARY, icon = NzIcon.PROJECTS, testTag = "first-run-open-project")
                                NzButton(if (importBusy) "Looking…" else "Import Orca settings", {
                                    importBusy = true; importNotice = null
                                    scope.launch {
                                        importSources = withContext(Dispatchers.IO) { state.legacySettings.discover() }
                                        importBusy = false
                                        if (importSources!!.isEmpty()) importNotice = "No importable Orca-family settings were found."
                                    }
                                }, kind = ButtonKind.SECONDARY, enabled = !importBusy)
                            }
                            importNotice?.let { Banner(it, if (it.startsWith("Import failed")) BannerKind.WARNING else BannerKind.INFO) }
                            importSources?.forEach { source ->
                                NzButton("Review ${source.label}", {
                                    importBusy = true; importNotice = null; importPrepared = null
                                    scope.launch {
                                        runCatching { withContext(Dispatchers.IO) { state.legacySettings.prepare(source) } }
                                            .onSuccess { review ->
                                                importPrepared = review
                                                importCategories = buildSet {
                                                    if (review.filaments.isNotEmpty()) add(com.nozzleitall.desktop.migration.LegacySettingsMigration.ImportCategory.FILAMENTS)
                                                    if (review.preservedOnly.isNotEmpty()) add(com.nozzleitall.desktop.migration.LegacySettingsMigration.ImportCategory.PROCESSES_AND_MACHINES)
                                                    if (review.projects.isNotEmpty()) add(com.nozzleitall.desktop.migration.LegacySettingsMigration.ImportCategory.RECENT_PROJECTS)
                                                    if (review.theme != null) add(com.nozzleitall.desktop.migration.LegacySettingsMigration.ImportCategory.APPEARANCE)
                                                }
                                            }
                                            .onFailure { importNotice = "Import review failed without changing the source: ${it.message}" }
                                        importBusy = false
                                    }
                                }, kind = ButtonKind.QUIET, enabled = !importBusy)
                            }
                            importPrepared?.let { review ->
                                Card(Modifier.fillMaxWidth()) {
                                    Txt("Choose what to import", Nz.type.title)
                                    Txt("Nothing is copied until you confirm. Credentials, account state, caches and updater identity are always excluded.", Nz.type.bodySmall, c.textMuted)
                                    @Composable fun categoryToggle(
                                        category: com.nozzleitall.desktop.migration.LegacySettingsMigration.ImportCategory,
                                        label: String,
                                        description: String,
                                        available: Boolean,
                                    ) {
                                        if (available) Toggle(label, category in importCategories, { checked ->
                                            importCategories = if (checked) importCategories + category else importCategories - category
                                        }, description)
                                    }
                                    categoryToggle(
                                        com.nozzleitall.desktop.migration.LegacySettingsMigration.ImportCategory.FILAMENTS,
                                        "Filament presets (${review.filaments.size})",
                                        "Compatible filament presets are copied into NIA's library.",
                                        review.filaments.isNotEmpty(),
                                    )
                                    categoryToggle(
                                        com.nozzleitall.desktop.migration.LegacySettingsMigration.ImportCategory.PROCESSES_AND_MACHINES,
                                        "Process and machine presets (${review.preservedOnly.size})",
                                        "Compatible process presets are activated; unresolved machine data is preserved in the report.",
                                        review.preservedOnly.isNotEmpty(),
                                    )
                                    categoryToggle(
                                        com.nozzleitall.desktop.migration.LegacySettingsMigration.ImportCategory.RECENT_PROJECTS,
                                        "Recent projects (${review.projects.size})",
                                        "Verified local .3mf files are copied into the NIA project library.",
                                        review.projects.isNotEmpty(),
                                    )
                                    categoryToggle(
                                        com.nozzleitall.desktop.migration.LegacySettingsMigration.ImportCategory.APPEARANCE,
                                        "Appearance (${review.theme ?: "none found"})",
                                        "Imports only the compatible light or dark appearance preference.",
                                        review.theme != null,
                                    )
                                    Txt("${review.sanitizedCount} file${if (review.sanitizedCount == 1) "" else "s"} will be sanitized; ${review.skipped.size} unsupported item${if (review.skipped.size == 1) "" else "s"} will be listed in the report.", Nz.type.bodySmall, c.textMuted)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        NzButton(if (importBusy) "Importing…" else "Import selected", {
                                            importBusy = true; importNotice = null
                                            scope.launch {
                                                runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        state.legacySettings.apply(state.legacySettings.select(review, importCategories))
                                                    }
                                                }.onSuccess { result ->
                                                    importNotice = "Imported ${result.importedFilaments} filament preset${if (result.importedFilaments == 1) "" else "s"}, ${result.importedProcesses} process preset${if (result.importedProcesses == 1) "" else "s"}, ${result.importedProjects} project${if (result.importedProjects == 1) "" else "s"}, and ${result.importedPreferences} appearance preference${if (result.importedPreferences == 1) "" else "s"}. Choose the machine configuration to use."
                                                    importPrepared = null
                                                    step = SetupStep.MACHINE
                                                }.onFailure { importNotice = "Import failed without changing the source: ${it.message}" }
                                                importBusy = false
                                            }
                                        }, kind = ButtonKind.PRIMARY, enabled = !importBusy && importCategories.isNotEmpty())
                                        NzButton("Cancel", { importPrepared = null }, kind = ButtonKind.QUIET, enabled = !importBusy)
                                    }
                                }
                            }
                        }
                    }
                    SetupStep.MACHINE -> {
                        Field("Search machines", profileQuery, { profileQuery = it }, placeholder = "Snapmaker, Prusa, Bambu, Voron…")
                        Row(Modifier.fillMaxWidth().heightIn(min = 190.dp, max = 280.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.width(210.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                machineVendorGroups.forEach { group ->
                                    NzButton("${group.vendor} (${group.machines.size})", { selectedMachineVendor = group.key },
                                        Modifier.fillMaxWidth().semantics {
                                            contentDescription = "${group.vendor}, ${group.machines.size} printer presets"
                                        }, kind = if (group.key == selectedMachineVendorGroup?.key) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                                }
                            }
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                selectedMachineVendorGroup?.machines.orEmpty().forEach { candidate ->
                                    NzButton(candidate.model, { selectProfile(candidate.id) }, Modifier.fillMaxWidth().semantics {
                                        contentDescription = "${candidate.vendor} ${candidate.model} printer preset"
                                    }, kind = if (candidate.id == profileId) ButtonKind.PRIMARY else ButtonKind.QUIET)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            library?.machines?.map { it.nozzle }?.distinct()?.forEach { diameter ->
                                NzButton("$diameter mm", {
                                    val next = library.machineFor(diameter)
                                    resetCatalog()
                                    nozzle = diameter; processId = next.defaultProcess; filamentProfiles.clear()
                                    installedFilaments.clear()
                                    next.defaultFilament?.let { id -> installedFilaments[id] = true; repeat(tools) { filamentProfiles[it + 1] = id } }
                                },
                                    kind = if (machine?.nozzle == diameter) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                            }
                        }
                        Txt("${profile?.model ?: profileId} · $tools toolhead${if (tools == 1) "" else "s"}", Nz.type.body)
                        Toggle("Custom/offline configuration", customOffline, {
                            resetCatalog()
                            customOffline = it
                            if (it) {
                                if (profileId != "generic_klipper") selectProfile("generic_klipper")
                                setupName = "Custom printer"
                                resizeCustomToolheads(customTools)
                            }
                        }, "Create an offline machine from NIA's generic Klipper baseline. A physical connection can be added later.")
                        if (customOffline) {
                            Field("Configuration name", setupName, { setupName = it }, placeholder = "Workshop machine")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Field("Bed width (mm)", bedWidth, { bedWidth = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(1f))
                                Field("Bed depth (mm)", bedDepth, { bedDepth = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(1f))
                                Field("Height (mm)", buildHeight, { buildHeight = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(1f))
                                Field("Nozzle (mm)", customNozzle, { customNozzle = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Txt("Toolheads", Nz.type.label)
                                (1..8).forEach { count -> NzButton(count.toString(), { resizeCustomToolheads(count) },
                                    kind = if (customTools == count) ButtonKind.PRIMARY else ButtonKind.QUIET) }
                            }
                            Txt("Firmware / G-code dialect", Nz.type.label)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    listOf("klipper" to "Klipper", "marlin2" to "Marlin 2", "marlin" to "Marlin (legacy)"),
                                    listOf("reprapfirmware" to "RepRapFirmware", "repetier" to "Repetier"),
                                ).forEach { choices ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        choices.forEach { (id, label) ->
                                            NzButton(label, { gcodeFlavor = id }, kind = if (gcodeFlavor == id) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                                        }
                                    }
                                }
                            }
                            Txt("This controls the output dialect used by the slicing engine; it does not connect to firmware.", Nz.type.bodySmall, c.textMuted)
                            if (!customDimensionsValid()) Banner("Enter bed and height dimensions from 10 to 2000 mm.", BannerKind.WARNING)
                        }
                    }
                    SetupStep.FILAMENT -> {
                        com.nozzleitall.desktop.settings.DenseInput(
                            filamentQuery,
                            { filamentQuery = it },
                            "Search compatible filaments",
                            Modifier.fillMaxWidth(),
                            placeholder = "Vendor, material type, or material",
                        )
                        val visibleFilaments = compatibleFilaments.filter { filament ->
                            val query = filamentQuery.trim()
                            query.isBlank() || listOf(filament.vendor, filament.type, filament.displayName)
                                .any { it.contains(query, ignoreCase = true) }
                        }
                        val installedIds = setupInstalledFilamentIds(installedFilaments, effectiveFilamentProfiles)
                        val preferredFilament = compatibleFilaments.firstOrNull { it.id in installedIds }
                        val visibleVendorGroups = setupFilamentVendorGroups(visibleFilaments)
                        val selectedVendorGroup = visibleVendorGroups.firstOrNull { it.key == selectedFilamentVendor }
                            ?: preferredFilament?.let { installed ->
                                visibleVendorGroups.firstOrNull { it.key == setupFilamentVendorKey(installed.vendor) }
                            }
                            ?: visibleVendorGroups.firstOrNull()
                        val visibleTypeGroups = selectedVendorGroup?.let { setupFilamentMaterialTypeGroups(it.filaments) }.orEmpty()
                        val selectedTypeGroup = visibleTypeGroups.firstOrNull { it.key == selectedFilamentType }
                            ?: preferredFilament?.takeIf {
                                selectedVendorGroup?.key == setupFilamentVendorKey(it.vendor)
                            }?.let { installed ->
                                visibleTypeGroups.firstOrNull { it.key == installed.type.trim().ifBlank { "Other" }.lowercase() }
                            }
                            ?: visibleTypeGroups.firstOrNull()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt("${installedIds.size} installed", Nz.type.label)
                            Spacer(Modifier.weight(1f))
                            NzButton("Install this type", {
                                selectedTypeGroup?.filaments?.forEach { installedFilaments[it.id] = true }
                            }, kind = ButtonKind.SECONDARY, enabled = selectedTypeGroup != null)
                            NzButton("Default only", {
                                val assigned = effectiveFilamentProfiles.values.toSet()
                                installedFilaments.keys.toList().forEach { id -> installedFilaments[id] = id in assigned }
                            }, kind = ButtonKind.QUIET)
                        }
                        if (visibleVendorGroups.isEmpty()) {
                            Banner("No compatible filament matches this search.", BannerKind.WARNING)
                        } else Row(
                            Modifier.fillMaxWidth().heightIn(min = 190.dp, max = 280.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                Modifier.width(190.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                visibleVendorGroups.forEach { group ->
                                    NzButton("${group.vendor} (${group.filaments.size})", {
                                        selectedFilamentVendor = group.key
                                        selectedFilamentType = null
                                    }, Modifier.fillMaxWidth().semantics {
                                        contentDescription = "${group.vendor}, ${group.filaments.size} compatible filament presets"
                                    }, kind = if (group.key == selectedVendorGroup?.key) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                                }
                            }
                            Column(
                                Modifier.width(150.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                visibleTypeGroups.forEach { group ->
                                    NzButton("${group.materialType} (${group.filaments.size})", {
                                        selectedFilamentType = group.key
                                    }, Modifier.fillMaxWidth().semantics {
                                        contentDescription = "${group.materialType}, ${group.filaments.size} materials"
                                    }, kind = if (group.key == selectedTypeGroup?.key) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                                }
                            }
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                selectedTypeGroup?.filaments?.forEach { filament ->
                                    val installed = filament.id in installedIds
                                    val assigned = filament.id in effectiveFilamentProfiles.values
                                    FilamentInstallRow(
                                        filament,
                                        installed,
                                        assigned,
                                        { next -> if (!assigned) installedFilaments[filament.id] = next },
                                    )
                                }
                            }
                        }
                        Txt("Vendor → Material type → Material", Nz.type.bodySmall, c.textMuted)

                        if (catalogSlot != 0) NzButton("Find another brand or product online", {
                            resetCatalog()
                            catalogSlot = 0
                            catalogContextKey = filamentProfileKey
                            catalogQuery = ""
                        }, kind = ButtonKind.SECONDARY)
                        else Column(
                            Modifier.fillMaxWidth().background(c.surfaceSunken, RoundedCornerShape(8.dp)).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Txt("3D Filament Profiles", Nz.type.label, modifier = Modifier.weight(1f))
                                Txt("Close", Nz.type.label, c.accent, modifier = Modifier.clickable { resetCatalog() }.padding(4.dp))
                            }
                            Txt("Searches the online catalog only when you press Search. Your search text is sent to that service; printer data is not.", Nz.type.bodySmall, c.textMuted)
                            com.nozzleitall.desktop.settings.DenseInput(
                                catalogQuery, { catalogQuery = it }, "Search filament brands and products",
                                Modifier.fillMaxWidth(), placeholder = "Brand or product",
                            )
                            NzButton(if (catalogBusy) "Searching…" else "Search catalog", {
                                catalogSearchJob?.cancel()
                                val request = FilamentCatalogRequest(++catalogGeneration, 0, filamentProfileKey)
                                val query = catalogQuery.trim()
                                catalogBusy = true
                                catalogError = null
                                catalogResults = emptyList()
                                catalogSearchJob = scope.launch {
                                    val reply = searchFilamentCatalog(query, state.filamentCatalog::search)
                                    if (!catalogRequestIsCurrent(request, catalogGeneration, catalogSlot, catalogContextKey)) return@launch
                                    catalogResults = reply.records
                                    catalogError = reply.error
                                    catalogBusy = false
                                    catalogSearchJob = null
                                }
                            }, kind = ButtonKind.SECONDARY, enabled = !catalogBusy && catalogQuery.isNotBlank())
                            catalogError?.let { Banner(it, BannerKind.WARNING) }
                            if (!catalogBusy && catalogError == null && catalogResults.isEmpty())
                                Txt("Search results will appear here. Nothing is imported until you choose a product.", Nz.type.bodySmall, c.textMuted)
                            catalogResults.take(25).forEach { record ->
                                Row(
                                    Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(6.dp)).padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Txt(record.productName, Nz.type.bodySmall)
                                        Txt(listOf(record.baseMaterial, record.color).filter(String::isNotBlank).joinToString(" · "), Nz.type.bodySmall, c.textMuted)
                                    }
                                    NzButton(if (catalogImportBusy) "Saving…" else "Install", {
                                        val request = FilamentCatalogRequest(catalogGeneration, 0, filamentProfileKey)
                                        catalogImportBusy = true
                                        catalogError = null
                                        scope.launch {
                                            val result = runCatching {
                                                withContext(Dispatchers.IO) { state.filamentPresets.import(request.profileKey, record) }
                                            }
                                            if (!catalogRequestIsCurrent(request, catalogGeneration, catalogSlot, catalogContextKey)) return@launch
                                            result.onSuccess { imported ->
                                                installedFilaments[imported.entry.id] = true
                                                resetCatalog()
                                            }.onFailure { error ->
                                                catalogError = error.message ?: "The preset could not be imported."
                                                catalogImportBusy = false
                                            }
                                        }
                                    }, kind = ButtonKind.PRIMARY, enabled = !catalogImportBusy)
                                }
                            }
                            Txt("Installing a result saves a machine-compatible preset in your NIA filament library. Map it to a loaded toolhead later in Prepare.", Nz.type.bodySmall, c.textMuted)
                        }

                        val startingFilament = compatibleFilaments.firstOrNull {
                            it.id == effectiveFilamentProfiles[1]
                        }
                        Txt("Starting preset", Nz.type.label)
                        Txt(
                            startingFilament?.let { "${it.vendor} · ${it.displayName}" } ?: "Machine default",
                            Nz.type.bodySmall,
                            c.textMuted,
                        )
                        Txt("This is only the offline slicing default. A connected printer's toolhead, RFID, spool and object mappings remain separate.", Nz.type.bodySmall, c.textMuted)
                    }
                    SetupStep.PROCESS -> {
                        if (compatibleProcesses.isEmpty()) {
                            Txt("Standard", Nz.type.title); Txt("This profile uses its bundled standard process.", Nz.type.bodySmall, c.textMuted)
                        } else compatibleProcesses.forEach { process ->
                            NzButton(process.displayLabel, { processId = process.id }, Modifier.fillMaxWidth(),
                                kind = if (processId == process.id) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                        }
                    }
                    SetupStep.CONNECT -> {
                        Txt("Slice and export offline, or link a discovered/manual-IP printer to ${profile?.model ?: profileId}. Connecting does not change the installed slicing setup.", Nz.type.body, c.textMuted)
                        val linked = state.fleet.printers.values.filter { it.config.identity.profileId == profileId }
                        linked.forEach { entry -> Card(Modifier.fillMaxWidth()) {
                            Txt(entry.config.identity.displayName, Nz.type.title)
                            Txt("${entry.config.identity.address} · ${entry.status.value.state.label}", Nz.type.bodySmall, c.textMuted)
                        } }
                        NzButton(if (linked.isEmpty()) "Find or add physical printer" else "Add another physical printer",
                            { state.startPrinterSetup(true, true, profileId) }, kind = ButtonKind.PRIMARY, icon = NzIcon.SEARCH,
                            testTag = "first-run-connect")
                        Txt("Skip this step to slice offline. You can connect later from Printers.", Nz.type.bodySmall, c.textMuted)
                    }
                    SetupStep.REVIEW -> {
                        Card(Modifier.fillMaxWidth(), raised = true) {
                            Txt(setupName.ifBlank { profile?.model ?: profileId }, Nz.type.title)
                            Txt("Machine", Nz.type.label); Txt(if (customOffline) "$setupName · custom offline" else "${profile?.vendor} · ${profile?.model}", Nz.type.body)
                            Txt("Nozzle and toolheads", Nz.type.label); Txt("${if (customOffline) customNozzle else machine?.nozzle ?: nozzle ?: "Profile default"} mm · $tools toolhead${if (tools == 1) "" else "s"}", Nz.type.body)
                            if (customOffline) {
                                Txt("Build volume", Nz.type.label)
                                Txt("$bedWidth × $bedDepth × $buildHeight mm", Nz.type.body)
                                Txt("Output dialect", Nz.type.label)
                                Txt(gcodeFlavor, Nz.type.body)
                            }
                            Txt("Filaments", Nz.type.label)
                            Txt("${setupInstalledFilamentIds(installedFilaments, effectiveFilamentProfiles).size} compatible presets installed", Nz.type.bodySmall)
                            repeat(tools) { i -> Txt("${i + 1}: ${compatibleFilaments.firstOrNull { it.id == effectiveFilamentProfiles[i + 1] }?.displayName ?: "Profile default"}", Nz.type.bodySmall) }
                            Txt("Process", Nz.type.label); Txt(compatibleProcesses.firstOrNull { it.id == processId }?.displayLabel ?: "Standard", Nz.type.body)
                            val linked = state.fleet.printers.values.filter { it.config.identity.profileId == profileId }
                            Txt("Physical printer", Nz.type.label); Txt(linked.joinToString { it.config.identity.displayName }.ifBlank { "Not connected — offline slicing" }, Nz.type.body)
                        }
                        Txt("RFID/toolhead/spool/profile/object mapping remains visible and editable in Prepare after a connected printer reports its loaded materials.", Nz.type.bodySmall, c.textMuted)
                    }
                }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (step == SetupStep.WELCOME) {
                        NzButton("Explore without setup", state::completeFirstRun, kind = ButtonKind.QUIET, testTag = "first-run-skip")
                        Spacer(Modifier.weight(1f))
                        NzButton("Start setup", { step = SetupStep.MACHINE }, kind = ButtonKind.PRIMARY, testTag = "first-run-start")
                    } else {
                        NzButton("Back", {
                            if (step == SetupStep.FILAMENT) resetCatalog()
                            step = SetupStep.entries[step.ordinal - 1]
                        }, kind = ButtonKind.SECONDARY)
                        Spacer(Modifier.weight(1f))
                        if (step == SetupStep.CONNECT) NzButton("Skip for now", { step = SetupStep.REVIEW }, kind = ButtonKind.SECONDARY, testTag = "first-run-skip-connect")
                        if (step == SetupStep.REVIEW) NzButton("Finish setup", { state.finishFirstRun(setup()) }, kind = ButtonKind.PRIMARY, testTag = "first-run-finish")
                        else NzButton(if (step == SetupStep.CONNECT) "Review" else "Next", {
                            if (step == SetupStep.FILAMENT) resetCatalog()
                            step = SetupStep.entries[step.ordinal + 1]
                        }, kind = ButtonKind.PRIMARY,
                            enabled = profile != null && (!customOffline || setupName.isNotBlank() && customDimensionsValid()), testTag = "first-run-next")
                    }
                }
            }
        }
    }
}
