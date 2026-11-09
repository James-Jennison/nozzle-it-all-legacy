package com.nozzleitall.desktop.prepare

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.ui.NozzleTheme
import com.nozzleitall.project.ProjectManifest
import org.junit.Rule
import org.junit.Test

class FoldedFilamentHeaderUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `folded filament header keeps all four slot colours visible`() {
        val slots = listOf("#171717", "#F26A3D", "#F4F4F4", "#3CCB7F").mapIndexed { index, colour ->
            ProjectManifest.MaterialSlot(slot = index + 1, colorHex = colour)
        }

        compose.setContent {
            NozzleTheme("dark") {
                FoldedFilamentBadges(slots)
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag("folded-filament-badges").assertIsDisplayed()
        slots.forEach { slot ->
            compose.onNodeWithTag("folded-filament-slot-${slot.slot}").assertIsDisplayed()
        }
    }

    @Test
    fun `folded filament header remains visible at 150 percent text`() {
        val slots = listOf("#171717", "#F26A3D", "#F4F4F4", "#3CCB7F").mapIndexed { index, colour ->
            ProjectManifest.MaterialSlot(slot = index + 1, colorHex = colour)
        }

        compose.setContent {
            NozzleTheme("dark", textScale = 1.5f) {
                FoldedFilamentBadges(slots)
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag("folded-filament-badges").assertIsDisplayed()
        slots.forEach { slot ->
            compose.onNodeWithTag("folded-filament-slot-${slot.slot}")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(24.dp)
        }
    }
}
