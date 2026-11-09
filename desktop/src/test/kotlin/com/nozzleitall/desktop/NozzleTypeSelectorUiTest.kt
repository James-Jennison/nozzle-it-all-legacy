package com.nozzleitall.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import com.nozzleitall.desktop.settings.Choice
import com.nozzleitall.desktop.settings.DenseSelect
import com.nozzleitall.desktop.ui.NozzleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class NozzleTypeSelectorUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `all four Standard-only nozzle selectors open and commit their actual option`() {
        val selected = mutableListOf("", "", "", "")
        compose.setContent {
            NozzleTheme("dark") {
                Column {
                    (1..4).forEach { tool ->
                        DenseSelect(
                            label = "T$tool nozzle type",
                            choices = listOf(Choice("standard", "Standard")),
                            value = "standard",
                            modifier = Modifier,
                            onSelect = { selected[tool - 1] = it },
                        )
                    }
                }
            }
        }

        (1..4).forEach { tool ->
            compose.onNodeWithContentDescription("T$tool nozzle type, Standard")
                .assertIsEnabled()
                .assertContentDescriptionEquals("T$tool nozzle type, Standard")
                .performClick()
            compose.onNodeWithContentDescription("T$tool nozzle type option Standard").performClick()
        }
        compose.runOnIdle { assertEquals(listOf("standard", "standard", "standard", "standard"), selected) }
    }

    @Test
    fun `nozzle selector is keyboard operable and preserves Standard until High Flow is chosen`() {
        val selected = mutableStateListOf("standard", "standard", "standard", "standard")
        compose.setContent {
            NozzleTheme("dark") {
                Column {
                    (1..4).forEach { tool ->
                        DenseSelect(
                            label = "T$tool nozzle type",
                            choices = listOf(Choice("standard", "Standard"), Choice("high_flow", "High Flow")),
                            value = selected[tool - 1],
                            modifier = Modifier,
                            onSelect = { selected[tool - 1] = it },
                        )
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("T1 nozzle type, Standard")
            .assertContentDescriptionEquals("T1 nozzle type, Standard")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription("T1 nozzle type option High Flow").performClick()
        compose.runOnIdle { assertEquals(listOf("high_flow", "standard", "standard", "standard"), selected) }
        compose.onNodeWithContentDescription("T1 nozzle type, High Flow")
            .assertContentDescriptionEquals("T1 nozzle type, High Flow")
    }
}
