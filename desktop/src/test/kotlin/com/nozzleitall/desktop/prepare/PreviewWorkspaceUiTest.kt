package com.nozzleitall.desktop.prepare

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.ui.NozzleTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PreviewWorkspaceUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun focusKeepsPreviewControlsAndSummaryUsesSliceTotals() {
        val root = Files.createTempDirectory("nia-preview-workspace").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val gcode = File(root, "preview.gcode").apply {
            writeText("""
                M83
                G1 X2 E1 F600
                ;LAYER_CHANGE
                ;WIDTH:0.42
                G1 Z0.2
                G1 X10 E1 F600
                ;LAYER_CHANGE
                G1 Z0.4
                G1 X20 E1 F600
            """.trimIndent())
        }
        try {
            val result = SliceOutcome.Done(gcode, SliceStats(2, 90.0, 3.5, 1.25, 0), emptyList())
            app.prepare.slice = SliceState.Done(result, GcodePreview.parse(gcode))
            app.prepare.previewLayer = 1
            app.prepare.showPreview = true
            compose.setContent { NozzleTheme("dark") { PrepareScreen(app) } }

            compose.onNodeWithTag("save-project").assertExists()
            compose.onNodeWithTag("preview-focus-mode").performClick()
            compose.onNodeWithTag("save-project").assertDoesNotExist()
            val from = compose.onNodeWithContentDescription("From layer")
            val to = compose.onNodeWithContentDescription("To layer")
            from.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0f, 0f..1f)))
            to.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f)))
            from.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
            from.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Layer 2 of 2"))
            to.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 1f..1f)))
            from.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
            to.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
            from.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0f, 0f..0f)))
            to.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Layer 1 of 2"))
            to.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
            compose.onNodeWithTag("preview-section-view").performClick()
            compose.onNodeWithContentDescription("From layer").assertDoesNotExist()
            compose.onNodeWithContentDescription("Section layer").assertExists()
            compose.onNodeWithTag("preview-section-view").performClick()
            compose.onNodeWithContentDescription("From layer").assertExists()
            compose.onNodeWithTag("preview-view-selector").performClick()
            compose.onNodeWithTag("preview-view-actual_speed").assertIsNotEnabled()
            compose.onNodeWithText("Summary").performClick()
            compose.onNodeWithText("1.25 m · 3.50 g").assertExists()
            compose.onNodeWithTag("preview-legend-toggle").performClick()
            compose.onNodeWithText("1.25 m · 3.50 g").assertDoesNotExist()
            compose.onNodeWithTag("preview-legend-toggle").performClick()
            compose.onNodeWithText("1.25 m · 3.50 g").assertExists()
            compose.onNodeWithTag("preview-realistic-view").performClick()
            compose.onNodeWithText("Realistic View · recorded extrusion widths").assertExists()
            compose.onNodeWithTag("preview-realistic-view").performClick()
            compose.onNodeWithText("1.25 m · 3.50 g").assertExists()
            compose.onNodeWithTag("preview-legend-toggle").performClick()
            compose.onNodeWithTag("preview-warnings-open").performClick()
            val warning = compose.onNodeWithText("Extrusion before the first layer marker", substring = true)
            warning.assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            warning.assertDoesNotExist()
            compose.onNodeWithTag("preview-focus-mode").performClick()
            compose.onNodeWithTag("save-project").assertExists()
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun narrowPreviewKeepsLayerActionSeparateFromLegend() {
        val root = Files.createTempDirectory("nia-narrow-preview").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(root.resolve("config"), root.resolve("data"), root.resolve("cache")).ensure(), scope)
        val gcode = root.resolve("preview.gcode").apply {
            writeText("M83\n;LAYER_CHANGE\n;Z:0.2\nG1 Z0.2\nG1 X10 E1 F600\n")
        }
        try {
            val result = SliceOutcome.Done(gcode, SliceStats(1, 45.0, 0.5, 0.2, 0,
                filamentMetres = listOf(0.2), filamentGrams = listOf(null)), emptyList())
            app.prepare.slice = SliceState.Done(result, GcodePreview.parse(gcode))
            app.prepare.previewView = PreviewView.TOOL
            app.prepare.showPreview = true
            compose.setContent {
                NozzleTheme("dark", textScale = 1.5f) {
                    Box(Modifier.requiredSize(960.dp, 640.dp)) { PrepareScreen(app) }
                }
            }
            val action = compose.onNodeWithTag("preview-layer-action").assertIsDisplayed()
            val selector = compose.onNodeWithTag("preview-view-selector").assertIsDisplayed()
            assertTrue(action.fetchSemanticsNode().boundsInRoot.right <= selector.fetchSemanticsNode().boundsInRoot.left)
            compose.onNodeWithText("0.20 m").assertIsDisplayed()
            compose.onNodeWithText("— = unavailable").assertIsDisplayed()
            compose.onNodeWithText("Path").assertDoesNotExist()
            action.performClick()
            compose.onNodeWithText("Layer 1 action").assertIsDisplayed()
        } finally {
            app.fleet.shutdown()
            scope.cancel()
            root.deleteRecursively()
        }
    }

}
