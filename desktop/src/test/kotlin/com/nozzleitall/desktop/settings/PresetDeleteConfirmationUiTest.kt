package com.nozzleitall.desktop.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.SlicingSetup
import com.nozzleitall.desktop.ui.NozzleTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PresetDeleteConfirmationUiTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var appScope: CoroutineScope
    private lateinit var app: AppState
    private lateinit var profile: ProfileValues

    @Before
    fun setUp() {
        val root = Files.createTempDirectory("nia-preset-delete-ui-").toFile()
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), appScope)
        app.finishFirstRun(SlicingSetup("snapmaker_u1", "Snapmaker U1", "0.4", toolheads = 4))
        profile = ProfileValues(mapOf("layer_height" to "0.20", "wall_loops" to "2"))
        app.prepare.overrides["layer_height"] = "0.18"
        app.prepare.saveUserPreset(Scope.PROCESS, "Delete confirmation", profile, overwrite = false)
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    @Test
    fun `Delete requires confirmation and Cancel preserves the selected user preset`() {
        compose.setContent {
            NozzleTheme("dark") {
                Box(Modifier.width(520.dp).height(760.dp)) {
                    SettingsSheet(SettingsCatalog.bundled, profile, app.prepare)
                }
            }
        }

        compose.onNodeWithContentDescription("Delete Process user preset").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Delete Process preset?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertNotNull(app.prepare.selectedUserPreset(Scope.PROCESS))

        compose.onNodeWithContentDescription("Delete Process user preset").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Delete preset").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertNull(app.prepare.selectedUserPreset(Scope.PROCESS))
    }
}
