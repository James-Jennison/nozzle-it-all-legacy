package com.nozzleitall.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nozzleitall.desktop.prepare.PrepItem
import com.nozzleitall.desktop.prepare.PrepareScreen
import com.nozzleitall.desktop.prepare.PrepareState
import com.nozzleitall.desktop.prepare.ProfileCatalog
import com.nozzleitall.desktop.prepare.WorkspaceMachineReview
import com.nozzleitall.desktop.prepare.WorkspaceSliceAction
import com.nozzleitall.desktop.prepare.chooseFiles
import com.nozzleitall.desktop.projects.ProjectLibrary
import com.nozzleitall.desktop.projects.ProjectsScreen
import com.nozzleitall.desktop.screens.*
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.Glossary
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Main areas stay reachable from the invariant header. Prepare is the product's primary slicer workspace. */
enum class Destination(val label: String, val icon: NzIcon, val key: Key, val inDestinations: Boolean = true) {
    PREPARE("Workspace", NzIcon.PREPARE, Key.Six),
    JOBS("Queue & history", NzIcon.MONITOR, Key.One, inDestinations = false),
    PROJECTS("Projects", NzIcon.PROJECTS, Key.Two),
    FLEET("Printers", NzIcon.FLEET, Key.Three),
    MATERIALS("Materials", NzIcon.MATERIALS, Key.Four),
    ENVIRONMENT("Environment", NzIcon.HEAT, Key.Five),
    // One printer's own page (a Monitor tab and a Materials tab). It is reached from a printer on the Printers page, so it
    // has no top-level menu entry of its own, so Printers remains the represented destination.
    MONITOR("Print & Monitor", NzIcon.MONITOR, Key.Seven, inDestinations = false),
    PRINTER_MATERIALS("Printer materials", NzIcon.MATERIALS, Key.Nine, inDestinations = false),
    HISTORY("History", NzIcon.LAYERS, Key.Eight, inDestinations = false),
    SETTINGS("Settings", NzIcon.SETTINGS, Key.Comma);

    /** The destination item represented while a printer-specific child page is shown. */
    val destinationItem: Destination get() = when (this) {
        HISTORY -> JOBS
        MONITOR, PRINTER_MATERIALS -> FLEET
        else -> this
    }
}

/** The destination menu distinguishes the slicing workflow from libraries and workshop tools. */
internal val primaryDestinations = listOf(Destination.PREPARE)
internal val libraryDestinations = listOf(Destination.PROJECTS, Destination.FLEET, Destination.MATERIALS)
internal val workshopDestinations = listOf(Destination.ENVIRONMENT)

data class HistoryAssessmentDraft(
    val assessment: com.nozzleitall.desktop.jobs.LocalJobStore.Assessment? = null,
    val notes: String = "",
)

data class HistorySaveNotice(val message: String, val isError: Boolean)

enum class WorkspaceToolRequest { ARRANGE, ORIENT, SIMPLIFY }

internal enum class PlateEditKind(val label: String) {
    CLEAR_PLATE("Clear this plate"), CLEAR_ALL("Clear all plates"), DELETE_PLATE("Delete plate"),
}

internal data class ReviewedPlate(val id: String, val name: String, val items: List<PrepItem>)
internal data class PendingPlateEdit(
    val kind: PlateEditKind,
    val plateId: String?,
    val editorGeneration: Long,
    val scope: List<ReviewedPlate>,
    val detail: String,
)

enum class MonitorHandoffState(val label: String) {
    UPLOADED("Uploaded to printer"),
    QUEUED("Queued for review and start"),
    STARTED("Start accepted"),
    STARTED_RECORD_PENDING("Start accepted · local record needs recovery"),
    START_UNCERTAIN("Check Start outcome"),
}

data class MonitorHandoff(
    val printerId: String,
    val projectName: String,
    val fileName: String,
    val state: MonitorHandoffState,
    val plate: com.nozzleitall.desktop.prepare.SlicedPlateContext? = null,
    val remotePath: String? = null,
)

data class AddPrinterRequest(val autoScan: Boolean, val returnToFirstRunOnCancel: Boolean = false,
                             val compatibilityProfileId: String? = null)

class AppState(val paths: AppPaths, val scope: CoroutineScope) {
    val version: String = System.getProperty("nozzle.version") ?: "0.1.0-dev"
    val userPresets = com.nozzleitall.desktop.settings.UserPresetStore(paths.userPresets)
    val filamentCatalog = com.nozzleitall.desktop.materials.FilamentProfilesCatalog()
    val filamentPresets = com.nozzleitall.desktop.materials.FilamentPresetStore(paths.filamentPresets).also {
        com.nozzleitall.desktop.prepare.FilamentLibrary.installCustomProfiles(it)
    }
    val fleet = Fleet(paths, scope, log = { System.err.println(it) })
    val library = ProjectLibrary(paths)
    val legacyProcessPresets = com.nozzleitall.desktop.migration.LegacyProcessPresetStore(paths.legacyProcessPresets).also {
        com.nozzleitall.desktop.prepare.PrinterLibrary.installCustomProcesses(it)
    }
    val legacySettings = com.nozzleitall.desktop.migration.LegacySettingsMigration(
        paths, filamentPresets, legacyProcessPresets, library, fleet::importLegacyTheme, version)
    // Lazy IO on explicit discovery/panel entry; locally saved names, never keys.
    val goveeNames = com.nozzleitall.desktop.environment.DesktopGoveeNames(
        com.nozzleitall.environment.GoveeNameCache(File(paths.config, "environment/govee-names.json")))
    val environment = com.nozzleitall.environment.EnvironmentalCoordinator(
        File(paths.config, "environment/control.json"),
        com.nozzleitall.environment.EnvironmentalHistory(com.nozzleitall.environment.SqlStoreFactory {
            com.nozzleitall.desktop.environment.JdbcEnvironmentalStore(File(paths.data, "environment/history.db"))
        }), { com.nozzleitall.desktop.environment.LinuxBleTransport(System.getProperty("nozzle.environment.bluezAdapter")) },
        platformIdentity = "linux/${System.getProperty("os.version")}/${System.getProperty("os.arch")}",
        goveeNames = goveeNames::currentDevices,
        restoreGoveeNames = { goveeNames.cachedDevices(); Unit })
    val prepare = PrepareState(this).also { prepared -> fleet.settings.value.slicingSetup?.let(prepared::applySlicingSetup) }
    val queueRowUi = mutableStateMapOf<String, QueueRowUiState>()
    /** Off unless the user turns it on in Settings: lets Nozzle It All Web on this computer reach these printers. */
    val connector = com.nozzleitall.desktop.connector.LocalConnector(fleet, java.io.File(paths.config, "connector-tokens.json"))
    // A slicer should open as a slicer. Jobs, materials and fleet management remain
    // available from the application menu, but they must not displace the plate.
    /** Any ordinary navigation away from Monitor retires its optimistic Send handoff. */
    private var destinationState by mutableStateOf(Destination.PREPARE)
    var destination: Destination
        get() = destinationState
        set(value) {
            if (value != Destination.MONITOR) monitorHandoff = null
            destinationState = value
        }
    var selectedPrinter by mutableStateOf<String?>(null)
    /** The current workspace job handed to Monitor before the printer reports it as active. */
    var monitorHandoff by mutableStateOf<MonitorHandoff?>(null)
    /** A return from Monitor can request Send without coupling Monitor to Prepare's drawer implementation. */
    var workspaceSendRequested by mutableStateOf(false)
        private set
    /** Whether the fixed workflow header should present Send as the active workspace stage. */
    var workspaceSendOpen by mutableStateOf(false)
        private set
    internal val commandShortcuts = CommandShortcuts(java.io.File(paths.config, "command-shortcuts.json"))
    var showCommandPalette by mutableStateOf(false)
    var workspaceToolRequest by mutableStateOf<WorkspaceToolRequest?>(null)
        private set
    /** Exact observation requested from Jobs or History, including one whose printer was removed. */
    var selectedHistoryRecord by mutableStateOf<com.nozzleitall.desktop.history.JobRecord?>(null)
    /** Assessment work survives leaving History; its result or failure belongs to the observation. */
    val historySavePending = mutableStateMapOf<com.nozzleitall.desktop.history.JobRecord, Unit>()
    val historyCompletedSaves = mutableStateMapOf<com.nozzleitall.desktop.history.JobRecord,
        com.nozzleitall.desktop.jobs.LocalJobStore.ObservationReview>()
    val historySaveProblems = mutableStateMapOf<com.nozzleitall.desktop.history.JobRecord, String>()
    /** Uncommitted assessment choices survive a visit to another page and a failed write. */
    val historyDrafts = mutableStateMapOf<com.nozzleitall.desktop.history.JobRecord, HistoryAssessmentDraft>()
    /** Off-page and detached completion/failure notices remain distinct for concurrent prints. */
    val historySaveNotices = mutableStateMapOf<com.nozzleitall.desktop.history.JobRecord, HistorySaveNotice>()
    /** One application-level printer setup path, shared by first run, Workspace and the Printers page. */
    var addPrinterRequest by mutableStateOf<AddPrinterRequest?>(null)
        private set
    // A saved physical connection is not a completed slicer setup. Only an explicit finish/explore choice suppresses
    // onboarding; later printer removal must not make it reappear.
    var firstRunVisible by mutableStateOf(!fleet.settings.value.firstRunComplete)
        private set
    var showAbout by mutableStateOf(false)
    /** Which menu is open in the menu bar, if any. */
    var openMenu by mutableStateOf<Int?>(null)
    internal var pendingProjectAction by mutableStateOf<(() -> Unit)?>(null)
        private set
    internal var projectSaveProblem by mutableStateOf<String?>(null)
        private set

    internal var pendingPlateEdit by mutableStateOf<PendingPlateEdit?>(null)
        private set

    internal fun requestPlateEdit(kind: PlateEditKind, plate: Int = prepare.currentPlate) {
        if (pendingPlateEdit != null || pendingProjectAction != null || prepare.toolBusy != null) return
        val name = prepare.plateNames.getOrNull(plate) ?: return
        val count = prepare.plateItems(plate).size
        val objects = if (kind == PlateEditKind.CLEAR_ALL) prepare.items.size else count
        if (kind == PlateEditKind.DELETE_PLATE && prepare.plateIds.size <= 1) return
        if (kind != PlateEditKind.DELETE_PLATE && objects == 0) return
        val description = when (kind) {
            PlateEditKind.CLEAR_PLATE -> "Remove all $count objects from “$name”. The plate remains."
            PlateEditKind.CLEAR_ALL -> "Remove all $objects objects across ${prepare.plateIds.size} plates. The plates remain."
            PlateEditKind.DELETE_PLATE -> "Remove “$name” and its $count objects. Other plates remain."
        }
        val target = plate.takeUnless { kind == PlateEditKind.CLEAR_ALL }
        pendingPlateEdit = PendingPlateEdit(kind, target?.let(prepare.plateIds::get),
            prepare.editorGeneration, plateEditScope(target), "$description You can undo this edit.")
    }

    private fun plateEditScope(plate: Int?): List<ReviewedPlate> {
        val indices = if (plate == null) prepare.plateIds.indices else plate..plate
        return indices.map { ReviewedPlate(prepare.plateIds[it], prepare.plateNames[it], prepare.plateItems(it)) }
    }

    internal fun cancelPlateEdit() { pendingPlateEdit = null }

    internal fun confirmPlateEdit() {
        val review = pendingPlateEdit ?: return
        cancelPlateEdit()
        val plate = review.plateId?.let(prepare.plateIds::indexOf)
        if (review.editorGeneration != prepare.editorGeneration || plate == -1 || prepare.toolBusy != null ||
            review.scope != plateEditScope(plate)) {
            prepare.notice = "The workspace changed. Review the plate edit again."
            return
        }
        when (review.kind) {
            PlateEditKind.CLEAR_PLATE, PlateEditKind.CLEAR_ALL -> prepare.clearPlateObjects(plate)
            PlateEditKind.DELETE_PLATE -> prepare.removePlate(requireNotNull(plate), allowPopulated = true)
        }
    }

    internal fun guardProjectReplacement(action: () -> Unit) {
        if (pendingPlateEdit != null) return
        if (pendingProjectAction != null) {
            if (projectSaveProblem == null) projectSaveProblem = "Finish this project decision before starting another."
            return
        }
        if (prepare.dirty) pendingProjectAction = action else action()
    }

    internal fun cancelProjectReplacement() {
        pendingProjectAction = null
        projectSaveProblem = null
    }

    internal data class ConversionReview(val losses: List<String>, val confirm: () -> Unit)
    internal var conversionReview by mutableStateOf<ConversionReview?>(null)
        private set

    internal fun reviewConversion(losses: List<String>, action: () -> Unit) {
        if (conversionReview != null) return
        if (losses.isEmpty()) action() else conversionReview = ConversionReview(losses, action)
    }

    internal fun resolveConversion(approved: Boolean) {
        val review = conversionReview ?: return
        conversionReview = null
        if (approved) review.confirm()
    }

    internal fun reviewCurrentConversion(action: () -> Unit) {
        val generation = prepare.editorGeneration
        val file = prepare.file
        reviewConversion(prepare.conversionLosses) {
            if (prepare.editorGeneration == generation && prepare.file == file) action()
            else prepare.notice = "The project changed. Review the conversion again."
        }
    }

    internal fun continueProjectReplacement(save: Boolean) {
        val action = pendingProjectAction ?: return
        if (!save) {
            cancelProjectReplacement()
            action()
            return
        }
        reviewCurrentConversion {
            if (pendingProjectAction !== action) return@reviewCurrentConversion
            try {
                prepare.save()
            } catch (error: Exception) {
                projectSaveProblem = error.message ?: "The project could not be saved."
                return@reviewCurrentConversion
            }
            cancelProjectReplacement()
            action()
        }
    }
    internal var droppedProject by mutableStateOf<File?>(null)
    internal var gcodeInspection by mutableStateOf<File?>(null)
    internal var meshyOpen by mutableStateOf(false)
    internal var meshySettingsReturn by mutableStateOf(false)
        private set
    internal var meshyPrompt by mutableStateOf("")
    internal var meshySize by mutableStateOf("80")
    internal var meshyReviewOnOpen by mutableStateOf(false)

    internal fun openMeshySettings() {
        meshyOpen = false
        meshySettingsReturn = true
        destination = Destination.SETTINGS
    }

    internal fun returnToMeshyReview() {
        if (!meshySettingsReturn) return
        meshySettingsReturn = false
        destination = Destination.PREPARE
        meshyReviewOnOpen = true
        meshyOpen = true
    }

    internal fun closeMeshy() {
        meshyOpen = false
        meshyPrompt = ""
        meshySize = "80"
        meshyReviewOnOpen = false
    }


    internal fun chooseWorkspaceProject() {
        val file = chooseFiles("Open a project", listOf("3mf"), multiple = false).firstOrNull() ?: return
        guardProjectReplacement { openWorkspaceProject(file) }
    }

    internal fun chooseWorkspaceModels() {
        val files = chooseFiles("Add a model", listOf("stl", "3mf", "obj"))
        if (files.isNotEmpty()) importWorkspaceModels(files)
    }

    internal fun openWorkspaceProject(file: File) {
        runCatching { prepare.open(file) }
            .onSuccess { returnToWorkspace(preview = false) }
            .onFailure { prepare.notice = it.message ?: "The project could not be opened." }
    }

    internal fun importWorkspaceModels(files: List<File>) {
        val problems = files.mapNotNull { file ->
            runCatching { prepare.importModel(file) }.exceptionOrNull()?.let { "${file.name}: ${it.message}" }
        }
        returnToWorkspace(preview = false)
        if (problems.isNotEmpty()) prepare.notice = problems.joinToString("\n")
    }

    internal fun receiveWorkspaceDrop(files: List<File>) {
        if (pendingProjectAction != null) {
            if (projectSaveProblem == null) projectSaveProblem = "Finish this project decision before opening dropped files."
            return
        }
        if (pendingPlateEdit != null || pendingMachineSelection != null || droppedProject != null || gcodeInspection != null) {
            prepare.notice = "Close the current dialog before opening dropped files."
            return
        }
        when {
            files.size == 1 && files.single().extension.equals("3mf", true) -> droppedProject = files.single()
            files.size == 1 && files.single().extension.equals("gcode", true) -> gcodeInspection = files.single()
            files.size in 1..32 && files.all { it.extension.lowercase() in setOf("stl", "obj") } -> importWorkspaceModels(files)
            else -> {
                returnToWorkspace(preview = false)
                prepare.notice = "Drop up to 32 STL or OBJ models together, or one 3MF project or G-code file at a time."
            }
        }
    }

    internal var pendingMachineSelection by mutableStateOf<Pair<String?, String?>?>(null)

    internal fun requestWorkspacePrinter(id: String?) {
        if (id == null) {
            prepare.choosePrinter(null)
            return
        }
        val entry = fleet.printers[id] ?: return
        val associated = ProfileCatalog.byId(entry.config.identity.profileId)
        if (associated?.id == prepare.profileId || id == prepare.printerId && associated == null) prepare.choosePrinter(id)
        else pendingMachineSelection = id to associated?.id
    }

    fun selectPrinter(id: String) {
        monitorHandoff = null
        selectedPrinter = id
    }
    /** Opening Monitor outside a Send handoff must not reuse an older job card for the same printer. */
    fun openPrinter(id: String) {
        selectPrinter(id)
        workspaceSendOpen = false
        destination = Destination.MONITOR
    }
    fun openJobMonitor(handoff: MonitorHandoff) {
        monitorHandoff = handoff
        selectedPrinter = handoff.printerId
        workspaceSendOpen = false
        destination = Destination.MONITOR
    }
    fun returnToWorkspace(preview: Boolean, send: Boolean = false) {
        monitorHandoff = null
        prepare.showPreview = preview
        workspaceSendRequested = send
        workspaceSendOpen = send
        destination = Destination.PREPARE
    }
    fun openWorkflowStage(stage: PrintWorkflowStage) {
        when (stage) {
            PrintWorkflowStage.PREPARE -> returnToWorkspace(preview = false)
            PrintWorkflowStage.PREVIEW -> returnToWorkspace(preview = true)
            PrintWorkflowStage.SEND -> returnToWorkspace(preview = true, send = true)
            PrintWorkflowStage.MONITOR -> (prepare.printer()?.config?.identity?.id ?: selectedPrinter)?.let(::openPrinter)
                ?: run { destination = Destination.MONITOR }
        }
    }
    fun reportWorkspaceSendOpen(open: Boolean) { workspaceSendOpen = open }
    fun consumeWorkspaceSendRequest() { workspaceSendRequested = false }
    fun requestWorkspaceTool(tool: WorkspaceToolRequest) {
        destination = Destination.PREPARE
        prepare.showPreview = false
        workspaceToolRequest = tool
    }
    fun consumeWorkspaceToolRequest() { workspaceToolRequest = null }
    fun clearMonitorHandoff(expected: MonitorHandoff) {
        if (monitorHandoff == expected) monitorHandoff = null
    }
    fun reconcileMonitorHandoff(expected: MonitorHandoff, superseded: Boolean) {
        if (superseded) clearMonitorHandoff(expected)
    }
    fun openHistoryRecord(record: com.nozzleitall.desktop.history.JobRecord) {
        selectedHistoryRecord = record
        selectedPrinter = record.printerId
        destination = Destination.HISTORY
    }
    fun startPrinterSetup(autoScan: Boolean = true, fromFirstRun: Boolean = false, compatibilityProfileId: String? = null) {
        if (addPrinterRequest != null) return
        // A physical connection may carry an explicit compatibility association for preflight, but it must never create,
        // select or infer the user's offline slicing setup. The onboarding wizard passes its reviewed machine explicitly;
        // standalone printer setup relies only on discovery or the selected physical-printer family.
        addPrinterRequest = AddPrinterRequest(autoScan, fromFirstRun, compatibilityProfileId)
    }
    fun finishPrinterSetup(addedPrinterId: String?) {
        val request = addPrinterRequest ?: return
        addPrinterRequest = null
        if (request.returnToFirstRunOnCancel) firstRunVisible = true
    }
    fun completeFirstRun() {
        if (fleet.completeFirstRun()) firstRunVisible = false
        else prepare.notice = "Your welcome choice could not be saved. Try again before continuing."
    }
    fun finishFirstRun(setup: SlicingSetup) {
        if (fleet.saveSlicingSetup(setup)) {
            prepare.applySlicingSetup(setup)
            firstRunVisible = false
        } else prepare.notice = "Your setup could not be saved. Check this computer's storage and try again."
    }
    /** Closing setup is a safe dismissal, not an implicit permanent skip. */
    fun dismissFirstRun() { firstRunVisible = false }
    fun reopenFirstRun() { firstRunVisible = true }
}

/**
 * Names the window class "nozzle-it-all" on Linux (X11 and XWayland), so the dock and task switcher match windows to the
 * installed launcher (StartupWMClass) and show the Nozzle It All icon instead of a generic one. Java otherwise uses the
 * main class name. Must run before the first window is created.
 */
private fun setLinuxWindowClass() {
    if (!System.getProperty("os.name").orEmpty().startsWith("Linux")) return
    runCatching {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(toolkit, "nozzle-it-all")
    }
}

/** Files opened with Nozzle It All (from the file manager or the command line): a 3MF opens as a project, models are added. */
fun main(args: Array<String>) { setLinuxWindowClass(); runApp(args.map(::File).filter { it.isFile }) }

/**
 * Skiko's Linux frame limiter can query an X11 drawing surface after a window-manager close has invalidated it. Linux
 * therefore closes NIA's owned services and halts before Compose or Skiko can dispatch that close to the native surface.
 * Menu Quit keeps Compose's ordinary disposal path, which is already clean.
 */
internal class DesktopShutdown(
    private val closeServices: () -> Unit,
    private val disposeCompose: () -> Unit,
    private val terminateProcess: () -> Unit,
) {
    private val shutdownStarted = AtomicBoolean()

    fun menuQuit() {
        if (!shutdownStarted.compareAndSet(false, true)) return
        closeServices()
        disposeCompose()
    }

    fun windowManagerQuit(osName: String) {
        if (!shutdownStarted.compareAndSet(false, true)) return
        if (osName.startsWith("Linux")) {
            try { closeServices() } finally { terminateProcess() }
        } else {
            closeServices()
            disposeCompose()
        }
    }
}

/** Intercepts X11's WM_DELETE before Compose's window listener can invalidate the Skiko drawing surface. */
private class LinuxCloseEventQueue(
    private val mainWindow: java.awt.Window,
    private val onWindowClose: () -> Unit,
) : EventQueue() {
    override fun dispatchEvent(event: AWTEvent) {
        if (event is WindowEvent && event.id == WindowEvent.WINDOW_CLOSING && event.window === mainWindow) {
            onWindowClose()
            return
        }
        super.dispatchEvent(event)
    }
}

private fun runApp(open: List<File>) = application {
    val availableWindow = remember { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val initialWidth = minOf(1360, (availableWindow.width - 48).coerceAtLeast(320))
    val initialHeight = minOf(860, (availableWindow.height - 72).coerceAtLeast(240))
    val paths = remember { AppPaths.resolve().ensure() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val state = remember { AppState(paths, scope) }
    LaunchedEffect(Unit) {
        if (open.isEmpty()) return@LaunchedEffect
        val (projects, models) = open.partition { it.extension.equals("3mf", true) && open.size == 1 }
        runCatching { projects.firstOrNull()?.let(state.prepare::open); models.forEach(state.prepare::importModel) }.onFailure { state.prepare.notice = it.message }
        state.destination = Destination.PREPARE
    }
    val shutdown = remember {
        DesktopShutdown(
            closeServices = { state.environment.close(); state.connector.stop(); state.fleet.shutdown() },
            disposeCompose = { exitApplication() },
            // System.exit waits for JVM shutdown hooks, leaving enough time for Skiko's frame limiter to reach the
            // invalid X11 visual. Services are already synchronously closed, so halt is the safe Linux WM-close edge.
            terminateProcess = { Runtime.getRuntime().halt(0) },
        )
    }
    val osName = System.getProperty("os.name").orEmpty()
    val quit = { state.guardProjectReplacement(shutdown::menuQuit) }
    val closeWindow = { state.guardProjectReplacement { shutdown.windowManagerQuit(osName) } }
    Window(onCloseRequest = closeWindow, title = Glossary.PRODUCT_FAMILY,
        icon = painterResource("brand/mark-violet.svg"), state = rememberWindowState(size = DpSize(initialWidth.dp, initialHeight.dp)),
        onPreviewKeyEvent = { e ->
            // Menu shortcuts (all with Ctrl, so typing is never taken) and Alt+letter to open a menu.
            val menus = appMenus(state, quit)
            if (e.type == KeyEventType.KeyDown && e.isAltPressed && !e.isCtrlPressed) menus.indexOfFirst { it.mnemonic == e.key }.takeIf { it >= 0 }?.let { state.openMenu = it; true } ?: false
            else handleMenuShortcut(menus, e)
        },
        // Keys no focused control used (a text field keeps its Delete): plain shortcuts such as Delete for the selected object.
        onKeyEvent = { e -> handleMenuShortcut(appMenus(state, quit), e, plain = true) }) {
        // Keep the normal desktop floor, but allow a capped HiDPI window to shrink below its initial size.
        window.minimumSize = java.awt.Dimension(
            minOf(960, (initialWidth * 3 / 4).coerceAtLeast(320)),
            minOf(640, (initialHeight * 3 / 4).coerceAtLeast(240)))
        DisposableEffect(window) {
            if (osName.startsWith("Linux")) {
                // One main window owns this interception for its process lifetime. Popping it on Compose disposal
                // would allow a pending native close to reach Skiko while the surface is being torn down.
                Toolkit.getDefaultToolkit().systemEventQueue.push(LinuxCloseEventQueue(window, closeWindow))
            }
            val listener = com.nozzleitall.desktop.environment.DesktopMonitoringWindowListener(state.environment::foreground)
            state.environment.foreground(true)
            window.addWindowListener(listener)
            onDispose { window.removeWindowListener(listener); state.environment.foreground(false) }
        }
        DisposableEffect(window) {
            val dropTarget = workspaceDropTarget(window, state::receiveWorkspaceDrop)
            onDispose { dropTarget.component = null }
        }
        NozzleTheme(state.fleet.settings.value.theme, state.fleet.settings.value.textScale) {
            Column(Modifier.fillMaxSize()) {
                NozzleMenuBar(appMenus(state, quit), state.openMenu) { state.openMenu = it }
                App(state)
            }
            if (state.showCommandPalette) CommandPalette(state, quit)
            if (state.showAbout) AboutWindow(state) { state.showAbout = false }
            if (state.firstRunVisible) SetupWizardDialog(state)
            state.addPrinterRequest?.let { request ->
                AddPrinterDialog(state, autoScan = request.autoScan, compatibilityProfileId = request.compatibilityProfileId,
                    onDone = state::finishPrinterSetup)
            }
            state.pendingPlateEdit?.let { edit ->
                ConfirmDialog(edit.kind.label + "?", edit.detail, edit.kind.label,
                    destructive = true, onConfirm = state::confirmPlateEdit, onDismiss = state::cancelPlateEdit)
            }
            state.conversionReview?.let { review ->
                ConfirmDialog("Save a converted copy?", "The original 3MF will stay as it is.", "Save converted copy",
                    destructive = false, detail = review.losses.joinToString("\n\n"),
                    onConfirm = { state.resolveConversion(true) }, onDismiss = { state.resolveConversion(false) })
            }
            if (state.pendingProjectAction != null && state.conversionReview == null) {
                ConfirmDialog("Save changes?", "${state.prepare.name} has unsaved changes.", "Discard changes",
                    destructive = true, detail = state.projectSaveProblem,
                    onConfirm = { state.continueProjectReplacement(save = false) },
                    onDismiss = state::cancelProjectReplacement,
                    alternativeAction = { NzButton("Save", { state.continueProjectReplacement(save = true) }) })
            }
        }
    }
}

@Composable
fun App(state: AppState) {
    WorkspaceMachineReview(state)
    WorkspaceFiles(state)
    if (state.meshyOpen && state.destination == Destination.PREPARE)
        com.nozzleitall.desktop.prepare.MeshyGenerationDialog(state)
    val c = Nz.colors
    Column(Modifier.fillMaxSize().background(c.background).semantics { paneTitle = state.destination.label }) {
        AppHeader(state)
        JobBar(state)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.destination) {
                Destination.JOBS -> JobsScreen(state)
                Destination.FLEET -> FleetScreen(state)
                Destination.PROJECTS -> ProjectsScreen(state)
                Destination.PREPARE -> PrepareScreen(state)
                Destination.MONITOR -> MonitorScreen(state)
                Destination.MATERIALS -> WorkshopMaterialsScreen(state)
                Destination.PRINTER_MATERIALS -> MaterialsScreen(state)
                Destination.HISTORY -> HistoryScreen(state)
                Destination.SETTINGS -> SettingsScreen(state)
                Destination.ENVIRONMENT -> EnvironmentalScreen(state)
            }
        }
    }
}

@Composable
private fun AppHeader(state: AppState) {
    val c = Nz.colors
    val done = state.prepare.slice as? com.nozzleitall.desktop.prepare.SliceState.Done
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val compactWorkflow = usesCompactNavigation(windowWidth, Nz.textScale)
    val activeWorkflow = when {
        state.destination == Destination.MONITOR -> PrintWorkflowStage.MONITOR
        state.workspaceSendOpen -> PrintWorkflowStage.SEND
        state.prepare.showPreview -> PrintWorkflowStage.PREVIEW
        else -> PrintWorkflowStage.PREPARE
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).background(c.surface).border(1.dp, c.line)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "Application header" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.foundation.Image(
            painterResource("brand/mark-violet.svg"),
            contentDescription = Glossary.PRODUCT_FAMILY,
            modifier = Modifier.size(30.dp),
        )
        Box(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState())) {
                    PrintWorkflowBar(
                        active = activeWorkflow,
                        hasPreview = done?.preview != null,
                        hasPrinter = true, // Queue & history is available even without a printer.
                        onSelect = state::openWorkflowStage,
                        modifier = Modifier.semantics { testTag = "header-workflow" },
                        compact = compactWorkflow,
                    )
                }
                Box(Modifier.width(1.dp).height(32.dp).background(c.line))
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val selected = when (state.destination) {
                        Destination.FLEET, Destination.PRINTER_MATERIALS -> Destination.FLEET
                        else -> null
                    }
                    HeaderDestinationTab(Destination.FLEET, selected == Destination.FLEET) { state.destination = Destination.FLEET }
                }
            }
        }
        if (state.destination == Destination.PREPARE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkspaceSliceAction(state)
            }
        }
    }
}

@Composable
private fun HeaderDestinationTab(destination: Destination, selected: Boolean, onClick: () -> Unit) {
    val c = Nz.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.heightIn(min = 36.dp).clip(shape)
            .background(if (selected) c.accent.copy(alpha = 0.16f) else c.surfaceRaised)
            .border(1.dp, if (selected) c.accent else c.line, shape)
            .focusRing(focused, c.focus, shape)
            .selectable(selected, interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(destination.icon, c.accent, 17.dp)
        Txt(destination.label, Nz.type.body, if (selected) c.accent else c.text, maxLines = 1)
    }
}
