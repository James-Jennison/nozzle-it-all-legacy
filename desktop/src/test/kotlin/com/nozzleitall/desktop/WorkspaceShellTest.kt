package com.nozzleitall.desktop

import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import com.nozzleitall.desktop.prepare.TransformTool
import com.nozzleitall.desktop.prepare.Grab
import com.nozzleitall.desktop.prepare.grab
import com.nozzleitall.desktop.prepare.bodyGrab
import com.nozzleitall.desktop.prepare.toolheadLabel
import com.nozzleitall.desktop.prepare.MaterialObjectUsage
import com.nozzleitall.desktop.prepare.materialObjectAssignments
import com.nozzleitall.desktop.prepare.sendPreflightVisible
import com.nozzleitall.desktop.prepare.previewWarningMessages
import com.nozzleitall.desktop.prepare.GcodePreview
import com.nozzleitall.desktop.prepare.compactContextWidth
import com.nozzleitall.desktop.prepare.setupRowLabelWidth
import com.nozzleitall.desktop.prepare.disconnectedConnectionLabel
import com.nozzleitall.desktop.prepare.objectScaleFieldWidth
import com.nozzleitall.desktop.prepare.plateToolbarVisibleCount
import com.nozzleitall.desktop.prepare.compactToolbarMaxWidth
import com.nozzleitall.desktop.prepare.compactSetupContextVisible
import com.nozzleitall.desktop.prepare.compactPreviewLegendMaxHeight
import com.nozzleitall.desktop.prepare.compactPreviewOverlayMaxHeight
import com.nozzleitall.desktop.prepare.compactDrawerMaxHeight
import com.nozzleitall.desktop.prepare.CompactPreviewLayerNavigatorFootprint
import com.nozzleitall.desktop.prepare.CompactPreviewResetViewFootprintAndGap
import com.nozzleitall.desktop.prepare.compactPreviewFooterReservation
import com.nozzleitall.desktop.prepare.compactPreviewFooterLayout
import com.nozzleitall.desktop.prepare.compactPreviewComposesResetView
import com.nozzleitall.desktop.prepare.compactAdviceVisible
import com.nozzleitall.desktop.prepare.viewportAdviceVisible
import com.nozzleitall.desktop.prepare.workspaceBarUsesTwoRows
import com.nozzleitall.desktop.prepare.workspaceUsesCompactLayout
import com.nozzleitall.desktop.prepare.workspaceUsesInspectorPane
import com.nozzleitall.desktop.prepare.workspaceSidebarWidth
import com.nozzleitall.desktop.prepare.workspaceSidebarBounds
import com.nozzleitall.desktop.prepare.workspaceWideCanvasWidth
import com.nozzleitall.desktop.prepare.workspaceGlobalPanelWidth
import com.nozzleitall.desktop.prepare.workspaceSettingsMinimumHeight
import com.nozzleitall.desktop.prepare.workspaceSetupMaximumHeight
import com.nozzleitall.desktop.prepare.workspaceFoldsFilamentByDefault
import com.nozzleitall.desktop.prepare.workspaceInspectorOpensByDefault
import com.nozzleitall.desktop.prepare.resetViewEndPadding
import com.nozzleitall.desktop.prepare.filamentSlotRows
import com.nozzleitall.desktop.prepare.filamentBadgeNeedsOutline
import com.nozzleitall.desktop.prepare.flushingVolumesVisible
import com.nozzleitall.desktop.prepare.nozzleFlowOptions
import com.nozzleitall.desktop.prepare.nozzleFlowControlEnabled
import com.nozzleitall.desktop.prepare.effectiveNozzleFlow
import com.nozzleitall.desktop.prepare.adviceUsesStackedLayout
import com.nozzleitall.desktop.ui.PrintWorkflowStage
import com.nozzleitall.desktop.ui.printWorkflowStageEnabled
import com.nozzleitall.desktop.ui.printWorkflowStageDescription
import com.nozzleitall.desktop.screens.monitorHandoffSuperseded
import com.nozzleitall.printer.ConnectionRoute
import com.nozzleitall.printer.JobProgress
import com.nozzleitall.printer.PrinterState
import com.nozzleitall.printer.PrinterStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceShellTest {
    @Test
    fun `print workflow keeps familiar stages visible in their conventional order`() {
        assertEquals(
            listOf("Prepare", "Preview", "Send", "Monitor"),
            PrintWorkflowStage.entries.map { it.label },
        )
        assertTrue(printWorkflowStageEnabled(PrintWorkflowStage.PREPARE, hasPreview = false, hasPrinter = false))
        assertTrue(!printWorkflowStageEnabled(PrintWorkflowStage.PREVIEW, hasPreview = false, hasPrinter = true))
        assertTrue(!printWorkflowStageEnabled(PrintWorkflowStage.SEND, hasPreview = false, hasPrinter = true))
        assertTrue(printWorkflowStageEnabled(PrintWorkflowStage.PREVIEW, hasPreview = true, hasPrinter = false))
        assertTrue(printWorkflowStageEnabled(PrintWorkflowStage.SEND, hasPreview = true, hasPrinter = false))
        assertTrue(!printWorkflowStageEnabled(PrintWorkflowStage.MONITOR, hasPreview = true, hasPrinter = false))
        assertTrue(printWorkflowStageEnabled(PrintWorkflowStage.MONITOR, hasPreview = false, hasPrinter = true))
    }

    @Test
    fun `print workflow explains every disabled prerequisite to assistive technology`() {
        assertEquals(
            "Prepare. Current stage.",
            printWorkflowStageDescription(PrintWorkflowStage.PREPARE, PrintWorkflowStage.PREPARE, hasPreview = false, hasPrinter = false),
        )
        assertEquals(
            "Preview. Unavailable until the current plate is sliced.",
            printWorkflowStageDescription(PrintWorkflowStage.PREVIEW, PrintWorkflowStage.PREPARE, hasPreview = false, hasPrinter = false),
        )
        assertEquals(
            "Print plate. Unavailable until the current plate is sliced.",
            printWorkflowStageDescription(PrintWorkflowStage.SEND, PrintWorkflowStage.PREPARE, hasPreview = false, hasPrinter = false),
        )
        assertEquals(
            "Monitor. Unavailable until a printer is selected.",
            printWorkflowStageDescription(PrintWorkflowStage.MONITOR, PrintWorkflowStage.PREPARE, hasPreview = false, hasPrinter = false),
        )
        assertEquals(
            "Preview. Available.",
            printWorkflowStageDescription(PrintWorkflowStage.PREVIEW, PrintWorkflowStage.PREPARE, hasPreview = true, hasPrinter = false),
        )
        assertEquals(
            "Monitor. Available.",
            printWorkflowStageDescription(PrintWorkflowStage.MONITOR, PrintWorkflowStage.PREPARE, hasPreview = false, hasPrinter = true),
        )
    }

    @Test
    fun `workspace exposes the conventional transform tools in familiar order`() {
        assertEquals(listOf("Move", "Rotate", "Scale"), TransformTool.entries.map { it.label })
        assertEquals(listOf(Grab.MOVE, Grab.ROTATE, Grab.SCALE), TransformTool.entries.map { it.grab() })
        assertEquals(listOf(Grab.MOVE, Grab.SELECT, Grab.SELECT), TransformTool.entries.map { it.bodyGrab() })
    }

    @Test
    fun `desktop opens in the slicer workspace`() {
        val root = Files.createTempDirectory("nia-workspace-shell-").toFile()
        val app = AppState(
            AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        assertEquals(Destination.PREPARE, app.destination)
    }

    @Test
    fun `every destination receives one canonical slicing machine and nozzle summary`() {
        val root = Files.createTempDirectory("nia-machine-summary-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        try {
            app.finishFirstRun(SlicingSetup("snapmaker_u1", "Snapmaker U1", "0.4", toolheads = 4))

            assertEquals("Snapmaker U1", app.prepare.slicingMachineLabel)
            assertEquals("0.4 mm · 4 toolheads", app.prepare.nozzleToolheadLabel)
            assertTrue(!app.prepare.slicingMachineLabel.contains("Snapmaker Snapmaker"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `workspace is the first invariant header destination`() {
        assertEquals(Destination.PREPARE, Destination.entries.first())
        assertTrue(Destination.PREPARE.inDestinations)
        assertEquals(Destination.PREPARE, Destination.PREPARE.destinationItem)
        assertEquals(
            listOf("Workspace", "Projects", "Printers", "Materials", "Environment", "Settings"),
            Destination.entries.filter { it.inDestinations }.map { it.label },
        )
    }

    @Test
    fun `destination header menu separates slicing from libraries and workshop tools`() {
        assertEquals(listOf("Workspace"), primaryDestinations.map { it.label })
        assertEquals(listOf("Projects", "Printers", "Materials"), libraryDestinations.map { it.label })
        assertEquals(listOf("Environment"), workshopDestinations.map { it.label })
        assertEquals(
            Destination.entries.filter { it.inDestinations && it != Destination.SETTINGS }.toSet(),
            (primaryDestinations + libraryDestinations + workshopDestinations).toSet(),
        )
        assertEquals(Destination.FLEET, Destination.MONITOR.destinationItem)
        assertEquals(Destination.FLEET, Destination.PRINTER_MATERIALS.destinationItem)
        assertEquals(Destination.JOBS, Destination.HISTORY.destinationItem)
    }

    @Test
    fun `workspace preserves familiar left anchor and opens the inspector only on demand`() {
        assertTrue(!workspaceUsesCompactLayout(1360.dp, 1f)) // the workspace owns the full 1360px shell
        assertTrue(!workspaceUsesCompactLayout(1920.dp, 1f))
        assertTrue(!workspaceUsesCompactLayout(1360.dp, 1.5f)) // no persistent rail steals scaled workspace width
        assertTrue(!workspaceUsesCompactLayout(1400.dp, 1f))
        assertTrue(!workspaceUsesCompactLayout(1100.dp, 1f))
        assertTrue(!workspaceUsesCompactLayout(1250.dp, 2f))
        assertTrue(!workspaceUsesCompactLayout(1672.dp, 1f))
        assertTrue(!workspaceUsesCompactLayout(1671.dp, 1f))
        assertTrue(workspaceUsesCompactLayout(955.dp, 1f))
        assertTrue(!workspaceUsesCompactLayout(956.dp, 2f))
        assertTrue(workspaceUsesInspectorPane(false, requested = true, forcedContext = false))
        assertTrue(workspaceUsesInspectorPane(false, requested = false, forcedContext = true))
        assertTrue(!workspaceUsesInspectorPane(false, requested = false, forcedContext = false))
        assertTrue(!workspaceUsesInspectorPane(true, requested = true, forcedContext = true))
        assertEquals(400.dp, workspaceSidebarWidth(1360.dp, 1f))
        assertEquals(600.dp, workspaceSidebarWidth(1360.dp, 1.5f))
        assertEquals(600.dp, workspaceSidebarWidth(1920.dp, 1.5f))
        assertEquals(360.dp, workspaceSidebarBounds(1360.dp, 1f).minimum)
        assertEquals(640.dp, workspaceSidebarBounds(1360.dp, 1f).maximum)
        assertEquals(400.dp, workspaceSidebarBounds(1360.dp, 1.5f).minimum)
        assertEquals(580.dp, workspaceSidebarWidth(1360.dp, 1f, 580.dp))
        assertEquals(360.dp, workspaceSidebarWidth(1360.dp, 1f, 200.dp))
        assertEquals(640.dp, workspaceSidebarWidth(1360.dp, 1f, 800.dp))
        assertTrue((1360f - 600f - 36f) / 1360f >= 0.53f)
        assertEquals(924.dp, workspaceWideCanvasWidth(1360.dp, inspectorOpen = false))
        assertEquals(924.dp, workspaceWideCanvasWidth(1360.dp, inspectorOpen = true))
        assertEquals(19, plateToolbarVisibleCount(workspaceWideCanvasWidth(1360.dp, inspectorOpen = false), 19))
        assertEquals(19, plateToolbarVisibleCount(workspaceWideCanvasWidth(1360.dp, inspectorOpen = true), 19))
        assertEquals(1364.dp, workspaceWideCanvasWidth(1920.dp, inspectorOpen = false))
        assertEquals(1364.dp, workspaceWideCanvasWidth(1920.dp, inspectorOpen = true))
        assertEquals(1167.84f, workspaceWideCanvasWidth(1672.dp, inspectorOpen = false).value, 0.01f)
        assertEquals(1167.84f, workspaceWideCanvasWidth(1672.dp, inspectorOpen = true).value, 0.01f)
        assertEquals(264.dp, workspaceWideCanvasWidth(700.dp, inspectorOpen = false))
        assertEquals(440.dp, workspaceGlobalPanelWidth(1400.dp, 1f, process = true))
        assertEquals(400.dp, workspaceGlobalPanelWidth(1400.dp, 1f, process = false))
        assertEquals(520.dp, workspaceGlobalPanelWidth(1400.dp, 1.5f, process = true))
        assertEquals(560.dp, workspaceGlobalPanelWidth(1700.dp, 1f, process = true))
        assertEquals(440.dp, workspaceGlobalPanelWidth(1700.dp, 1f, process = false))
        assertEquals(396.dp, workspaceGlobalPanelWidth(420.dp, 1f, process = true))
        assertTrue(adviceUsesStackedLayout(420.dp, 1f))
        assertTrue(adviceUsesStackedLayout(700.dp, 1.5f))
        assertTrue(!adviceUsesStackedLayout(700.dp, 1f))
    }

    @Test
    fun `large text keeps every filament slot above a usable process settings floor`() {
        assertEquals(260.dp, workspaceSettingsMinimumHeight(860.dp, 1.5f))
        assertEquals(260.dp, workspaceSettingsMinimumHeight(860.dp, 2f))
        assertEquals(260.dp, workspaceSettingsMinimumHeight(860.dp, 1f))
        assertTrue(workspaceSettingsMinimumHeight(670.dp, 1f) >= 260.dp)
        assertTrue(workspaceSettingsMinimumHeight(670.dp, 1.5f) >= 260.dp)
        assertTrue(workspaceSetupMaximumHeight(670.dp, 1f) >= 174.dp)
        assertEquals(380.dp, workspaceSetupMaximumHeight(670.dp, 1f))
        assertTrue(workspaceSetupMaximumHeight(670.dp, 1.5f) >= 380.dp)
        assertTrue(workspaceSetupMaximumHeight(860.dp, 1.5f) <= 420.dp)
        assertTrue(workspaceSetupMaximumHeight(860.dp, 1f) <= 380.dp)
        assertTrue(
            workspaceSetupMaximumHeight(670.dp, 1f) +
                workspaceSettingsMinimumHeight(670.dp, 1f) <= 654.dp,
        )
    }

    @Test
    fun `1360 workspace folds filament and keeps object inspector on demand`() {
        assertTrue(workspaceFoldsFilamentByDefault(1360.dp))
        assertTrue(!workspaceFoldsFilamentByDefault(1361.dp))
        assertTrue(!workspaceInspectorOpensByDefault(1360.dp))
        assertTrue(workspaceInspectorOpensByDefault(1361.dp))
        assertEquals(260.dp, workspaceSettingsMinimumHeight(860.dp, 1.5f))
        assertEquals(360.dp, resetViewEndPadding(inspectorVisible = true, compactWorkspace = false))
        assertEquals(0.dp, resetViewEndPadding(inspectorVisible = false, compactWorkspace = false))
        assertEquals(0.dp, resetViewEndPadding(inspectorVisible = true, compactWorkspace = true))
    }

    @Test
    fun `prepare uses Orca style filament and nozzle picker rules`() {
        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), filamentSlotRows(4))
        assertEquals(listOf(listOf(1, 2), listOf(3)), filamentSlotRows(3))
        assertTrue(filamentBadgeNeedsOutline("#000000"))
        assertTrue(filamentBadgeNeedsOutline("#111827"))
        assertTrue(!filamentBadgeNeedsOutline("#F6F7F9"))
        assertEquals(listOf("standard"), nozzleFlowOptions(highFlow = false))
        assertEquals(listOf("standard", "high_flow"), nozzleFlowOptions(highFlow = true))
        assertTrue(nozzleFlowControlEnabled(nozzleFlowOptions(highFlow = false)))
        assertTrue(nozzleFlowControlEnabled(nozzleFlowOptions(highFlow = true)))
        assertEquals("standard", effectiveNozzleFlow(nozzleFlowOptions(highFlow = false), "standard"))
        assertEquals("standard", effectiveNozzleFlow(nozzleFlowOptions(highFlow = false), "high_flow"))
        assertEquals("standard", effectiveNozzleFlow(nozzleFlowOptions(highFlow = true), "standard"))
        assertEquals("high_flow", effectiveNozzleFlow(nozzleFlowOptions(highFlow = true), "high_flow"))
        assertTrue(flushingVolumesVisible(slotCount = 4, primeTowerEnabled = true))
        assertTrue(!flushingVolumesVisible(slotCount = 4, primeTowerEnabled = false))
        assertTrue(!flushingVolumesVisible(slotCount = 1, primeTowerEnabled = true))
    }

    @Test
    fun `compact advice never overlaps an open settings or tool panel`() {
        assertTrue(compactAdviceVisible(1f, showPreview = false, controlsOpen = false))
        assertTrue(!compactAdviceVisible(1f, showPreview = false, controlsOpen = true))
        assertTrue(!compactAdviceVisible(1f, showPreview = true, controlsOpen = false))
        assertTrue(!compactAdviceVisible(1.5f, showPreview = false, controlsOpen = false))
        assertTrue(viewportAdviceVisible(true, 1f, showPreview = false, controlsOpen = false))
        assertTrue(!viewportAdviceVisible(false, 1f, showPreview = false, controlsOpen = false))
    }

    @Test
    fun `monitor handoff distinguishes uploaded from queued`() {
        assertEquals("Uploaded to printer", MonitorHandoffState.UPLOADED.label)
        assertEquals("Queued for review and start", MonitorHandoffState.QUEUED.label)
    }

    @Test
    fun `ordinary Monitor entry and workspace return clear optimistic handoff`() {
        val root = Files.createTempDirectory("nia-monitor-handoff-").toFile()
        val app = AppState(
            AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val handoff = MonitorHandoff("printer-1", "Project", "job.gcode", MonitorHandoffState.QUEUED)

        app.openJobMonitor(handoff)
        assertEquals(handoff, app.monitorHandoff)
        app.returnToWorkspace(preview = true)
        assertEquals(null, app.monitorHandoff)
        assertEquals(Destination.PREPARE, app.destination)
        assertTrue(app.prepare.showPreview)

        app.openJobMonitor(handoff)
        app.returnToWorkspace(preview = true, send = true)
        assertTrue(app.workspaceSendRequested)
        app.consumeWorkspaceSendRequest()
        assertTrue(!app.workspaceSendRequested)

        app.openJobMonitor(handoff)
        app.openPrinter("printer-1")
        assertEquals(null, app.monitorHandoff)

        app.openJobMonitor(handoff)
        app.destination = Destination.JOBS
        assertEquals(null, app.monitorHandoff)

        app.openJobMonitor(handoff)
        app.destination = Destination.HISTORY
        assertEquals(null, app.monitorHandoff)

        app.destination = Destination.MONITOR
        assertEquals(null, app.monitorHandoff)
    }

    @Test
    fun `every ordinary destination permanently retires an optimistic Monitor handoff`() {
        val root = Files.createTempDirectory("nia-monitor-navigation-").toFile()
        val app = AppState(
            AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val handoff = MonitorHandoff("printer-1", "Project", "job.gcode", MonitorHandoffState.QUEUED)

        listOf(Destination.PREPARE, Destination.JOBS, Destination.HISTORY, Destination.PROJECTS).forEach { destination ->
            app.openJobMonitor(handoff)
            app.destination = destination
            assertEquals(null, app.monitorHandoff)
            app.destination = Destination.MONITOR
            assertEquals(null, app.monitorHandoff)
        }

        app.openJobMonitor(handoff)
        app.openPrinter("printer-1")
        assertEquals(null, app.monitorHandoff)
        app.destination = Destination.MONITOR
        assertEquals(null, app.monitorHandoff)
    }

    @Test
    fun `live and terminal printer status supersede optimistic handoff`() {
        fun status(state: PrinterState, job: JobProgress? = null) = PrinterStatus(state, ConnectionRoute.LAN, job)
        assertTrue(!monitorHandoffSuperseded(status(PrinterState.READY)))
        assertTrue(monitorHandoffSuperseded(status(PrinterState.PRINTING)))
        assertTrue(monitorHandoffSuperseded(status(PrinterState.READY, JobProgress("job.gcode", 0f))))
        assertTrue(monitorHandoffSuperseded(status(PrinterState.FINISHED)))
        assertTrue(monitorHandoffSuperseded(status(PrinterState.CANCELLED)))
        assertTrue(monitorHandoffSuperseded(status(PrinterState.ERROR)))
    }

    @Test
    fun `superseding live state permanently retires an optimistic handoff`() {
        val root = Files.createTempDirectory("nia-monitor-live-").toFile()
        val app = AppState(
            AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val handoff = MonitorHandoff("printer-1", "Project", "job.gcode", MonitorHandoffState.UPLOADED)
        app.openJobMonitor(handoff)
        app.reconcileMonitorHandoff(handoff, superseded = true)
        assertEquals(null, app.monitorHandoff)
        app.reconcileMonitorHandoff(handoff, superseded = false)
        assertEquals(null, app.monitorHandoff)
    }

    @Test
    fun `toolhead labels pin zero based printer indices and never guess from a slot`() {
        assertEquals("Toolhead 1", toolheadLabel(0, 1))
        assertEquals("Toolhead 4", toolheadLabel(3, 4))
        assertEquals("Slot 2", toolheadLabel(null, 2))
    }

    @Test
    fun `material mapping names base objects and painted regions once`() {
        val objects = listOf(
            MaterialObjectUsage("Housing", 1, setOf(2, 3)),
            MaterialObjectUsage("Badge", 2, setOf(2)),
            MaterialObjectUsage("Feet", 4, emptySet()),
        )

        assertEquals(listOf("Housing"), materialObjectAssignments(1, objects))
        assertEquals(listOf("Housing", "Badge"), materialObjectAssignments(2, objects))
        assertEquals(listOf("Housing"), materialObjectAssignments(3, objects))
        assertEquals(listOf("Feet"), materialObjectAssignments(4, objects))
    }

    @Test
    fun `Send preflight is visible only from Preview with a completed slice`() {
        assertTrue(sendPreflightVisible(showPreview = true, sendRequested = true, sliceDone = true))
        assertTrue(!sendPreflightVisible(showPreview = false, sendRequested = true, sliceDone = true))
        assertTrue(!sendPreflightVisible(showPreview = true, sendRequested = false, sliceDone = true))
        assertTrue(!sendPreflightVisible(showPreview = true, sendRequested = true, sliceDone = false))
    }

    @Test
    fun `Preview warnings are shared by wide and compact layouts`() {
        val preview = GcodePreview(emptyList(), truncated = true, discardedPreamble = true)
        val warnings = previewWarningMessages(preview)
        assertEquals(2, warnings.size)
        assertTrue(warnings.any { "limited" in it })
        assertTrue(warnings.any { "before the first layer marker" in it })
    }

    @Test
    fun `compact toolbar reserves the exact context card width at normal and large text scales`() {
        assertEquals(774f, compactToolbarMaxWidth(1100.dp, 1f).value)
        assertEquals(476f, compactToolbarMaxWidth(500.dp, 1.5f).value)
        assertEquals(244f, compactToolbarMaxWidth(600.dp, 1.5f).value)
        assertEquals(320f, compactContextWidth(600.dp, 1.5f).value)
        assertEquals(744f, compactToolbarMaxWidth(1100.dp, 1.5f).value)
        assertEquals(320f, compactContextWidth(1100.dp, 1.5f).value)
        assertEquals(80f, setupRowLabelWidth(1f).value)
        assertEquals(112f, setupRowLabelWidth(1.5f).value)
        assertEquals("Export only", disconnectedConnectionLabel())
        assertEquals(64f, objectScaleFieldWidth(1f).value)
        assertEquals(82f, objectScaleFieldWidth(1.5f).value)
        assertEquals(19, plateToolbarVisibleCount(780.dp, 19))
        assertEquals(9, plateToolbarVisibleCount(470.dp, 19))
        assertEquals(4, plateToolbarVisibleCount(200.dp, 19))
    }

    @Test
    fun `compact workspace keeps setup context visible until a tool or settings surface replaces it`() {
        assertTrue(compactSetupContextVisible(compactWorkspace = true, controlsOpen = false))
        assertTrue(!compactSetupContextVisible(compactWorkspace = true, controlsOpen = true))
        assertTrue(!compactSetupContextVisible(compactWorkspace = false, controlsOpen = false))
    }

    @Test
    fun `large text keeps Preview controls in separate bounded surfaces`() {
        assertTrue(!workspaceBarUsesTwoRows(1052.dp, 1f))
        assertTrue(!workspaceBarUsesTwoRows(1360.dp, 1f))
        assertTrue(!workspaceBarUsesTwoRows(1052.dp, 1.5f))
        assertTrue(!workspaceBarUsesTwoRows(1360.dp, 1.5f))
        assertTrue(!workspaceBarUsesTwoRows(1700.dp, 1.5f))
        assertEquals(440f, compactPreviewLegendMaxHeight(1.5f)?.value)
        assertEquals(400f, compactPreviewLegendMaxHeight(2f)?.value)
        assertEquals(null, compactPreviewLegendMaxHeight(1.25f))
        assertEquals(286f, compactPreviewOverlayMaxHeight(438.dp).value)
        assertEquals(120f, compactPreviewOverlayMaxHeight(180.dp).value)
        assertEquals(326f, compactDrawerMaxHeight(438.dp, 1.5f, showPreview = true).value)
        assertEquals(286f, compactDrawerMaxHeight(438.dp, 1.25f, showPreview = true).value)
        assertEquals(414f, compactDrawerMaxHeight(438.dp, 1.5f, showPreview = false).value)
    }

    @Test
    fun `compact Preview surfaces reserve the composed footer footprint at every accepted scale`() {
        val viewportHeight = 438.dp
        mapOf(1f to true, 1.25f to true, 1.5f to false, 2f to false).forEach { (textScale, expectedVisible) ->
            val footer = compactPreviewFooterLayout(textScale, controlsOpen = true)
            val expectedFootprint = CompactPreviewLayerNavigatorFootprint +
                if (expectedVisible) CompactPreviewResetViewFootprintAndGap else 0.dp

            assertEquals(expectedVisible, footer.showResetView)
            assertEquals(expectedFootprint, footer.reservedFootprint)
            assertEquals(
                (viewportHeight - footer.reservedFootprint).coerceAtLeast(120.dp),
                compactDrawerMaxHeight(viewportHeight, textScale, showPreview = true),
            )
        }

        val visible = compactPreviewFooterLayout(2f, controlsOpen = false)
        val suppressed = compactPreviewFooterLayout(2f, controlsOpen = true)
        assertTrue(visible.showResetView)
        assertTrue(!suppressed.showResetView)
        assertEquals(CompactPreviewResetViewFootprintAndGap,
            visible.reservedFootprint - suppressed.reservedFootprint)
        assertEquals(CompactPreviewLayerNavigatorFootprint, suppressed.reservedFootprint)
        assertEquals(visible.reservedFootprint, compactPreviewFooterReservation(showResetView = true))
        assertEquals(suppressed.reservedFootprint, compactPreviewFooterReservation(showResetView = false))
    }

    @Test
    fun `compact workspace reset row composition consumes the shared footer decision`() {
        val visible = compactPreviewFooterLayout(2f, controlsOpen = false)
        val suppressed = compactPreviewFooterLayout(2f, controlsOpen = true)

        assertTrue(compactPreviewComposesResetView(
            compactWorkspace = true,
            hasObjectsOnPlate = true,
            showPreview = false,
            footerLayout = visible,
        ))
        assertTrue(compactPreviewComposesResetView(
            compactWorkspace = true,
            hasObjectsOnPlate = false,
            showPreview = true,
            footerLayout = visible,
        ))
        assertTrue(compactPreviewComposesResetView(
            compactWorkspace = false,
            hasObjectsOnPlate = false,
            showPreview = false,
            footerLayout = visible,
        ))
        assertTrue(!compactPreviewComposesResetView(
            compactWorkspace = true,
            hasObjectsOnPlate = false,
            showPreview = false,
            footerLayout = visible,
        ))
        assertTrue(!compactPreviewComposesResetView(
            compactWorkspace = false,
            hasObjectsOnPlate = true,
            showPreview = true,
            footerLayout = suppressed,
        ))
    }

    @Test
    fun `Linux window-manager close terminates before Compose disposal and closes services once`() {
        var closes = 0
        var disposals = 0
        var terminations = 0
        val shutdown = DesktopShutdown({ closes++ }, { disposals++ }, { terminations++ })

        shutdown.windowManagerQuit("Linux")
        shutdown.windowManagerQuit("Linux")

        assertEquals(1, closes)
        assertEquals(0, disposals)
        assertEquals(1, terminations)
    }

    @Test
    fun `menu and non-Linux close retain ordinary Compose disposal`() {
        val events = mutableListOf<String>()
        val shutdown = DesktopShutdown(
            closeServices = { events += "services" },
            disposeCompose = { events += "compose" },
            terminateProcess = { events += "process" },
        )

        shutdown.menuQuit()
        shutdown.windowManagerQuit("Windows 11")

        assertEquals(listOf("services", "compose"), events)
        assertTrue("process" !in events)
    }

    @Test
    fun `Linux termination still runs when service cleanup reports a failure`() {
        val events = mutableListOf<String>()
        val shutdown = DesktopShutdown(
            closeServices = { events += "services"; error("cleanup failed") },
            disposeCompose = { events += "compose" },
            terminateProcess = { events += "process" },
        )

        runCatching { shutdown.windowManagerQuit("Linux") }

        assertEquals(listOf("services", "process"), events)
    }
}
