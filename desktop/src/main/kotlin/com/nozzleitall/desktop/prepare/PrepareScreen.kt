@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
package com.nozzleitall.desktop.prepare

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.nozzleitall.design.NozzleTokens
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.SlicingSetup
import com.nozzleitall.desktop.PlateEditKind
import com.nozzleitall.desktop.WorkspaceToolRequest
import com.nozzleitall.desktop.screens.*
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Cursor
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PreparedSendIdentity(val printerId: String, val preparedDigest: String?,
                                         val projectId: String?, val projectRevision: Long?, val plateId: String?,
                                         val targetProfileId: String?, val inputDigest: String?, val engineBuild: String?,
                                         val draftPlateId: String? = null)

internal fun isSameQueuedPrint(item: com.nozzleitall.desktop.queue.QueueItem, request: PreparedSendIdentity): Boolean {
    if (request.preparedDigest == null || item.sha256 != request.preparedDigest ||
        item.printerId != request.printerId || item.targetProfileId != request.targetProfileId) return false
    return if (request.projectId != null && request.projectRevision != null && request.plateId != null) {
        item.projectId == request.projectId && item.projectRevision == request.projectRevision && item.plateId == request.plateId
    } else {
        request.projectId == null && request.projectRevision == null && request.plateId == null &&
            item.projectId == null && item.projectRevision == null && item.plateId == null &&
            request.inputDigest != null && item.inputDigest == request.inputDigest &&
            request.engineBuild != null && item.engineBuild == request.engineBuild &&
            request.draftPlateId != null && item.draftPlateId == request.draftPlateId
    }
}

internal fun digestPreparedFile(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        md.update(buffer, 0, count)
    } }
    return md.digest().joinToString("") { "%02x".format(it) }
}

internal fun writePresetComparison(changes: List<String>, target: File) {
    check(!target.exists()) { "The selected comparison file already exists. Choose a new name." }
    val temporary = File(target.parentFile, ".${target.name}.${java.util.UUID.randomUUID()}.part")
    try {
        temporary.outputStream().use { output ->
            output.write(changes.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        java.nio.file.Files.createLink(target.toPath(), temporary.toPath())
    } finally { temporary.delete() }
}

internal data class PreviewLayerCursor(val index: Int, val count: Int, val z: Float)

internal fun previewLayerCursor(preview: GcodePreview, requested: Int): PreviewLayerCursor? {
    if (preview.layers.isEmpty()) return null
    val index = requested.coerceIn(0, preview.layers.lastIndex)
    return PreviewLayerCursor(index, preview.layers.size, preview.layers[index].z)
}

@Composable
private fun profileValuesFor(p: PrepareState): Result<com.nozzleitall.desktop.settings.ProfileValues>? {
    val selection = p.presetSelection()
    return key(selection) {
        val loaded by produceState<Result<com.nozzleitall.desktop.settings.ProfileValues>?>(null) {
            value = withContext(Dispatchers.IO) { runCatching {
                com.nozzleitall.desktop.settings.ProfileValues.read(p.profileDir(selection),
                    com.nozzleitall.desktop.settings.SettingsCatalog.bundled)
            } }
        }
        loaded
    }
}

fun chooseFiles(title: String, extensions: List<String>, multiple: Boolean = true): List<File> {
    val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
    d.isMultipleMode = multiple
    if (extensions.isNotEmpty()) d.setFilenameFilter { _, n -> extensions.any { n.endsWith(".$it", true) } }
    d.isVisible = true
    return d.files?.toList() ?: emptyList()
}

fun chooseSaveFile(title: String, suggested: String): File? {
    val d = FileDialog(null as Frame?, title, FileDialog.SAVE); d.file = suggested; d.isVisible = true
    return d.file?.let { File(d.directory, it) }
}

private enum class Drawer { PRINTER, FILAMENT, PROCESS, SEND, CUT, MODEL, AI }
private enum class ModelingTool(val label: String) {
    PLACE_ON_FACE("Place on face"), BOOLEAN("Boolean"), SIMPLIFY("Simplify"), REPAIR("Repair"), MEASURE("Measure"),
    TEXT("Text"), SVG("SVG"), CAD("CAD and STEP"), MESHY("Meshy 3D generation")
}

private val FamiliarLeftPaneWidth = 400.dp
private val FamiliarLeftPaneMaximumWidth = 520.dp
private val AccessibleLeftPaneWidth = 600.dp
private val AdjustableLeftPaneMaximumWidth = 640.dp
private val FamiliarWorkspaceGapAndInsets = 36.dp
private val MinimumFamiliarCanvasWidth = 520.dp

internal data class WorkspaceSidebarBounds(val minimum: Dp, val maximum: Dp)

internal fun workspaceSidebarWidth(width: Dp, textScale: Float = 1f): Dp {
    val familiar = (width * 0.28f).coerceIn(FamiliarLeftPaneWidth, FamiliarLeftPaneMaximumWidth)
    // At 150% use the accessible pane width. After the sidebar and
    // compact-card insets, this leaves enough room for all six full Orca category
    // names without reducing the user's requested text size. The pane remains
    // adjustable; narrower user-selected widths use the tab strip's scroll fallback.
    return if (textScale >= 1.5f) familiar.coerceAtLeast(AccessibleLeftPaneWidth) else familiar
}

internal fun workspaceSidebarBounds(width: Dp, textScale: Float): WorkspaceSidebarBounds {
    val minimum = if (textScale >= 1.5f) 400.dp else 360.dp
    val available = (width - FamiliarWorkspaceGapAndInsets - MinimumFamiliarCanvasWidth).coerceAtLeast(minimum)
    return WorkspaceSidebarBounds(minimum, minOf(AdjustableLeftPaneMaximumWidth, available).coerceAtLeast(minimum))
}

internal fun workspaceSidebarWidth(
    width: Dp,
    textScale: Float,
    requested: Dp?,
): Dp {
    val bounds = workspaceSidebarBounds(width, textScale)
    return (requested ?: workspaceSidebarWidth(width, textScale)).coerceIn(bounds.minimum, bounds.maximum)
}

@Composable
private fun SidebarEdge(
    width: Dp,
    onCollapse: () -> Unit,
    onResizeTo: (Dp) -> Unit,
    collapseFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val c = Nz.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(
        modifier.width(28.dp).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SidebarCollapseControl(collapseFocusRequester, onCollapse)
        Box(
            Modifier.width(12.dp).weight(1f)
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(width, density) {
                    var accumulatedX = 0f
                    detectDragGestures(
                        onDragStart = { accumulatedX = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumulatedX += dragAmount.x
                            onResizeTo(width + with(density) { accumulatedX.toDp() })
                        },
                    )
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> onResizeTo(width - 16.dp)
                        Key.DirectionRight -> onResizeTo(width + 16.dp)
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .focusable()
                .semantics {
                    contentDescription = "Resize settings sidebar"
                    stateDescription = "${width.value.toInt()} dp wide"
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(c.lineStrong))
        }
    }
}

@Composable
internal fun SidebarCollapseControl(focusRequester: FocusRequester, onCollapse: () -> Unit) {
    val c = Nz.colors
    TooltipArea(tooltip = { SidebarTooltip("Collapse settings sidebar") }, delayMillis = 500) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                .focusRequester(focusRequester)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Collapse settings sidebar",
                    onClick = onCollapse,
                )
                .semantics {
                    contentDescription = "Collapse settings sidebar"
                    stateDescription = "Settings sidebar expanded"
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(NzIcon.ARROW_RIGHT, c.textMuted, 16.dp, modifier = Modifier.rotate(180f))
        }
    }
}

@Composable
internal fun SidebarExpandControl(focusRequester: FocusRequester, onExpand: () -> Unit) {
    val c = Nz.colors
    TooltipArea(tooltip = { SidebarTooltip("Expand settings sidebar") }, delayMillis = 500) {
        Box(
            Modifier.padding(top = 4.dp).size(28.dp).clip(RoundedCornerShape(6.dp))
                .focusRequester(focusRequester)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Expand settings sidebar",
                    onClick = onExpand,
                )
                .semantics {
                    contentDescription = "Expand settings sidebar"
                    stateDescription = "Settings sidebar collapsed"
                },
            contentAlignment = Alignment.Center,
        ) { Icon(NzIcon.ARROW_RIGHT, c.textMuted, 16.dp) }
    }
}

@Composable
private fun SidebarTooltip(text: String) {
    val c = Nz.colors
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(c.surfaceRaised)
            .border(1.dp, c.lineStrong, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
    ) { Txt(text, Nz.type.bodySmall, c.text, maxLines = 1) }
}

internal fun filamentSlotRows(slotCount: Int): List<List<Int>> =
    (1..slotCount.coerceAtLeast(0)).chunked(2)

internal fun flushingVolumesVisible(slotCount: Int, primeTowerEnabled: Boolean): Boolean =
    slotCount > 1 && primeTowerEnabled

internal fun workspaceWideCanvasWidth(width: Dp, inspectorOpen: Boolean = false): Dp =
    // The inspector floats over the canvas. Opening it must never create a third
    // layout column or shrink the plate, so inspectorOpen is intentionally ignored.
    (width - workspaceSidebarWidth(width) - FamiliarWorkspaceGapAndInsets).coerceAtLeast(0.dp)

internal fun workspaceUsesCompactLayout(width: Dp, textScale: Float): Boolean {
    // Established desktop slicers keep printer, filament and process context visible
    // at ordinary laptop widths and let the plate and contextual inspector adapt.
    // Collapse the familiar left pane only when it and a genuinely usable canvas no
    // longer fit. Text scale is intentionally not a second reason to hide this anchor.
    return width < FamiliarLeftPaneWidth + FamiliarWorkspaceGapAndInsets + MinimumFamiliarCanvasWidth
}

internal fun workspaceUsesInspectorPane(
    compactWorkspace: Boolean,
    requested: Boolean,
    forcedContext: Boolean,
): Boolean = !compactWorkspace && (requested || forcedContext)

internal fun workspaceGlobalPanelWidth(viewportWidth: Dp, textScale: Float, process: Boolean): Dp {
    val available = (viewportWidth - 24.dp).coerceAtLeast(280.dp)
    val preferred = when {
        process && textScale >= 1.5f && viewportWidth >= 1600.dp -> 680.dp
        process && textScale >= 1.5f -> 520.dp
        process && viewportWidth >= 1600.dp -> 560.dp
        process -> 440.dp
        viewportWidth >= 1500.dp -> 440.dp
        else -> 400.dp
    }
    return available.coerceAtMost(preferred)
}

internal fun workspaceSettingsMinimumHeight(viewportHeight: Dp, textScale: Float): Dp {
    // Keep the reviewed 260dp Process floor at every text scale. The remainder is
    // deliberately available to the intrinsic Printer/Filament content: when a
    // folded Filament section is opened at 1360px, all four two-line slots and
    // Add Mixed Filament must be visible instead of being clipped by a 174dp cap.
    val preferred = 260.dp
    val share = viewportHeight * 0.40f
    val setupFloor = if (textScale >= 1.5f) 180.dp else 174.dp
    return minOf(preferred, share, (viewportHeight - setupFloor).coerceAtLeast(160.dp))
}

internal fun workspaceFoldsFilamentByDefault(width: Dp): Boolean = width <= 1360.dp

internal fun workspaceInspectorOpensByDefault(width: Dp): Boolean = width > 1360.dp

/** Keeps the bottom-right camera control clear of the floating inspector and its scrollbar. */
internal fun resetViewEndPadding(inspectorVisible: Boolean, compactWorkspace: Boolean): Dp =
    if (inspectorVisible && !compactWorkspace) 360.dp else 0.dp

internal fun workspaceSetupMaximumHeight(viewportHeight: Dp, textScale: Float): Dp {
    val ceiling = if (textScale >= 1.5f) 420.dp else 380.dp
    val floor = if (textScale >= 1.5f) 180.dp else 174.dp
    return (viewportHeight - workspaceSettingsMinimumHeight(viewportHeight, textScale) - 16.dp)
        .coerceIn(floor, ceiling)
}

internal fun adviceUsesStackedLayout(width: Dp, textScale: Float): Boolean =
    textScale >= 1.5f || width < 520.dp

private fun Drawer?.isGlobalSetup(): Boolean =
    this == Drawer.PRINTER || this == Drawer.FILAMENT || this == Drawer.PROCESS

internal fun sendPreflightVisible(showPreview: Boolean, sendRequested: Boolean, sliceDone: Boolean): Boolean =
    showPreview && sendRequested && sliceDone

private val CompactContextWidth = 260.dp
private val LargeTextCompactContextWidth = 320.dp
private val WideContextWidth = 290.dp

internal fun compactContextWidth(viewportWidth: Dp, textScale: Float): Dp = when {
    textScale >= 1.5f -> LargeTextCompactContextWidth
    viewportWidth >= 900.dp -> WideContextWidth
    else -> CompactContextWidth
}

internal fun setupRowLabelWidth(textScale: Float): Dp =
    if (textScale >= 1.5f) 112.dp else 80.dp

internal fun disconnectedConnectionLabel(): String = "Export only"

internal fun compactContextSharesToolbarRow(viewportWidth: Dp, textScale: Float): Boolean =
    viewportWidth >= 900.dp || (viewportWidth >= 530.dp && textScale >= 1.5f)

internal fun compactToolbarMaxWidth(viewportWidth: Dp, textScale: Float): Dp =
    if (compactContextSharesToolbarRow(viewportWidth, textScale)) {
        // 12dp leading inset + 12dp gap + card width + 12dp trailing inset.
        (viewportWidth - compactContextWidth(viewportWidth, textScale) - 36.dp).coerceAtLeast(120.dp)
    } else {
        (viewportWidth - 24.dp).coerceAtLeast(120.dp)
    }

internal fun compactSetupContextVisible(compactWorkspace: Boolean, controlsOpen: Boolean): Boolean =
    compactWorkspace && !controlsOpen

internal fun workspaceBarUsesTwoRows(viewportWidth: Dp, textScale: Float): Boolean = false

internal fun compactPreviewLegendMaxHeight(textScale: Float): Dp? = when {
    textScale >= 2f -> 400.dp
    textScale >= 1.5f -> 440.dp
    else -> null
}

/**
 * The compact Preview footer is a layout contract shared by the overlay, drawers,
 * and the footer that they must stay clear of. The layer navigator occupies the
 * lower 112dp including the plate inset and safety gap. When the separate reset
 * row is visible it contributes another 40dp including the inter-row gap.
 */
internal val CompactPreviewLayerNavigatorFootprint = 112.dp
internal val CompactPreviewResetViewFootprintAndGap = 40.dp
internal const val CompactPreviewResetViewSuppressionScale = 1.5f

internal data class CompactPreviewFooterLayout(
    val showResetView: Boolean,
    val reservedFootprint: Dp,
)

internal fun compactPreviewFooterReservation(showResetView: Boolean): Dp =
    CompactPreviewLayerNavigatorFootprint +
        if (showResetView) CompactPreviewResetViewFootprintAndGap else 0.dp

internal fun compactPreviewFooterLayout(textScale: Float, controlsOpen: Boolean): CompactPreviewFooterLayout {
    val showResetView = textScale < CompactPreviewResetViewSuppressionScale || !controlsOpen
    return CompactPreviewFooterLayout(
        showResetView = showResetView,
        reservedFootprint = compactPreviewFooterReservation(showResetView),
    )
}

internal fun compactPreviewComposesResetView(
    compactWorkspace: Boolean,
    hasObjectsOnPlate: Boolean,
    showPreview: Boolean,
    footerLayout: CompactPreviewFooterLayout,
): Boolean =
    (!compactWorkspace || hasObjectsOnPlate || showPreview) && footerLayout.showResetView

internal fun compactAdviceVisible(textScale: Float, showPreview: Boolean, controlsOpen: Boolean): Boolean =
    textScale < 1.5f && !showPreview && !controlsOpen

internal fun viewportAdviceVisible(
    compactWorkspace: Boolean,
    textScale: Float,
    showPreview: Boolean,
    controlsOpen: Boolean,
): Boolean = compactWorkspace && compactAdviceVisible(textScale, showPreview, controlsOpen)

internal fun plateToolbarVisibleCount(width: Dp, actionCount: Int): Int {
    if (width >= 780.dp) return actionCount
    // Reserve the explicit More control and dividers, then keep every icon target at least 36dp.
    return ((width.value - 96f) / 40f).toInt().coerceIn(4, actionCount)
}

internal fun compactPreviewOverlayMaxHeight(viewportHeight: Dp): Dp =
    // The undocked Preview legend/warnings coexist with both the layer slider and
    // the reset-view row at every text scale. Reserve both rows plus a visible gap;
    // a smaller normal-text reservation lets the final warning extend behind the
    // layer navigator on short windows.
    (viewportHeight - compactPreviewFooterReservation(showResetView = true)).coerceAtLeast(120.dp)

internal fun compactDrawerMaxHeight(viewportHeight: Dp, textScale: Float, showPreview: Boolean): Dp =
    if (showPreview) {
        // Below 150% the bottom reset-view row remains visible with a drawer; at
        // larger scales a drawer suppresses that row and only the layer slider is
        // reserved.
        (viewportHeight - compactPreviewFooterLayout(textScale, controlsOpen = true).reservedFootprint)
            .coerceAtLeast(120.dp)
    }
    else (viewportHeight - 24.dp).coerceAtLeast(120.dp)

internal fun previewWarningMessages(preview: GcodePreview): List<String> = buildList {
    if (preview.truncated) add("Preview limited to ${GcodePreview.MAX_SEGMENTS} extrusion paths; the sliced file may have more.")
    if (preview.discardedPreamble) add(
        "Extrusion before the first layer marker was omitted as start G-code; review the exported file if it contains model geometry before that marker.",
    )
}

@Composable
private fun PreviewWarnings(preview: GcodePreview, modifier: Modifier = Modifier) {
    previewWarningMessages(preview).forEach { Banner(it, BannerKind.WARNING, modifier = modifier) }
}

/**
 * The Workspace: one place from model to print. The top bar holds project controls,
 * Prepare / Preview and the current action; objects stay left, the plate stays central,
 * with global printer / material / process context on the left and object context on the right.
 */
@Composable
fun PrepareScreen(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    val camera = p.camera
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var drawer by remember { mutableStateOf<Drawer?>(Drawer.PROCESS) }
    var modelingTool by remember { mutableStateOf<ModelingTool?>(null) }
    var compactObjectsOpen by remember { mutableStateOf(false) }
    var previewFocusRequested by remember { mutableStateOf(false) }
    var previewSectionRequested by remember { mutableStateOf(false) }
    var previewRealisticRequested by remember { mutableStateOf(false) }
    var objectInspectorOpen by remember { mutableStateOf(false) }
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
    var returnFocusToCollapseControl by remember { mutableStateOf(false) }
    var requestedSidebarWidth by rememberSaveable { mutableStateOf<Float?>(null) }
    val collapseSidebarFocus = remember { FocusRequester() }
    val expandSidebarFocus = remember { FocusRequester() }
    var transformTool by remember { mutableStateOf(TransformTool.MOVE) }
    fun toggle(d: Drawer) {
        drawer = if (drawer == d) null else d
    }
    fun toggleModeling(tool: ModelingTool) {
        if (tool == ModelingTool.MESHY) {
            state.meshyOpen = true
            return
        }
        if (drawer == Drawer.MODEL && modelingTool == tool) {
            drawer = null
            modelingTool = null
        } else {
            modelingTool = tool
            drawer = Drawer.MODEL
        }
    }
    val done = p.slice as? SliceState.Done
    val previewReady = p.showPreview && done?.preview?.layers?.isNotEmpty() == true
    val previewFocused = previewFocusRequested && previewReady && drawer != Drawer.SEND
    val previewSection = previewSectionRequested && previewReady && drawer != Drawer.SEND
    val realisticAvailable = done?.preview?.supportsRealisticView == true
    val previewRealistic = previewRealisticRequested && previewReady && realisticAvailable && drawer != Drawer.SEND
    LaunchedEffect(previewReady) {
        if (!previewReady) {
            previewFocusRequested = false
            previewSectionRequested = false
            previewRealisticRequested = false
        }
    }
    // The tools need an object; settings pages from the printer and filament icons open the print settings drawer.
    LaunchedEffect(p.selectedIds) { if (p.selectedIds.size != 1 && (drawer == Drawer.CUT || drawer == Drawer.AI)) drawer = null }
    LaunchedEffect(drawer, p.selected) { if (drawer != Drawer.AI || (p.aiItemId != null && p.aiItemId != p.selected)) p.aiDiscard() }
    LaunchedEffect(p.settingsScope) { if (p.settingsScope != com.nozzleitall.desktop.settings.Scope.PROCESS) drawer = Drawer.PROCESS }
    // Send is a visible workflow stage backed by Preview's preflight drawer. Losing the slice closes it.
    LaunchedEffect(done) { if (done == null && drawer == Drawer.SEND) drawer = null }
    LaunchedEffect(state.workspaceSendRequested, done) {
        if (state.workspaceSendRequested) {
            if (done != null) {
                p.showPreview = true
                drawer = Drawer.SEND
            }
            state.consumeWorkspaceSendRequest()
        }
    }
    LaunchedEffect(state.workspaceSendOpen, done) {
        if (state.workspaceSendOpen && done != null) {
            p.showPreview = true
            drawer = Drawer.SEND
        } else if (!state.workspaceSendOpen && drawer == Drawer.SEND) {
            drawer = null
        }
    }
    LaunchedEffect(state.workspaceToolRequest) {
        when (state.workspaceToolRequest) {
            WorkspaceToolRequest.ARRANGE -> p.arrange()
            WorkspaceToolRequest.ORIENT -> p.orient()
            WorkspaceToolRequest.SIMPLIFY -> if (p.selected != null) toggleModeling(ModelingTool.SIMPLIFY)
            null -> Unit
        }
        if (state.workspaceToolRequest != null) state.consumeWorkspaceToolRequest()
    }
    LaunchedEffect(drawer) { state.reportWorkspaceSendOpen(drawer == Drawer.SEND) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val workspaceWidth = maxWidth
    val workspaceTextScale = Nz.textScale
    val sidebarWidth = workspaceSidebarWidth(workspaceWidth, workspaceTextScale, requestedSidebarWidth?.dp)
    LaunchedEffect(sidebarCollapsed, returnFocusToCollapseControl) {
        if (sidebarCollapsed) {
            expandSidebarFocus.requestFocus()
        } else if (returnFocusToCollapseControl) {
            collapseSidebarFocus.requestFocus()
            returnFocusToCollapseControl = false
        }
    }
    LaunchedEffect(p.selected, workspaceWidth) {
        objectInspectorOpen = p.selected != null && workspaceInspectorOpensByDefault(workspaceWidth)
    }
    // Printer / filament / process remains anchored at mainstream desktop sizes.
    // The object inspector is explicitly opened on demand; only genuinely narrow
    // workspaces collapse the familiar left pane as well.
    val compactWorkspace = workspaceUsesCompactLayout(workspaceWidth, Nz.textScale)
    Column(Modifier.fillMaxSize()) {
        if (!previewFocused) WorkspaceBar(state)
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!previewFocused && !compactWorkspace) Spacer(Modifier.width(if (sidebarCollapsed) 28.dp else sidebarWidth))
            if (!previewFocused && compactWorkspace) NzButton("Setup & objects (${p.plateItems().size})", { compactObjectsOpen = !compactObjectsOpen },
                kind = if (compactObjectsOpen) ButtonKind.PRIMARY else ButtonKind.SECONDARY, testTag = "compact-objects")
            val compactNavigation = usesCompactNavigation(workspaceWidth, Nz.textScale)
            Box(Modifier.weight(1f)) { PlateTabs(state, compactNavigation) }
            if (!previewFocused && p.plateNames.size > 1) {
                if (compactNavigation)
                    NzIconButton(NzIcon.PROJECTS, "Plate overview", { p.showPlateOverview = true }, testTag = "plate-overview")
                else NzButton("Plate overview", { p.showPlateOverview = true }, kind = ButtonKind.QUIET, testTag = "plate-overview")
            }
            if (previewReady && drawer != Drawer.SEND) {
                val sectionLabel = if (previewSection) "Exit Section View" else "Section View"
                val realisticLabel = if (previewRealistic) "Exit Realistic View" else "Realistic View"
                val focusLabel = if (previewFocused) "Exit Focus Mode" else "Focus Mode"
                val compactPreviewActions = Nz.textScale >= 1.5f || compactWorkspace
                val toggleSection = {
                    previewSectionRequested = !previewSectionRequested
                    if (!previewSectionRequested) p.previewLayer = p.previewLayer.coerceAtLeast(p.previewFirstLayer)
                }
                if (compactPreviewActions) {
                    NzIconButton(NzIcon.CUT, sectionLabel, toggleSection, selected = previewSection,
                        testTag = "preview-section-view")
                    NzIconButton(NzIcon.PREPARE, realisticLabel, { previewRealisticRequested = !previewRealisticRequested },
                        enabled = realisticAvailable, selected = previewRealistic, testTag = "preview-realistic-view",
                        tooltip = if (realisticAvailable) realisticLabel else "Realistic View requires recorded extrusion widths")
                    NzIconButton(NzIcon.WORKSPACE, focusLabel, { previewFocusRequested = !previewFocusRequested },
                        selected = previewFocused, testTag = "preview-focus-mode")
                } else {
                    NzButton(sectionLabel, toggleSection, kind = ButtonKind.QUIET, testTag = "preview-section-view")
                    NzButton(realisticLabel, { previewRealisticRequested = !previewRealisticRequested },
                        kind = ButtonKind.QUIET, enabled = realisticAvailable, testTag = "preview-realistic-view",
                        accessibilityDescription = if (realisticAvailable) null else "Realistic View requires recorded extrusion widths")
                    NzButton(focusLabel, { previewFocusRequested = !previewFocusRequested },
                        kind = ButtonKind.QUIET, testTag = "preview-focus-mode")
                }
            }
        }
        if (compactWorkspace) {
            val alertScroll = rememberScrollState()
            if (alertScroll.maxValue > 0)
                Txt("Alerts · scroll to read all", Nz.type.bodySmall, c.textMuted,
                    modifier = Modifier.padding(horizontal = 12.dp))
            Column(Modifier.fillMaxWidth().heightIn(max = 100.dp)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val delta = when (event.key) {
                        Key.DirectionDown -> 48f
                        Key.DirectionUp -> -48f
                        Key.PageDown -> 100f
                        Key.PageUp -> -100f
                        else -> return@onKeyEvent false
                    }
                    alertScroll.dispatchRawDelta(delta)
                    true
                }
                .focusable()
                .verticalScroll(alertScroll)) {
                (p.slice as? SliceState.Failed)?.let { SliceFailureNotice(p, it) }
                p.notice?.let { Banner(it, BannerKind.WARNING, "Dismiss" to { p.notice = null }) }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Left: the persistent slicing context and plate/object hierarchy. Global choices belong here rather
        // than in the contextual inspector, matching the established slicer mental model without copying its UI.
        if (!previewFocused && !compactWorkspace && !sidebarCollapsed) BoxWithConstraints(Modifier.width(sidebarWidth).fillMaxHeight()) {
            val sidebarHeight = maxHeight
            val scrollSetup = p.showPreview && sidebarHeight < 550.dp
            val setupScroll = rememberScrollState()
            Column(Modifier.fillMaxSize().padding(end = 28.dp)
                .then(if (scrollSetup) Modifier.padding(end = 8.dp).verticalScroll(setupScroll) else Modifier),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                p.notice?.let { Banner(it, BannerKind.WARNING, "Dismiss" to { p.notice = null }) }
                (p.slice as? SliceState.Failed)?.let { SliceFailureNotice(p, it) }
                WorkspaceSetupStack(
                    state,
                    drawer,
                    setDrawer = { drawer = it },
                    modifier = Modifier.fillMaxWidth().then(
                        if (scrollSetup) Modifier else Modifier.heightIn(max = workspaceSetupMaximumHeight(sidebarHeight, Nz.textScale))),
                    foldFilamentByDefault = workspaceFoldsFilamentByDefault(workspaceWidth),
                    scrollInternally = !scrollSetup,
                )
                WorkspaceLeftPanel(
                    state,
                    drawer,
                    { drawer = it },
                    Modifier.fillMaxWidth().then(if (scrollSetup) Modifier.height(390.dp)
                        else Modifier.weight(1f).heightIn(min = workspaceSettingsMinimumHeight(sidebarHeight, Nz.textScale))),
                )
            }
            if (scrollSetup) PreviewScrollbar(setupScroll,
                Modifier.align(Alignment.CenterEnd).padding(end = 28.dp).fillMaxHeight(),
                "Preview setup scroll bar")
            SidebarEdge(
                width = sidebarWidth,
                onCollapse = { sidebarCollapsed = true },
                onResizeTo = { target ->
                    requestedSidebarWidth = workspaceSidebarWidth(
                        workspaceWidth,
                        workspaceTextScale,
                        target,
                    ).value
                },
                collapseFocusRequester = collapseSidebarFocus,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        } else if (!previewFocused && !compactWorkspace) {
            Box(
                Modifier.width(28.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                    .background(c.surface).border(1.dp, c.line, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.TopCenter,
            ) {
                SidebarExpandControl(expandSidebarFocus) {
                    returnFocusToCollapseControl = true
                    sidebarCollapsed = false
                }
            }
        }
        // Centre: the build plate and its familiar editing tools.
        val onPlate = p.plateItems()
        val sel = onPlate.firstOrNull { it.id == p.selected }
        val forcedInspectorContext = p.paintKind != null || drawer == Drawer.CUT ||
            drawer == Drawer.MODEL || drawer == Drawer.AI || drawer == Drawer.SEND
        val sendReviewOpen = sendPreflightVisible(p.showPreview, drawer == Drawer.SEND, done != null)
        val dockSendReview = sendReviewOpen && !compactWorkspace
        val inspectorVisible = !previewFocused && workspaceUsesInspectorPane(
            compactWorkspace,
            requested = objectInspectorOpen && sel != null,
            forcedContext = forcedInspectorContext,
        )
        val slots = p.materials()
        val slotColors = slots.map { parseHex(it.colorHex) ?: c.accent }
        val objects = onPlate.filter { p.objectVisible(it.id) }.map { item ->
            val aiMesh = p.aiPreviewMesh?.takeIf { drawer == Drawer.AI && item.id == p.aiItemId && !p.aiShowOriginal }
            // A slot is a loaded filament or a Full Spectrum mix (drawn in its blended colour).
            // While a non-colour paint tool is open on the selected object, that kind of paint is what the plate shows.
            val shown = p.paintKind?.takeIf { item.id == p.selected && it != PaintOps.Kind.COLOUR }
            val paintColors = when (shown) {
                PaintOps.Kind.SUPPORT -> listOf(null, Color(0xFF3BA55D), Color(0xFFE5484D))
                PaintOps.Kind.SEAM -> listOf(null, Color(0xFF3E7BFA), Color(0xFFF5A623))
                PaintOps.Kind.FUZZY -> listOf(null, Color(0xFF9B59B6))
                else -> if (aiMesh != null) List(maxOf(p.aiPrepared?.filaments?.maxOfOrNull { it.index } ?: 0, item.paintSlots.maxOrNull() ?: 0, item.slot).coerceIn(0, 255) + 1) { n -> parseHex(p.slotHex(if (n == 0) item.slot else n)) }
                    else if (item.painted.isEmpty()) emptyList() else List((item.painted.maxOrNull() ?: 0) + 1) { n -> parseHex(p.slotHex(item.slotFor(n))) }
            }
            PlateObject(item.id, item.name, aiMesh ?: item.mesh, parseHex(p.slotHex(item.slot)) ?: c.accent, item.x, item.y, item.rotZ, item.scale, item.id in p.selectedIds, paintColors,
                when (shown) { PaintOps.Kind.SUPPORT -> 1; PaintOps.Kind.SEAM -> 2; PaintOps.Kind.FUZZY -> 3; else -> 0 },
                item.sx, item.sy, item.sz, item.parts.toList(),
                selectedPart = if (item.id == p.selected) p.selectedPart else -1,
                reach = item.reach, height = item.height)
        }
        val (bw, bd) = p.bed
        // Zoom to fit the bed when the printer (and so the bed) changes.
        LaunchedEffect(bw, bd) { camera.fitBed(bw, bd) }
        val shape = RoundedCornerShape(12.dp)
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clip(shape).background(c.surfaceSunken).border(1.dp, c.line, shape)) {
            val viewportWidth = maxWidth
            val previewControlsVisible = p.showPreview && done?.preview != null && !sendReviewOpen
            val stackPreviewFooter = viewportWidth < 700.dp || Nz.textScale >= 1.5f
            var warningsOpen by remember(done?.preview, previewControlsVisible) { mutableStateOf(false) }
            val density = LocalDensity.current
            var previewFooterHeight by remember { mutableStateOf(0.dp) }
            var previewZWidth by remember { mutableStateOf(0.dp) }
            val plateContextMenu = remember(p.currentPlate, p.showPreview) { ContextMenuState() }
            var contextObject by remember(p.currentPlate, p.showPreview) { mutableStateOf<PrepItem?>(null) }
            val bedActions = {
                buildList {
                    add(ContextMenuItem("Add model…", state::chooseWorkspaceModels))
                    if (p.plateItems().isNotEmpty()) add(ContextMenuItem("Arrange plate") {
                        state.requestWorkspaceTool(WorkspaceToolRequest.ARRANGE)
                    })
                    if (p.plateItems().isNotEmpty()) add(ContextMenuItem("Clear this plate") {
                        state.requestPlateEdit(PlateEditKind.CLEAR_PLATE)
                    })
                    if (p.items.isNotEmpty()) add(ContextMenuItem("Clear all plates") {
                        state.requestPlateEdit(PlateEditKind.CLEAR_ALL)
                    })
                    add(ContextMenuItem("Fit build plate", camera::fitView))
                }
            }
            if (onPlate.isNotEmpty()) LocalContextMenuRepresentation.current.Representation(plateContextMenu) {
                contextObject?.let { objectContextActions(p, it) } ?: bedActions()
            }
            if (onPlate.isEmpty() && !p.showPreview) ContextMenuArea(items = bedActions, state = plateContextMenu) {
                Box(Modifier.fillMaxSize()) {
                    EmptyState(NzIcon.IMPORT, if (p.items.isEmpty()) "Start with a model" else "${p.plateNames.getOrNull(p.currentPlate) ?: "This plate"} is empty",
                        "Opening or importing a model keeps it on this computer. Add an STL, 3MF or OBJ file, or open a saved project.", Modifier.align(Alignment.Center)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NzButton("Add model", state::chooseWorkspaceModels, kind = ButtonKind.PRIMARY, icon = NzIcon.IMPORT)
                                NzButton("Open a project", state::chooseWorkspaceProject, kind = ButtonKind.SECONDARY, icon = NzIcon.PROJECTS)
                                NzButton("Set up machine", state::reopenFirstRun, kind = ButtonKind.SECONDARY, icon = NzIcon.TUNE, testTag = "empty-workspace-setup-machine")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NzButton("CAD and STEP", { toggleModeling(ModelingTool.CAD) }, kind = ButtonKind.SECONDARY,
                                    enabled = p.toolBusy == null, testTag = "empty-workspace-cad")
                                NzButton("Meshy 3D", { toggleModeling(ModelingTool.MESHY) }, kind = ButtonKind.SECONDARY,
                                    enabled = p.toolBusy == null, testTag = "empty-workspace-meshy")
                            }
                        }
                    }
                }
            }
            else PlateViewer(bw, bd, objects, camera, c.surfaceRaised, c.lineStrong,
                Modifier.fillMaxSize().padding(bottom = if (previewControlsVisible) previewFooterHeight else 0.dp),
                preview = if (p.showPreview) done?.preview else null, previewLayer = p.previewLayer, previewMove = p.previewMove, toolColors = slotColors,
                previewFirstLayer = if (previewSection) p.previewLayer else p.previewFirstLayer,
                previewRealistic = previewRealistic,
                previewView = p.previewView ?: if ((done?.preview?.toolsUsed?.size ?: 0) > 1) PreviewView.TOOL else PreviewView.LINE_TYPE,
                hiddenPreviewKinds = p.previewHiddenKinds.toSet(), hiddenPreviewTools = p.previewHiddenTools.toSet(),
                paint = p.selected?.takeIf { p.paintKind != null && !p.showPreview }?.let { id -> PaintInput(id, p.paintRadius) { rays -> p.paintStroke(id, rays) } },
                onSelection = { id, ctrl, shift -> focus.clearFocus(); p.selectObject(id, ctrl, shift) }, accent = c.accent,
                onMove = p::moveSelection, onTurn = p::turnSelection, onResize = p::resizeSelection,
                onTransformStart = p::beginSelectionTransform, onTransformEnd = p::endSelectionTransform,
                transformTool = transformTool, backgroundColor = c.surfaceSunken,
                onContextMenu = { position, id ->
                    contextObject = p.plateItems().firstOrNull { it.id == id }
                    plateContextMenu.status = ContextMenuState.Status.Open(
                        androidx.compose.ui.geometry.Rect(position, androidx.compose.ui.geometry.Size.Zero))
                })
            if (!compactWorkspace && !p.showPreview && sel != null && !forcedInspectorContext && !objectInspectorOpen) {
                Column(
                    Modifier.align(Alignment.TopEnd).padding(top = 66.dp, end = 12.dp)
                        .width(38.dp).clip(RoundedCornerShape(10.dp)).background(c.surface.copy(alpha = 0.97f))
                        .border(1.dp, c.line, RoundedCornerShape(10.dp)).padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NzIconButton(
                        NzIcon.TUNE,
                        "Open object inspector for ${sel.name}",
                        { objectInspectorOpen = true },
                        modifier = Modifier.size(32.dp),
                        testTag = "object-inspector-open",
                        tooltip = "Object inspector · ${sel.name}",
                    )
                }
            }
            // Top left: one floating icon toolbar over the plate at every desktop width.
            if (!p.showPreview && onPlate.isNotEmpty()) Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                // When the Job context card shares this row, reserve its exact layout width plus the
                // surrounding insets. The tools wrap inside the visible remainder instead of relying on
                // undiscoverable horizontal scrolling that can make the later actions appear missing.
                val toolWidth = if (compactWorkspace) compactToolbarMaxWidth(viewportWidth, Nz.textScale)
                    else (viewportWidth - 24.dp).coerceAtLeast(120.dp)
                FloatingToolbar(Modifier.widthIn(max = toolWidth)) {
                    PlateToolbar(p, sel, drawer, modelingTool, transformTool, { transformTool = it }, ::toggleModeling) { toggle(it) }
                }
            }
            val previewLegendHeight = (maxHeight * 0.29f).coerceIn(96.dp, 150.dp)
            if (previewControlsVisible && done?.preview != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(12.dp).padding(end = previewZWidth)) {
                    if (previewRealistic) Floating(Modifier) {
                        Txt("Realistic View · recorded extrusion widths", Nz.type.bodySmall, c.textMuted)
                    } else PreviewLegend(p, done.preview, p.previewView ?: if (done.preview.toolsUsed.size > 1) PreviewView.TOOL else PreviewView.LINE_TYPE,
                        slots, done.result.stats,
                        overlayWidth = minOf(340.dp, viewportWidth * 0.47f),
                        overlayHeight = previewLegendHeight,
                        onShowWarnings = { warningsOpen = true })
                }
                PreviewResetButton(camera::reset, Modifier.align(Alignment.TopStart).padding(12.dp))
                if (warningsOpen) PreviewWarningPopup(
                    done.preview,
                    (viewportWidth - 24.dp).coerceIn(0.dp, 460.dp),
                    (maxHeight - previewFooterHeight - 80.dp).coerceIn(0.dp, 300.dp),
                    with(density) { (previewFooterHeight / 2).roundToPx() },
                ) { warningsOpen = false }
            }
            if (p.showPreview && done != null && done.preview == null) Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                Banner("The sliced file has no available toolpath preview. Return to Plate or slice again before reviewing it.", BannerKind.WARNING)
            }
            if (p.showPreview && done?.preview != null && !sendReviewOpen) {
                val cursor = previewLayerCursor(done.preview, p.previewLayer)
                if (cursor != null) {
                    val slider = Modifier.align(Alignment.CenterEnd)
                        .padding(top = 12.dp, end = 12.dp, bottom = previewFooterHeight + 8.dp)
                        .onSizeChanged { previewZWidth = with(density) { it.width.toDp() } }
                        .heightIn(min = 220.dp, max = 520.dp)
                    if (previewSection) {
                        VerticalLayerSlider(cursor.index, cursor.count, slider, "Section") {
                            p.previewLayer = it
                            p.previewMove = Int.MAX_VALUE
                        }
                    } else {
                        VerticalLayerRangeSlider(p.previewFirstLayer.coerceIn(0, cursor.index), cursor.index, cursor.count, slider,
                            onFirst = { p.previewFirstLayer = it },
                            onLast = {
                                p.previewLayer = it
                                p.previewMove = Int.MAX_VALUE
                            },
                        )
                    }
                }
            }
            // Top right: the drawer: print setup, the Send step, or the panel for the tool in use (paint, cut, AI auto-paint).
            val toolOpen = !p.showPreview && sel != null && (p.paintKind != null || drawer == Drawer.CUT || drawer == Drawer.MODEL || drawer == Drawer.AI)
            val settingsOpen = drawer == Drawer.PRINTER || drawer == Drawer.FILAMENT || drawer == Drawer.PROCESS ||
                sendPreflightVisible(p.showPreview, drawer == Drawer.SEND, done != null)
            if (!previewFocused && compactSetupContextVisible(compactWorkspace, toolOpen || settingsOpen)) {
                WorkspaceSetupStack(
                    state,
                    drawer,
                    { drawer = it },
                    Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp)
                        .width(compactContextWidth(viewportWidth, Nz.textScale)),
                )
            }
            if (!previewFocused && compactWorkspace && (toolOpen || settingsOpen)) {
                val panelWidth = if (drawer.isGlobalSetup())
                    workspaceGlobalPanelWidth(viewportWidth, Nz.textScale, drawer == Drawer.PROCESS)
                else if (Nz.textScale >= 1.5f) (viewportWidth - 24.dp).coerceAtMost(560.dp) else 360.dp
                val panel = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp, bottom = 12.dp)
                    .width(panelWidth).heightIn(max = compactDrawerMaxHeight(maxHeight, Nz.textScale, p.showPreview))
                    .clip(RoundedCornerShape(14.dp)).background(c.surface.copy(alpha = 0.97f)).border(1.dp, c.line, RoundedCornerShape(14.dp))
                if (drawer == Drawer.PROCESS && !toolOpen) Column(panel.fillMaxHeight().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DrawerTitle("Process", { drawer = null })
                    val profileValues = profileValuesFor(p)
                    if (profileValues == null) Txt("Loading process settings…", Nz.type.bodySmall, c.textMuted)
                    else profileValues.onSuccess { values ->
                        com.nozzleitall.desktop.settings.SettingsSheet(com.nozzleitall.desktop.settings.SettingsCatalog.bundled, values, p, Modifier.weight(1f).fillMaxWidth())
                    }.onFailure { Txt("Process settings unavailable: ${it.message}", Nz.type.bodySmall, c.danger) }
                } else Column(panel.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        toolOpen && p.paintKind != null -> PaintPanel(p)
                        toolOpen && drawer == Drawer.CUT -> CutPanel(p, sel) { drawer = null }
                        toolOpen && drawer == Drawer.MODEL && modelingTool != null -> ModelingPanel(p, sel, modelingTool!!) { drawer = null }
                        toolOpen && drawer == Drawer.AI -> AutoPaintPanel(p) { drawer = null }
                        drawer == Drawer.PRINTER -> { DrawerTitle("Printer", { drawer = null }); StepPrinter(state) }
                        drawer == Drawer.FILAMENT -> { DrawerTitle("Filament", { drawer = null }); MaterialsSection(state) }
                        sendPreflightVisible(p.showPreview, drawer == Drawer.SEND, done != null) -> {
                            DrawerTitle("Print plate", { drawer = null })
                            SendDrawer(state, done!!, showSetupContext = true) { drawer = it }
                        }
                    }
                }
            }
            if (inspectorVisible && !compactWorkspace && !dockSendReview) {
                WorkspaceInspector(
                    state,
                    drawer,
                    modelingTool,
                    done,
                    sel,
                    setDrawer = { drawer = it },
                    closeObjectInspector = { objectInspectorOpen = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 66.dp, end = 12.dp, bottom = 12.dp)
                        .heightIn(max = (maxHeight - 78.dp).coerceAtLeast(240.dp)),
                )
            }
            if (!previewFocused && p.showPlateOverview && p.plateNames.size > 1) {
                val projectId = p.manifest?.projectId
                val projectRevision = p.manifest?.revision
                var progressRefresh by remember(projectId, projectRevision) { mutableIntStateOf(0) }
                var printProgress by remember(projectId, projectRevision) { mutableStateOf<Map<String, com.nozzleitall.desktop.PlatePrintProgress>?>(null) }
                var progressProblem by remember(projectId, projectRevision) { mutableStateOf(false) }
                LaunchedEffect(projectId, projectRevision, progressRefresh) {
                    if (projectId != null && projectRevision != null) {
                        try {
                            printProgress = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                state.fleet.projectPlateProgress(projectId, projectRevision)
                            }
                            progressProblem = false
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            progressProblem = true
                        }
                    }
                }
                Card(Modifier.align(Alignment.Center).widthIn(max = 540.dp).heightIn(max = 560.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Txt("Plates in ${p.name}", Nz.type.title, modifier = Modifier.weight(1f))
                        NzButton("Refresh progress", { progressRefresh++ }, kind = ButtonKind.QUIET,
                            enabled = projectId != null && projectRevision != null)
                        NzButton("Close", { p.showPlateOverview = false }, kind = ButtonKind.QUIET)
                    }
                    Txt("Choose a plate to prepare or slice. Print results are for saved revision ${projectRevision ?: "not recorded"}; machine completion and physical acceptance are tracked separately.", Nz.type.bodySmall, c.textMuted)
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.plateNames.forEachIndexed { i, plateName ->
                            val plateId = p.plateIds.getOrNull(i)
                            val queued = state.fleet.queue.value.count { it.projectId == projectId && it.projectRevision == projectRevision && it.plateId == plateId && plateId != null }
                            val progress = plateId?.let { printProgress?.get(it) }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Txt(plateName, Nz.type.label)
                                    Txt("${p.plateItems(i).size} object(s) · ${if (queued > 0) "$queued queued" else if (i == p.currentPlate && p.slice is SliceState.Done) "sliced in this workspace" else "No job queued"}", Nz.type.bodySmall, c.textMuted)
                                    Txt(when {
                                        progress?.physicallyAccepted == true -> "Physical result accepted"
                                        progress?.machineCompleted == true -> "Machine completed; no accepted physical result"
                                        projectId == null || projectRevision == null -> "No linked print record"
                                        progressProblem -> "Print records unavailable"
                                        printProgress == null -> "Checking print records…"
                                        else -> "No machine completion recorded"
                                    }, Nz.type.bodySmall, c.textMuted)
                                }
                                NzButton("Open plate", { p.showPlate(i) }, kind = ButtonKind.SECONDARY,
                                    testTag = "overview-plate-$i")
                            }
                        }
                    }
                }
            }
            // Bottom: what the app sees wrong, the layer slider in the preview, the plates and a hint.
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .onSizeChanged { previewFooterHeight = with(density) { it.height.toDp() } }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (p.showPreview && done?.preview != null && !sendReviewOpen) {
                    val cursor = previewLayerCursor(done.preview, p.previewLayer)
                    if (cursor == null) Floating(Modifier.fillMaxWidth()) {
                        Txt("This slice has no extrusion layers to preview.", Nz.type.metricSmall, c.textMuted)
                    } else Floating(Modifier.fillMaxWidth()) {
                        val moveCount = done.preview.layers[cursor.index].count.coerceAtLeast(1)
                        val shownMove = p.previewMove.coerceAtMost(moveCount)
                        val moveControls: @Composable RowScope.() -> Unit = {
                            Txt("Move", Nz.type.metricSmall, c.textMuted)
                            LayerSlider(shownMove, moveCount + 1, Modifier.weight(1f)) { p.previewMove = it }
                            Txt("$shownMove / $moveCount", Nz.type.metricSmall, c.textMuted, maxLines = 1)
                        }
                        val first = p.previewFirstLayer.coerceIn(0, cursor.index)
                        val firstZ = done.preview.layers[first].z
                        val layers = if (previewSection) "Section ${cursor.index + 1}/${cursor.count}"
                            else "Layers ${first + 1}–${cursor.index + 1}/${cursor.count}"
                        val zRange = if (previewSection) "Z ${"%.2f".format(cursor.z)} mm"
                            else "Z ${"%.2f".format(firstZ)}–${"%.2f".format(cursor.z)} mm"
                        val readouts: @Composable () -> Unit = {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Txt(layers, Nz.type.metricSmall, c.textMuted, maxLines = 1)
                                Txt(zRange, Nz.type.metricSmall, c.textMuted, maxLines = 1)
                            }
                        }
                        if (stackPreviewFooter) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp), content = moveControls)
                                readouts()
                            }
                        } else {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp), content = moveControls)
                            Spacer(Modifier.width(8.dp))
                            readouts()
                        }
                    }
                }
                // The full-width advice overlay must never cover a compact tool or settings panel.
                if (viewportAdviceVisible(compactWorkspace, Nz.textScale, p.showPreview, toolOpen || settingsOpen))
                    AdviceStrip(state, onChoosePrinter = { drawer = Drawer.PRINTER })
                if (!previewControlsVisible) Row(
                    Modifier.fillMaxWidth().padding(end = resetViewEndPadding(inspectorVisible, compactWorkspace)),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PreviewResetButton(camera::reset)
                }
            }
            if (!previewFocused && compactWorkspace && compactObjectsOpen) Column(Modifier.align(Alignment.CenterStart).padding(12.dp)
                .widthIn(max = workspaceGlobalPanelWidth(viewportWidth, Nz.textScale, process = false)).fillMaxHeight(0.9f).clip(RoundedCornerShape(14.dp))
                .background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp))
                .verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NzButton("Close setup and objects", { compactObjectsOpen = false }, kind = ButtonKind.QUIET)
                WorkspaceSetupStack(state, drawer, setDrawer = { drawer = it; compactObjectsOpen = false })
                if (Nz.textScale >= 1.5f && !p.showPreview && drawer != Drawer.AI)
                    AdviceStrip(state, onChoosePrinter = { drawer = Drawer.PRINTER; compactObjectsOpen = false })
                StepObjects(state)
            }
            }
        }
        if (dockSendReview) {
            WorkspaceInspector(state, drawer, modelingTool, done, sel,
                setDrawer = { drawer = it },
                closeObjectInspector = { objectInspectorOpen = false },
                modifier = Modifier.fillMaxHeight())
        }
        }
    }
    }
}

/** Global slicing choices are separate from object inspection and get enough room for real setting names and values. */
@Composable
private fun WorkspaceGlobalPanel(
    state: AppState,
    drawer: Drawer,
    viewportWidth: Dp,
    setDrawer: (Drawer?) -> Unit,
) {
    val p = state.prepare
    val c = Nz.colors
    val shape = RoundedCornerShape(12.dp)
    val width = workspaceGlobalPanelWidth(viewportWidth, Nz.textScale, drawer == Drawer.PROCESS)
    val panel = Modifier.width(width).fillMaxHeight().clip(shape).background(c.surface)
        .border(1.dp, c.line, shape).padding(12.dp).semantics { testTag = "workspace-global-settings" }

    if (drawer == Drawer.PROCESS) Column(panel, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DrawerTitle("Process", { setDrawer(null) })
        val profileValues = profileValuesFor(p)
        if (profileValues == null) Txt("Loading process settings…", Nz.type.bodySmall, c.textMuted)
        else profileValues.onSuccess { values ->
            com.nozzleitall.desktop.settings.SettingsSheet(
                com.nozzleitall.desktop.settings.SettingsCatalog.bundled, values, p,
                Modifier.weight(1f).fillMaxWidth())
        }.onFailure { Txt("Process settings unavailable: ${it.message}", Nz.type.bodySmall, c.danger) }
    } else Column(panel.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (drawer) {
            Drawer.PRINTER -> { DrawerTitle("Printer and nozzle", { setDrawer(null) }); StepPrinter(state) }
            Drawer.FILAMENT -> { DrawerTitle("Filaments and toolheads", { setDrawer(null) }); MaterialsSection(state) }
            else -> Unit
        }
    }
}

/**
 * The stable right side of the wide workspace is contextual: selection, tool, preview or preflight.
 * Printer, material and process choices live in the left setup stack and the separate global panel.
 */
@Composable
private fun WorkspaceInspector(
    state: AppState,
    drawer: Drawer?,
    modelingTool: ModelingTool?,
    done: SliceState.Done?,
    selected: PrepItem?,
    setDrawer: (Drawer?) -> Unit,
    closeObjectInspector: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = state.prepare
    val c = Nz.colors
    val shape = RoundedCornerShape(12.dp)
    val panel = modifier.width(340.dp).clip(shape).background(c.surface.copy(alpha = 0.97f))
        .border(1.dp, c.line, shape).padding(12.dp).semantics { testTag = "workspace-inspector" }
    val inspectorScroll = rememberScrollState()

    Box(panel) {
        Column(
            Modifier.fillMaxSize().padding(end = 10.dp).verticalScroll(inspectorScroll),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                sendPreflightVisible(p.showPreview, drawer == Drawer.SEND, done != null) -> {
                    DrawerTitle("Print plate", { setDrawer(null) })
                    SendDrawer(state, done!!, showSetupContext = false) { setDrawer(it) }
                }
                p.showPreview && done?.preview != null -> {
                    DrawerTitle("Preview", null)
                    PreviewLegend(p, done.preview,
                        p.previewView ?: if (done.preview.toolsUsed.size > 1) PreviewView.TOOL else PreviewView.LINE_TYPE,
                        p.materials(), done.result.stats)
                    PreviewWarnings(done.preview)
                }
                p.showPreview -> {
                    DrawerTitle("Preview", null)
                    Txt("Slice this plate to inspect its toolpaths and layers.", Nz.type.bodySmall, c.textMuted)
                }
                p.paintKind != null -> PaintPanel(p)
                drawer == Drawer.CUT && selected != null -> CutPanel(p, selected) { setDrawer(null) }
                drawer == Drawer.MODEL && modelingTool != null -> ModelingPanel(p, selected, modelingTool) { setDrawer(null) }
                drawer == Drawer.AI -> AutoPaintPanel(p) { setDrawer(null) }
                selected != null -> {
                    DrawerTitle(if (p.selectedIds.size > 1) "Selection" else selected.name, closeObjectInspector)
                    StepObjects(state, showList = false)
                }
                else -> {
                    DrawerTitle("Inspector", closeObjectInspector)
                    Txt("Select an object to edit its position, size, parts and object-specific settings.", Nz.type.bodySmall, c.textMuted)
                }
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(inspectorScroll),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(c.lineStrong.copy(alpha = 0.35f))
                .semantics { contentDescription = "Object inspector scroll bar" },
            style = ScrollbarStyle(
                minimalHeight = 28.dp,
                thickness = 6.dp,
                shape = RoundedCornerShape(3.dp),
                hoverDurationMillis = 200,
                unhoverColor = c.textMuted.copy(alpha = 0.55f),
                hoverColor = c.accent,
            ),
        )
    }
}

/** What the preview's colours mean, and a switch between colouring by line type, filament and speed. */
@Composable
private fun PreviewLegend(p: PrepareState, preview: GcodePreview, view: PreviewView,
                          slots: List<com.nozzleitall.project.ProjectManifest.MaterialSlot>, stats: SliceStats,
                          overlayWidth: Dp? = null, overlayHeight: Dp? = null,
                          onShowWarnings: (() -> Unit)? = null) {
    val c = Nz.colors
    val shape = RoundedCornerShape(14.dp)
    var showDetails by remember { mutableStateOf(true) }
    val warnings = previewWarningMessages(preview)
    val expanded = overlayHeight == null || showDetails
    val showsTimeBreakdown = view == PreviewView.LINE_TYPE || view == PreviewView.TOOL
    val narrowLegend = overlayWidth != null && overlayWidth < 270.dp
    val stackedRows = overlayWidth != null && (Nz.textScale >= 1.5f || overlayWidth < 300.dp)
    val baseLegendWidth = if (Nz.textScale >= 1.5f) 390.dp else 300.dp
    val legendWidth = baseLegendWidth + if (showsTimeBreakdown) 40.dp else 0.dp
    Column(Modifier.width(overlayWidth ?: legendWidth).clip(shape)
        .background(c.surface.copy(alpha = 0.94f)).border(1.dp, c.line, shape)
        .padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var viewMenuOpen by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f)) {
                NzButton(
                    if (narrowLegend) "${view.label} ▾" else "Color by ${view.label} ▾",
                    { viewMenuOpen = !viewMenuOpen },
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.SECONDARY,
                    testTag = "preview-view-selector",
                    accessibilityDescription = "Preview color: ${view.label}",
                )
                if (viewMenuOpen) Popup(onDismissRequest = { viewMenuOpen = false }, properties = PopupProperties(focusable = true)) {
                    Column(
                        Modifier.width(260.dp).heightIn(max = 360.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceRaised)
                            .border(1.dp, c.lineStrong, RoundedCornerShape(10.dp)).verticalScroll(rememberScrollState()).padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        PreviewView.entries.forEach { choice ->
                            val on = choice == view
                            val available = preview.available(choice)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
                                    .background(if (on) c.accent.copy(alpha = 0.16f) else Color.Transparent)
                                    .selectable(on, enabled = available, role = Role.RadioButton) {
                                        p.previewView = choice
                                        viewMenuOpen = false
                                        showDetails = true
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .semantics { testTag = "preview-view-${choice.name.lowercase()}" },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Txt(if (available) choice.label else "${choice.label} (unavailable)",
                                    Nz.type.body, if (!available) c.textMuted else if (on) c.accent else c.text, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (overlayHeight != null) {
                val label = if (showDetails) "Hide color legend" else "Show color legend"
                NzIconButton(NzIcon.LAYERS, label, { showDetails = !showDetails },
                    selected = showDetails, testTag = "preview-legend-toggle")
            }
        }
        if (onShowWarnings != null && warnings.isNotEmpty()) {
            NzButton("Warnings (${warnings.size})", onShowWarnings,
                kind = ButtonKind.QUIET, icon = NzIcon.ALERT, testTag = "preview-warnings-open")
        }
        if (!expanded) return@Column
        val scroll = rememberScrollState()
        val detailSize = if (overlayHeight == null) Modifier else Modifier.heightIn(max = overlayHeight)
        Box(detailSize.fillMaxWidth()) {
            val scrollable = if (overlayHeight == null) Modifier else Modifier.padding(end = 8.dp).verticalScroll(scroll)
            Column(scrollable.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val usage = if (showsTimeBreakdown) preview.usage else null
                if (showsTimeBreakdown && !stackedRows) Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Spacer(Modifier.width(12.dp))
                    Txt("Type", Nz.type.bodySmall, c.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
                    Txt("Time", Nz.type.bodySmall, c.textMuted, maxLines = 1)
                    Txt("%", Nz.type.bodySmall, c.textMuted, maxLines = 1)
                    Txt(if (preview.plannerBacked) "Filament" else "Path", Nz.type.bodySmall, c.textMuted, maxLines = 1)
                    Spacer(Modifier.width(18.dp))
                }
                fun entry(colour: Color, label: String, metrics: GcodePreview.Usage, visible: Boolean, toggle: () -> Unit) = @Composable {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(colour))
                        val time = formatDuration(metrics.seconds, includeSeconds = true)
                        val total = requireNotNull(usage).total
                        val totalMm = if (preview.plannerBacked) total.filamentMm else total.pathMm
                        val mm = if (preview.plannerBacked) metrics.filamentMm else metrics.pathMm
                        val percent = if (totalMm > 0.0) mm / totalMm * 100.0 else 0.0
                        val amount = if (mm >= 1000f) "%.1f m".format(mm / 1000f) else "%.0f mm".format(mm)
                        if (stackedRows) {
                            Column(Modifier.weight(1f)) {
                                if (narrowLegend) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Txt(label, Nz.type.bodySmall, maxLines = 2)
                                        Txt(time, Nz.type.bodySmall, c.textMuted, maxLines = 1)
                                    }
                                    Txt("${percent.toInt()}% · $amount", Nz.type.bodySmall, c.textMuted)
                                } else {
                                    Txt(label, Nz.type.bodySmall, maxLines = 2)
                                    Txt("$time · ${percent.toInt()}% · $amount", Nz.type.bodySmall, c.textMuted)
                                }
                            }
                        } else {
                            Txt(label, Nz.type.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                            Txt(time, Nz.type.bodySmall, c.textMuted, maxLines = 1)
                            Txt("${percent.toInt()}%", Nz.type.bodySmall, c.textMuted, maxLines = 1)
                            Txt(amount, Nz.type.bodySmall, c.textMuted, maxLines = 1)
                        }
                        Txt(if (visible) "●" else "○", Nz.type.label, if (visible) c.accent else c.textMuted,
                            modifier = Modifier.clip(CircleShape).clickable(onClickLabel = if (visible) "Hide $label" else "Show $label") { toggle() }.padding(3.dp))
                    }
                }
                when (view) {
                    PreviewView.SUMMARY -> {
                        MappingValue("Est. time", formatDuration(stats.seconds))
                        MappingValue("Filament", listOfNotNull(stats.metres?.let { "%.2f m".format(it) },
                            stats.grams?.let { "%.2f g".format(it) }).joinToString(" · ").ifBlank { "Unavailable" })
                        MappingValue("Layers", "${preview.layers.size}")
                        MappingValue("Tool changes", "${stats.toolChanges}")
                    }
                    PreviewView.LINE_TYPE -> preview.kindsUsed.forEach { k ->
                        val f = GcodePreview.FEATURES.getOrNull(k)
                        entry(Color(f?.second ?: 0xFF8C8C8C), f?.first ?: "Other", requireNotNull(usage).byKind.getValue(k),
                            k !in p.previewHiddenKinds) {
                            if (k in p.previewHiddenKinds) p.previewHiddenKinds.remove(k) else p.previewHiddenKinds.add(k)
                        }()
                    }
                    PreviewView.TOOL -> preview.toolsUsed.forEach { t ->
                        val slot = slots.getOrNull(t)
                        val profileName = slot?.filamentProfile?.let { id -> p.filamentLibrary().firstOrNull { it.id == id }?.name }
                        entry(parseHex(slot?.colorHex) ?: c.accent, "${t + 1} · " + (profileName ?: slot?.type ?: "Filament"),
                            requireNotNull(usage).byTool.getValue(t), t !in p.previewHiddenTools) {
                            if (t in p.previewHiddenTools) p.previewHiddenTools.remove(t) else p.previewHiddenTools.add(t)
                        }()
                    }
                    else -> {
                        val (lo, hi) = preview.valueRange(view)
                        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient((0..8).map { Color(GcodePreview.speedColour(it / 8f)) })))
                        Row {
                            Txt("${"%.2f".format(lo)} ${view.units}".trim(), Nz.type.bodySmall, c.textMuted, modifier = Modifier.weight(1f))
                            Txt("${"%.2f".format(hi)} ${view.units}".trim(), Nz.type.bodySmall, c.textMuted)
                        }
                    }
                }
            }
            if (overlayHeight != null) PreviewScrollbar(scroll,
                Modifier.matchParentSize().wrapContentWidth(Alignment.End).fillMaxHeight(),
                "Color legend scroll bar")
        }
    }
}

@Composable
private fun PreviewScrollbar(scroll: ScrollState, modifier: Modifier, label: String) {
    if (scroll.maxValue <= 0 || scroll.maxValue == Int.MAX_VALUE) return
    val c = Nz.colors
    val shape = RoundedCornerShape(3.dp)
    VerticalScrollbar(rememberScrollbarAdapter(scroll),
        modifier.width(6.dp).clip(shape).background(c.lineStrong.copy(alpha = 0.35f))
            .semantics { contentDescription = label },
        style = ScrollbarStyle(18.dp, 6.dp, shape, 200, c.textMuted, c.accent))
}

@Composable
private fun PreviewResetButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    NzIconButton(NzIcon.RESET_VIEW, "Reset 3D view", onClick, modifier = modifier,
        testTag = "plate-reset-view",
        tooltip = "Reset 3D view · Left-drag to orbit · Middle- or right-drag to pan · Scroll to zoom")
}

@Composable
private fun PreviewWarningPopup(preview: GcodePreview, width: Dp, maxBodyHeight: Dp, footerOffsetPx: Int, close: () -> Unit) {
    Popup(alignment = Alignment.Center, offset = IntOffset(0, -footerOffsetPx),
        onDismissRequest = close, properties = PopupProperties(focusable = true)) {
        Card(Modifier.width(width), raised = true) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt("Preview warnings", Nz.type.label, modifier = Modifier.weight(1f))
                NzButton("Close", close, kind = ButtonKind.QUIET)
            }
            val scroll = rememberScrollState()
            Box(Modifier.heightIn(max = maxBodyHeight).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(end = 10.dp).verticalScroll(scroll).padding(bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PreviewWarnings(preview)
                }
                PreviewScrollbar(scroll,
                    Modifier.matchParentSize().wrapContentWidth(Alignment.End).fillMaxHeight(),
                    "Preview warning scroll bar")
            }
        }
    }
}

/** Project controls below the fixed application workflow header. */
@Composable
private fun WorkspaceBar(state: AppState) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkspaceProjectControls(state)
    }
}

@Composable
private fun RowScope.WorkspaceProjectControls(state: AppState) {
    val p = state.prepare
    com.nozzleitall.desktop.settings.DenseInput(p.name, { p.name = it; p.dirty = true }, "Project name",
        Modifier.width(if (Nz.textScale >= 1.5f) 290.dp else 230.dp))
    NzIconButton(
        NzIcon.SAVE,
        "Save project",
        { runCatching { p.save() }.onFailure { p.notice = "Couldn't save: ${it.message}" } },
        kind = if (p.dirty) ButtonKind.SECONDARY else ButtonKind.QUIET,
        enabled = p.dirty || p.file != null,
        testTag = "save-project",
        tooltip = "Save project · Ctrl+S",
    )
    NzIconButton(
        NzIcon.UNDO,
        "Undo",
        p.history::undo,
        enabled = p.history.canUndo,
        testTag = "workspace-undo",
        tooltip = "Undo · Ctrl+Z",
    )
    NzIconButton(
        NzIcon.REDO,
        "Redo",
        p.history::redo,
        enabled = p.history.canRedo,
        testTag = "workspace-redo",
        tooltip = "Redo · Ctrl+Shift+Z",
    )
    com.nozzleitall.desktop.settings.IconToggle(NzIcon.IMPORT, "Add model", false) {
        chooseFiles("Add a model", listOf("stl", "3mf", "obj")).forEach { f ->
            runCatching { p.importModel(f) }.onFailure { p.notice = it.message }
        }
    }
    com.nozzleitall.desktop.settings.IconToggle(NzIcon.PROJECTS, "Projects", false) { state.destination = Destination.PROJECTS }
    if (p.dirty) Box(Modifier.size(8.dp).clip(CircleShape).background(Nz.status.paused).semantics { contentDescription = "Unsaved changes" })
}

/** The stable slicing context shown above the object tree, independent of the selected object. */
@Composable
private fun WorkspaceSetupStack(
    state: AppState,
    drawer: Drawer?,
    setDrawer: (Drawer?) -> Unit,
    modifier: Modifier = Modifier,
    foldFilamentByDefault: Boolean = false,
    scrollInternally: Boolean = true,
) {
    val p = state.prepare
    val c = Nz.colors
    val slots = p.materials()
    val machine = p.machineVariant()
    val library = p.filamentLibrary().associateBy { it.id }
    val collapsed = state.fleet.settings.value.collapsedSettingGroups
    val printerKey = "sidebar:printer"
    val filamentKey = "sidebar:filament"
    var filamentExpandedOverride by remember(p.profileId, foldFilamentByDefault) { mutableStateOf<Boolean?>(null) }
    var priorProfile by remember { mutableStateOf(p.profileId) }
    LaunchedEffect(p.profileId) {
        if (priorProfile != p.profileId) {
            state.fleet.updateSettingGroupCollapsed(printerKey, false)
            state.fleet.updateSettingGroupCollapsed(filamentKey, foldFilamentByDefault)
            filamentExpandedOverride = null
            priorProfile = p.profileId
        }
    }
    val scroll = rememberScrollState()
    var flushing by remember { mutableStateOf(false) }
    val connected = p.printer()
    val status = connected?.status?.value?.state
    var editConnection by remember(connected) { mutableStateOf(false) }
    var syncReview by remember(connected, connected?.session) { mutableStateOf<List<com.nozzleitall.printer.Toolhead>?>(null) }
    val reported = syncReview
    if (reported != null && connected != null) {
        val details = reported.mapNotNull { head ->
            head.material?.let { material ->
                val detected = listOfNotNull(material.vendor, material.subType, material.type)
                    .distinct().joinToString(" ").ifBlank { "Unidentified filament" }
                val selected = slots.firstOrNull { it.slot == head.index + 1 }?.filamentProfile
                    ?.let(library::get)?.name
                "Slot ${head.index + 1}: $detected" + (selected?.let { " · slicing preset: $it" } ?: "")
            }
        }
        ConfirmDialog(
            title = "Sync filament from printer?",
            body = "Copy the loaded spool details from ${connected.config.identity.displayName} into this project.",
            detail = details.takeIf { it.isNotEmpty() }?.joinToString("\n")
                ?: "This printer did not report any loaded spool details.",
            confirmLabel = "Sync filament",
            destructive = false,
            confirmEnabled = details.isNotEmpty(),
            onConfirm = {
                p.notice = if (p.printer() === connected && connected.status.value.toolheads == reported) p.syncFromPrinter()
                    else "Loaded filament changed. Review the current spools before syncing."
                syncReview = null
            },
            onDismiss = { syncReview = null },
        )
    }
    if (editConnection && connected != null) {
        com.nozzleitall.desktop.screens.EditPrinterDialog(state, connected) { editConnection = false }
    }
    Column(modifier.then(if (scrollInternally) Modifier.verticalScroll(scroll) else Modifier),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SetupSection(
            "Printer",
            p.printer()?.config?.identity?.displayName ?: disconnectedConnectionLabel(),
            expanded = printerKey !in collapsed,
            onExpandedChange = { state.fleet.updateSettingGroupCollapsed(printerKey, !it) },
            action = {
                NzIconButton(NzIcon.TUNE, "Manage printers", { state.destination = Destination.FLEET })
            },
        ) {
            val nozzle = machine?.nozzle ?: p.nozzle ?: "0.4"
            val nozzleSizes = p.library()?.machines?.map { it.nozzle }.orEmpty()
            val beds = p.beds()
            val bed = p.effectiveBedType()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PrinterCardField("Printer", Modifier.weight(0.47f)) {
                    PrinterPicker(state, Modifier.fillMaxWidth())
                }
                PrinterCardField("Nozzle", Modifier.weight(0.19f)) {
                    val choices = nozzleSizes.ifEmpty { listOf(nozzle) }
                    com.nozzleitall.desktop.settings.DenseSelect("Nozzle diameter",
                        choices.map { com.nozzleitall.desktop.settings.Choice(it, it) },
                        nozzle, Modifier.fillMaxWidth(), enabled = choices.size > 1, onSelect = p::chooseNozzle)
                }
                PrinterCardField("Bed", Modifier.weight(0.34f)) {
                    com.nozzleitall.desktop.settings.DenseSelect("Bed type",
                        beds.choices.map { com.nozzleitall.desktop.settings.Choice(it.value, it.label) },
                        bed, Modifier.fillMaxWidth(), enabled = beds.enabled && beds.choices.size > 1,
                        compactLabel = { it.removeSuffix(" Plate") }) { value ->
                        p.bedType = value
                        p.changed()
                    }
                }
            }
            val machineName = p.slicingMachineLabel
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Machine profile: $machineName", Nz.type.bodySmall, c.textMuted,
                    modifier = Modifier.weight(1f), maxLines = 2)
                NzIconButton(NzIcon.SETTINGS, "Edit machine preset and nozzle types",
                    { setDrawer(Drawer.PRINTER) })
            }
            connected?.let { printer ->
                val identity = printer.config.identity
                val association = identity.profileId
                when {
                    association == null || ProfileCatalog.byId(association) == null -> {
                        Txt("Printer profile unknown. Verify the machine profile before sending.", Nz.type.bodySmall, c.textMuted)
                        com.nozzleitall.desktop.screens.AssociatePrinterProfile(state, printer, p.profileId)
                    }
                    association != p.profileId ->
                        Txt("Profile mismatch. Select ${ProfileCatalog.byId(association)?.model} for ${identity.displayName}.",
                            Nz.type.bodySmall, c.danger)
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .clickable(onClickLabel = "Open printer connection") {
                    if (connected == null) state.startPrinterSetup(autoScan = true) else editConnection = true
                }
                .padding(horizontal = 6.dp, vertical = 5.dp).semantics { testTag = "setup-connection" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(status?.let { Nz.status.forState(it.glossaryId) } ?: c.textMuted))
                Txt("Connection", Nz.type.bodySmall, c.textMuted,
                    modifier = Modifier.width(setupRowLabelWidth(Nz.textScale)), maxLines = 1)
                Txt(status?.label ?: if (connected == null) disconnectedConnectionLabel() else "Status unavailable", Nz.type.bodySmall,
                    modifier = Modifier.weight(1f), maxLines = 1)
            }
        }
        val filamentExpanded = filamentExpandedOverride ?: (filamentKey !in collapsed && !foldFilamentByDefault)
        SetupSection(
            "Filament",
            "${slots.size} filament${if (slots.size == 1) "" else "s"}",
            expanded = filamentExpanded,
            onExpandedChange = {
                filamentExpandedOverride = it
                state.fleet.updateSettingGroupCollapsed(filamentKey, !it)
            },
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (!filamentExpanded) {
                        FoldedFilamentBadges(slots)
                        Spacer(Modifier.width(3.dp))
                    }
                    if (p.printer() != null) NzIconButton(
                        NzIcon.IMPORT,
                        "Sync filament from printer",
                        { syncReview = p.printer()?.status?.value?.toolheads },
                        testTag = "sync-filament",
                    )
                    if (flushingVolumesVisible(slots.size, p.primeTowerEnabled())) NzIconButton(
                        NzIcon.SPECTRUM,
                        "Flushing volumes",
                        { flushing = true },
                        selected = p.editedFlush() != null,
                        testTag = "flushing-volumes",
                    )
                    NzIconButton(
                        NzIcon.TUNE,
                        "Set filaments to use",
                        { setDrawer(Drawer.FILAMENT) },
                        testTag = "setup-filament",
                    )
                }
            },
        ) {
            val choices = p.filamentLibrary()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                filamentSlotRows(slots.size).forEach { row ->
                    val rowSlots = row.mapNotNull { slots.getOrNull(it - 1) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowSlots.forEach { slot ->
                            val profileName = slot.filamentProfile?.let(library::get)?.name?.removeSuffix(" @U1")
                            val name = profileName ?: listOfNotNull(slot.vendor, slot.subType, slot.type).distinct().joinToString(" ")
                                .ifBlank { "Unassigned filament" }
                            FilamentSlot(
                                slot = slot.slot,
                                name = name,
                                colourHex = slot.colorHex,
                                choices = choices,
                                selected = slot.filamentProfile.orEmpty(),
                                modifier = Modifier.weight(1f),
                                onSelect = { id -> p.setSlotFilament(slot.slot, library[id]) },
                                onEdit = {
                                    p.requestFilamentEditor(slot.slot)
                                    setDrawer(Drawer.FILAMENT)
                                },
                            )
                        }
                        if (rowSlots.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (com.nozzleitall.printer.ext.MixedFilamentFormat.FEATURE in p.features() && slots.size >= 2) {
                NzButton(
                    "Add Mixed Filament",
                    {
                        p.requestMixedFilamentEditor()
                        setDrawer(Drawer.FILAMENT)
                    },
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.QUIET,
                    icon = NzIcon.ADD,
                    testTag = "add-mixed-filament",
                )
            }
        }
        if (drawer != null && drawer != Drawer.PROCESS) SetupRow("Process", p.currentProcess()?.displayLabel ?: p.preset.label,
            false, "setup-process") { setDrawer(Drawer.PROCESS) }
    }
    if (flushing) FlushVolumesWindow(p) { flushing = false }
}

@Composable
internal fun FoldedFilamentBadges(
    slots: List<com.nozzleitall.project.ProjectManifest.MaterialSlot>,
    modifier: Modifier = Modifier,
) {
    val c = Nz.colors
    // The normal label font scales with accessibility text. An 18dp badge is
    // sufficient at 100%, but clips the numeral baseline at 150%.
    val badgeSize = if (Nz.textScale >= 1.5f) 24.dp else 18.dp
    Row(
        modifier.heightIn(min = badgeSize + 6.dp).padding(vertical = 3.dp)
            .semantics { testTag = "folded-filament-badges" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        slots.take(4).forEach { slot ->
            SlotBadge(
                slot.slot,
                slot.colorHex,
                badgeSize,
                Modifier.semantics { testTag = "folded-filament-slot-${slot.slot}" },
            )
        }
        if (slots.size > 4) Txt("+${slots.size - 4}", Nz.type.bodySmall, c.textMuted)
    }
}

@Composable
private fun PrinterCardField(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(label, Nz.type.bodySmall, Nz.colors.textMuted, Modifier.padding(start = 4.dp), maxLines = 1)
        content()
    }
}

@Composable
internal fun WorkspaceMachineReview(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    val current = ProfileCatalog.byId(p.profileId)
    state.pendingMachineSelection?.let { (destinationId, profileId) ->
        val target = ProfileCatalog.byId(profileId)
        val entry = destinationId?.let { state.fleet.printers[it] }
        val unknown = profileId == null
        val mismatched = destinationId == null && p.printer()?.config?.identity?.profileId?.let { it != profileId } == true
        ConfirmDialog(
            title = if (unknown) "Printer profile unknown" else "Switch machine profile?",
            body = if (unknown) {
                "${entry?.config?.identity?.displayName ?: "This printer"} has no recognized machine profile. The current machine profile stays selected."
            } else {
                "Switch from ${current?.name ?: p.profileId} to ${target?.name ?: profileId}." +
                    (entry?.let { " Use ${it.config.identity.displayName} as the destination." } ?: "")
            },
            confirmLabel = if (unknown) "Select printer" else "Switch profile",
            destructive = false,
            extraContent = {
                val detail = when {
                    unknown -> "Verify compatibility before sending."
                    mismatched -> "The saved printer uses another machine profile. Send will require a matching printer or a new slice."
                    else -> "The nozzle, build plate and Process preset reset to the new machine defaults. Compatible project filament presets and plate objects stay."
                }
                Txt(detail, Nz.type.bodySmall, c.textMuted)
            },
            onConfirm = {
                val latest = destinationId?.let { state.fleet.printers[it] }
                if (unknown && latest != null && ProfileCatalog.byId(latest.config.identity.profileId) == null) {
                    p.choosePrinter(destinationId)
                } else if (target != null && (destinationId == null || latest?.config?.identity?.profileId == target.id)) {
                    val availableIds = FilamentLibrary.forProfile(target.id).mapTo(linkedSetOf()) { it.id }
                    val compatible = p.manualSlots.mapNotNull { slot ->
                        slot.filamentProfile?.takeIf { slot.slot <= target.tools && it in availableIds }
                            ?.let { slot.slot to it }
                    }.toMap()
                    p.switchWorkspaceMachine(SlicingSetup(target.id, target.name, toolheads = target.tools,
                        filamentProfiles = compatible, installedFilamentProfiles = availableIds), destinationId)
                } else {
                    p.notice = "The saved printer or its machine profile changed. Open the printer picker and review it again."
                }
                state.pendingMachineSelection = null
            },
            onDismiss = { state.pendingMachineSelection = null },
        )
    }
}

@Composable
private fun PrinterPicker(state: AppState, modifier: Modifier = Modifier) {
    val p = state.prepare
    val c = Nz.colors
    var open by remember { mutableStateOf(false) }
    val current = ProfileCatalog.byId(p.profileId)
    val printerName = p.printer()?.config?.identity?.displayName ?: disconnectedConnectionLabel()
    val viewport = LocalWindowInfo.current.containerSize
    val popupWidth = with(LocalDensity.current) { minOf(320.dp, viewport.width.takeIf { it > 0 }?.toDp() ?: 320.dp) }
    val popupHeight = with(LocalDensity.current) { minOf(330.dp, viewport.height.takeIf { it > 0 }?.toDp() ?: 330.dp) }
    val position = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                val top = if (anchorBounds.bottom <= maxY) anchorBounds.bottom else anchorBounds.top - popupContentSize.height
                return IntOffset(anchorBounds.left.coerceIn(0, maxX), top.coerceIn(0, maxY))
            }
        }
    }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(6.dp)).background(c.surfaceSunken)
            .border(1.dp, if (open) c.focus else c.line, RoundedCornerShape(6.dp))
            .clickable(onClickLabel = "Choose printer or machine profile") { open = !open }
            .padding(horizontal = 8.dp).semantics {
                testTag = "setup-printer"
                contentDescription = "Printer: $printerName"
                role = Role.DropdownList
                stateDescription = if (open) "Expanded" else "Collapsed"
            },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(printerName, Nz.type.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
            Txt("▾", Nz.type.bodySmall, c.textMuted)
        }
        if (open) Popup(
            popupPositionProvider = position,
            onDismissRequest = { open = false }, properties = PopupProperties(focusable = true),
        ) {
            var query by remember { mutableStateOf("") }
            val profiles = remember(p.profileId, query) {
                if (query.isBlank()) listOfNotNull(current) + ProfileCatalog.search("").filter { it.id != current?.id }.take(7)
                else ProfileCatalog.search(query).take(20)
            }
            Column(Modifier.width(popupWidth).height(popupHeight).clip(RoundedCornerShape(10.dp)).background(c.surfaceRaised)
                .border(1.dp, c.lineStrong, RoundedCornerShape(10.dp)).padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt("Saved printers", Nz.type.label, c.textMuted, Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    state.fleet.order.value.mapNotNull { state.fleet.printers[it]?.config?.identity }.forEach { printer ->
                        PrinterPickerOption(printer.displayName, p.printerId == printer.id, "saved-printer-${printer.id}") {
                            state.requestWorkspacePrinter(printer.id)
                            open = false
                        }
                    }
                    PrinterPickerOption("Export only", p.printer() == null, "saved-printer-export") {
                        p.choosePrinter(null)
                        open = false
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                    Txt("Machine profiles", Nz.type.label, c.textMuted, Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    com.nozzleitall.desktop.settings.DenseInput(query, { query = it }, "Search machine profiles",
                        Modifier.fillMaxWidth(), placeholder = "Search machine profiles")
                    profiles.forEach { profile ->
                        PrinterPickerOption("${profile.vendor} · ${profile.model}", profile.id == p.profileId,
                            "slicing-profile-${profile.id}") {
                            if (profile.id != p.profileId) state.pendingMachineSelection = null to profile.id
                            open = false
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                Txt("Add or remove printers…", Nz.type.bodySmall, c.accent,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable {
                        open = false
                        state.destination = Destination.FLEET
                    }.padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun PrinterPickerOption(label: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    val c = Nz.colors
    Txt((if (selected) "✓  " else "") + label, Nz.type.bodySmall, if (selected) c.accent else c.text,
        maxLines = 1, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (selected) c.accent.copy(alpha = 0.14f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).semantics { testTag = tag }.padding(horizontal = 10.dp, vertical = 7.dp))
}

@Composable
private fun SetupSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Nz.colors
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = if (expanded) "Fold $title" else "Open $title") { onExpandedChange(!expanded) }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt(if (expanded) "▾" else "▸", Nz.type.label, c.textMuted)
                Txt(title, Nz.type.label)
                if (!expanded) Txt(summary, Nz.type.bodySmall, c.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
            }
            action?.invoke()
        }
        if (expanded) content()
    }
}

@Composable
private fun FilamentSlot(
    slot: Int,
    name: String,
    colourHex: String?,
    choices: List<FilamentLibrary.Entry>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
    onEdit: () -> Unit,
) {
    val c = Nz.colors
    Row(
        modifier.clip(RoundedCornerShape(7.dp)).padding(vertical = 2.dp)
            .semantics { testTag = "setup-filament-$slot" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SlotBadge(slot, colourHex, 22.dp)
        GroupedFilamentPicker(slot, name, choices, selected, Modifier.weight(1f), onSelect = onSelect)
        NzIconButton(
            NzIcon.TUNE,
            "Edit slot $slot: $name",
            onEdit,
            modifier = Modifier.size(28.dp),
            testTag = "edit-filament-$slot",
        )
    }
}

@Composable
internal fun GroupedFilamentPicker(
    slot: Int,
    label: String,
    choices: List<FilamentLibrary.Entry>,
    selected: String,
    modifier: Modifier = Modifier,
    allowDefault: Boolean = false,
    onSelect: (String) -> Unit,
) {
    val c = Nz.colors
    var open by remember { mutableStateOf(false) }
    val vendors = remember(choices) { com.nozzleitall.desktop.screens.setupFilamentVendorGroups(choices) }
    var vendorKey by remember(open, choices) { mutableStateOf<String?>(null) }
    var typeKey by remember(open, choices) { mutableStateOf<String?>(null) }
    val vendor = vendors.firstOrNull { it.key == vendorKey }
    val types = remember(vendor) {
        com.nozzleitall.desktop.screens.setupFilamentMaterialTypeGroups(vendor?.filaments.orEmpty())
    }
    val type = types.firstOrNull { it.key == typeKey }
    val selectedEntry = choices.firstOrNull { it.id == selected }
    val (vendorLabel, productLabel) = filamentPickerLabels(selectedEntry?.vendor.orEmpty(), label)
    Box(modifier) {
        TooltipArea(tooltip = { SidebarTooltip("Slot $slot · $label") }, delayMillis = 500) {
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(6.dp)).background(c.surfaceSunken)
                .border(1.dp, if (open) c.focus else c.line, RoundedCornerShape(6.dp))
                .clickable(onClickLabel = "Swap filament for slot $slot") { open = !open }
                .padding(horizontal = 7.dp, vertical = 4.dp).semantics {
                    testTag = "setup-filament-combo-$slot"
                    contentDescription = "Slot $slot filament: $label"
                },
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    vendorLabel?.let { Txt(it, Nz.type.metricSmall, c.textMuted, maxLines = 1) }
                    Txt(productLabel, Nz.type.bodySmall, maxLines = 1)
                }
                Txt("▾", Nz.type.bodySmall, c.textMuted)
            }
        }
        if (open) Popup(
            offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 46.dp.roundToPx() }),
            onDismissRequest = { open = false }, properties = PopupProperties(focusable = true),
        ) {
            Column(Modifier.width(310.dp).heightIn(max = 330.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceRaised)
                .border(1.dp, c.lineStrong, RoundedCornerShape(10.dp)).verticalScroll(key(vendorKey, typeKey) { rememberScrollState() }).padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)) {
                when {
                    vendor == null -> {
                        Txt("Vendor", Nz.type.label, c.textMuted, modifier = Modifier.padding(8.dp))
                        if (allowDefault) FilamentPickerOption("Printer default", selected.isEmpty()) {
                            onSelect("")
                            open = false
                        }
                        if (vendors.isEmpty() && !allowDefault) {
                            Txt("No compatible materials", Nz.type.bodySmall, c.textMuted, modifier = Modifier.padding(8.dp))
                        }
                        vendors.forEach { group ->
                            FilamentPickerOption(group.vendor) { vendorKey = group.key }
                        }
                    }
                    type == null -> {
                        FilamentPickerOption("‹  Vendor") { vendorKey = null }
                        Txt("${vendor.vendor} · Material Type", Nz.type.label, c.textMuted, modifier = Modifier.padding(8.dp))
                        types.forEach { group ->
                            FilamentPickerOption(group.materialType) { typeKey = group.key }
                        }
                    }
                    else -> {
                        FilamentPickerOption("‹  Material Type") { typeKey = null }
                        Txt("${vendor.vendor} · ${type.materialType} · Material", Nz.type.label, c.textMuted,
                            modifier = Modifier.padding(8.dp))
                        val options = remember(type) { filamentPickerOptions(type.filaments) }
                        val savedChoice = type.filaments.firstOrNull { it.id == selected }
                        if (savedChoice != null && options.none { it.id == selected }) {
                            Txt("Current saved catalog preset: ${savedChoice.displayName}", Nz.type.bodySmall, c.textMuted,
                                modifier = Modifier.padding(8.dp))
                        }
                        options.forEach { entry ->
                            FilamentPickerOption(entry.displayName, entry.id == selected) {
                                onSelect(entry.id)
                                open = false
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilamentPickerOption(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val c = Nz.colors
    Txt(if (selected) "✓  $label" else label, Nz.type.bodySmall, if (selected) c.accent else c.text,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (selected) c.accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp))
}

/** Collapse repeated catalog names in the picker while leaving saved profile IDs resolvable. */
internal fun filamentPickerOptions(entries: List<FilamentLibrary.Entry>): List<FilamentLibrary.Entry> =
    entries.distinctBy { it.id }.groupBy { it.displayName.lowercase() }.toSortedMap().values.flatMap { sameName ->
        val presets = sameName.filterNot { it.id.startsWith("catalog-") }
        val hasMachinePreset = presets.any { !it.id.startsWith("3dfp-") && !it.id.startsWith("legacy-") }
        val catalogChoice = if (hasMachinePreset) null else sameName.firstOrNull { it.id.startsWith("catalog-") }
        sameName.filter { !it.id.startsWith("catalog-") || it == catalogChoice }
    }

internal fun filamentPickerLabels(vendor: String, displayName: String): Pair<String?, String> {
    val normalizedVendor = vendor.trim().ifBlank { return null to displayName }
    val product = displayName.removePrefix("$normalizedVendor ").trim().ifBlank { displayName }
    return normalizedVendor to product
}

/** Orca's familiar hierarchy in NIA's own shell: global setup and settings stay left; selection stays right. */
@Composable
private fun WorkspaceLeftPanel(
    state: AppState,
    drawer: Drawer?,
    setDrawer: (Drawer?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = state.prepare
    val c = Nz.colors
    val profileValues = profileValuesFor(p)
    val processValues = profileValues?.getOrNull()
    val processScope = com.nozzleitall.desktop.settings.Scope.PROCESS
    val processControls = drawer == Drawer.PROCESS && p.settingsScope == processScope
    var compare by remember { mutableStateOf(false) }
    var objectSettings by remember { mutableStateOf(false) }
    if (compare && processValues != null) {
        com.nozzleitall.desktop.settings.ComparePresetsDialog(
            com.nozzleitall.desktop.settings.SettingsCatalog.bundled, processScope, p, processValues,
            onDismiss = { compare = false },
        )
    }
    if (objectSettings && processValues != null) {
        PlateObjectSettingsDialog(p, processValues) { objectSettings = false }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt("Process", Nz.type.label, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.weight(1f))
            if (processControls) {
                Txt(if (p.advancedSettings) "Advanced" else "Essential", Nz.type.bodySmall, c.textMuted, maxLines = 1)
                NzIconButton(NzIcon.TUNE, if (p.advancedSettings) "Show essential settings" else "Show advanced settings",
                    { p.advancedSettings = !p.advancedSettings }, selected = p.advancedSettings)
                NzIconButton(NzIcon.COMPARE, "Compare process presets",
                    { if (processValues != null) compare = true })
            }
            NzIconButton(NzIcon.LAYERS, "Object settings on this plate",
                { if (processValues != null) objectSettings = true })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NzButton("Global", { setDrawer(Drawer.PROCESS) }, Modifier.weight(1f),
                kind = if (drawer == Drawer.PROCESS) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                testTag = "workspace-global-tab", centerContent = true)
            NzButton("Objects", { setDrawer(null) }, Modifier.weight(1f),
                kind = if (!drawer.isGlobalSetup()) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                testTag = "workspace-objects-tab", centerContent = true)
        }
        when (drawer) {
            Drawer.PROCESS -> {
                if (profileValues == null) Txt("Loading process settings…", Nz.type.bodySmall, c.textMuted)
                else profileValues.onSuccess { values ->
                    com.nozzleitall.desktop.settings.SettingsSheet(
                        com.nozzleitall.desktop.settings.SettingsCatalog.bundled, values, p,
                        Modifier.weight(1f).fillMaxWidth(), processActionsInHeader = processControls)
                }.onFailure { Txt("Process settings unavailable: ${it.message}", Nz.type.bodySmall, c.danger) }
            }
            Drawer.PRINTER -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Txt("Machine, nozzles and connection", Nz.type.label, modifier = Modifier.weight(1f))
                    NzButton("Setup wizard", state::reopenFirstRun, kind = ButtonKind.QUIET)
                }
                StepPrinter(state)
            }
            Drawer.FILAMENT -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("Filaments and toolheads", Nz.type.label)
                MaterialsSection(state)
            }
            else -> {
                val objectScroll = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxSize().padding(end = 10.dp).verticalScroll(objectScroll),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!p.showPreview && drawer != Drawer.AI)
                            AdviceStrip(state, onChoosePrinter = { setDrawer(Drawer.PRINTER) })
                        StepObjects(state, showInspector = false)
                    }
                    VerticalScrollbar(
                        rememberScrollbarAdapter(objectScroll),
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(c.lineStrong.copy(alpha = 0.35f))
                            .semantics { contentDescription = "Objects sidebar scroll bar" },
                        style = ScrollbarStyle(
                            minimalHeight = 28.dp,
                            thickness = 6.dp,
                            shape = RoundedCornerShape(3.dp),
                            hoverDurationMillis = 200,
                            unhoverColor = c.textMuted.copy(alpha = 0.55f),
                            hoverColor = c.accent,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupRow(label: String, value: String, selected: Boolean, tag: String,
                     colours: List<Color> = emptyList(), onClick: () -> Unit) {
    val c = Nz.colors
    val shape = RoundedCornerShape(7.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(if (selected) c.accent.copy(alpha = 0.14f) else Color.Transparent)
        .clickable(onClickLabel = "Open $label settings") { onClick() }
        .padding(horizontal = 7.dp, vertical = if (Nz.textScale >= 1.5f) 4.dp else 5.dp)
        .semantics(mergeDescendants = true) { testTag = tag },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Txt(label, Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(setupRowLabelWidth(Nz.textScale)), maxLines = 1)
        if (colours.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            colours.take(4).forEach { colour -> Box(Modifier.size(11.dp).clip(CircleShape).background(colour).border(1.dp, c.line, CircleShape)) }
        }
        Txt(value, Nz.type.bodySmall, if (selected) c.accent else c.text, modifier = Modifier.weight(1f), maxLines = 1)
        Txt("›", Nz.type.label, if (selected) c.accent else c.textMuted)
    }
}

@Composable
internal fun RowScope.WorkspaceSliceAction(state: AppState) {
    val p = state.prepare
    when (val s = p.slice) {
        SliceState.Idle, SliceState.Cancelled, is SliceState.Failed ->
            NzButton("Slice", { p.slice(state.scope) }, kind = ButtonKind.PRIMARY, icon = NzIcon.SLICE,
                enabled = p.plateItems().isNotEmpty() && p.hasSlicingSetup, testTag = "slice")
        is SliceState.Running -> {
            Txt(s.stage, Nz.type.bodySmall, Nz.colors.textMuted, maxLines = 1)
            Box(Modifier.width(if (Nz.textScale >= 1.5f) 64.dp else 120.dp)) { ProgressBar(s.progress, label = "Slicing progress") }
            NzButton("Cancel", { p.cancelSlice() }, kind = ButtonKind.QUIET, icon = NzIcon.CLOSE)
        }
        is SliceState.Done -> {
            val st = s.result.stats
            Txt(listOfNotNull(formatDuration(st.seconds), st.grams?.let { "%.0f g".format(it) }).joinToString(" · "), Nz.type.metricSmall, maxLines = 1)
            com.nozzleitall.desktop.settings.IconToggle(NzIcon.SLICE, "Slice again", false) { p.slice(state.scope) }
            if (p.showPreview)
                NzButton("Print plate", { state.openWorkflowStage(PrintWorkflowStage.SEND) }, kind = ButtonKind.PRIMARY, icon = NzIcon.SEND, testTag = "open-send")
            else NzButton("Preview", { state.openWorkflowStage(PrintWorkflowStage.PREVIEW) }, kind = ButtonKind.PRIMARY, icon = NzIcon.LAYERS, testTag = "open-preview")
        }
    }
}

/** The Send step: the slice's numbers and warnings, then send, export or queue. */
@Composable
private fun SendDrawer(
    state: AppState,
    s: SliceState.Done,
    showSetupContext: Boolean,
    setDrawer: (Drawer?) -> Unit,
) {
    val p = state.prepare
    val c = Nz.colors
    val st = s.result.stats
    if (showSetupContext) WorkspaceSetupStack(state, Drawer.SEND, setDrawer)
    s.plateContext?.let { plate ->
        Txt(plate.plateName, Nz.type.title, modifier = Modifier.semantics { testTag = "send-plate-name" })
        Txt("${plate.machine} · ${plate.nozzles} · ${plate.bed}", Nz.type.bodySmall, c.textMuted)
    }
    if (s.materials.isNotEmpty()) {
        Txt(s.materials.joinToString(" · ") { material ->
            "${material.slot}: " + listOfNotNull(material.vendor, material.subType, material.type)
                .distinct().joinToString(" ").ifBlank { "Unspecified" }
        }, Nz.type.bodySmall, c.textMuted)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Metric("Time", formatDuration(st.seconds))
        Metric("Material", st.grams?.let { "%.1f g".format(it) } ?: "–")
        Metric("Layers", "${st.layers ?: "–"}")
    }
    if (st.toolChanges > 0) Txt("${st.toolChanges} toolhead changes", Nz.type.bodySmall, c.textMuted)
    s.result.warnings.forEach { Banner(it, BannerKind.WARNING) }
    s.preview?.let { PreviewWarnings(it) }
    val outside = p.outOfBounds().map { it.name }
    outside.forEach { Banner("$it is outside the selected printer's build area; move it onto the plate before queuing or sending.", BannerKind.DANGER) }
    var uploadOnly by remember(s) { mutableStateOf(false) }
    if (uploadOnly) UploadOnlyPanel(state) else SendPanel(state)
    if (p.printer()?.capabilities?.value?.uploadJob == true) {
        NzButton(if (uploadOnly) "Back to print review" else "Upload only", { uploadOnly = !uploadOnly },
            kind = ButtonKind.QUIET, testTag = "upload-only")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NzButton("Export file", { chooseSaveFile("Export sliced file", p.name + "." + s.result.output)?.let { t -> s.result.printFile.copyTo(t, overwrite = true) } }, kind = ButtonKind.QUIET, icon = NzIcon.EXPORT)
        NzButton("Add to queue", {
            val printer = p.printer()
            val toolMap = if (printer?.capabilities?.value?.multiMaterial == true && s.materials.size > 1) s.materials.map { (it.toolhead ?: (it.slot - 1)) } else emptyList()
            runCatching {
                state.fleet.enqueue(p.name, s.result.printFile, printer?.config?.identity?.id, toolMap,
                    s.savedProjectId, s.savedProjectRevision, s.savedPlateId, s.targetProfileId,
                    s.result.engineSha256, s.result.inputSha256,
                    draftPlateId = s.plateContext?.draftQueueId?.takeIf { s.savedProjectId == null }, materialPlan = s.materialPlan)
            }.onSuccess { p.notice = "Added to Queue & history in Monitor." }
                .onFailure { p.notice = "Couldn't add the prepared file to the queue: ${it.message ?: "Unknown queue error."}" }
        }, kind = ButtonKind.QUIET, enabled = outside.isEmpty(), testTag = "add-to-queue")
    }
}

/** What the app can already see is wrong with the plate, one line each, with the fix when it has one. Each can be dismissed. */
@Composable
private fun SliceFailureNotice(prepare: PrepareState, failure: SliceState.Failed) {
    var diagnose by remember(failure.diagnosisEvent) { mutableStateOf(false) }
    Banner(failure.message, BannerKind.DANGER, "AI diagnosis" to { diagnose = true })
    if (diagnose) com.nozzleitall.desktop.settings.LocalGuidanceDialog(
        PreflightGuidance.sliceFailure(failure.cause),
        current = { (prepare.slice as? SliceState.Failed)?.takeIf { it === failure }?.let { PreflightGuidance.sliceFailure(it.cause) } },
        onDismiss = { diagnose = false },
    )
}

@Composable
private fun AdviceStrip(state: AppState, onChoosePrinter: () -> Unit) {
    val p = state.prepare
    val c = Nz.colors
    var dismissed by remember { mutableStateOf(setOf<String>()) }
    val onPlate = p.plateItems()
    val advice = PrepareAdvice.compute(PrepareAdvice.Input(
        offPlate = p.outOfBounds().map { it.name }, hasPrinter = p.printer() != null,
        openEdges = onPlate.associate { it.name to it.openEdges }, objectCount = onPlate.size,
        anyPrinterSaved = state.fleet.order.value.isNotEmpty(), hasMachine = p.hasSlicingSetup)).filter { it.id !in dismissed }
    advice.forEach { a ->
        val act = { when (a.action) {
            PrepareAdvice.ARRANGE -> p.arrange()
            PrepareAdvice.FIND_PRINTER -> state.startPrinterSetup(autoScan = true)
            PrepareAdvice.SET_UP_MACHINE -> state.reopenFirstRun()
            else -> onChoosePrinter()
        } }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (adviceUsesStackedLayout(maxWidth, Nz.textScale)) Card(Modifier.fillMaxWidth()) {
                Txt(a.text, Nz.type.bodySmall, c.text)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (a.action != null) NzButton(a.actionLabel ?: "Fix", act, kind = ButtonKind.SECONDARY, testTag = "advice-${a.id}")
                    NzButton("Dismiss", { dismissed = dismissed + a.id }, kind = ButtonKind.QUIET)
                }
            } else Floating(Modifier.fillMaxWidth()) {
                Icon(NzIcon.ALERT, Nz.status.paused, 18.dp, modifier = Modifier.padding(start = 6.dp))
                Txt(a.text, Nz.type.bodySmall, c.text, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
                if (a.action != null) NzButton(a.actionLabel ?: "Fix", act, kind = ButtonKind.SECONDARY, testTag = "advice-${a.id}")
                NzButton("Dismiss", { dismissed = dismissed + a.id }, kind = ButtonKind.QUIET)
            }
        }
    }
}

/** A pill that floats over the viewport. */
@Composable
private fun Floating(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = Nz.colors
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.clip(shape).background(c.surface.copy(alpha = 0.94f)).border(1.dp, c.line, shape).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** A one-line floating tool surface. Dense tools use icons; overflow is explicit rather than wrapping. */
@Composable
private fun FloatingToolbar(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Nz.colors
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.clip(shape).background(c.surface.copy(alpha = 0.94f)).border(1.dp, c.line, shape)
            .padding(6.dp).semantics { contentDescription = "Plate tools" },
        content = content,
    )
}

@Composable
private fun ViewToggle(label: String, icon: NzIcon, on: Boolean, enabled: Boolean, onClick: () -> Unit) =
    NzButton(label, onClick, kind = if (on) ButtonKind.PRIMARY else ButtonKind.QUIET, icon = icon, enabled = enabled)

/**
 * One part of the panel: a heading that folds it away, with a one-line [summary] of its current choice shown while
 * folded, so the whole setup can be read at a glance.
 */
@Composable
private fun Section(title: String, summary: String, initiallyOpen: Boolean = true, action: (@Composable () -> Unit)? = null, bare: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Nz.colors
    if (bare) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), content = content); return }
    var open by remember(title) { mutableStateOf(initiallyOpen) }
    val shape = RoundedCornerShape(NozzleTokens.Radius.card)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = if (open) "Fold $title" else "Open $title") { open = !open }
                .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(if (open) "▾" else "▸", Nz.type.label, c.textMuted)
                Txt(title, Nz.type.label)
                Txt(if (open) "" else summary, Nz.type.bodySmall, c.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
            }
            action?.invoke()
        }
        if (open) content()
    }
}

@Composable
private fun StepPrinter(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    var query by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    var showPresetChanges by remember(p.file) { mutableStateOf(false) }
    var comparisonRetry by remember(p.file) { mutableIntStateOf(0) }
    val presetReview by key(p.profileId, p.nozzle, p.processId, p.presetSnapshot, p.presetSnapshotProblem, p.materialSelection(),
        p.preset, p.overrides.toMap(), p.editorGeneration, comparisonRetry) {
        produceState<Result<PrepareState.PresetUpdateReview?>?>(null) {
            value = if (p.presetSnapshotProblem != null) Result.success(null)
                else withContext(Dispatchers.IO) { runCatching { p.presetUpdateReview() } }
        }
    }
    val savedPreferredId = p.manifest?.printer?.printerId
    val preferred = savedPreferredId?.let { state.fleet.printers[it] }
    var showAlternatives by remember(savedPreferredId) { mutableStateOf(preferred == null) }
    val printerName = p.printer()?.config?.identity?.displayName ?: "None (export only)"
    Section("Printer", printerName + " · " + (p.profile?.model ?: p.profileId),
        action = { com.nozzleitall.desktop.settings.IconToggle(NzIcon.TUNE, "Printer settings", p.settingsScope == com.nozzleitall.desktop.settings.Scope.PRINTER) {
            p.settingsScope = if (p.settingsScope == com.nozzleitall.desktop.settings.Scope.PRINTER) com.nozzleitall.desktop.settings.Scope.PROCESS else com.nozzleitall.desktop.settings.Scope.PRINTER } }) {
        val ids = state.fleet.order.value
        NzButton("Add printer", { state.startPrinterSetup(autoScan = true) }, kind = ButtonKind.SECONDARY,
            icon = NzIcon.ADD, testTag = "printer-drawer-add-printer")
        val choices = listOf(com.nozzleitall.desktop.settings.Choice("", "None (export only)")) +
            ids.sortedByDescending { it == savedPreferredId }.mapNotNull { id -> state.fleet.printers[id]?.let {
                com.nozzleitall.desktop.settings.Choice(id,
                    "${if (id == savedPreferredId) "Preferred · " else ""}${it.config.identity.displayName} · last reported ${it.status.value.state.label}")
            } }
        if (savedPreferredId != null && preferred == null)
            Txt("The project's preferred printer is not available on this computer. Choose an alternative or export the file.",
                Nz.type.bodySmall, c.textMuted)
        preferred?.let { entry ->
            Txt("Preferred: ${entry.config.identity.displayName} · last reported ${entry.status.value.state.label}",
                Nz.type.bodySmall, c.textMuted)
            if (p.printerId != savedPreferredId) NzButton("Use preferred printer", { state.requestWorkspacePrinter(savedPreferredId) },
                kind = ButtonKind.SECONDARY)
            NzButton(if (showAlternatives) "Hide alternatives" else "Show alternatives",
                { showAlternatives = !showAlternatives }, kind = ButtonKind.QUIET)
        }
        if (preferred == null || showAlternatives || preferred.status.value.state != PrinterState.READY)
            com.nozzleitall.desktop.settings.DenseSelect("Printer", choices, p.printerId ?: "", Modifier.fillMaxWidth()) {
                v -> state.requestWorkspacePrinter(v.ifBlank { null })
            }
        // Slicing needs only a profile. It is independent of the connection, so any printer can be prepared for, offline.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt("Profile", Nz.type.bodySmall, c.textMuted)
            Txt(p.profile?.let { "${it.vendor} · ${it.model}" } ?: p.profileId, Nz.type.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
            Txt(if (choosing) "Done" else "Change", Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { choosing = !choosing }.padding(4.dp))
        }
        if (choosing) {
            com.nozzleitall.desktop.settings.DenseInput(query, { query = it }, "Search printer models", Modifier.fillMaxWidth(), placeholder = "Snapmaker, Prusa, Bambu, Voron…")
            ProfileCatalog.search(query).take(8).forEach { pr ->
                Txt("${pr.vendor} · ${pr.model}", Nz.type.bodySmall, if (p.profileId == pr.id) c.accent else c.text, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { if (p.profileId != pr.id) state.pendingMachineSelection = null to pr.id; choosing = false }.padding(horizontal = 6.dp, vertical = 5.dp))
            }
            Txt("${ProfileCatalog.all.size} printer profiles. A profile means Nozzle can slice for that printer; it doesn't mean the printer has been tested.",
                Nz.type.bodySmall, c.textMuted)
        }
        p.presetSnapshotProblem?.let { problem ->
            Txt(problem, Nz.type.bodySmall, c.danger)
            NzButton("Use current bundled presets", { runCatching { p.applyPresetUpdate() }.onFailure { p.notice = it.message } },
                kind = ButtonKind.SECONDARY)
        }
        if (p.manifest != null && p.presetSnapshot == null && p.presetSnapshotProblem == null) {
            Txt("This project has no saved preset snapshot. Review the current printer and material settings before recording them.",
                Nz.type.bodySmall, c.textMuted)
            NzButton("Record current presets", { runCatching { p.applyPresetUpdate() }.onFailure { p.notice = it.message } },
                kind = ButtonKind.SECONDARY)
        }
        presetReview?.onFailure { error ->
            Txt("Preset comparison unavailable: ${error.message}", Nz.type.bodySmall, c.danger)
        }
        val computedReview = presetReview?.getOrNull()
        val reviewed = computedReview?.takeIf { p.reviewSelectionMatches(it) && p.presetSnapshotProblem == null }
        if (computedReview != null && reviewed == null && p.presetSnapshotProblem == null) {
            Txt("The printer or material selection changed while the preset comparison was prepared. Refresh it before applying an update.",
                Nz.type.bodySmall, c.textMuted)
            NzButton("Refresh preset comparison", { comparisonRetry++ }, kind = ButtonKind.SECONDARY)
        }
        val changes = reviewed?.changes.orEmpty()
        if (p.presetSnapshot != null && p.presetSnapshotProblem == null && presetReview?.isSuccess == true && presetReview?.getOrNull() == null) {
            Txt("The saved preset belongs to a different printer or variant. Review the selected printer and material settings before recording its current presets.",
                Nz.type.bodySmall, c.textMuted)
            NzButton("Use selected printer presets", { runCatching { p.applyPresetUpdate() }.onFailure { p.notice = it.message } },
                kind = ButtonKind.SECONDARY)
        }
        if (p.presetSnapshot != null && changes.isNotEmpty()) {
            Txt("Bundled preset update available · ${changes.size} changed setting${if (changes.size == 1) "" else "s"}. The saved version remains in use.",
                Nz.type.bodySmall, c.textMuted)
            NzButton(if (showPresetChanges) "Hide preset changes" else "Review preset changes",
                { showPresetChanges = !showPresetChanges }, kind = ButtonKind.SECONDARY)
            if (showPresetChanges) {
                val overridden = changes.mapNotNull { change ->
                    change.substringAfter(" · ", "").substringBefore(": ").takeIf { it.isNotBlank() && it in p.overrides }
                }.distinct()
                if (overridden.isNotEmpty()) Txt("Project overrides still take precedence for: ${overridden.joinToString()}. Review their effective values in Process after applying.",
                    Nz.type.bodySmall, c.textMuted)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(changes) { _, change ->
                        Txt(if (change.length <= 240) change else change.take(240) + "… (export comparison for full value)",
                            Nz.type.bodySmall, c.textMuted)
                    }
                }
                NzButton("Export complete preset comparison", {
                    if (reviewed == null || !p.reviewSelectionMatches(reviewed)) {
                        p.notice = "The preset selection changed. Review the comparison again before exporting."
                    } else chooseSaveFile("Export preset comparison", p.name + "-preset-comparison.txt")?.let { target ->
                        runCatching { writePresetComparison(changes, target) }
                            .onFailure { p.notice = "Preset comparison could not be exported: ${it.message}" }
                    }
                }, kind = ButtonKind.QUIET)
                NzButton("Apply bundled preset update", { runCatching { p.applyPresetUpdate(reviewed) }.onFailure { p.notice = it.message } },
                    kind = ButtonKind.PRIMARY)
            }
        }
        PrinterSetupRows(p)
    }
}

/**
 * Bed type and the nozzles, as Snapmaker Orca's printer card shows them: the bed types this printer offers (fixed at its
 * default when it offers no choice), then one tab per nozzle with its diameter and flow (Standard, or High Flow where the
 * printer has high-flow nozzles).
 */
@Composable
private fun PrinterSetupRows(p: PrepareState) {
    val c = Nz.colors
    val beds = remember(p.profileId, p.nozzle) { p.beds() }
    val machine = remember(p.profileId, p.nozzle) { p.machineJson() }
    val nozzles = remember(p.profileId, p.nozzle) { PrinterSetup.nozzles(machine) }
    val highFlow = remember(p.profileId, p.nozzle) { PrinterSetup.supportsHighFlow(machine) }
    if (nozzles.size > 1 && p.library() != null && p.machineVariant() != null)
        Txt("Changing the diameter changes every nozzle to it.", Nz.type.bodySmall, c.textMuted)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Bed type", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(72.dp))
        if (beds.enabled) com.nozzleitall.desktop.settings.DenseSelect("Bed type", beds.choices.map { com.nozzleitall.desktop.settings.Choice(it.value, it.label) },
            p.effectiveBedType(), Modifier.weight(1f)) { v -> p.bedType = v; p.changed() }
        else Txt(beds.choices.firstOrNull { it.value == beds.default }?.label ?: beds.default, Nz.type.bodySmall, modifier = Modifier.weight(1f))
    }
    var tab by remember(p.profileId) { mutableStateOf(0) }
    if (nozzles.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        nozzles.indices.forEach { i ->
            val on = tab == i
            Txt("Nozzle ${i + 1}", Nz.type.label, if (on) c.accent else c.textMuted, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                .background(if (on) c.accent.copy(alpha = 0.14f) else Color.Transparent).clickable { tab = i }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
    val i = tab.coerceIn(0, nozzles.lastIndex)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(if (nozzles.size > 1) "Diameter" else "Nozzle", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(72.dp))
        // The printer's nozzle sizes (its profile family); choosing one switches every nozzle, as Snapmaker Orca does.
        val sizes = p.library()?.machines?.map { it.nozzle }.orEmpty()
        if (sizes.size > 1) com.nozzleitall.desktop.settings.DenseSelect("Nozzle diameter", sizes.map { com.nozzleitall.desktop.settings.Choice(it, "$it mm") },
            p.machineVariant()?.nozzle ?: nozzles[i], Modifier.weight(1f)) { v -> p.chooseNozzle(v) }
        else Txt("${nozzles[i]} mm", Nz.type.bodySmall, modifier = Modifier.weight(1f))
        Txt("Flow", Nz.type.bodySmall, c.textMuted)
        if (highFlow) com.nozzleitall.desktop.settings.DenseSelect("Nozzle ${i + 1} flow",
            listOf(com.nozzleitall.desktop.settings.Choice("standard", "Standard"), com.nozzleitall.desktop.settings.Choice("high_flow", "High Flow")),
            p.nozzleFlows.getOrElse(i) { "standard" }, Modifier.width(130.dp)) { v ->
            while (p.nozzleFlows.size <= i) p.nozzleFlows.add("standard"); p.nozzleFlows[i] = v; p.changed() }
        else Txt("Standard", Nz.type.bodySmall, modifier = Modifier.width(130.dp))
    }
}

@Composable
private fun MaterialsSection(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    val slots = p.materials()
    val printerToolheads = p.printer()?.status?.value?.toolheads.orEmpty()
    val fromPrinter = printerToolheads.any { it.material != null }
    Section("Filament", slots.joinToString(" · ") { "${it.slot} ${it.type ?: "?"}" },
        action = { com.nozzleitall.desktop.settings.IconToggle(NzIcon.TUNE, "Material settings", p.settingsScope == com.nozzleitall.desktop.settings.Scope.FILAMENT) {
            p.settingsScope = if (p.settingsScope == com.nozzleitall.desktop.settings.Scope.FILAMENT) com.nozzleitall.desktop.settings.Scope.PROCESS else com.nozzleitall.desktop.settings.Scope.FILAMENT } }) {
        if (fromPrinter) Txt("As loaded in ${p.printer()?.config?.identity?.displayName}", Nz.type.bodySmall, c.textMuted)
        if (p.printer() != null) NzButton("Sync filament from printer", { p.notice = p.syncFromPrinter() }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = NzIcon.IMPORT, testTag = "sync-filament")
        Txt("Toolhead → scanned spool → slicing profile → model regions", Nz.type.bodySmall, c.textMuted)
        var editingSlot by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(p.filamentEditorRequest) {
            p.filamentEditorRequest?.first?.let { editingSlot = it }
        }
        val library = p.filamentLibrary()
        slots.forEach { s ->
            val toolhead = printerToolheads.firstOrNull { it.index == s.toolhead }
            val spool = listOfNotNull(s.vendor, s.type, s.subType).joinToString(" ").ifBlank { "Unspecified spool" }
            val profile = s.filamentProfile?.let { id -> library.firstOrNull { it.id == id }?.displayName }
                ?: "Printer profile default"
            val assignments = materialObjectAssignments(s.slot, p.items.map { item ->
                MaterialObjectUsage(item.name, item.slot, item.painted.map(item::slotFor).toSet())
            })
            val source = when {
                !s.fromTag && SpoolReader.owns(s.spoolId) -> "External spool reader"
                toolhead?.material?.fromTag == true -> "RFID tag"
                toolhead?.material != null -> "Printer report"
                else -> "Project setting"
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surfaceSunken)
                .border(1.dp, if (editingSlot == s.slot) c.accent else c.line, RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Review slot ${s.slot} material mapping") {
                    editingSlot = if (editingSlot == s.slot) null else s.slot
                }.padding(8.dp).semantics(mergeDescendants = true) {
                    testTag = "material-mapping-slot-${s.slot}"
                    contentDescription = "${toolheadLabel(s.toolhead, s.slot)}, $source, $spool, profile $profile, assigned to ${assignments.ifEmpty { listOf("no model regions") }.joinToString()}"
                }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SlotBadge(s.slot, s.colorHex)
                    Txt(toolheadLabel(s.toolhead, s.slot), Nz.type.label, modifier = Modifier.weight(1f))
                    Pill(source, if (source == "RFID tag") c.accent else c.textMuted)
                }
                MappingValue("Spool", spool)
                if (source == "External spool reader") MappingValue("Reader tag", s.spoolId.orEmpty().substringAfter(':').substringAfter(':'))
                if (s.fromTag) MappingValue("RFID spool", s.spoolId ?: "Scanned tag (printer supplied no stable id)")
                s.sku?.let { MappingValue("Product", it) }
                MappingValue("Slice profile", profile)
                MappingValue("Objects", assignments.joinToString().ifBlank { "Not assigned" })
            }
        }
        editingSlot?.let { id -> slots.firstOrNull { it.slot == id }?.let { SlotEditor(state, p, it, library, fromPrinter) { editingSlot = null } } }
        // Flushing volumes only apply when the selected process actually uses a prime tower.
        var flushing by remember { mutableStateOf(false) }
        if (flushingVolumesVisible(slots.size, p.primeTowerEnabled())) NzButton(if (p.editedFlush() == null) "Flushing volumes" else "Flushing volumes (edited)", { flushing = true },
            kind = ButtonKind.QUIET, testTag = "flushing-volumes")
        if (flushing) FlushVolumesWindow(p) { flushing = false }
        // Painted models: which slot prints each of the model's own colours.
        val paintedItems = p.items.filter { it.painted.isNotEmpty() }
        if (paintedItems.isNotEmpty()) Txt("Painted-region assignments", Nz.type.label)
        paintedItems.forEach { item -> ModelColours(p, item, slots) }
        // Mixed filaments (OrcaSlicer's colour mixing), wherever the printer or profile offers them.
        if (com.nozzleitall.printer.ext.MixedFilamentFormat.FEATURE in p.features() && slots.size >= 2) ColourMixingSection(p)
    }
}

/** Toolheads are zero-based in the printer model; an unmapped slot is not guessed into a toolhead. */
internal fun toolheadLabel(toolhead: Int?, slot: Int): String = toolhead?.let { "Toolhead ${it + 1}" } ?: "Slot $slot"

internal data class MaterialObjectUsage(val name: String, val baseSlot: Int, val paintedRegionSlots: Set<Int>)

/** Names every object whose base material or painted regions use [slot], without duplicating one object. */
internal fun materialObjectAssignments(slot: Int, objects: List<MaterialObjectUsage>): List<String> =
    objects.filter { it.baseSlot == slot || slot in it.paintedRegionSlots }.map { it.name }.distinct()

@Composable
private fun MappingValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Txt(label, Nz.type.bodySmall, Nz.colors.textMuted, modifier = Modifier.width(72.dp), maxLines = 1)
        Txt(value, Nz.type.bodySmall, modifier = Modifier.weight(1f), maxLines = 2)
    }
}

@Composable
private fun ModelColours(p: PrepareState, item: PrepItem, slots: List<com.nozzleitall.project.ProjectManifest.MaterialSlot>) {
    val c = Nz.colors
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Colours in ${item.name}", Nz.type.label, c.text, modifier = Modifier.weight(1f), maxLines = 1)
        Txt("Match", Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Match the model's colours to the nearest loaded filament") {
            p.matchByColour(item) }.padding(4.dp))
    }
    // Loaded filaments, then any Full Spectrum mixes: a model colour can print as either.
    val choices = p.allSlots().map { (id, label) -> com.nozzleitall.desktop.settings.Choice(id.toString(), "$id · $label") }
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(16.dp))
        Txt("Rest of the model", Nz.type.bodySmall, c.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
        Txt("→", Nz.type.bodySmall, c.textMuted)
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(parseHex(p.slotHex(item.slot)) ?: c.accent))
        com.nozzleitall.desktop.settings.DenseSelect("Slot for the rest of the model", choices, item.slot.toString(), Modifier.width(140.dp)) { v -> v.toIntOrNull()?.let { p.setObjectSlot(item, it) } }
    }
    item.painted.forEach { n ->
        val src = item.sources.firstOrNull { it.index == n }
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(parseHex(src?.colorHex) ?: c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(4.dp)))
            Txt(src?.name?.substringBefore(" @") ?: src?.type ?: "Colour $n", Nz.type.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
            Txt("→", Nz.type.bodySmall, c.textMuted)
            val slot = item.slotFor(n)
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(parseHex(p.slotHex(slot)) ?: c.accent))
            com.nozzleitall.desktop.settings.DenseSelect("Slot for colour $n", choices, slot.toString(), Modifier.width(140.dp)) { v -> v.toIntOrNull()?.let { p.setPaintSlot(item, n, it) } }
        }
    }
}

private fun objectContextActions(p: PrepareState, item: PrepItem): List<ContextMenuItem> {
    fun selectTarget(): Boolean {
        if (p.plateItems().none { it === item }) return false
        p.selectObject(item.id)
        return true
    }
    return listOf(
        ContextMenuItem("Select ${item.name}") { selectTarget() },
        ContextMenuItem(if (p.objectVisible(item.id)) "Hide object" else "Show object") {
            if (p.plateItems().any { it === item }) p.toggleObjectVisibility(item.id)
        },
        ContextMenuItem("Duplicate object") {
            if (p.toolBusy == null && selectTarget()) p.duplicateSelected()
        },
        ContextMenuItem("Delete object") {
            if (p.toolBusy == null && selectTarget()) p.removeSelected()
        },
    )
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun StepObjects(state: AppState, showList: Boolean = true, showInspector: Boolean = true) {
    val p = state.prepare
    val c = Nz.colors
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val onPlate = p.plateItems()
    val slots = p.materials()
    val expandedParts = remember { mutableStateMapOf<Int, Boolean>() }
    // The tab is already called Objects, and Arrange is on the plate's toolbar, so this page has no card heading of its own.
    Section("Objects", "", bare = true) {
        if (showList) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(if (onPlate.isEmpty()) "Objects" else "Objects (${onPlate.size})", Nz.type.label, modifier = Modifier.weight(1f))
                com.nozzleitall.desktop.settings.IconToggle(NzIcon.IMPORT, "Add model", false) { chooseFiles("Add a model", listOf("stl", "3mf", "obj")).forEach { f -> runCatching { p.importModel(f) }.onFailure { p.notice = it.message } } }
                if (onPlate.size > 1) com.nozzleitall.desktop.settings.IconToggle(NzIcon.MOVE, "Arrange the plate", false) { p.arrange() }
            }
            if (onPlate.isEmpty()) { Txt("Nothing on this plate yet.", Nz.type.bodySmall, c.textMuted); return@Section }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt("Select all", Nz.type.label, c.accent, modifier = Modifier.clickable { focus.clearFocus(); p.selectAll() }.padding(4.dp))
                if (p.selectedIds.isNotEmpty()) Txt("Deselect", Nz.type.label, c.accent, modifier = Modifier.clickable { focus.clearFocus(); p.deselect() }.padding(4.dp))
            }
            Txt("Ctrl-click to toggle · Shift-click for a range", Nz.type.bodySmall, c.textMuted)
            onPlate.forEach { item ->
                val sel = item.id in p.selectedIds
                Column(Modifier.fillMaxWidth()) {
                    ContextMenuArea(items = { objectContextActions(p, item) }) {
                        Row(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (sel) c.accent.copy(alpha = 0.14f) else Color.Transparent),
                            verticalAlignment = Alignment.CenterVertically) {
                            ObjectVisibilityToggle(item.id, item.name, p.objectVisible(item.id)) { p.toggleObjectVisibility(item.id) }
                            if (item.parts.isNotEmpty()) {
                                val expanded = expandedParts[item.id] == true
                                Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp))
                                    .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse parts for ${item.name}" else "Expand parts for ${item.name}") {
                                        expandedParts[item.id] = !expanded
                                    }.semantics {
                                        testTag = "object-parts-toggle-${item.id}"
                                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                                    }, contentAlignment = Alignment.Center) {
                                    Txt(if (expanded) "▾" else "▸", Nz.type.bodySmall, c.textMuted)
                                }
                            } else Spacer(Modifier.width(24.dp))
                            Row(Modifier.weight(1f).fillMaxHeight()
                                .semantics(mergeDescendants = true) { selected = sel; role = Role.Button; testTag = "object-row-${item.id}"; onClick { focus.clearFocus(); p.selectObject(item.id); true } }
                                .onKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.Spacebar)) {
                                        p.selectObject(item.id, e.isCtrlPressed, e.isShiftPressed, listRange = true); true
                                    } else false
                                }.focusable()
                                .pointerInput(item.id) { awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                                    val up = waitForUpOrCancellation()
                                    if (up != null) {
                                        val modifiers = currentEvent.keyboardModifiers
                                        focus.clearFocus(); p.selectObject(item.id, modifiers.isCtrlPressed, modifiers.isShiftPressed, listRange = true)
                                        up.consume()
                                    }
                                } }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(parseHex(p.slotHex(item.slot)) ?: c.accent))
                                Txt(item.name, Nz.type.bodySmall, if (sel) c.accent else c.text, modifier = Modifier.weight(1f), maxLines = 1)
                                Txt("${"%.0f".format(item.footprintW)}×${"%.0f".format(item.footprintD)}×${"%.0f".format(item.height)} mm", Nz.type.bodySmall, c.textMuted)
                            }
                        }
                    }
                    if (expandedParts[item.id] == true) item.parts.forEachIndexed { index, part ->
                        val selectedPart = sel && p.selectedPart == index
                        val label = objectPartLabel(part)
                        ContextMenuArea(items = {
                            fun partStillPresent() =
                                p.plateItems().any { it === item } && item.parts.getOrNull(index) === part
                            listOf(
                                ContextMenuItem("Select $label") {
                                    if (partStillPresent()) p.selectPart(item.id, index)
                                },
                                ContextMenuItem("Remove part") {
                                    if (p.toolBusy == null && partStillPresent()) p.editSelection {
                                        p.selectObject(item.id)
                                        p.removePart(item, index)
                                    }
                                },
                            )
                        }) {
                            Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 48.dp)
                                .clip(RoundedCornerShape(5.dp)).background(if (selectedPart) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable(role = Role.Button, onClickLabel = "Select $label") { p.selectPart(item.id, index) }
                                .semantics(mergeDescendants = true) {
                                    testTag = "object-part-${item.id}-$index"
                                    contentDescription = "$label part of ${item.name}"
                                    selected = selectedPart
                                    stateDescription = if (selectedPart) "Selected" else "Not selected"
                                }.padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Txt("└", Nz.type.bodySmall, c.textMuted)
                                Txt(label, Nz.type.bodySmall, if (selectedPart) c.accent else c.text, modifier = Modifier.weight(1f), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        if (!showInspector) {
            p.outOfBounds().takeIf { it.isNotEmpty() }?.let { Txt("Off the plate: ${it.joinToString { o -> o.name }}", Nz.type.bodySmall, c.danger) }
            return@Section
        }
        val s = onPlate.firstOrNull { it.id == p.selected }
        if (s != null && p.selectedIds.size > 1) {
            Txt("${p.selectedIds.size} objects selected", Nz.type.label, c.accent)
            Txt("Values follow ${s.name}; edits move, turn and scale the group around its centre. Select one object for individual settings.", Nz.type.bodySmall, c.textMuted)
            ObjectRow("Move (mm)") { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GroupNumber("X", s.x) { p.editSelection { p.moveSelection(s.id, it, s.y) } }; GroupNumber("Y", s.y) { p.editSelection { p.moveSelection(s.id, s.x, it) } }
            } }
            ObjectRow("Turn together") { GroupNumber("°", s.rotZ) { p.editSelection { p.turnSelection(s.id, it - s.rotZ) } } }
            ObjectRow("Scale together") { GroupNumber("%", s.scale * 100f, positive = true) { p.editSelection { p.resizeSelection(s.id, it / (s.scale * 100f)) } } }
            Txt("Enter or leave a field to apply · Esc to cancel", Nz.type.bodySmall, c.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt("Duplicate", Nz.type.label, c.accent, modifier = Modifier.clickable { p.duplicateSelected() }.padding(4.dp))
                Txt("Remove", Nz.type.label, c.danger, modifier = Modifier.clickable { p.removeSelected() }.padding(4.dp))
            }
        }
        if (s != null && p.selectedIds.size == 1) {
            if (s.painted.isNotEmpty()) Txt("This object's colours are painted: choose what each prints as under Filament in the sidebar.", Nz.type.bodySmall, c.textMuted)
            else ObjectRow("Material") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    slots.forEach { m ->
                        val on = s.slot == m.slot
                        Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(parseHex(m.colorHex) ?: c.surfaceSunken)
                            .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.line, RoundedCornerShape(5.dp)).clickable(onClickLabel = "Material ${m.slot}") { s.slot = m.slot; p.changed() },
                            contentAlignment = Alignment.Center) { Txt("${m.slot}", Nz.type.label, if ((parseHex(m.colorHex)?.let { it.red + it.green + it.blue } ?: 0f) > 1.8f) Color(0xFF0D1114) else Color.White) }
                    }
                }
            }
            ObjectRow("Position (mm)") { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { ObjectNumber("X", s.x) { s.x = it; p.changed() }; ObjectNumber("Y", s.y) { s.y = it; p.changed() } } }
            ObjectRow("Turn") { ObjectNumber("°", s.rotZ) { s.rotZ = it; p.changed() } }
            ObjectSizeSection(p, s)
            if (p.plateNames.size > 1) ObjectRow("Plate") {
                com.nozzleitall.desktop.settings.DenseSelect("Move to plate", p.plateNames.mapIndexed { i, n -> com.nozzleitall.desktop.settings.Choice("$i", n) }, "${s.plate}",
                    Modifier.width(160.dp)) { v -> v.toIntOrNull()?.let { p.moveToPlate(s, it) } }
            }
            ObjectInfo(s)
            val profileValues = profileValuesFor(p)
            if (profileValues == null) Txt("Loading object settings…", Nz.type.bodySmall, c.textMuted)
            else profileValues.onSuccess { values ->
                ObjectSettingsSection(p, s, values)
                ObjectPartsSection(p, s, values)
                HeightRangesSection(p, s, values)
            }.onFailure { Txt("Object settings unavailable: ${it.message}", Nz.type.bodySmall, c.danger) }
            VariableLayerSection(p, s)
            s.instanceOf?.let { root -> Txt("Instance of ${p.items.firstOrNull { it.id == root }?.name ?: "another object"}: its settings, parts and paint follow that object; edit them there.", Nz.type.bodySmall, c.textMuted) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt("Duplicate", Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { p.duplicateSelected() }.padding(4.dp))
                Txt("Add instance", Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Add an instance that shares this object's settings, parts and paint") { p.addInstance() }.padding(4.dp))
                Txt("Remove", Nz.type.label, c.danger, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { p.removeSelected() }.padding(4.dp))
            }
        }
        p.outOfBounds().takeIf { it.isNotEmpty() }?.let { Txt("Off the plate: ${it.joinToString { o -> o.name }}", Nz.type.bodySmall, c.danger) }
    }
}

@Composable
private fun ObjectVisibilityToggle(id: Int, name: String, visible: Boolean, onToggle: () -> Unit) {
    val c = Nz.colors
    val label = if (visible) "Hide $name from plate view" else "Show $name on plate"
    TooltipArea(tooltip = { SidebarTooltip(label) }, delayMillis = 500) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(5.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onToggle)
            .semantics {
                contentDescription = label
                stateDescription = if (visible) "Visible in 3D view" else "Hidden from 3D view"
                testTag = "object-visibility-$id"
            },
            contentAlignment = Alignment.Center) {
            Icon(if (visible) NzIcon.VISIBLE else NzIcon.HIDDEN,
                if (visible) c.textMuted else c.textMuted.copy(alpha = 0.55f), 16.dp)
        }
    }
}

internal fun objectPartLabel(part: com.nozzleitall.project.ProjectManifest.ObjectPart): String {
    val kind = when (part.kind) {
        "negative" -> "Negative volume"
        "modifier" -> "Modifier"
        "blocker" -> "Support blocker"
        "enforcer" -> "Support enforcer"
        "part" -> "Part"
        else -> part.kind.replaceFirstChar { it.titlecase() }
    }
    val shape = part.shape.replaceFirstChar { it.titlecase() }
    return "$kind · $shape"
}

/** The project's plates (Orca's plate list): pick one to show it, rename it in place, add one, remove an empty one. */
@Composable
private fun PlateTabs(state: AppState, compact: Boolean) {
    val p = state.prepare
    val c = Nz.colors
    var renaming by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            p.plateNames.forEachIndexed { i, name ->
                val on = i == p.currentPlate
                if (renaming == i) {
                    var text by remember(i) { mutableStateOf(name) }
                    com.nozzleitall.desktop.settings.DenseInput(text, { text = it }, "Plate name", Modifier.width(150.dp))
                    NzButton("OK", { p.renamePlate(i, text.trim().ifBlank { "Plate ${i + 1}" }); renaming = null }, kind = ButtonKind.PRIMARY)
                } else {
                    val count = p.plateItems(i).size
                    val plateId = p.plateIds[i]
                    ContextMenuArea(items = {
                        fun onPlate(action: (Int) -> Unit) {
                            val currentIndex = p.plateIds.indexOf(plateId)
                            if (currentIndex >= 0) action(currentIndex)
                        }
                        buildList {
                            add(ContextMenuItem("Show $name") { onPlate(p::showPlate) })
                            add(ContextMenuItem("Rename plate") { onPlate { renaming = it } })
                            add(ContextMenuItem("Duplicate plate") {
                                onPlate {
                                    if (p.toolBusy == null) {
                                        p.showPlate(it)
                                        p.duplicateCurrentPlate()
                                    }
                                }
                            })
                            if (count > 0) add(ContextMenuItem("Clear this plate") {
                                onPlate { state.requestPlateEdit(PlateEditKind.CLEAR_PLATE, it) }
                            })
                            if (p.plateIds.size > 1) add(ContextMenuItem("Delete plate") {
                                onPlate { state.requestPlateEdit(PlateEditKind.DELETE_PLATE, it) }
                            })
                        }
                    }) {
                        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) c.accent.copy(alpha = 0.14f) else c.surfaceSunken)
                            .border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(8.dp))
                            .clickable(onClickLabel = if (on) "Rename $name" else "Show $name") { if (on) renaming = i else p.showPlate(i) }
                            .semantics { role = Role.Tab; selected = on; contentDescription = "$name, $count object(s)" }
                            .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Txt(name, Nz.type.label, if (on) c.accent else c.text, maxLines = 1)
                            Txt("$count", Nz.type.bodySmall, c.textMuted)
                            if (on && count == 0 && p.plateNames.size > 1)
                                Txt("×", Nz.type.label, c.textMuted, modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClickLabel = "Remove $name") { state.requestPlateEdit(PlateEditKind.DELETE_PLATE, i) }.padding(horizontal = 2.dp))
                        }
                    }
                }
            }
        }
        if (compact) NzIconButton(NzIcon.ADD, "Add plate", { p.addPlate() }, testTag = "add-plate")
        else NzButton("Add plate", { p.addPlate() }, kind = ButtonKind.QUIET, icon = NzIcon.ADD, testTag = "add-plate")
    }
}

/** What the object is: its size, volume and triangles, and open edges when its surface isn't closed (as Orca's object info). */
@Composable
private fun ObjectInfo(s: PrepItem) {
    val c = Nz.colors
    ObjectRow("Volume") { Txt("${"%.1f".format(s.volume / 1000.0)} cm³ · ${"%,d".format(s.mesh.triangleCount)} triangles", Nz.type.bodySmall) }
    if (s.openEdges > 0) ObjectRow("Surface") { Txt("${s.openEdges} open edge${if (s.openEdges == 1) "" else "s"}: it may not slice as expected", Nz.type.bodySmall, c.danger) }
}

private data class PlateToolAction(
    val label: String,
    val icon: NzIcon,
    val testTag: String,
    val group: Int,
    val enabled: Boolean,
    val selected: Boolean = false,
    val disabledReason: String? = null,
    val shortcut: String? = null,
    val run: () -> Unit,
)

@Composable
private fun PlateToolButton(action: PlateToolAction, closeOverflow: (() -> Unit)? = null) {
    val named = action.shortcut?.let { "${action.label} · $it" } ?: action.label
    val tooltip = if (action.enabled) named else "$named: ${action.disabledReason ?: "Unavailable"}"
    NzIconButton(
        icon = action.icon,
        label = action.label,
        onClick = { closeOverflow?.invoke(); action.run() },
        kind = ButtonKind.QUIET,
        enabled = action.enabled,
        selected = action.selected,
        testTag = action.testTag,
        tooltip = tooltip,
    )
}

/** Orca-familiar order with NIA-owned icons: transform, layout, edit, add, analyse and paint. */
@Composable
private fun PlateToolbar(p: PrepareState, sel: PrepItem?, drawer: Drawer?, modelingTool: ModelingTool?,
                         transformTool: TransformTool, selectTransformTool: (TransformTool) -> Unit,
                         toggleModeling: (ModelingTool) -> Unit, toggle: (Drawer) -> Unit) {
    val idle = p.toolBusy == null
    val single = sel != null && p.selectedIds.size == 1
    fun modelAction(tool: ModelingTool, icon: NzIcon, group: Int, enabled: Boolean = single) = PlateToolAction(
        tool.label, icon, "plate-${tool.name.lowercase().replace('_', '-')}", group,
        idle && enabled, drawer == Drawer.MODEL && modelingTool == tool,
        when {
            !idle -> "Another tool is still running"
            tool == ModelingTool.BOOLEAN -> "Select exactly two objects"
            !enabled -> "Select one object"
            else -> null
        }, shortcut = if (tool == ModelingTool.SIMPLIFY) "E" else null,
    ) { toggleModeling(tool) }
    fun paintAction(kind: PaintOps.Kind, icon: NzIcon) = PlateToolAction(
        "${kind.label} paint", icon, if (kind == PaintOps.Kind.SUPPORT) "plate-paint" else "plate-paint-${kind.name.lowercase()}", 5,
        idle && single, p.paintKind == kind,
        if (!idle) "Another tool is still running" else "Select one object",
    ) {
        if (p.paintKind == kind) p.stopPainting() else { p.paintKind = kind; p.paintState = 1 }
    }
    val selectedRequired = if (!idle) "Another tool is still running" else "Select one object"
    val actions = buildList {
        TransformTool.entries.forEach { tool ->
            add(PlateToolAction(tool.label, when (tool) {
                TransformTool.MOVE -> NzIcon.MOVE
                TransformTool.ROTATE -> NzIcon.ROTATE
                TransformTool.SCALE -> NzIcon.SCALE
            }, "tool-${tool.name.lowercase()}", 0, idle && sel != null, transformTool == tool, selectedRequired) { selectTransformTool(tool) })
        }
        add(PlateToolAction("Arrange", NzIcon.ARRANGE, "plate-arrange", 1, idle && p.plateItems().isNotEmpty(),
            disabledReason = if (!idle) "Another tool is still running" else "Add an object first", shortcut = "A") { p.arrange() })
        add(PlateToolAction(if (sel == null) "Auto orient all" else "Auto orient", NzIcon.ORIENT, "plate-orient", 1, idle && p.plateItems().isNotEmpty(),
            disabledReason = if (!idle) "Another tool is still running" else "Add an object first", shortcut = "O") { p.orient() })
        add(modelAction(ModelingTool.PLACE_ON_FACE, NzIcon.PLACE, 1))
        add(PlateToolAction("Cut", NzIcon.CUT, "plate-cut", 2, idle && single, drawer == Drawer.CUT, selectedRequired) { toggle(Drawer.CUT) })
        add(PlateToolAction("Split", NzIcon.SPLIT, "plate-split", 2, idle && single, disabledReason = selectedRequired) { p.splitSelected() })
        add(modelAction(ModelingTool.BOOLEAN, NzIcon.BOOLEAN, 2, p.selectedIds.size == 2))
        add(modelAction(ModelingTool.SIMPLIFY, NzIcon.SIMPLIFY, 2))
        add(modelAction(ModelingTool.REPAIR, NzIcon.REPAIR, 2))
        add(modelAction(ModelingTool.TEXT, NzIcon.TEXT, 3, true))
        add(modelAction(ModelingTool.SVG, NzIcon.SVG, 3, true))
        add(modelAction(ModelingTool.CAD, NzIcon.PLACE, 3, true))
        add(modelAction(ModelingTool.MESHY, NzIcon.AI, 3, true))
        add(modelAction(ModelingTool.MEASURE, NzIcon.MEASURE, 4))
        add(paintAction(PaintOps.Kind.SUPPORT, NzIcon.PAINT_SUPPORT))
        add(paintAction(PaintOps.Kind.SEAM, NzIcon.PAINT_SEAM))
        add(paintAction(PaintOps.Kind.COLOUR, NzIcon.PAINT_COLOR))
        add(paintAction(PaintOps.Kind.FUZZY, NzIcon.PAINT_FUZZY))
        add(PlateToolAction("AI auto-paint", NzIcon.AI, "plate-autopaint", 5,
            idle && single && p.allSlots().size >= 2, drawer == Drawer.AI,
            when { !idle -> "Another tool is still running"; !single -> "Select one object"; else -> "Load at least two filaments" }) { toggle(Drawer.AI) })
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        var overflowOpen by remember { mutableStateOf(false) }
        // A full row needs roughly 40dp per action plus group dividers. At narrower widths the
        // analyse/paint tail moves into one explicit worded menu; the row never wraps or clips.
        val visible = actions.take(plateToolbarVisibleCount(maxWidth, actions.size))
        val overflow = actions.drop(visible.size)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var previousGroup: Int? = null
            visible.forEach { action ->
                if (previousGroup != null && previousGroup != action.group)
                    Box(Modifier.width(1.dp).height(24.dp).background(Nz.colors.line))
                PlateToolButton(action)
                previousGroup = action.group
            }
            if (overflow.isNotEmpty()) {
                Box(Modifier.width(1.dp).height(24.dp).background(Nz.colors.line))
                Box {
                    NzButton(
                        text = "More",
                        onClick = { overflowOpen = !overflowOpen },
                        kind = ButtonKind.SECONDARY,
                        testTag = "plate-tools-more",
                    )
                    if (overflowOpen) Popup(onDismissRequest = { overflowOpen = false }, properties = PopupProperties(focusable = true)) {
                        Column(
                            Modifier.width(220.dp).clip(RoundedCornerShape(10.dp)).background(Nz.colors.surfaceRaised)
                                .border(1.dp, Nz.colors.lineStrong, RoundedCornerShape(10.dp)).padding(6.dp)
                                .semantics { contentDescription = "More plate tools" },
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            overflow.forEach { action ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
                                        .clickable(enabled = action.enabled) { overflowOpen = false; action.run() }
                                        .alpha(if (action.enabled) 1f else 0.45f).padding(horizontal = 8.dp, vertical = 6.dp)
                                        .semantics {
                                            contentDescription = action.label
                                            testTag = action.testTag
                                            if (!action.enabled) disabled()
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(action.icon, if (action.selected) Nz.colors.accent else Nz.colors.text, 18.dp)
                                    Txt(action.label, Nz.type.body, if (action.selected) Nz.colors.accent else Nz.colors.text)
                                }
                            }
                        }
                    }
                }
            }
            p.toolBusy?.let {
                Spacer(Modifier.weight(1f))
                Txt("$it…", Nz.type.bodySmall, Nz.colors.accent, modifier = Modifier.padding(horizontal = 8.dp))
                NzButton("Cancel", p::cancelTool, kind = ButtonKind.SECONDARY, testTag = "tool-cancel")
            }
        }
    }
}

@Composable
private fun DrawerTitle(title: String, onClose: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(title, Nz.type.title, modifier = Modifier.weight(1f), maxLines = 1)
        if (onClose != null) NzButton("Close", onClose, kind = ButtonKind.QUIET)
    }
}

@Composable
private fun CutPanel(p: PrepareState, sel: PrepItem, onDone: () -> Unit) {
    val c = Nz.colors
    var height by remember(sel.id) { mutableStateOf("%.1f".format(sel.height / 2)) }
    DrawerTitle("Cut", onDone)
    Txt("Cut ${sel.name} in two at a height above its base.", Nz.type.bodySmall, c.textMuted)
    com.nozzleitall.desktop.settings.DenseInput(height, { height = it }, "Cut height above the base (of ${"%.1f".format(sel.height)} mm)", Modifier.fillMaxWidth(), units = "mm")
    NzButton("Cut here", { height.replace(',', '.').toFloatOrNull()?.let { p.cutSelected(it); onDone() } }, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY, enabled = p.toolBusy == null)
}

/** Engine-backed mesh tools presented under their familiar direct names, with applied mutations in PlateHistory. */
@Composable
private fun MeshEditResult(p: PrepareState, preview: PrepareState.MeshEditPreview) {
    val c = Nz.colors
    val result = preview.result
    val bounds = result.bounds
    val size = List(3) { axis -> bounds[axis + 3] - bounds[axis] }
    fun dimensions(item: PrepItem) = listOf(item.footprintW, item.footprintD, item.height)
        .joinToString(" × ") { "%.2f".format(it) }
    val camera = remember(preview) { ViewCamera().apply { distance = 260f } }
    val model = remember(preview, c.accent) {
        PlateObject(result.id, result.name, result.mesh, c.accent, 60f, 60f, 0f,
            90f / size.maxOrNull()!!.coerceAtLeast(1f), false)
    }
    val before = preview.primary.mesh.triangleCount + (preview.other?.mesh?.triangleCount ?: 0)
    Txt("${preview.title} result", Nz.type.label, c.text)
    PlateViewer(120f, 120f, listOf(model), camera, c.surfaceSunken, c.line,
        Modifier.fillMaxWidth().height(160.dp), onSelect = {})
    Txt("Triangles: $before → ${result.mesh.triangleCount}", Nz.type.bodySmall, c.textMuted)
    preview.repairStats?.let { stats ->
        Txt("Open edges: ${stats.before.openEdges} → ${stats.after.openEdges}", Nz.type.bodySmall,
            if (stats.after.openEdges == 0) c.textMuted else c.danger)
        Txt("Connected parts: ${stats.before.parts} → ${stats.after.parts}", Nz.type.bodySmall, c.textMuted)
        if (stats.after.openEdges > 0)
            Txt("The repaired mesh still has open edges. Review the result before applying.", Nz.type.bodySmall, c.danger)
    }
    if (preview.other == null)
        Txt("Size: ${dimensions(preview.primary)} mm → ${dimensions(result)} mm", Nz.type.bodySmall, c.textMuted)
    else Txt("Result size: ${dimensions(result)} mm", Nz.type.bodySmall, c.textMuted)
    if (preview.primary.mesh.isPainted || preview.other?.mesh?.isPainted == true)
        Txt("Applying clears facet paint from the result.", Nz.type.bodySmall, c.textMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NzButton("Cancel", p::discardMeshEditPreview, kind = ButtonKind.SECONDARY)
        NzButton(if (preview.repairStats == null) "Apply" else "Apply repair", p::applyMeshEditPreview,
            kind = ButtonKind.PRIMARY, enabled = p.toolBusy == null,
            testTag = if (preview.repairStats != null) "model-repair-apply" else null)
    }
}

@Composable
private fun CadPanel(p: PrepareState) {
    var circular by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf("20") }
    var height by remember { mutableStateOf("20") }
    var depth by remember { mutableStateOf("10") }
    var hole by remember { mutableStateOf("0") }
    fun loadRecipe(recipe: CadRecipe) {
        circular = recipe.circular
        width = recipe.widthMm.toString()
        height = recipe.heightMm.toString()
        depth = recipe.depthMm.toString()
        hole = recipe.holeMm.toString()
    }
    val idle = p.toolBusy == null
    val recipe = runCatching {
        CadRecipe(circular, width.toDouble(), if (circular) 20.0 else height.toDouble(), depth.toDouble(), hole.toDouble())
    }.getOrNull()

    Txt("Extrude a rectangle or circle. Save the recipe to edit the dimensions later. Adding to the plate creates a mesh.",
        Nz.type.bodySmall, Nz.colors.textMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NzButton("Rectangle", { circular = false }, enabled = idle, kind = if (!circular) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
        NzButton("Circle", { circular = true }, enabled = idle, kind = if (circular) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
    }
    com.nozzleitall.desktop.settings.DenseInput(width, { width = it }, if (circular) "Diameter" else "Width", Modifier.fillMaxWidth(), units = "mm")
    if (!circular) com.nozzleitall.desktop.settings.DenseInput(height, { height = it }, "Height", Modifier.fillMaxWidth(), units = "mm")
    com.nozzleitall.desktop.settings.DenseInput(depth, { depth = it }, "Extrusion depth", Modifier.fillMaxWidth(), units = "mm")
    com.nozzleitall.desktop.settings.DenseInput(hole, { hole = it }, "Through hole diameter (0 for none)", Modifier.fillMaxWidth(), units = "mm")
    if (recipe == null) Txt("Enter positive dimensions and a hole smaller than the sketch.", Nz.type.bodySmall, Nz.colors.textMuted)
    NzButton("Add CAD model to plate", { recipe?.let(p::addCad) }, enabled = idle && recipe != null, testTag = "cad-add")
    NzButton("Open CAD recipe…", {
        chooseFiles("Open CAD recipe", listOf("niacad"), false).firstOrNull()?.let { file ->
            p.openCadRecipe(file, ::loadRecipe)
        }
    }, enabled = idle)
    NzButton("Save CAD recipe…", {
        if (recipe != null) chooseSaveFile("Save CAD recipe", "model.niacad")?.let { p.saveCadRecipe(recipe, it) }
    }, enabled = idle && recipe != null)
    NzButton("Export STEP…", {
        if (recipe != null) chooseSaveFile("Export STEP", "model.step")?.let { p.exportCadStep(recipe, it) }
    }, enabled = idle && recipe != null)
    if (idle) CadPluginPanel(p.cadPlugins, ::loadRecipe)
    Txt("STEP import adds the converted geometry as one plate mesh.", Nz.type.bodySmall, Nz.colors.textMuted)
    NzButton("Import STEP as mesh…", {
        chooseFiles("Import STEP", listOf("step", "stp"), false).firstOrNull()?.let(p::importStep)
    }, enabled = idle)
}

@Composable
private fun ModelingPanel(p: PrepareState, sel: PrepItem?, tool: ModelingTool, onDone: () -> Unit) {
    val c = Nz.colors
    DrawerTitle(tool.label, onDone)
    if (tool == ModelingTool.CAD) {
        CadPanel(p)
        return
    }
    if (tool == ModelingTool.TEXT || tool == ModelingTool.SVG) {
        var mode by remember(tool, sel?.id) {
            mutableStateOf(if (sel == null) ModelingOps.ReliefMode.STANDALONE else ModelingOps.ReliefMode.RAISED)
        }
        Txt(if (sel == null) "Create a printable object. Select an object first to raise or engrave its top surface."
            else "Create a separate object, or apply it to the horizontal top surface of ${sel.name}.", Nz.type.bodySmall, c.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModelingOps.ReliefMode.entries.forEach { choice ->
                NzButton(choice.label, { mode = choice },
                    kind = if (mode == choice) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                    enabled = choice == ModelingOps.ReliefMode.STANDALONE || sel != null,
                    testTag = "model-relief-${choice.name.lowercase()}")
            }
        }
        var depth by remember(tool) { mutableStateOf("1.6") }
        com.nozzleitall.desktop.settings.DenseInput(depth, { depth = it }, "Depth", Modifier.fillMaxWidth(), units = "mm")
        val depthMm = depth.replace(',', '.').toDoubleOrNull()
        if (tool == ModelingTool.TEXT) {
            var value by remember { mutableStateOf("Text") }
            var size by remember { mutableStateOf("12") }
            com.nozzleitall.desktop.settings.DenseInput(value, { value = it }, "Text", Modifier.fillMaxWidth())
            com.nozzleitall.desktop.settings.DenseInput(size, { size = it }, "Text size", Modifier.fillMaxWidth(), units = "mm")
            val sizeMm = size.replace(',', '.').toDoubleOrNull()
            NzButton("Add text", { if (sizeMm != null && depthMm != null) p.addText(value, sizeMm, depthMm, mode) },
                Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY,
                enabled = p.toolBusy == null && value.isNotBlank() && (sizeMm ?: 0.0) > 0.0 && (depthMm ?: 0.0) > 0.0,
                testTag = "model-text-apply")
        } else {
            var source by remember { mutableStateOf<File?>(null) }
            var tolerance by remember { mutableStateOf("0.1") }
            NzButton(source?.name ?: "Choose SVG…", {
                chooseFiles("Choose SVG", listOf("svg"), false).firstOrNull()?.let { source = it }
            }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, testTag = "model-svg-choose")
            com.nozzleitall.desktop.settings.DenseInput(tolerance, { tolerance = it }, "Curve tolerance", Modifier.fillMaxWidth(), units = "mm")
            val toleranceMm = tolerance.replace(',', '.').toDoubleOrNull()
            NzButton("Add SVG", { val file = source; if (file != null && depthMm != null && toleranceMm != null) p.addSvg(file, depthMm, toleranceMm, mode) },
                Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY,
                enabled = p.toolBusy == null && source != null && (depthMm ?: 0.0) > 0.0 && (toleranceMm ?: 0.0) > 0.0,
                testTag = "model-svg-apply")
        }
        return
    }
    if (tool == ModelingTool.BOOLEAN) {
        if (p.selectedIds.size != 2) {
            Txt("Select exactly two objects. The primary selection will be the base and the other object will be the tool.", Nz.type.bodySmall, c.textMuted)
            return
        }
        Txt("The primary selection is the base; the other selected object is the tool.", Nz.type.bodySmall, c.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModelingOps.BooleanMode.entries.forEach { mode ->
                NzButton(mode.name.lowercase().replaceFirstChar { it.uppercase() }, { p.previewBooleanSelected(mode) },
                    kind = ButtonKind.SECONDARY, enabled = p.toolBusy == null, testTag = "model-boolean-${mode.wire}")
            }
        }
        p.meshEditPreview?.takeIf { it.other != null }?.let { MeshEditResult(p, it) }
        return
    }
    if (p.selectedIds.size != 1 || sel == null) {
        Txt("Select one object to use ${tool.label.lowercase()}.", Nz.type.bodySmall, c.textMuted)
        return
    }

    when (tool) {
        ModelingTool.REPAIR -> {
            Txt("Check the selected base mesh for open edges and review the repaired result.", Nz.type.bodySmall, c.textMuted)
            NzButton("Check and preview repair", p::previewRepairSelected, kind = ButtonKind.SECONDARY,
                enabled = p.toolBusy == null, testTag = "model-repair-preview")
            p.meshEditPreview?.takeIf { it.primary === sel && it.repairStats != null }?.let { MeshEditResult(p, it) }
        }
        ModelingTool.SIMPLIFY -> {
            var target by remember(sel.id, sel.mesh.triangleCount) { mutableStateOf(maxOf(4, sel.mesh.triangleCount / 2).toString()) }
            com.nozzleitall.desktop.settings.DenseInput(target, { value -> target = value.filter { it.isDigit() }; p.discardMeshEditPreview() }, "Target triangles (currently ${sel.mesh.triangleCount})",
                Modifier.fillMaxWidth())
            NzButton("Preview simplification", { target.toIntOrNull()?.let { p.previewSimplifySelected(it) } }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY,
                enabled = p.toolBusy == null && (target.toIntOrNull() ?: 0) in 4 until sel.mesh.triangleCount, testTag = "model-simplify")
            p.meshEditPreview?.takeIf { it.other == null && it.primary === sel && it.targetTriangles == target.toIntOrNull() }?.let { MeshEditResult(p, it) }
        }
        ModelingTool.PLACE_ON_FACE -> {
            var normal by remember(sel.id) { mutableStateOf("0, 0, 1") }
            com.nozzleitall.desktop.settings.DenseInput(normal, { normal = it }, "Selected face normal (x, y, z)", Modifier.fillMaxWidth())
            val normalValue = normal.split(',').mapNotNull { it.trim().toDoubleOrNull() }.takeIf { it.size == 3 }?.toDoubleArray()
            NzButton("Place this face on the plate", { normalValue?.let { p.laySelectedOnFace(it) } }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY,
                enabled = p.toolBusy == null && normalValue != null, testTag = "model-lay-face")
        }
        ModelingTool.MEASURE -> {
            var pointA by remember(sel.id) { mutableStateOf("0, 0, 0") }
            var pointB by remember(sel.id) { mutableStateOf("10, 0, 0") }
            var pointC by remember(sel.id) { mutableStateOf("") }
            fun parse(value: String) = value.split(',').mapNotNull { it.trim().toDoubleOrNull() }.takeIf { it.size == 3 }?.toDoubleArray()
            com.nozzleitall.desktop.settings.DenseInput(pointA, { pointA = it }, "Point A (x, y, z)", Modifier.fillMaxWidth())
            com.nozzleitall.desktop.settings.DenseInput(pointB, { pointB = it }, "Point B (x, y, z)", Modifier.fillMaxWidth())
            com.nozzleitall.desktop.settings.DenseInput(pointC, { pointC = it }, "Point C for angle/diameter (optional)", Modifier.fillMaxWidth())
            val a = parse(pointA); val b = parse(pointB); val third = parse(pointC)
            NzButton("Measure", { if (a != null && b != null) { if (third == null) p.measurePoints(a, b) else p.measurePoints(a, b, third) } },
                Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, enabled = p.toolBusy == null && a != null && b != null, testTag = "model-measure")
            p.lastMeasurement?.let { measurement ->
                val extras = listOfNotNull(measurement.angleDegrees?.let { "angle ${"%.2f".format(it)}°" },
                    measurement.diameter?.let { "diameter ${"%.2f".format(it)} mm" }).joinToString(" · ")
                Txt("Distance ${"%.2f".format(measurement.distance)} mm${if (extras.isBlank()) "" else " · $extras"}", Nz.type.bodySmall, c.textMuted)
                var target by remember(sel.id, measurement.distance) { mutableStateOf("%.2f".format(measurement.distance)) }
                com.nozzleitall.desktop.settings.DenseInput(target, { target = it }, "Scale measured distance to", Modifier.fillMaxWidth(), units = "mm")
                val targetMm = target.replace(',', '.').toDoubleOrNull()
                NzButton("Scale to measurement", { targetMm?.let(p::scaleSelectedFromMeasurement) }, Modifier.fillMaxWidth(),
                    kind = ButtonKind.PRIMARY, enabled = targetMm != null && targetMm > 0.0, testTag = "model-measure-scale")
            }
        }
        ModelingTool.BOOLEAN, ModelingTool.TEXT, ModelingTool.SVG, ModelingTool.CAD, ModelingTool.MESHY -> Unit
    }
}

/** Orca's paint tools: what to paint (support / seam enforcers and blockers, a colour, fuzzy skin), how (brush, bucket fill, smart fill) and how big. */
@Composable
private fun PaintPanel(p: PrepareState) {
    val c = Nz.colors
    val kind = p.paintKind ?: return
    fun choice(v: String, l: String) = com.nozzleitall.desktop.settings.Choice(v, l)
    val what = when (kind) {
        PaintOps.Kind.SUPPORT, PaintOps.Kind.SEAM -> listOf(choice("1", "Enforcer"), choice("2", "Blocker"), choice("0", "Erase"))
        PaintOps.Kind.FUZZY -> listOf(choice("1", "Fuzzy skin"), choice("0", "Erase"))
        PaintOps.Kind.COLOUR -> p.allSlots().map { (id, label) -> choice(id.toString(), "$id · $label") } + choice("0", "Erase")
    }
    DrawerTitle("Paint", { p.stopPainting() })
    Txt("Drag on the model to paint. Alt-drag orbits, Shift-drag pans.", Nz.type.bodySmall, c.textMuted)
    com.nozzleitall.desktop.settings.DenseSelect("Paint", PaintOps.Kind.values().map { choice(it.name, it.label) }, kind.name, Modifier.fillMaxWidth()) { v ->
        val k = PaintOps.Kind.valueOf(v); p.paintKind = k; p.paintState = if (k == PaintOps.Kind.COLOUR) (p.allSlots().firstOrNull()?.first ?: 1) else 1 }
    com.nozzleitall.desktop.settings.DenseSelect("What", what, p.paintState.toString(), Modifier.fillMaxWidth()) { v -> v.toIntOrNull()?.let { p.paintState = it } }
    com.nozzleitall.desktop.settings.DenseSelect("Tool", PaintOps.Mode.values().map { choice(it.name, it.label) }, p.paintMode.name, Modifier.fillMaxWidth()) { v -> p.paintMode = PaintOps.Mode.valueOf(v) }
    if (p.paintMode == PaintOps.Mode.BRUSH) {
        var radius by remember(p.paintRadius) { mutableStateOf("%.1f".format(p.paintRadius).removeSuffix(".0")) }
        com.nozzleitall.desktop.settings.DenseInput(radius, { radius = it; it.replace(',', '.').toFloatOrNull()?.takeIf { r -> r in 0.2f..40f }?.let { r -> p.paintRadius = r } }, "Brush radius", Modifier.fillMaxWidth(), units = "mm")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        NzButton("Undo", { p.undoPaint() }, kind = ButtonKind.SECONDARY, enabled = p.canUndoPaint && !p.paintBusy)
        NzButton("Clear ${kind.label.lowercase()}", { p.clearPaint(kind) }, kind = ButtonKind.SECONDARY, enabled = !p.paintBusy, testTag = "paint-clear")
        NzButton("Done", { p.stopPainting() }, kind = ButtonKind.PRIMARY, testTag = "paint-done")
        p.takeIf { it.paintBusy }?.let { Txt("Painting…", Nz.type.bodySmall, c.accent) }
    }
}

/**
 * AI auto-paint, in the steps Orca's dialog has: choose how to ask and add a hint, look at exactly the views that will be sent,
 * send them, then keep or drop what comes back. Nothing leaves the computer until Send.
 */
@Composable
private fun AutoPaintPanel(p: PrepareState, onDone: () -> Unit) {
    val c = Nz.colors
    var s by remember { mutableStateOf(p.autoPaintSettings) }
    var hint by remember { mutableStateOf("") }
    var showReferenceViews by remember { mutableStateOf(false) }
    fun update(n: AutoPaint.Settings) { s = n; p.saveAutoPaintSettings(n) }
    val prepared = p.aiPrepared
    var correctionRegion by remember(prepared) { mutableStateOf(0) }
    val answer = p.aiAnswer
    fun close() { p.aiDiscard(); onDone() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DrawerTitle("AI auto-paint", ::close)
        if (prepared == null) {
            Txt("Automatic detection finds geometric regions, not guaranteed semantic parts. Review the images before sending; inspect and correct the painted draft before applying.", Nz.type.bodySmall, c.textMuted)
            if (p.selectedItems().singleOrNull()?.mesh?.isPainted == true) Banner("This model already has colour regions. AI can replace their intentional boundaries. Compare the original and use Keep original paint wherever needed.", BannerKind.WARNING)
            com.nozzleitall.desktop.settings.DenseSelect("Ask through", listOf(com.nozzleitall.desktop.settings.Choice("command", "A command on this computer"),
                com.nozzleitall.desktop.settings.Choice("server", "An OpenAI-compatible server")), s.kind, Modifier.fillMaxWidth()) { update(s.copy(kind = it)) }
            if (s.kind == "command") {
                com.nozzleitall.desktop.settings.DenseInput(s.command, { update(s.copy(command = it)) }, "Command (reads the question on stdin; {dir} is the folder with the views)", Modifier.fillMaxWidth(), placeholder = AutoPaint.DEFAULT_COMMAND)
            } else {
                com.nozzleitall.desktop.settings.DenseInput(s.baseUrl, { update(s.copy(baseUrl = it)) }, "Server address", Modifier.fillMaxWidth(), placeholder = "http://localhost:11434/v1")
                com.nozzleitall.desktop.settings.DenseInput(s.model, { update(s.copy(model = it)) }, "Model (must accept images)", Modifier.fillMaxWidth())
                com.nozzleitall.desktop.settings.DenseInput(s.apiKey, { update(s.copy(apiKey = it)) }, "API key (blank for a local server)", Modifier.fillMaxWidth())
            }
            com.nozzleitall.desktop.settings.DenseInput(hint, { hint = it }, "Hint", Modifier.fillMaxWidth(), placeholder = "Hint (optional), e.g. a goldfish, orange body, black eyes")
            NzButton("Show what will be sent", { p.aiPrepare(hint) }, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY, enabled = p.toolBusy == null && s.provider != null, testTag = "autopaint-run")
        } else {
            val reviewedProvider = s.provider
            val disclosure = reviewedProvider?.let(AutoPaint::requestDisclosure)
            if (answer == null) {
                disclosure?.let {
                    Txt(it.recipient, Nz.type.label)
                    Txt(it.payload, Nz.type.bodySmall, c.textMuted)
                }
            } else {
                Txt("Your saved model is unchanged. Orbit the plate to inspect the draft from every side.", Nz.type.bodySmall, c.textMuted)
            }
            val images = remember(prepared) { prepared.seg.views.mapNotNull { v -> runCatching { v.name to org.jetbrains.skia.Image.makeFromEncoded(File(v.path).readBytes()).toComposeImageBitmap() }.getOrNull() } }
            if (answer != null) NzButton(if (showReferenceViews) "Hide numbered reference views" else "Show numbered reference views", { showReferenceViews = !showReferenceViews }, Modifier.fillMaxWidth(), kind = ButtonKind.QUIET)
            if (answer == null || showReferenceViews) images.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (name, bmp) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.foundation.Image(bmp, name, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)))
                            Txt(name, Nz.type.bodySmall, c.textMuted)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Txt("${prepared.seg.segmentCount} parts", Nz.type.bodySmall, c.textMuted)
            if (answer == null) {
                val reviewReady = disclosure?.valid == true && images.size == prepared.seg.views.size && images.isNotEmpty()
                if (disclosure?.valid == false) Banner("Go back and enter a valid server address before sending.", BannerKind.WARNING)
                if (images.size != prepared.seg.views.size) {
                    Banner("Some reference images could not be displayed. Go back and prepare the views again.", BannerKind.WARNING)
                }
                NzButton("Send reviewed request", { reviewedProvider?.let { p.aiSend(prepared, it) } }, Modifier.fillMaxWidth(),
                    kind = ButtonKind.PRIMARY, enabled = p.toolBusy == null && reviewReady, testTag = "autopaint-send")
                NzButton("Back", { p.aiDiscard() }, Modifier.fillMaxWidth(), kind = ButtonKind.QUIET, enabled = p.toolBusy == null)
            } else {
                if (p.aiPreviewMesh != null) Banner("${answer.faces().count { it > 0 }} of ${answer.faceRegions.size} faces assigned. Inspect the draft on the plate.", BannerKind.SUCCESS)
                else {
                    Banner(if (p.toolBusy != null) "Building the painted draft…" else "The painted draft could not be built. Nothing can be applied until Retry succeeds.", BannerKind.WARNING)
                    NzButton("Retry draft preview", { p.aiRetryPreview() }, Modifier.fillMaxWidth(), enabled = p.toolBusy == null, kind = ButtonKind.SECONDARY)
                }
                NzButton(if (p.aiShowOriginal) "Show painted draft" else "Compare with original", { p.aiShowOriginal = !p.aiShowOriginal }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, enabled = p.aiPreviewMesh != null && p.toolBusy == null)
                NzButton("Keep all original paint", { p.aiKeepOriginal() }, Modifier.fillMaxWidth(), kind = ButtonKind.QUIET, enabled = p.toolBusy == null)
                NzButton(if (p.aiCleanBoundaries) "Boundary cleanup: on" else "Boundary cleanup: off", { p.aiSetCleanup(!p.aiCleanBoundaries) }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, enabled = p.toolBusy == null)
                Txt("Optional tip smoothing on exact shared edges; may make no changes. Preserves small details and sharp creases. ${p.aiCleanedFaces} faces adjusted. Cannot repair semantic segmentation.", Nz.type.bodySmall, c.textMuted)
                Txt("Correct regions (numbers match the images)", Nz.type.label)
                val choices = listOf(com.nozzleitall.desktop.settings.Choice("0", "Keep original paint")) + prepared.filaments.map { f ->
                    com.nozzleitall.desktop.settings.Choice(f.index.toString(), "Slot ${f.index} · ${f.name.ifBlank { f.type }} · ${f.colorHex}")
                }
                com.nozzleitall.desktop.settings.DenseSelect("Region", answer.slots.indices.map { region ->
                    com.nozzleitall.desktop.settings.Choice(region.toString(), "Region $region")
                }, correctionRegion.toString(), Modifier.fillMaxWidth()) { correctionRegion = it.toInt() }
                val correctionSlot = answer.slots.getOrElse(correctionRegion) { 0 }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val filament = prepared.filaments.firstOrNull { it.index == correctionSlot }
                    Box(Modifier.size(18.dp).background(parseHex(filament?.colorHex) ?: c.surfaceRaised, RoundedCornerShape(3.dp)).border(1.dp, c.line, RoundedCornerShape(3.dp)))
                    com.nozzleitall.desktop.settings.DenseSelect("Paint region $correctionRegion with", choices, correctionSlot.toString(), Modifier.weight(1f), enabled = p.toolBusy == null) { p.aiCorrect(correctionRegion, it.toInt()) }
                }
                NzButton("Reset region corrections", { p.aiResetCorrections() }, Modifier.fillMaxWidth(), kind = ButtonKind.QUIET, enabled = p.toolBusy == null)
                val noChanges = answer.slots.all { it == 0 }
                if (noChanges) Txt("No paint changes. Your original model will stay unchanged.", Nz.type.bodySmall, c.textMuted)
                NzButton(if (noChanges) "Done — keep original" else "Apply as paint", { p.aiApply(); onDone() }, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY, enabled = p.toolBusy == null && p.aiPreviewMesh != null, testTag = "autopaint-apply")
                NzButton("Discard", { p.aiDiscard() }, Modifier.fillMaxWidth(), kind = ButtonKind.QUIET)
            }
        }
    }
}

@Composable
private fun ObjectRow(label: String, content: @Composable () -> Unit) = Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
    Txt(label, Nz.type.bodySmall, Nz.colors.textMuted, modifier = Modifier.weight(1f)); content()
}

@Composable
private fun ObjectNumber(units: String, value: Float, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf("%.1f".format(value).removeSuffix(".0")) }
    com.nozzleitall.desktop.settings.DenseInput(text, { t -> text = t; t.replace(',', '.').toFloatOrNull()?.let(onValue) }, units, Modifier.width(80.dp), units = units)
}

/** Group numeric edits commit once, so typing speed never changes the Undo boundary. */
@Composable
private fun GroupNumber(units: String, value: Float, positive: Boolean = false, onCommit: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(groupNumberDisplay(value)) }
    var focused by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    com.nozzleitall.desktop.settings.DenseInput(text, { text = it }, units,
        Modifier.width(80.dp).onFocusChanged { state ->
            if (focused && !state.hasFocus && !cancelled) {
                val parsed = groupNumberCommit(text, value, positive)
                if (parsed != null) onCommit(parsed)
                else text = groupNumberDisplay(value)
            }
            focused = state.hasFocus
            if (focused) cancelled = false
        }.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) false
            else when (e.key) {
                Key.Enter -> { focus.clearFocus(); true }
                Key.Escape -> { cancelled = true; text = groupNumberDisplay(value); focus.clearFocus(); true }
                else -> false
            }
        }, units = units)
}

@Composable
private fun NumberField(label: String, value: Float, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf("%.1f".format(value).removeSuffix(".0")) }
    Field(label, text, { t -> text = t; t.replace(',', '.').toFloatOrNull()?.let(onValue) }, Modifier.width(84.dp))
}

@Composable
private fun SendPanel(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    val entry = p.printer() ?: run { Txt("No printer connected: export the file and send it yourself, or choose Connection in the Printer section.", Nz.type.bodySmall, c.textMuted); return }
    val caps = entry.capabilities.value ?: run { Txt("Connecting to ${entry.config.identity.displayName}…", Nz.type.bodySmall, c.textMuted); return }
    val done = p.slice as? SliceState.Done ?: return
    var preparedDigest by remember(done) { mutableStateOf<String?>(null) }
    var bundleProblem by remember(done) { mutableStateOf<String?>(null) }
    var digestChecked by remember(done) { mutableStateOf(false) }
    var stagedItem by remember(done, entry) { mutableStateOf<com.nozzleitall.desktop.queue.QueueItem?>(null) }
    var problem by remember(done, entry) { mutableStateOf<String?>(null) }
    LaunchedEffect(done) {
        preparedDigest = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { digestPreparedFile(done.result.printFile) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        bundleProblem = withContext(Dispatchers.IO) {
            done.result.bundle?.let { com.nozzleitall.desktop.jobs.bambuLanProblem(it) }
        }
        digestChecked = true
    }
    val printerId = entry.config.identity.id
    val profileId = entry.config.identity.profileId
    val status = entry.status.value
    val reviewedTools = status.toolheads.map { it.preflightObservation() }
    val session = entry.session
    val guard = entry.guard
    fun stillCurrent() = p.slice === done && p.printer() === entry && !entry.retired &&
        state.destination == Destination.PREPARE && state.workspaceSendOpen &&
        p.outOfBounds().isEmpty()
    val sendIdentity = PreparedSendIdentity(printerId, preparedDigest,
        done.savedProjectId, done.savedProjectRevision, done.savedPlateId, done.targetProfileId,
        done.result.inputSha256, done.result.engineSha256,
        if (done.savedProjectId == null) done.plateContext?.draftQueueId
            ?: p.plateIds.getOrNull(p.currentPlate)?.let(p::draftQueuePlateId) else null)
    val items = state.fleet.queue.value
    val staged = stagedItem?.takeIf { expected -> items.any { it.id == expected.id } }
    if (staged != null) {
        DirectPrintStart(state, staged, ::stillCurrent, { problem = it }) { remotePath, handoff ->
            state.openJobMonitor(com.nozzleitall.desktop.MonitorHandoff(
                printerId, staged.name, remotePath.substringAfterLast('/').substringAfterLast('\\'),
                handoff, done.plateContext))
        }
        return
    }
    fun conflicts(item: com.nozzleitall.desktop.queue.QueueItem) = isSameQueuedPrint(item, sendIdentity) ||
        (item.startUncertain && item.startPrinterId == printerId) ||
        (sendIdentity.projectId == null && item.projectId == null && item.sha256 == preparedDigest &&
            item.printerId == printerId && item.targetProfileId == sendIdentity.targetProfileId &&
            (item.inputDigest == null || item.engineBuild == null || item.draftPlateId == null ||
                sendIdentity.inputDigest == null || sendIdentity.engineBuild == null || sendIdentity.draftPlateId == null))
    fun reusable(item: com.nozzleitall.desktop.queue.QueueItem) =
        !item.pendingLink && !item.startUncertain && !item.directDraft && isSameQueuedPrint(item, sendIdentity)
    val existing = items.any { conflicts(it) && !reusable(it) }
    val compatible = done.result.output in caps.acceptedOutputs &&
        (caps.uploadAndStart || (caps.uploadJob && caps.startPrint))
    val profileMismatch = done.targetProfileId != null && profileId != null && done.targetProfileId != profileId
    val preflight = preparedMaterialChecks(done.materialPlan, status, caps)
    val ready = status.state in PrinterAction.StartJob("x").allowedStates
    Txt("Printer: ${entry.config.identity.displayName}", Nz.type.bodySmall, c.textMuted)
    problem?.let { Banner(it, BannerKind.WARNING) }
    bundleProblem?.let { Banner(it, BannerKind.DANGER) }
    preflight.forEach { Banner(it.message, if (it.blocks) BannerKind.DANGER else BannerKind.WARNING) }
    if (!compatible) Banner("This printer cannot start the prepared file. Export it or choose a compatible printer.", BannerKind.WARNING)
    if (profileMismatch) Banner("Choose a printer matching ${done.targetProfileId} or slice again.", BannerKind.DANGER)
    if (!ready) Banner("Wait for the printer to be ready before starting a print.", BannerKind.WARNING)
    if (guard?.needsReconcile == true) Banner("Check the last printer command in Monitor before starting another print.", BannerKind.DANGER)
    if (digestChecked && preparedDigest == null) Banner("The prepared file could not be verified. Slice it again.", BannerKind.DANGER)
    if (existing) {
        Banner("A matching prepared print or unresolved Start needs attention in Queue & history.", BannerKind.WARNING)
        NzButton("Open queue", { state.destination = Destination.JOBS }, kind = ButtonKind.SECONDARY)
    }
    NzButton("Review and Start", {
        val digest = preparedDigest ?: return@NzButton
        if (!stillCurrent() || entry.session !== session || entry.guard !== guard || entry.capabilities.value != caps ||
            entry.status.value.toolheads.map { it.preflightObservation() } != reviewedTools ||
            entry.config.identity.profileId != profileId || entry.guard?.needsReconcile == true ||
            entry.status.value.state !in PrinterAction.StartJob("x").allowedStates ||
            preparedMaterialChecks(done.materialPlan, entry.status.value, caps).any { it.blocks } ||
            state.fleet.queue.value.any { conflicts(it) && !reusable(it) }) {
            problem = "The plate or printer changed. Review the print again."
            return@NzButton
        }
        state.fleet.queue.value.firstOrNull(::reusable)?.let {
            stagedItem = it
            return@NzButton
        }
        val tools = if (caps.multiMaterial && done.materials.size > 1)
            done.materials.map { it.toolhead ?: (it.slot - 1) } else emptyList()
        runCatching {
            state.fleet.enqueue(p.name, done.result.printFile, printerId, tools,
                done.savedProjectId, done.savedProjectRevision, done.savedPlateId, done.targetProfileId,
                done.result.engineSha256, done.result.inputSha256, expectedSha256 = digest,
                draftPlateId = sendIdentity.draftPlateId, materialPlan = done.materialPlan, directDraft = true)
        }.onSuccess { stagedItem = it }
            .onFailure { problem = "Couldn't prepare Start: ${it.message}" }
    }, kind = ButtonKind.PRIMARY, icon = NzIcon.PLAY, testTag = "print-plate-review",
        enabled = digestChecked && preparedDigest != null && bundleProblem == null && session != null && guard != null && !guard.needsReconcile &&
            compatible && !profileMismatch && !existing && ready && preflight.none { it.blocks } && p.outOfBounds().isEmpty())
}

@Composable
private fun UploadOnlyPanel(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    val scope = state.scope
    val entry = p.printer() ?: run { Txt("No printer connected: export the file and send it yourself, or choose Connection in the Printer section.", Nz.type.bodySmall, c.textMuted); return }
    val caps = entry.capabilities.value ?: run { Txt("Connecting to ${entry.config.identity.displayName}…", Nz.type.bodySmall, c.textMuted); return }
    val flow = rememberActionFlow(entry)
    var uploaded by remember(p.slice, entry.config.identity.id) { mutableStateOf<String?>(null) }
    var problem by remember(p.slice, entry.config.identity.id) { mutableStateOf<String?>(null) }
    val done = p.slice as? SliceState.Done ?: return
    var preparedDigest by remember(done) { mutableStateOf<String?>(null) }
    var bundleProblem by remember(done) { mutableStateOf<String?>(null) }
    var digestChecked by remember(done) { mutableStateOf(false) }
    LaunchedEffect(done) {
        preparedDigest = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { digestPreparedFile(done.result.printFile) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        bundleProblem = withContext(kotlinx.coroutines.Dispatchers.IO) {
            done.result.bundle?.let { com.nozzleitall.desktop.jobs.bambuLanProblem(it) }
        }
        digestChecked = true
    }
    val outside = p.outOfBounds().map { it.name }
    val profileMismatch = done.targetProfileId != null && entry.config.identity.profileId != null &&
        done.targetProfileId != entry.config.identity.profileId
    val unknownProfile = done.targetProfileId != null && entry.config.identity.profileId == null
    val commandUnknown = entry.guard?.needsReconcile == true
    val status = entry.status.value
    val session = entry.session
    val guard = entry.guard
    val preflight = preparedMaterialChecks(done.materialPlan, status, caps)
    fun sameConnection() = !entry.retired && p.printer() === entry && session != null &&
        entry.session === session && entry.guard === guard
    val reviewedTools = status.toolheads.map { it.preflightObservation() }
    fun materialsMatchNow() = entry.capabilities.value == caps &&
        entry.status.value.toolheads.map { it.preflightObservation() } == reviewedTools &&
        preparedMaterialChecks(done.materialPlan, entry.status.value, caps).none { it.blocks }
    var readinessReviewed by remember(done, entry, session, guard, outside, caps, profileMismatch, unknownProfile, preflight, reviewedTools) {
        mutableStateOf(false)
    }
    ActionFlowUi(flow, entry)
    problem?.let { Banner(it, BannerKind.WARNING) }
    if (commandUnknown) NzButton("Check printer", { state.scope.launch(kotlinx.coroutines.Dispatchers.Main) { flow.reconcile() } }, kind = ButtonKind.SECONDARY)
    if (!readinessReviewed) {
        Txt("Review upload only", Nz.type.title)
        Txt("Connection: ${entry.config.identity.displayName}", Nz.type.bodySmall, c.textMuted)
        preflight.forEach { Banner(it.message, if (it.blocks) BannerKind.DANGER else BannerKind.WARNING) }
        bundleProblem?.let { Banner(it, BannerKind.DANGER) }
        if (profileMismatch) Banner("This file was sliced for ${done.targetProfileId}, but the printer reports ${entry.config.identity.profileId}. Choose a matching printer or slice again.", BannerKind.DANGER)
        if (unknownProfile) {
            Banner("This connection has no linked machine profile. Check its model and loaded materials before continuing.", BannerKind.WARNING)
            com.nozzleitall.desktop.screens.AssociatePrinterProfile(state, entry, done.targetProfileId)
        }
        if (commandUnknown) Banner("The last printer command has an unknown outcome. Check the printer before sending another file.", BannerKind.DANGER)
        if (done.result.output !in caps.acceptedOutputs || !caps.uploadJob)
            Banner("This connection cannot receive the prepared file format. Export the file and use a supported printer path.", BannerKind.DANGER)
        if (status.state !in PrinterAction.StartJob("x").allowedStates) Banner("The printer is ${status.state.label.lowercase()}; wait until it is ready before sending another job.", BannerKind.WARNING)
        outside.forEach { Banner("$it is outside the selected printer's build area. Move it onto the plate before sending.", BannerKind.DANGER) }
        done.result.warnings.forEach { Banner(it, BannerKind.WARNING) }
        val requiresManualCheck = unknownProfile || preflight.any { !it.blocks }
        NzButton(if (requiresManualCheck) "I checked the printer · Continue" else "Continue to upload",
            { readinessReviewed = true }, kind = ButtonKind.PRIMARY,
            enabled = outside.isEmpty() && !profileMismatch && !commandUnknown && preflight.none { it.blocks } &&
                sameConnection() && (done.result.bundle == null || digestChecked && bundleProblem == null) && done.result.output in caps.acceptedOutputs && caps.uploadJob &&
                status.state in PrinterAction.StartJob("x").allowedStates, testTag = "send-readiness")
        return
    }
    if (commandUnknown) Banner("The last printer command has an unknown outcome. Check the printer before sending or starting another job.", BannerKind.DANGER)
    if (preparedDigest == null) Banner(
        if (digestChecked) "The prepared print could not be verified. Slice it again before uploading."
        else "Checking the prepared print before it can be uploaded.",
        if (digestChecked) BannerKind.DANGER else BannerKind.INFO)
    when {
        // Everything below follows what this printer's adapter reports; nothing depends on who made it.
        done.result.output !in caps.acceptedOutputs -> Banner("${entry.config.identity.displayName} needs a ${caps.acceptedOutputs.joinToString(" or ")} file, but this plate produced a .${done.result.output} file. Select a matching machine profile and slice again.", BannerKind.INFO)
        caps.uploadJob -> if (uploaded == null) {
            p.uploadProgress?.let { ProgressBar(it, label = "Sending to printer") }
            NzButton("Send to ${entry.config.identity.displayName}", { scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                problem = null
                if (p.slice !== done || !sameConnection() || !materialsMatchNow() || p.outOfBounds().isNotEmpty() ||
                    (done.targetProfileId != null && entry.config.identity.profileId != null &&
                        done.targetProfileId != entry.config.identity.profileId) || entry.guard?.needsReconcile == true) {
                    problem = "The job or printer changed after review. Review the job again."
                    readinessReviewed = false
                    return@launch
                }
                when (val r = runCatching { state.fleet.withPrinterTransfer(entry.config.identity.id) {
                    check(sameConnection() && materialsMatchNow() && entry.capabilities.value?.let {
                        it.uploadJob && done.result.output in it.acceptedOutputs
                    } == true) { "The printer connection or compatibility changed. Review the job again." }
                    check(entry.guard?.needsReconcile != true) { "The last printer command needs checking before another transfer." }
                    p.upload(entry, requireNotNull(session), requireNotNull(preparedDigest))
                } }
                    .getOrElse {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        UploadResult.Failed(it.message ?: "Transfer failed.")
                    }) {
                    is UploadResult.Uploaded -> {
                        if (!sameConnection()) {
                            problem = "The printer connection changed during the transfer. Inspect the original destination before retrying."
                            readinessReviewed = false
                            return@launch
                        }
                        uploaded = r.remotePath
                        state.openJobMonitor(com.nozzleitall.desktop.MonitorHandoff(
                            entry.config.identity.id, p.name, r.remotePath.substringAfterLast('/').substringAfterLast('\\'),
                            com.nozzleitall.desktop.MonitorHandoffState.UPLOADED, done.plateContext))
                    }
                    is UploadResult.Interrupted -> problem = "The transfer was interrupted: ${r.reason} The file on the printer may be incomplete. Send it again before printing."
                    is UploadResult.Failed -> problem = r.reason
                }
            } }, kind = ButtonKind.PRIMARY, icon = NzIcon.SEND, enabled = preparedDigest != null &&
                outside.isEmpty() && !profileMismatch && !commandUnknown && p.uploadProgress == null && flow.pending == null && !flow.busy && status.state in PrinterAction.StartJob("x").allowedStates, testTag = "send")
        }
        else -> Txt("${entry.config.identity.displayName} can't receive jobs from Nozzle It All. Export the file instead.", Nz.type.bodySmall, c.textMuted)
    }
    if (caps.anyControl || caps.camera) NzButton("Watch it print", { state.openPrinter(entry.config.identity.id) }, kind = ButtonKind.QUIET, icon = NzIcon.MONITOR)
}

@Composable
fun LayerSlider(value: Int, count: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val c = Nz.colors
    var width by remember { mutableStateOf(1f) }
    Box(modifier.height(28.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }.semantics {
        stateDescription = "Layer ${value + 1} of $count"
        setProgress { t -> onChange(t.toInt().coerceIn(0, count - 1)); true }
        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..(count - 1).coerceAtLeast(1).toFloat())
    }.pointerInput(count) {
        awaitPointerEventScope { while (true) { val e = awaitPointerEvent(); val ch = e.changes.firstOrNull() ?: continue
            if (ch.pressed) onChange(((ch.position.x / width) * (count - 1)).toInt().coerceIn(0, count - 1)) } }
    }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceRaised))
        Box(Modifier.fillMaxWidth(if (count <= 1) 1f else value / (count - 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.accent))
    }
}

/** One Z track with independent first- and last-visible-layer handles. */
@Composable
private fun VerticalLayerRangeSlider(
    first: Int, last: Int, count: Int, modifier: Modifier, onFirst: (Int) -> Unit, onLast: (Int) -> Unit,
) {
    val c = Nz.colors
    val currentRange by rememberUpdatedState(first..last)
    val updateFirst by rememberUpdatedState(onFirst)
    val updateLast by rememberUpdatedState(onLast)
    val maxLayer = count - 1
    val thumb = 14.dp
    val textMeasurer = rememberTextMeasurer()
    val readoutWidth = with(LocalDensity.current) {
        textMeasurer.measure("From $count", style = Nz.type.metricSmall, maxLines = 1).size.width.toDp()
    }
    val sliderWidth = maxOf(44.dp, readoutWidth + 12.dp)
    fun fraction(layer: Int) = if (maxLayer == 0) 0f else layer / maxLayer.toFloat()

    Column(
        modifier.width(sliderWidth)
            .clip(RoundedCornerShape(14.dp)).background(c.surface.copy(alpha = 0.94f))
            .border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Txt("Z", Nz.type.metricSmall, c.textMuted, maxLines = 1)
        Txt("To ${last + 1}", Nz.type.metricSmall, c.accent, maxLines = 1)
        Box(Modifier.weight(1f).width(32.dp).pointerInput(count) {
            val radius = thumb.toPx() / 2
            awaitEachGesture {
                val down = awaitFirstDown()
                val span = (size.height - radius * 2).coerceAtLeast(1f)
                fun layerAt(y: Float) = ((size.height - radius - y) / span).coerceIn(0f, 1f) * maxLayer

                val start = currentRange
                val downLayer = layerAt(down.position.y)
                val nearest = if (abs(downLayer - start.first) < abs(downLayer - start.last)) start.first else start.last
                val layerRadius = radius / span * maxLayer
                val grabbingHandle = abs(downLayer - nearest) <= layerRadius
                var change = down
                var firstHandle: Boolean? = if (start.first == start.last) null else nearest == start.first
                while (change.pressed) {
                    val pointedLayer = layerAt(change.position.y)
                    val layer = if (grabbingHandle) (nearest + (pointedLayer - downLayer)).coerceIn(0f, maxLayer.toFloat()) else pointedLayer
                    if (firstHandle == null) {
                        firstHandle = when {
                            layer < start.first -> true
                            layer > start.last -> false
                            else -> null
                        }
                    }
                    val index = layer.roundToInt()
                    when (firstHandle) {
                        true -> updateFirst(index.coerceAtMost(currentRange.last))
                        false -> updateLast(index.coerceAtLeast(currentRange.first))
                        null -> Unit
                    }
                    change.consume()
                    change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                }
            }
        }) {
            Canvas(Modifier.matchParentSize()) {
                val radius = thumb.toPx() / 2
                val rail = (size.height - 2 * radius).coerceAtLeast(0f)
                fun point(layer: Int) = Offset(center.x, radius + rail * (1f - fraction(layer)))
                val stroke = 6.dp.toPx()
                drawLine(c.surfaceRaised, point(0), point(maxLayer), stroke, StrokeCap.Round)
                drawLine(c.accent, point(first), point(last), stroke, StrokeCap.Round)
            }
            for ((label, value, bounds, update) in listOf(
                LayerRangeHandle("From", first, 0..last, onFirst),
                LayerRangeHandle("To", last, first..maxLayer, onLast),
            )) {
                Box(Modifier.align(BiasAlignment(0f, 1f - 2f * fraction(value))).size(thumb)
                    .clip(CircleShape).background(c.accent).border(2.dp, c.surface, CircleShape)
                    .semantics {
                        contentDescription = "$label layer"
                        stateDescription = "Layer ${value + 1} of $count"
                        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), bounds.first.toFloat()..bounds.last.toFloat())
                        setProgress { target -> update(target.roundToInt().coerceIn(bounds)); true }
                    })
            }
        }
        Txt("From ${first + 1}", Nz.type.metricSmall, c.accent, maxLines = 1)
    }
}

private data class LayerRangeHandle(val label: String, val value: Int, val bounds: IntRange, val update: (Int) -> Unit)

@Composable
private fun VerticalLayerSlider(value: Int, count: Int, modifier: Modifier, label: String, onChange: (Int) -> Unit) {
    val c = Nz.colors
    var height by remember { mutableStateOf(1f) }
    val update by rememberUpdatedState(onChange)
    val fraction = if (count <= 1) 1f else value / (count - 1f)
    Column(
        modifier.widthIn(min = 44.dp * Nz.textScale.coerceAtLeast(1f)).width(IntrinsicSize.Max)
            .clip(RoundedCornerShape(14.dp)).background(c.surface.copy(alpha = 0.94f))
            .border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Txt(label, Nz.type.metricSmall, c.textMuted, maxLines = 1)
        Txt("${value + 1}", Nz.type.metricSmall, c.accent, maxLines = 1)
        Box(
            Modifier.weight(1f).width(28.dp).onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
                .semantics {
                    contentDescription = "$label layer"
                    stateDescription = "Layer ${value + 1} of $count"
                    setProgress { t -> onChange(t.toInt().coerceIn(0, count - 1)); true }
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..(count - 1).coerceAtLeast(1).toFloat())
                }.pointerInput(count) {
                    awaitPointerEventScope { while (true) {
                        val event = awaitPointerEvent(); val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed) update((((height - change.position.y) / height) * (count - 1)).toInt().coerceIn(0, count - 1))
                    } }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxHeight().width(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceRaised))
            Box(Modifier.fillMaxHeight(fraction).width(6.dp).clip(RoundedCornerShape(3.dp)).background(c.accent))
        }
        Txt("1", Nz.type.metricSmall, c.textMuted)
    }
}

/**
 * One slot's filament: the filament profile it slices with (the printer's own library), and for a slot Nozzle keeps
 * itself, its colour from that filament's catalogue colours (or any colour). A printer that reports what's loaded
 * decides the colour and material; only the profile is chosen here.
 */
@Composable
private fun SlotEditor(state: AppState, p: PrepareState, slot: com.nozzleitall.project.ProjectManifest.MaterialSlot, library: List<FilamentLibrary.Entry>,
                       fromPrinter: Boolean, onDone: () -> Unit) {
    val c = Nz.colors
    val current = library.firstOrNull { it.id == slot.filamentProfile }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("Slot ${slot.slot}", Nz.type.label)
            Spacer(Modifier.weight(1f))
            Txt("Done", Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onDone() }.padding(4.dp))
        }
        if (library.isEmpty()) Txt("This printer profile slices every slot with its own filament.", Nz.type.bodySmall, c.textMuted)
        else GroupedFilamentPicker(slot.slot, current?.displayName ?: "Printer default", library,
            current?.id.orEmpty(), Modifier.fillMaxWidth(), allowDefault = true) { id ->
            p.setSlotFilament(slot.slot, library.firstOrNull { it.id == id })
        }
        if (p.printer()?.status?.value?.toolheads.isNullOrEmpty() && library.isNotEmpty()) SpoolReaderControls(p, slot, library)
        if (fromPrinter) Txt("Colour and material are what the printer reports loaded.", Nz.type.bodySmall, c.textMuted)
        else {
            val swatches = current?.let { FilamentColours.forFamily(it.family) }.orEmpty()
            if (swatches.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                swatches.forEach { sw ->
                    val on = sw.hex.equals(slot.colorHex, true)
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(parseHex(sw.hex) ?: c.surface)
                        .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.line, RoundedCornerShape(5.dp))
                        .clickable(onClickLabel = sw.name.ifBlank { sw.hex }) { p.setSlotFilament(slot.slot, current, sw.hex) }
                        .semantics { contentDescription = "${sw.name} ${sw.hex}" })
                }
            }
            var hex by remember(slot.slot, slot.colorHex) { mutableStateOf(slot.colorHex ?: "") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("Colour", Nz.type.bodySmall, c.textMuted)
                com.nozzleitall.desktop.settings.DenseInput(hex, { t -> hex = t
                    if (Regex("#?[0-9A-Fa-f]{6}").matches(t.trim())) p.setSlotFilament(slot.slot, current, "#" + t.trim().removePrefix("#").uppercase()) },
                    "Colour for slot ${slot.slot}", Modifier.width(120.dp), placeholder = "#RRGGBB")
            }
        }
        Txt("Base object assignments", Nz.type.label)
        if (p.plateItems().isEmpty()) Txt("Add a model to assign this material.", Nz.type.bodySmall, c.textMuted)
        p.plateItems().forEach { item ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt(item.name, Nz.type.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                if (item.slot == slot.slot) Pill("Base material", c.accent)
                else NzButton("Use for base", { p.setObjectSlot(item, slot.slot) }, kind = ButtonKind.QUIET)
            }
        }
        Txt("Painted-region assignments remain editable directly below the material slots, where each source colour can map to any installed slot.", Nz.type.bodySmall, c.textMuted)
        var catalogOpen by remember(slot.slot) { mutableStateOf(false) }
        if (!catalogOpen) NzButton("Find brand in 3D Filament Profiles", { catalogOpen = true }, kind = ButtonKind.QUIET)
        else CatalogFilamentPicker(state, p, slot, fromPrinter) { catalogOpen = false }
    }
}

@Composable
private fun CatalogFilamentPicker(state: AppState, p: PrepareState, slot: com.nozzleitall.project.ProjectManifest.MaterialSlot,
                                  fromPrinter: Boolean, onClose: () -> Unit) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    var query by remember(slot.slot) { mutableStateOf(listOfNotNull(slot.vendor, slot.type, slot.subType).joinToString(" ")) }
    var results by remember(slot.slot) { mutableStateOf<List<com.nozzleitall.desktop.materials.FilamentProfileRecord>>(emptyList()) }
    var error by remember(slot.slot) { mutableStateOf<String?>(null) }
    var busy by remember(slot.slot) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surface).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("3D Filament Profiles", Nz.type.label, modifier = Modifier.weight(1f))
            Txt("Close", Nz.type.label, c.accent, modifier = Modifier.clickable { onClose() }.padding(4.dp))
        }
        com.nozzleitall.desktop.settings.DenseInput(query, { query = it }, "Search filament brands and products", Modifier.fillMaxWidth(), placeholder = "Brand or product")
        NzButton(if (busy) "Searching…" else "Search catalog", {
            busy = true; error = null
            scope.launch {
                val reply = withContext(Dispatchers.IO) { state.filamentCatalog.search(query) }
                results = reply.records; error = reply.error; busy = false
            }
        }, kind = ButtonKind.SECONDARY, enabled = !busy && query.isNotBlank())
        error?.let { Banner(it, BannerKind.WARNING) }
        if (!busy && error == null && results.isEmpty()) Txt("Search results will appear here. Nothing is imported until you choose a product.", Nz.type.bodySmall, c.textMuted)
        results.take(25).forEach { record ->
            Row(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(6.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                record.colorHex?.let { Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(parseHex(it) ?: c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(5.dp))) }
                Column(Modifier.weight(1f)) {
                    Txt(record.productName, Nz.type.bodySmall)
                    Txt(listOf(record.baseMaterial, record.color).filter(String::isNotBlank).joinToString(" · "), Nz.type.bodySmall, c.textMuted)
                }
                NzButton("Use", {
                    runCatching { state.filamentPresets.import(p.filamentProfileKey(), record) }
                        .onSuccess { imported -> p.setSlotFilament(slot.slot, imported.entry, if (fromPrinter) null else imported.colorHex); onClose() }
                        .onFailure { error = it.message ?: "The preset could not be imported." }
                }, kind = ButtonKind.PRIMARY)
            }
        }
    }
}
