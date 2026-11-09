package com.nozzleitall.desktop.prepare

import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.nozzleitall.desktop.ui.NozzleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SidebarAccessibilityUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `collapse and expand controls are named buttons with state and keyboard focus`() {
        val actions = mutableListOf<String>()
        compose.setContent {
            NozzleTheme("dark") {
                val focus = remember { FocusRequester() }
                SidebarCollapseControl(focus) { actions += "collapse" }
            }
        }

        compose.onNodeWithContentDescription("Collapse settings sidebar")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Settings sidebar expanded"))
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performClick()

        compose.setContent {
            NozzleTheme("dark") {
                val focus = remember { FocusRequester() }
                SidebarExpandControl(focus) { actions += "expand" }
            }
        }
        compose.onNodeWithContentDescription("Expand settings sidebar")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Settings sidebar collapsed"))
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performClick()

        compose.runOnIdle { assertEquals(listOf("collapse", "expand"), actions) }
    }
}
