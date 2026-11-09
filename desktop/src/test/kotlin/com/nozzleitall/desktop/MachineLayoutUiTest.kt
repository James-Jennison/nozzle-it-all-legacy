package com.nozzleitall.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nozzleitall.desktop.prepare.ProfileCatalog
import com.nozzleitall.desktop.settings.ProfileValues
import com.nozzleitall.desktop.settings.Scope
import com.nozzleitall.desktop.settings.SettingsCatalog
import com.nozzleitall.desktop.settings.SettingsSheet
import com.nozzleitall.desktop.ui.NozzleTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MachineLayoutUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun printerLayoutShowsBothModeSwitchesWithAdvancedOff() {
        val root = Files.createTempDirectory("nia-machine-layout").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        try {
            app.prepare.settingsScope = Scope.PRINTER
            val profile = ProfileValues.read(ProfileCatalog.materialize(app.paths.cache, "snapmaker_u1"))
            compose.setContent {
                NozzleTheme("dark") {
                    SettingsSheet(SettingsCatalog.bundled, profile, app.prepare, Modifier.fillMaxSize())
                }
            }
            compose.onNodeWithTag("settings-category-machine-layout").performClick()
            compose.onNodeWithText("Enable belt printing").assertExists()
            compose.onNodeWithText("IDEX/IQEX Printer").assertExists()
            compose.runOnIdle { assertFalse(app.prepare.advancedSettings) }
        } finally {
            app.fleet.shutdown()
            scope.cancel()
        }
    }
}
