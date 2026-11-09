package com.nozzleitall.desktop

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.nozzleitall.desktop.prepare.ProfileCatalog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedPrintersAndManagementAreReachableFromPrepare() {
        val root = temporary.newFolder()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(paths, scope)
        val u1 = PrinterIdentity("u1", "Snapmaker U1", "Snapmaker U1", PrinterFamily.PAXX_U1,
            "http://127.0.0.1:9", "snapmaker_u1")
        val cc1 = PrinterIdentity("cc1", "CC1", "Elegoo CC1", PrinterFamily.KLIPPER, "http://127.0.0.1:9")
        app.fleet.printers[u1.id] = PrinterEntry(PrinterConfig(u1, "paxx-lan"))
        app.fleet.printers[cc1.id] = PrinterEntry(PrinterConfig(cc1, "moonraker"))
        val extras = (1..14).map { cc1.copy(id = "extra-$it", displayName = "Fixture printer $it") }
        extras.forEach { app.fleet.printers[it.id] = PrinterEntry(PrinterConfig(it, "moonraker")) }
        app.fleet.order.value = (listOf(u1, cc1) + extras).map { it.id }
        app.destination = Destination.PREPARE
        val profile = app.prepare.profileId
        try {
            compose.setContent { NozzleTheme("dark", textScale = 1.5f) { App(app) } }
            compose.onNodeWithTag("setup-connection").assertHasClickAction()
            fun openPicker() = compose.onNodeWithTag("setup-printer").performClick()
            for (printer in listOf(u1, cc1)) {
                openPicker()
                compose.onNodeWithTag("saved-printer-u1").assertIsDisplayed()
                compose.onNodeWithTag("saved-printer-cc1").assertIsDisplayed()
                compose.onNodeWithTag("saved-printer-${printer.id}").performClick()
                compose.runOnIdle {
                    assertEquals(printer, app.prepare.printer()?.config?.identity)
                    assertEquals(profile, app.prepare.profileId)
                }
            }
            openPicker()
            val search = compose.onNodeWithContentDescription("Search slicing profiles")
            search.performScrollTo().assertIsDisplayed().performTextInput("Centauri Carbon")
            val cc1Profile = ProfileCatalog.search("Centauri Carbon").first()
            compose.onNodeWithTag("slicing-profile-${cc1Profile.id}").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertEquals(cc1.id, app.prepare.printerId)
                assertEquals(cc1Profile.id, app.prepare.profileId)
            }
            openPicker()
            compose.onNodeWithTag("saved-printer-export").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(null, app.prepare.printerId)
                assertEquals(cc1Profile.id, app.prepare.profileId)
            }
            openPicker()
            compose.onNodeWithTag("saved-printer-cc1").performClick()
            openPicker()
            compose.onNodeWithText("Add or remove printers…").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(Destination.FLEET, app.destination) }
            compose.onNodeWithText("Everything you print on, at a glance.").assertIsDisplayed()
            compose.runOnIdle { app.destination = Destination.PREPARE }
            compose.onNodeWithText("Add or remove printers…").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(cc1.id, app.prepare.printerId)
                assertEquals(cc1Profile.id, app.prepare.profileId)
            }
        } finally {
            scope.cancel()
        }
    }
}
