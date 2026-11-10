package com.nozzleitall.desktop

import com.nozzleitall.desktop.migration.LegacyProcessPresetStore
import com.nozzleitall.desktop.migration.LegacySettingsMigration
import com.nozzleitall.desktop.prepare.PrinterLibrary
import com.nozzleitall.desktop.settings.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LegacyProcessPresetStoreTest {
    @Test fun `resolved custom machine and process become a selectable engine profile`() {
        val root = Files.createTempDirectory("nia-legacy-process").toFile()
        val store = LegacyProcessPresetStore(root.resolve("legacy-processes.json"))
        val machine = LegacySettingsMigration.Profile("machine", "user/default/machine/My U1.json", JSONObject()
            .put("name", "My U1").put("inherits", "Snapmaker U1 (0.4 nozzle)").put("retraction_length", JSONArray(listOf("0.8", "0.8", "0.8", "0.8"))), false)
        val process = LegacySettingsMigration.Profile("process", "user/default/process/My Fine.json", JSONObject()
            .put("name", "My Fine").put("inherits", "0.20mm Standard @Snapmaker U1 (0.4 nozzle)")
            .put("compatible_printers", JSONArray(listOf("My U1"))).put("layer_height", "0.16").put("wall_loops", "4"), false)

        val imported = store.import("Hybrid Orca", listOf(machine, process))
        PrinterLibrary.installCustomProcesses(store)
        try {
            assertEquals(1, imported.imported)
            assertTrue(imported.unresolved.isEmpty())
            val library = requireNotNull(PrinterLibrary.of("snapmaker_u1"))
            val variant = library.machines.single { it.name == "Snapmaker U1 (0.4 nozzle)" }
            val custom = library.processesFor(variant).single { it.name == "My Fine" }
            assertEquals("Hybrid Orca", custom.madeBy)
            val materialized = library.materialize(root.resolve("cache"), variant, custom.id)
            assertEquals("4", JSONObject(materialized.resolve("process.json").readText()).getString("wall_loops"))
            assertEquals("0.8", JSONObject(materialized.resolve("machine.json").readText()).getJSONArray("retraction_length").getString(0))
        } finally {
            PrinterLibrary.installCustomProcesses(null)
        }
    }

    @Test fun `unsafe machine geometry overrides stay unresolved`() {
        val root = Files.createTempDirectory("nia-legacy-process-unsafe").toFile()
        val store = LegacyProcessPresetStore(root.resolve("legacy-processes.json"))
        val machine = LegacySettingsMigration.Profile("machine", "user/default/machine/My U1.json", JSONObject()
            .put("name", "My U1").put("inherits", "Snapmaker U1 (0.4 nozzle)").put("bed_shape", JSONArray(listOf("0x0", "1x1"))), false)
        val process = LegacySettingsMigration.Profile("process", "user/default/process/My Fine.json", JSONObject()
            .put("name", "My Fine").put("inherits", "0.20mm Standard @Snapmaker U1 (0.4 nozzle)")
            .put("compatible_printers", JSONArray(listOf("My U1"))).put("layer_height", "0.16"), false)

        val result = store.import("Hybrid Orca", listOf(machine, process))
        assertEquals(0, result.imported)
        assertTrue(result.unresolved.single().contains("bed_shape"))
        assertFalse(root.resolve("legacy-processes.json").exists())
    }

    @Test fun `ambiguous or unknown inheritance stays unresolved`() {
        val root = Files.createTempDirectory("nia-legacy-process-unresolved").toFile()
        val store = LegacyProcessPresetStore(root.resolve("legacy-processes.json"))
        val process = LegacySettingsMigration.Profile("process", "user/default/process/Unknown.json", JSONObject()
            .put("name", "Unknown").put("inherits", "Unknown base")
            .put("compatible_printers", JSONArray(listOf("Unknown machine"))).put("layer_height", "0.2"), false)
        val result = store.import("OrcaSlicer", listOf(process))
        assertEquals(0, result.imported)
        assertEquals(1, result.unresolved.size)
        assertFalse(root.resolve("legacy-processes.json").exists())
    }

    @Test fun `a preserved unresolved process can link to a selected bundled base`() {
        val root = Files.createTempDirectory("nia-legacy-process-link").toFile()
        val paths = AppPaths(root.resolve("config"), root.resolve("data"), root.resolve("cache")).ensure()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(paths, scope)
        try {
            val source = File(root, "orca")
            val original = File(source, "user/default/process/Link me.json")
            original.parentFile.mkdirs()
            original.writeText(JSONObject().put("name", "Link me").put("inherits", "Unbundled process")
                .put("compatible_printers", JSONArray(listOf("Unbundled machine"))).put("layer_height", "0.16")
                .put("sparse_infill_density", "25%").put("enable_support", "1")
                .put("machine_start_gcode", "M117 Fixture").put("print_sequence", "by object").toString())
            val before = original.readBytes()
            File(original.parentFile, "Identical bytes.json").writeBytes(before)
            val reviewed = app.legacySettings.prepare(LegacySettingsMigration.Source(
                LegacySettingsMigration.Kind.ORCA_SLICER, "OrcaSlicer", source, emptyList(),
            ))
            val result = app.legacySettings.apply(reviewed)
            assertEquals(0, result.importedProcesses)
            val candidates = app.legacySettings.archivedProcesses()
            assertEquals(2, candidates.size)
            assertEquals(1, candidates.map { it.sha256 }.distinct().size)
            assertEquals(2, candidates.map { it.selectionId }.distinct().size)
            val pending = candidates.single { it.relativePath.endsWith("/Link me.json") }
            assertEquals("Link me", pending.name)
            val prepare = app.prepare
            val variant = requireNotNull(prepare.machineVariant())
            val baseId = requireNotNull(variant.defaultProcess)
            val linked = app.legacySettings.linkArchivedProcess(pending, prepare.profileId, variant.id, baseId)
            assertEquals(3, linked.applied)
            assertEquals(2, linked.skipped)
            assertArrayEquals(before, original.readBytes())
            val custom = prepare.processes().single { it.name == linked.name }
            prepare.selectSettingsPreset(Scope.PROCESS, custom.id)
            assertTrue(prepare.supports)
            assertEquals(25, prepare.infill)
            val materialized = requireNotNull(prepare.library()).materialize(root.resolve("engine"), variant, custom.id)
            val output = JSONObject(materialized.resolve("process.json").readText())
            assertEquals("0.16", output.getString("layer_height"))
            assertFalse(output.has("machine_start_gcode"))
            assertNotEquals("by object", output.optString("print_sequence"))
            assertTrue(runCatching {
                app.legacySettings.linkArchivedProcess(pending, prepare.profileId, variant.id, "other base")
            }.isFailure)
        } finally {
            scope.cancel()
            app.fleet.shutdown()
            PrinterLibrary.installCustomProcesses(null)
            root.deleteRecursively()
        }
    }

}
