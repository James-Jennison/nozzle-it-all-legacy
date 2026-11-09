package com.nozzleitall.desktop.prepare

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.ui.NozzleTheme
import com.nozzleitall.project.Mesh
import com.nozzleitall.project.ProjectManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ObjectTreeAccessibilityUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `visibility disclosure and selectable part expose their state`() {
        val root = Files.createTempDirectory("object-tree-accessibility").toFile()
        val app = AppState(
            AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val item = PrepItem(
            1,
            "Bracket",
            Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)),
            50f,
            50f,
        ).also {
            it.parts += ProjectManifest.ObjectPart("modifier", "cylinder", listOf(0.0, 0.0, 0.0), listOf(1.0, 1.0, 1.0))
        }
        app.prepare.items += item
        app.prepare.selectPart(1, 0)

        compose.setContent { NozzleTheme("dark") { StepObjects(app, showInspector = false) } }
        compose.waitForIdle()

        val visibility = compose.onNodeWithTag("object-visibility-1")
        visibility.assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Hide Bracket from plate view")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Visible in 3D view"))
        visibility.performClick()
        compose.waitForIdle()
        assertFalse(app.prepare.objectVisible(1))
        assertEquals(1, app.prepare.selected)
        assertEquals(-1, app.prepare.selectedPart)
        visibility.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Show Bracket on plate")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Hidden from 3D view"))
        visibility.performClick()
        assertTrue(app.prepare.objectVisible(1))

        val disclosure = compose.onNodeWithTag("object-parts-toggle-1")
        disclosure.assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        disclosure.performClick()
        compose.waitForIdle()
        disclosure.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))

        val part = compose.onNodeWithTag("object-part-1-0")
        part.assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Modifier · Cylinder part of Bracket")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))
        part.performClick()
        compose.waitForIdle()
        assertEquals(0, app.prepare.selectedPart)
        part.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
    }
}
