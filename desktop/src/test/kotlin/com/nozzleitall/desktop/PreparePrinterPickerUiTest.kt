package com.nozzleitall.desktop

import androidx.compose.ui.test.assertIsDisplayed
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
        app.fleet.order.value = listOf(u1.id, cc1.id)
        app.destination = Destination.PREPARE
        val profile = app.prepare.profileId
        try {
            compose.setContent { NozzleTheme("dark") { App(app) } }
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
            compose.onNodeWithTag("saved-printer-export").performClick()
            compose.runOnIdle { assertEquals(null, app.prepare.printerId) }
            openPicker()
            compose.onNodeWithText("Add or remove printers…").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(Destination.FLEET, app.destination) }
            compose.onNodeWithText("Everything you print on, at a glance.").assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }
}
