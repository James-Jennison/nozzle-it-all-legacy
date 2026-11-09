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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nozzleitall.desktop.prepare.PrepareScreen
import com.nozzleitall.desktop.prepare.PrepareState
import com.nozzleitall.desktop.prepare.WorkspaceSliceAction
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
    JOBS("Jobs", NzIcon.MONITOR, Key.One),
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
internal val primaryDestinations = listOf(Destination.PREPARE, Destination.JOBS)
internal val libraryDestinations = listOf(Destination.PROJECTS, Destination.FLEET, Destination.MATERIALS)
internal val workshopDestinations = listOf(Destination.ENVIRONMENT)

data class HistoryAssessmentDraft(
    val assessment: com.nozzleitall.desktop.jobs.LocalJobStore.Assessment? = null,
    val notes: String = "",
)

data class HistorySaveNotice(val message: String, val isError: Boolean)

enum class WorkspaceToolRequest { ARRANGE, ORIENT, SIMPLIFY }

enum class MonitorHandoffState(val label: String) {
    UPLOADED("Uploaded to printer"),
    QUEUED("Queued for review and start"),
}

data class MonitorHandoff(
    val printerId: String,
    val projectName: String,
    val fileName: String,
    val state: MonitorHandoffState,
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
    /** An action waiting for "discard unsaved changes?" to be answered. */
    var pendingDiscard by mutableStateOf<(() -> Unit)?>(null)
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
private class LinuxCloseEventQueue(private val onWindowClose: () -> Unit) : EventQueue() {
    override fun dispatchEvent(event: AWTEvent) {
        if (event is WindowEvent && event.id == WindowEvent.WINDOW_CLOSING) {
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
    val quit = shutdown::menuQuit
    DisposableEffect(shutdown) {
        val osName = System.getProperty("os.name").orEmpty()
        if (!osName.startsWith("Linux")) return@DisposableEffect onDispose { }
        Toolkit.getDefaultToolkit().systemEventQueue.push(
            LinuxCloseEventQueue { shutdown.windowManagerQuit(osName) },
        )
        // The Linux close edge halts the process, so this queue cannot outlive the application. Popping it during
        // composition disposal would race the exact native shutdown path it protects.
        onDispose { }
    }
    Window(onCloseRequest = quit, title = Glossary.PRODUCT_FAMILY,
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
            val listener = com.nozzleitall.desktop.environment.DesktopMonitoringWindowListener(state.environment::foreground)
            state.environment.foreground(true)
            window.addWindowListener(listener)
            onDispose { window.removeWindowListener(listener); state.environment.foreground(false) }
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
            state.pendingDiscard?.let { action ->
                ConfirmDialog("Discard unsaved changes?", "${state.prepare.name} has changes that aren't saved.", "Discard changes", destructive = true,
                    onConfirm = { state.pendingDiscard = null; action() }, onDismiss = { state.pendingDiscard = null })
            }
        }
    }
}

@Composable
fun App(state: AppState) {
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
                        hasPrinter = state.prepare.printer() != null || state.selectedPrinter != null,
                        onSelect = state::openWorkflowStage,
                        modifier = Modifier.semantics { testTag = "header-workflow" },
                    )
                }
                Box(Modifier.width(1.dp).height(32.dp).background(c.line))
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val selected = when (state.destination) {
                        Destination.FLEET, Destination.PRINTER_MATERIALS -> Destination.FLEET
                        Destination.JOBS, Destination.HISTORY -> Destination.JOBS
                        else -> null
                    }
                    HeaderDestinationTab(Destination.FLEET, selected == Destination.FLEET) { state.destination = Destination.FLEET }
                    HeaderDestinationTab(Destination.JOBS, selected == Destination.JOBS) { state.destination = Destination.JOBS }
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
