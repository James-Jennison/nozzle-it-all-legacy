package com.nozzleitall.desktop.prepare

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.nozzleitall.desktop.App
import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.SlicingSetup
import com.nozzleitall.desktop.ui.NozzleTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CadWorkflowUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun createsCadGeometryInPrepareAndUndoesTheSinglePlateEdit() {
        assumeTrue("Requires the pinned native engine", System.getenv("NOZZLE_ENGINE")?.let { File(it).canExecute() } == true)
        val root = Files.createTempDirectory("nia-cad-workflow").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
            app.finishFirstRun(SlicingSetup("snapmaker_u1", "Snapmaker U1", "0.4", toolheads = 4))
            app.destination = Destination.PREPARE
            val prepare = app.prepare
            compose.setContent { NozzleTheme("dark") { App(app) } }
            compose.onNodeWithTag("empty-workspace-cad").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("cad-add").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(timeoutMillis = 20_000) { prepare.toolBusy == null }
            compose.runOnIdle {
                val item = prepare.items.single()
                assertEquals("CAD model", item.name)
                val bounds = item.mesh.bounds()
                assertEquals(20f, bounds[3] - bounds[0], 0.001f)
                assertEquals(10f, bounds[5] - bounds[2], 0.001f)
                prepare.history.undo()
                assertTrue(prepare.items.isEmpty())
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test fun loadsInstalledPluginIntoEditableCadFieldsWithoutAddingGeometry() {
        val root = Files.createTempDirectory("nia-cad-plugin-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val app = AppState(AppPaths(root.resolve("config"), root.resolve("data"), root.resolve("cache")).ensure(), scope)
            app.finishFirstRun(SlicingSetup("snapmaker_u1", "Snapmaker U1", "0.4", toolheads = 4))
            app.destination = Destination.PREPARE
            val plugin = CadPlugin.decode("""
                {"api":1,"kind":"cad-recipe","id":"round-spacer","name":"Round spacer","release":1,
                "recipe":{"shape":"circle","diameterMm":33,"depthMm":5,"holeDiameterMm":4}}
            """.trimIndent().toByteArray())
            app.prepare.cadPlugins.install(plugin)
            compose.setContent { NozzleTheme("dark") { App(app) } }
            compose.onNodeWithTag("empty-workspace-cad").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("cad-plugins").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Round spacer · v1").assertExists()
            compose.onNodeWithText("Load recipe").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNode(hasContentDescription("Diameter") and hasText("33.0") and hasSetTextAction()).assertExists()
            compose.onNode(hasContentDescription("Extrusion depth") and hasText("5.0") and hasSetTextAction()).assertExists()
            compose.onNode(hasContentDescription("Through hole diameter (0 for none)") and hasText("4.0") and hasSetTextAction()).assertExists()
            compose.runOnIdle { assertTrue(app.prepare.items.isEmpty()) }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }


    @Test fun bundledMarketplaceReviewsBeforeInstallingExactBytes() {
        val root = Files.createTempDirectory("nia-marketplace-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val app = AppState(AppPaths(root.resolve("config"), root.resolve("data"), root.resolve("cache")).ensure(), scope)
            app.finishFirstRun(SlicingSetup("snapmaker_u1", "Snapmaker U1", "0.4", toolheads = 4))
            app.destination = Destination.PREPARE
            compose.setContent { NozzleTheme("dark") { App(app) } }
            compose.onNodeWithTag("empty-workspace-cad").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("cad-plugins").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("cad-plugin-marketplace").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithContentDescription("Search marketplace").performTextInput("spacer")
            compose.onNodeWithText("Review Rectangular drill guide").assertDoesNotExist()
            compose.onNodeWithText("Review Round spacer").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Circle · 20.0 mm diameter · 5.0 mm extrusion").assertExists()
            compose.runOnIdle { assertTrue(app.prepare.cadPlugins.inventory().installed.isEmpty()) }
            compose.onNodeWithText("Install reviewed plugin").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Plugin installed.").assertExists()
            compose.runOnIdle {
                val installed = app.prepare.cadPlugins.inventory().installed.single()
                assertEquals("round-spacer", installed.plugin.id)
                assertEquals("76cafd53068c3d381a4a407b5ac21ad3e6337602c1610495723a976b297865e7", installed.digest)
                assertTrue(app.prepare.items.isEmpty())
                app.prepare.cadPlugins.setEnabled(installed, false)
            }
            compose.onNodeWithText("Close plugins").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("cad-plugins").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("cad-plugin-marketplace").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Installed and disabled").assertExists()
            compose.runOnIdle { root.resolve("config/plugins/round-spacer.niaplugin").appendText(" ") }
            compose.onNodeWithText("Close plugins").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("cad-plugins").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("cad-plugin-marketplace").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("This ID is installed with different content.").assertExists()
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

}
