package com.nozzleitall.desktop.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.prepare.workspaceSidebarWidth
import com.nozzleitall.desktop.ui.NozzleTheme
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SettingsCategoryStripUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val tabs = SettingsCatalog.bundled.tabs.getValue(Scope.PROCESS)

    @Test
    fun `all process categories fit the standard sidebar at 100 percent without a scrollbar`() {
        compose.setContent {
            NozzleTheme("dark", textScale = 1f) {
                Box(Modifier.width(workspaceSidebarWidth(1360.dp, 1f) - 44.dp)) {
                    TabStrip(tabs, tabs.first().id) {}
                }
            }
        }

        compose.waitForIdle()
        tabs.forEach { compose.onNodeWithTag("settings-category-${it.id}").assertIsDisplayed() }
        compose.onNodeWithTag("settings-category-scrollbar").assertDoesNotExist()
    }

    @Test
    fun `all process categories fit the standard sidebar at 150 percent without a scrollbar`() {
        compose.setContent {
            NozzleTheme("dark", textScale = 1.5f) {
                // The real sidebar reserves 28dp for its resize edge and the compact
                // settings card contributes 8dp on each side.
                Box(Modifier.width(workspaceSidebarWidth(1360.dp, 1.5f) - 44.dp)) {
                    TabStrip(tabs, tabs.first().id) {}
                }
            }
        }

        compose.waitForIdle()
        tabs.forEach { compose.onNodeWithTag("settings-category-${it.id}").assertIsDisplayed() }
        compose.onNodeWithTag("settings-category-scrollbar").assertDoesNotExist()
    }

    @Test
    fun `a genuinely narrow settings panel exposes horizontal overflow`() {
        compose.setContent {
            NozzleTheme("dark", textScale = 1.5f) {
                Box(Modifier.width(220.dp)) {
                    TabStrip(tabs, tabs.first().id) {}
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag("settings-category-scrollbar").assertExists()
    }
}
