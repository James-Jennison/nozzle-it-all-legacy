package com.nozzleitall.desktop

import com.nozzleitall.desktop.settings.ProfileValues
import com.nozzleitall.desktop.settings.Scope
import com.nozzleitall.desktop.settings.SettingsCatalog
import com.nozzleitall.desktop.settings.presetDifferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PresetActionsTest {
    private fun app(): Pair<AppState, CoroutineScope> {
        val root = Files.createTempDirectory("nia-preset-actions-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope) to scope
    }

    @Test fun `saving a Process preset makes edits its base while Reset still clears later edits`() {
        val (app, scope) = app()
        try {
            app.finishFirstRun(SlicingSetup("snapmaker_u1", "Snapmaker U1", "0.4", toolheads = 4))
            val p = app.prepare
            val profile = ProfileValues(mapOf("layer_height" to "0.20", "wall_loops" to "2"))
            p.overrides["layer_height"] = "0.18"

            val saved = p.saveUserPreset(Scope.PROCESS, "Detailed", profile, overwrite = false)
            assertEquals(saved.id, p.selectedPresetId(Scope.PROCESS))
            assertTrue(p.changedSettings(Scope.PROCESS).isEmpty())
            assertEquals("0.18", p.baseSetting("layer_height", profile))

            p.setSetting("layer_height", "0.16", profile)
            assertEquals(1, p.changedSettings(Scope.PROCESS).size)
            p.resetSettingsScope(Scope.PROCESS)
            assertEquals("0.18", p.setting("layer_height", profile))

            assertTrue(p.deleteSelectedUserPreset(Scope.PROCESS))
            assertNull(p.selectedUserPreset(Scope.PROCESS))
            assertEquals("0.20", p.baseSetting("layer_height", profile))
            assertFalse(p.deleteSelectedUserPreset(Scope.PROCESS))
        } finally { scope.cancel() }
    }

    @Test fun `Filament and Printer user presets use their own scopes and contexts`() {
        val (app, scope) = app()
        try {
            app.finishFirstRun(SlicingSetup("snapmaker_u1", "Snapmaker U1", "0.4", toolheads = 4))
            val p = app.prepare
            val profile = ProfileValues(mapOf("filament_flow_ratio" to "1", "retraction_length" to "0.8"))
            p.overrides["filament_flow_ratio"] = "0.97"
            val filament = p.saveUserPreset(Scope.FILAMENT, "Workshop PLA", profile, overwrite = false)
            p.overrides["retraction_length"] = "0.6"
            val printer = p.saveUserPreset(Scope.PRINTER, "Quiet U1", profile, overwrite = false)

            assertEquals(Scope.FILAMENT, filament.scope)
            assertEquals("0.97", filament.values["filament_flow_ratio"])
            assertEquals(Scope.PRINTER, printer.scope)
            assertEquals("0.6", printer.values["retraction_length"])
            assertEquals(filament.id, p.selectedPresetId(Scope.FILAMENT))
            assertEquals(printer.id, p.selectedPresetId(Scope.PRINTER))
        } finally { scope.cancel() }
    }

    @Test fun `Compare lists only settings whose effective values differ`() {
        val catalog = SettingsCatalog.bundled
        val differences = presetDifferences(catalog, Scope.PROCESS,
            mapOf("layer_height" to "0.20", "wall_loops" to "2", "seam_position" to "aligned"),
            mapOf("layer_height" to "0.16", "wall_loops" to "2", "seam_position" to "rear"))

        assertEquals(setOf("layer_height", "seam_position"), differences.map { it.key }.toSet())
        assertTrue(differences.none { it.key == "wall_loops" })
    }

    @Test fun `Compare never shows a bare numeric or percentage setting label`() {
        val catalog = SettingsCatalog.bundled
        for (scope in Scope.entries) {
            val settings = catalog.all.filter { it.scope == scope }
            val rows = presetDifferences(catalog, scope, settings.associate { it.key to "1" }, emptyMap())
            assertEquals(settings.size, rows.size)
            assertTrue(rows.all { row -> row.label.any(Char::isLetter) })
            if (scope == Scope.PROCESS) {
                val tabs = catalog.tabs.getValue(scope)
                assertEquals(listOf("Quality", "Strength", "Speed", "Supports", "Multi-material", "Others"),
                    rows.map { it.section }.distinct())
                val sectionByKey = tabs.flatMap { tab ->
                    tab.groups.flatMap { group -> group.settings.map { it.key to tab.title } }
                }.toMap()
                assertTrue(rows.all { it.section == (sectionByKey[it.key] ?: "Others") })
            }
        }
        val overhang = presetDifferences(catalog, Scope.PROCESS,
            mapOf("overhang_1_4_speed" to "35", "layer_height" to "0.20"),
            mapOf("overhang_1_4_speed" to "25%", "layer_height" to "0.16"))
        assertEquals(listOf("Quality", "Speed"), overhang.map { it.section })
        assertEquals("0.20 mm", overhang.first().left)
        assertEquals("Overhang speed (10%)", overhang.last().label)
        assertEquals("35 mm/s", overhang.last().left)
        assertEquals("25%", overhang.last().right)
    }

    @Test fun `Compare identifies each entry of a multi-value setting`() {
        val catalog = SettingsCatalog.bundled
        val points = presetDifferences(catalog, Scope.PRINTER,
            mapOf("extruder_offset" to "0x0,10x2"), mapOf("extruder_offset" to "0x0,12x2"))
        assertEquals("Value 1: X: 0 mm, Y: 0 mm\nValue 2: X: 10 mm, Y: 2 mm", points.single().left)

        val speeds = presetDifferences(catalog, Scope.PROCESS,
            mapOf("gap_infill_speed" to "210,150"), mapOf("gap_infill_speed" to "250"))
        assertEquals("Value 1: 210 mm/s\nValue 2: 150 mm/s", speeds.single().left)
        assertEquals("250 mm/s", speeds.single().right)

        val angles = presetDifferences(catalog, Scope.PROCESS,
            mapOf("sparse_infill_rotate_template" to "0,90,+5#5"), emptyMap())
        assertEquals("Value 1: 0°\nValue 2: 90°\nValue 3: +5#5", angles.single().left)
    }

    @Test fun `Compare offers the compatible system presets as well as NIA user presets`() {
        val (app, scope) = app()
        try {
            app.finishFirstRun(SlicingSetup("snapmaker_u1", "Snapmaker U1", "0.4", toolheads = 4))
            val p = app.prepare
            val profile = ProfileValues(mapOf("layer_height" to "0.20", "filament_flow_ratio" to "1"))
            val process = p.presetSnapshots(Scope.PROCESS, profile)
            val filament = p.presetSnapshots(Scope.FILAMENT, profile)
            val printer = p.presetSnapshots(Scope.PRINTER, profile)

            assertTrue(process.count { !it.user } > 1)
            assertTrue(filament.any { !it.user })
            assertTrue(printer.any { !it.user })

            val saved = p.saveUserPreset(Scope.PROCESS, "Comparison preset", profile, overwrite = false)
            assertTrue(p.presetSnapshots(Scope.PROCESS, profile).any { it.id == saved.id && it.user })
        } finally { scope.cancel() }
    }
}
