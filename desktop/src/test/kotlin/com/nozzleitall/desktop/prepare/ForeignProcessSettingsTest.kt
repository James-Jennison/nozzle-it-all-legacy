package com.nozzleitall.desktop.prepare

import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.desktop.AppState
import com.nozzleitall.project.Project3mf
import com.nozzleitall.project.ThreeMf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ForeignProcessSettingsTest {
    private val settingsEntry = "Metadata/project_settings.config"

    @Test fun appliesSupportedProcessValuesAndRetainsSkippedSettingsOnSave() {
        val root = Files.createTempDirectory("nia-foreign-process").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
        try {
            val settings = """{
                "enable_support":"1", "support_type":"normal(manual)", "support_style":"default",
                "layer_height":"0.2", "sparse_infill_density":"25%", "wall_loops":[1,2],
                "machine_start_gcode":"M117 Fixture", "printer_settings_id":"Different printer"
            }""".toByteArray()
            val input = File(root, "import.3mf")
            input.writeBytes(ThreeMf.write(Project3mf(emptyList(), passthrough = mapOf(settingsEntry to settings))))
            val prepare = app.prepare
            val beforePrinter = prepare.profileId
            prepare.open(input)
            assertEquals(beforePrinter, prepare.profileId)
            assertTrue(prepare.supports)
            assertEquals(25, prepare.infill)
            assertTrue(prepare.notice.orEmpty().contains("applied Layer height"))
            assertTrue(prepare.notice.orEmpty().contains("Skipped unsupported values for Wall count"))
            assertTrue(prepare.notice.orEmpty().contains("Preserved 2 other entries"))
            val saved = prepare.toProject()
            val overrides = requireNotNull(saved.manifest).settings.overrides
            assertEquals("normal(manual)", overrides["support_type"])
            assertEquals("default", overrides["support_style"])
            assertEquals("0.2", overrides["layer_height"])
            assertFalse(overrides.containsKey("machine_start_gcode"))
            assertArrayEquals(settings, saved.passthrough[settingsEntry])
            val roundTrip = File(root, "nia-saved.3mf")
            roundTrip.writeBytes(ThreeMf.write(saved))
            prepare.open(roundTrip)
            assertTrue(prepare.supports)
            assertEquals(25, prepare.infill)
            assertArrayEquals(settings, prepare.toProject().passthrough[settingsEntry])
            assertFalse(prepare.notice.orEmpty().contains("Embedded slicer settings"))
            prepare.newProject()
            assertFalse(prepare.supports)
            assertEquals(15, prepare.infill)
        } finally {
            scope.cancel()
            app.fleet.shutdown()
            root.deleteRecursively()
        }
    }

    @Test fun malformedAndOutOfRangeSettingsAreReportedWithoutApplyingThem() {
        val malformed = Project3mf(emptyList(), passthrough = mapOf(settingsEntry to "{".toByteArray()))
        assertTrue(requireNotNull(ForeignProcessSettings.read(malformed, 0.4)).notice.contains("could not be read"))
        val oversizedLayer = malformed.copy(passthrough = mapOf(settingsEntry to """{"layer_height":"0.8"}""".toByteArray()))
        val report = requireNotNull(ForeignProcessSettings.read(oversizedLayer, 0.4))
        assertTrue(report.values.isEmpty())
        assertTrue(report.notice.contains("Skipped unsupported values for Layer height"))
    }
}
