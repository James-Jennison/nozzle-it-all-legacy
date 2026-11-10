package com.nozzleitall.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.nozzleitall.desktop.prepare.ProfileCatalog
import com.nozzleitall.desktop.prepare.PrepItem
import com.nozzleitall.project.MeshIO
import com.nozzleitall.desktop.projects.ProjectsScreen
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import com.nozzleitall.desktop.ui.NozzleTheme
import com.nozzleitall.printer.PrinterConfig
import com.nozzleitall.printer.PrinterFamily
import com.nozzleitall.printer.PrinterIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreparePrinterPickerUiTest {
    @get:Rule val temporary = TemporaryFolder()

    @OptIn(ExperimentalTestApi::class)
    @Test fun projectSearchChangesTheSelectedDetailsAndPreservesTheOpenGuard() = runDesktopComposeUiTest(1360, 860) {
        val root = temporary.newFolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val mesh = MeshIO.readStl(File(repo, "site-src/assets/test-cube-20mm.stl").readBytes())
        try {
            for (name in listOf("First fixture", "Second fixture")) {
                app.prepare.newProject()
                app.prepare.name = name
                app.prepare.items += PrepItem(1, "Cube", mesh, 90f, 90f)
                app.prepare.changed()
                app.prepare.save()
            }
            app.prepare.name = "Unsaved editor"
            app.prepare.changed()
            setContent { NozzleTheme("dark", textScale = 1.5f) { ProjectsScreen(app) } }
            waitUntil(10_000) {
                onAllNodesWithText("First fixture").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithContentDescription("Search projects").performTextInput("First fixture")
            onAllNodesWithText("Second fixture").assertCountEquals(0)
            onNodeWithText("Open").performClick()
            runOnIdle {
                check(app.pendingProjectAction != null)
                assertEquals("Unsaved editor", app.prepare.name)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun savedPrintersAndManagementAt100Percent() = verifySidebar(1f)
    @Test fun savedPrintersAndManagementAt150Percent() = verifySidebar(1.5f)

    @Test fun captureSidebarAt100Percent() = captureSidebar(1f)
    @Test fun captureSidebarAt150Percent() = captureSidebar(1.5f)

    private fun captureSidebar(scale: Float) {
        val folder = System.getenv("NIA_SIDEBAR_CAPTURE_DIR")
        org.junit.Assume.assumeNotNull(folder)
        verifySidebar(scale, 1360, 860, folder)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun verifySidebar(scale: Float, width: Int = 960, height: Int = 640, captureFolder: String? = null) =
        runDesktopComposeUiTest(width, height) {
        val root = temporary.newFolder()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(paths, scope)
        val u1 = PrinterIdentity("u1", "Snapmaker U1", "Snapmaker U1", PrinterFamily.PAXX_U1,
            "http://127.0.0.1:9", "snapmaker_u1")
        val cc1Profile = requireNotNull(ProfileCatalog.byId("elegoo_centauri_carbon_cosmos_afc"))
        val cc1 = PrinterIdentity("cc1", "CC1", "Elegoo CC1", PrinterFamily.KLIPPER, "http://127.0.0.1:9", cc1Profile.id)
        app.fleet.printers[u1.id] = PrinterEntry(PrinterConfig(u1, "paxx-lan"))
        app.fleet.printers[cc1.id] = PrinterEntry(PrinterConfig(cc1, "moonraker"))
        val extras = (1..14).map { cc1.copy(id = "extra-$it", displayName = "Fixture printer $it") }
        extras.forEach { app.fleet.printers[it.id] = PrinterEntry(PrinterConfig(it, "moonraker")) }
        app.fleet.order.value = (listOf(u1, cc1) + extras).map { it.id }
        app.destination = Destination.PREPARE
        val profile = app.prepare.profileId
        try {
            setContent { NozzleTheme("dark", textScale = scale) { App(app) } }
            listOf("prepare", "preview", "send", "monitor").forEach { stage ->
                onNodeWithTag("workflow-$stage").assertIsDisplayed()
            }
            onNodeWithTag("add-plate").assertIsDisplayed().assertHasClickAction()
            if (width == 960 && scale == 1.5f) {
                listOf("Prepare", "Preview", "Send", "Monitor", "Add plate").forEach { label ->
                    onNodeWithContentDescription(label).assertIsDisplayed()
                }
            }
            onNodeWithTag("setup-connection").assertHasClickAction()
            onNodeWithTag("setup-printer").assertTextContains("Export only")
            onNodeWithContentDescription("Nozzle diameter, 0.4").assertIsDisplayed()
            onNodeWithContentDescription("Bed type, Textured PEI Plate").assertIsDisplayed()
            fun openPicker() = onNodeWithTag("setup-printer").performClick()
            for (printer in listOf(u1, cc1)) {
                openPicker()
                onNodeWithTag("saved-printer-u1").assertIsDisplayed()
                onNodeWithTag("saved-printer-cc1").assertIsDisplayed()
                onNodeWithTag("saved-printer-${printer.id}").performClick()
                if (printer == cc1) {
                    onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
                    runOnIdle {
                        assertEquals(u1.id, app.prepare.printerId)
                        assertEquals(profile, app.prepare.profileId)
                    }
                    openPicker()
                    onNodeWithTag("saved-printer-cc1").performClick()
                    val original = requireNotNull(app.fleet.printers[cc1.id])
                    runOnIdle {
                        app.fleet.printers[cc1.id] = PrinterEntry(PrinterConfig(cc1.copy(profileId = profile), "moonraker"))
                    }
                    onNodeWithTag("confirm-action").performSemanticsAction(SemanticsActions.OnClick)
                    onNodeWithText("The saved printer or its machine profile changed. Open the printer picker and review it again.").assertIsDisplayed()
                    runOnIdle {
                        assertEquals(u1.id, app.prepare.printerId)
                        app.fleet.printers[cc1.id] = original
                        app.prepare.notice = null
                    }
                    openPicker()
                    onNodeWithTag("saved-printer-cc1").performClick()
                    onNodeWithTag("confirm-action").performSemanticsAction(SemanticsActions.OnClick)
                }
                onNodeWithTag("setup-printer").assertTextContains(printer.displayName)
                captureFolder?.let { folder ->
                    val bitmap = captureToImage()
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.readPixels(pixels)
                    val capture = java.awt.image.BufferedImage(bitmap.width, bitmap.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    capture.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
                    javax.imageio.ImageIO.write(capture, "png",
                        File(folder, "${printer.id}-${(scale * 100).toInt()}.png"))
                }
                runOnIdle {
                    assertEquals(printer, app.prepare.printer()?.config?.identity)
                    assertEquals(printer.profileId, app.prepare.profileId)
                    if (printer == u1) app.prepare.headProfiles[1] = "fixture-u1-slot"
                    else assertEquals(null, app.prepare.headProfiles[1])
                }
            }
            openPicker()
            onNodeWithTag("saved-printer-cc1").assertIsSelected()
            onNodeWithTag("saved-printer-u1").assertIsNotSelected()
            onNodeWithTag("saved-printer-extra-14").performScrollTo().assertIsDisplayed()
            val search = onNodeWithContentDescription("Search machine profiles")
            search.performScrollTo().assertIsDisplayed().performTextInput("Centauri Carbon")
            onNodeWithTag("slicing-profile-${cc1Profile.id}").performScrollTo().assertIsDisplayed().performClick()
            runOnIdle {
                assertEquals(cc1.id, app.prepare.printerId)
                assertEquals(cc1Profile.id, app.prepare.profileId)
            }
            onNodeWithTag("setup-printer").assertTextContains("CC1")
            openPicker()
            onNodeWithTag("saved-printer-export").performScrollTo().performClick()
            onNodeWithTag("setup-printer").assertTextContains("Export only")
            runOnIdle {
                assertEquals(null, app.prepare.printerId)
                assertEquals(cc1Profile.id, app.prepare.profileId)
            }
            openPicker()
            onNodeWithTag("saved-printer-export").performScrollTo().assertIsSelected()
            onNodeWithTag("slicing-profile-${cc1Profile.id}").performScrollTo().assertIsSelected()
            onNodeWithTag("saved-printer-cc1").performScrollTo().performClick()
            openPicker()
            onNodeWithText("Add or remove printers…").assertIsDisplayed().performClick()
            runOnIdle { assertEquals(Destination.FLEET, app.destination) }
            onNodeWithText("Everything you print on, at a glance.").assertIsDisplayed()
            runOnIdle { app.destination = Destination.PREPARE }
            onNodeWithText("Add or remove printers…").assertDoesNotExist()
            openPicker()
            onNodeWithTag("saved-printer-u1").performClick()
            onNodeWithTag("confirm-action").performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithTag("setup-printer").assertTextContains(u1.displayName)
            runOnIdle {
                assertEquals(u1.id, app.prepare.printerId)
                assertEquals(u1.profileId, app.prepare.profileId)
                assertEquals("fixture-u1-slot", app.prepare.headProfiles[1])
                app.prepare.newProject()
                assertEquals(null, app.prepare.printerId)
                assertEquals(emptyMap<Int, String>(), app.prepare.headProfiles)
            }
        } finally {
            scope.cancel()
        }
    }
}
