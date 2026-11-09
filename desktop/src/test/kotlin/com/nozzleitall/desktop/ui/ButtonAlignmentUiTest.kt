package com.nozzleitall.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ButtonAlignmentUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `centered button content remains centered across weighted segments`() {
        compose.setContent {
            NozzleTheme("dark") {
                Row(Modifier.fillMaxWidth()) {
                    NzButton("Global", {}, Modifier.weight(1f), testTag = "global", centerContent = true)
                    NzButton("Objects", {}, Modifier.weight(1f), testTag = "objects", centerContent = true)
                }
            }
        }

        listOf("global" to "Global", "objects" to "Objects").forEach { (tag, text) ->
            val button = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val label = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            assertEquals(button.center.x, label.center.x, 1f)
        }
    }
}
