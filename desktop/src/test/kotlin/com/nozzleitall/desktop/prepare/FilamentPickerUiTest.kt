package com.nozzleitall.desktop.prepare

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nozzleitall.desktop.ui.NozzleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class FilamentPickerUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `only choosing a material or printer default changes the slot`() {
        val selections = mutableListOf<String>()
        val choices = listOf(
            FilamentLibrary.Entry("pla", "Brand PLA Basic", "Brand", "PLA", "PLA"),
            FilamentLibrary.Entry("petg", "Brand PETG Basic", "Brand", "PETG", "PETG"),
        )
        compose.setContent {
            NozzleTheme("dark") {
                GroupedFilamentPicker(2, "Printer default", choices, "", allowDefault = true) {
                    selections.add(it)
                }
            }
        }

        compose.onNodeWithTag("setup-filament-combo-2").performClick()
        compose.onNodeWithText("Vendor").assertExists()
        compose.onNodeWithText("Brand PETG Basic").assertDoesNotExist()
        compose.onNodeWithText("Brand", useUnmergedTree = false).performClick()
        compose.onNodeWithText("PETG").performClick()
        compose.onNodeWithText("Brand PLA Basic").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), selections) }
        compose.onNodeWithText("Brand PETG Basic").performClick()
        compose.onNodeWithTag("setup-filament-combo-2").performClick()
        compose.onNodeWithText("✓  Printer default").performClick()
        compose.runOnIdle { assertEquals(listOf("petg", ""), selections) }
    }

    @Test
    fun `collapsed catalog choice stays identified until the user selects a replacement`() {
        val selections = mutableListOf<String>()
        val native = FilamentLibrary.Entry("pla", "Brand PLA Basic", "Brand", "PLA", "PLA")
        val saved = native.copy(id = "catalog-saved")
        compose.setContent {
            NozzleTheme("dark") {
                GroupedFilamentPicker(2, saved.displayName, listOf(native, saved), saved.id) {
                    selections.add(it)
                }
            }
        }

        compose.onNodeWithTag("setup-filament-combo-2").performClick()
        compose.onNode(hasText("Brand") and !hasTestTag("setup-filament-combo-2")).performClick()
        compose.onNodeWithText("PLA").performClick()
        compose.onNodeWithText("Current saved catalog preset: Brand PLA Basic").assertExists()
        compose.runOnIdle { assertEquals(emptyList<String>(), selections) }
        compose.onNodeWithText("Brand PLA Basic").performClick()
        compose.runOnIdle { assertEquals(listOf(native.id), selections) }
    }

}
