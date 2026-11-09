package com.nozzleitall.desktop.prepare

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import com.nozzleitall.desktop.App
import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.SlicingSetup
import com.nozzleitall.desktop.ui.NozzleTheme
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SpoolReaderUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownTagFromPrepareRequiresExplicitProfile() {
        val root = Files.createTempDirectory("nia-spool-reader-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val app = AppState(AppPaths(root.resolve("config"), root.resolve("data"), root.resolve("cache")).ensure(), scope)
            app.finishFirstRun(SlicingSetup("snapmaker_u1", "Snapmaker U1", "0.4", toolheads = 4))
            app.destination = Destination.PREPARE
            val before = app.prepare.materials().first()
            compose.setContent { NozzleTheme("dark") { App(app) } }
            compose.onNodeWithTag("setup-filament").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("material-mapping-slot-1").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Map spool from reader").performSemanticsAction(SemanticsActions.OnClick)
            val input = compose.onNodeWithContentDescription("Reader tag ID")
            input.performTextInput("x".repeat(96) + " extra")
            compose.onNodeWithText("Tag ID is too long.").assertExists()
            compose.onNodeWithText("Apply reader mapping to slot 1").assertDoesNotExist()
            input.performTextReplacement("0042")
            compose.onNodeWithText("Unknown tag.", substring = true).assertExists()
            compose.onNodeWithText("Apply reader mapping to slot 1").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(before, app.prepare.materials().first()) }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
