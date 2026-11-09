package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.PresetSnapshot
import com.nozzleitall.desktop.prepare.FilamentLibrary
import com.nozzleitall.desktop.prepare.PrepItem
import com.nozzleitall.desktop.prepare.ProfileCatalog
import com.nozzleitall.desktop.prepare.writePresetComparison
import com.nozzleitall.desktop.settings.ProfileValues
import com.nozzleitall.desktop.settings.SettingsCatalog
import com.nozzleitall.project.Mesh
import com.nozzleitall.project.ProjectManifest
import com.nozzleitall.project.ThreeMf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PresetSnapshotTest {
    private fun profile(root: File, process: String): File = File(root, "snapmaker_u1").apply {
        mkdirs()
        File(this, "machine.json").writeText("""{"bed_shape":"0x0,270x270"}""")
        File(this, "process.json").writeText("""{"layer_height":"$process","wall_loops":"2"}""")
        File(this, "filament.json").writeText("""{"filament_type":["PLA"]}""")
    }

    @Test fun selectedLibraryProcessOwnsTheValuesShownInSettings() {
        val root = Files.createTempDirectory("library-process-values").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val prepare = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default)).prepare
        val selected = prepare.processes().first { it.label == "0.08mm Standard" }

        prepare.processId = selected.id
        val values = ProfileValues.read(prepare.profileDir(), SettingsCatalog.bundled)

        assertEquals("0.08mm Standard", prepare.currentProcess()?.displayLabel)
        assertEquals("0.08", prepare.baseSetting("layer_height", values))
        assertEquals("0.2", prepare.baseSetting("initial_layer_print_height", values))
    }

    @Test fun legacyGuidedProcessAliasFallsBackToTheLibraryDefaultWithoutDisplayDrift() {
        val root = Files.createTempDirectory("legacy-process-alias").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val prepare = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default)).prepare
        val source = prepare.toProject()
        val manifest = source.manifest!!
        val saved = File(root, "legacy.3mf").apply {
            writeBytes(ThreeMf.write(source.copy(manifest = manifest.copy(
                settings = manifest.settings.copy(preset = "process:standard"),
            ))))
        }

        prepare.open(saved)
        val values = ProfileValues.read(prepare.profileDir(), SettingsCatalog.bundled)

        assertEquals("0.20mm Standard", prepare.currentProcess()?.displayLabel)
        assertEquals("0.2", prepare.baseSetting("layer_height", values))
        assertEquals("0.25", prepare.baseSetting("initial_layer_print_height", values))
    }

    @Test fun bundledUpdateKeepsSavedFilesUntilExplicitReplacement() {
        val root = Files.createTempDirectory("preset-review").toFile()
        val bundled = profile(root, "0.20")
        val saved = PresetSnapshot.capture("snapmaker_u1", bundled, emptyList())
        val projectExtension = PresetSnapshot.withSnapshot(JSONObject().put("android", JSONObject().put("keep", true)), saved)
        assertTrue(projectExtension.getJSONObject("android").getBoolean("keep"))
        val reopened = PresetSnapshot.fromExtensions(projectExtension)!!
        profile(root, "0.24")
        val updated = PresetSnapshot.capture("snapmaker_u1", bundled, emptyList())
        assertEquals(listOf("process · layer_height: 0.20 → 0.24"), reopened.changes(updated))
        val pinned = reopened.materialize(root)
        assertEquals("0.20", JSONObject(File(pinned, "process.json").readText()).getString("layer_height"))
        assertEquals("0.24", JSONObject(File(bundled, "process.json").readText()).getString("layer_height"))
        assertTrue(reopened.changes(updated).isNotEmpty())
        assertTrue(updated.changes(updated).isEmpty())
    }

    @Test fun damagedSavedPresetCannotBeSilentlyReplacedByBundledFiles() {
        val wrongDesktopType = JSONObject().put("desktop", "unexpected value")
        assertThrows(org.json.JSONException::class.java) { PresetSnapshot.fromExtensions(wrongDesktopType) }
        assertThrows(org.json.JSONException::class.java) {
            PresetSnapshot.withSnapshot(wrongDesktopType, PresetSnapshot.capture(
                "snapmaker_u1", profile(Files.createTempDirectory("preset-desktop-type").toFile(), "0.20"), emptyList()))
        }
        val malformed = JSONObject().put("desktop", JSONObject().put("presetSnapshot", JSONObject()
            .put("profileId", "snapmaker_u1").put("directoryName", "../elsewhere")
            .put("files", JSONObject()).put("filamentProfiles", JSONObject())))
        assertThrows(IllegalArgumentException::class.java) { PresetSnapshot.fromExtensions(malformed) }
        malformed.getJSONObject("desktop").getJSONObject("presetSnapshot").put("directoryName", "..")
        assertThrows(IllegalArgumentException::class.java) { PresetSnapshot.fromExtensions(malformed) }
        malformed.getJSONObject("desktop").put("presetSnapshot", "not an object")
        assertThrows(org.json.JSONException::class.java) { PresetSnapshot.fromExtensions(malformed) }
    }

    @Test fun comparisonIgnoresNumericFormattingButPreservesOrderedArrays() {
        val root = Files.createTempDirectory("preset-semantic-diff").toFile()
        val bundled = profile(root, "0.20")
        val original = PresetSnapshot.capture("snapmaker_u1", bundled, emptyList())
        File(bundled, "process.json").writeText("""{"layer_height":0.20,"wall_loops":2,"pattern":["a","b"]}""")
        val numeric = PresetSnapshot.capture("snapmaker_u1", bundled, emptyList())
        val semanticallySame = numeric.copy(files = numeric.files + ("process.json" to
            """{"layer_height":0.2,"wall_loops":2.0,"pattern":["a","b"]}"""))
        assertTrue(numeric.changes(semanticallySame).isEmpty())
        val reordered = numeric.copy(files = numeric.files + ("process.json" to
            """{"layer_height":0.20,"wall_loops":2,"pattern":["b","a"]}"""))
        assertTrue(numeric.changes(reordered).any { it.contains("pattern") })
        assertTrue(original.changes(numeric).any { it.contains("layer_height") })
    }

    @Test fun deeplyNestedSavedProfileIsRejectedBeforeDiffOrMaterialization() {
        val root = Files.createTempDirectory("preset-nesting").toFile()
        val original = PresetSnapshot.capture("snapmaker_u1", profile(root, "0.20"), emptyList())
        val nested = "{\"x\":".repeat(70) + "0" + "}".repeat(70)
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(files = original.files + ("process.json" to nested))
        }
        val tooDeepForParser = "{\"x\":".repeat(10_000) + "0" + "}".repeat(10_000)
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(files = original.files + ("process.json" to tooDeepForParser))
        }
    }

    @Test fun selectedFilamentPresetChangesAppearInTheReview() {
        val root = Files.createTempDirectory("preset-filament-diff").toFile()
        val base = PresetSnapshot.capture("snapmaker_u1", profile(root, "0.20"), emptyList())
        val before = base.copy(filamentProfiles = mapOf("selected_pla" to """{"nozzle_temperature":[200]}"""))
        val after = base.copy(filamentProfiles = mapOf("selected_pla" to """{"nozzle_temperature":[220]}"""))
        assertTrue(before.changes(after).any { it.contains("filament selected_pla · nozzle_temperature") })
    }

    @Test fun exportedFullComparisonPreservesAnExistingFile() {
        val root = Files.createTempDirectory("preset-diff-export").toFile()
        val target = File(root, "comparison.txt")
        writePresetComparison(listOf("machine · speed: 100 → 120"), target)
        val original = target.readBytes()
        assertThrows(IllegalStateException::class.java) {
            writePresetComparison(listOf("different"), target)
        }
        assertArrayEquals(original, target.readBytes())
    }

    @Test fun concurrentMaterializationReturnsCompleteIdenticalFiles() {
        val root = Files.createTempDirectory("preset-concurrent-cache").toFile()
        val snapshot = PresetSnapshot.capture("snapmaker_u1", profile(root, "0.20"), emptyList())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..16).map { pool.submit<File> { snapshot.materialize(root) } }.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.toSet().size)
            snapshot.files.forEach { (name, value) -> assertEquals(value, File(results.first(), name).readText()) }
        } finally { pool.shutdownNow() }
    }

    @Test fun savedFilamentProfileIsUsedWhenTheBundledProfileIsAbsent() {
        val root = Files.createTempDirectory("saved-filament").toFile()
        val bundled = profile(root, "0.20")
        val output = File(root, "job").apply { mkdirs() }
        val selected = listOf(ProjectManifest.MaterialSlot(1, "PLA", filamentProfile = "legacy_saved"))
        val combined = FilamentLibrary.writeCombined("snapmaker_u1", bundled, selected, output,
            mapOf("legacy_saved" to """{"filament_type":["PLA"],"nozzle_temperature":["218"]}"""))!!
        assertEquals("218", JSONObject(combined.readText()).getJSONArray("nozzle_temperature").getString(0))
        val error = assertThrows(IllegalArgumentException::class.java) {
            FilamentLibrary.writeCombined("snapmaker_u1", bundled, selected, output, mapOf("legacy_saved" to "not JSON"))
        }
        assertTrue(error.message!!.contains("saved filament preset"))
    }

    @Test fun reopenedProjectKeepsOldPresetUntilReviewedApply() {
        val root = Files.createTempDirectory("project-preset-reopen").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val project = prepare.toProject()
        val manifest = project.manifest!!
        val current = PresetSnapshot.fromExtensions(manifest.extensions)!!
        val olderProcess = JSONObject(current.files.getValue("process.json")).put("layer_height", "0.42").toString()
        val older = current.copy(files = current.files + ("process.json" to olderProcess))
        val oldManifest = manifest.copy(extensions = PresetSnapshot.withSnapshot(manifest.extensions, older))
        val saved = File(root, "older.3mf").apply { writeBytes(ThreeMf.write(project.copy(manifest = oldManifest))) }
        val savedBytes = saved.readBytes()

        prepare.open(saved)
        assertFalse(prepare.dirty)
        assertTrue(prepare.presetUpdate().any { it.contains("layer_height") })
        assertEquals("0.42", JSONObject(File(prepare.profileDir(), "process.json").readText()).getString("layer_height"))
        assertThrows(IllegalStateException::class.java) { prepare.applyPresetUpdate() }
        val reviewed = prepare.presetUpdateReview()!!
        prepare.applyPresetUpdate(reviewed)
        assertTrue(prepare.dirty)
        assertTrue(prepare.presetUpdate().isEmpty())
        assertArrayEquals(savedBytes, saved.readBytes())
        assertEquals(current.files.getValue("process.json"), PresetSnapshot.fromExtensions(prepare.toProject().manifest!!.extensions)!!.files.getValue("process.json"))
    }

    @Test fun stalePresetReviewCannotBeAppliedAfterSelectionChanges() {
        val root = Files.createTempDirectory("preset-stale-review").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val prepare = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default)).prepare
        val initial = prepare.toProject().manifest!!
        val snapshot = PresetSnapshot.fromExtensions(initial.extensions)!!
        val older = snapshot.copy(files = snapshot.files + ("process.json" to
            JSONObject(snapshot.files.getValue("process.json")).put("layer_height", "0.42").toString()))
        val original = prepare.toProject().copy(manifest = initial.copy(extensions = PresetSnapshot.withSnapshot(initial.extensions, older)))
        val saved = File(root, "older.3mf").apply { writeBytes(ThreeMf.write(original)) }
        prepare.open(saved)
        val reviewed = prepare.presetUpdateReview()!!
        prepare.processId = "nonexistent_process"
        assertThrows(IllegalStateException::class.java) { prepare.applyPresetUpdate(reviewed) }
        prepare.processId = reviewed.processId
        prepare.overrides["layer_height"] = "0.30"
        assertThrows(IllegalStateException::class.java) { prepare.applyPresetUpdate(reviewed) }
    }

    @Test fun selectingAnotherPrinterOffersAnExplicitPresetRebind() {
        val root = Files.createTempDirectory("preset-printer-rebind").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val prepare = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default)).prepare
        val source = prepare.toProject()
        val originalBytes = ThreeMf.write(source)
        val saved = File(root, "source.3mf").apply { writeBytes(originalBytes) }
        prepare.open(saved)
        val original = prepare.presetSnapshot!!
        val other = ProfileCatalog.all.first { it.id != original.profileId }
        prepare.profileId = other.id
        prepare.changed()
        assertNull(prepare.presetUpdateReview())
        assertThrows(IllegalStateException::class.java) { prepare.toProject() }
        prepare.applyPresetUpdate()
        assertEquals(other.id, prepare.presetSnapshot!!.profileId)
        assertArrayEquals(originalBytes, saved.readBytes())
    }

    @Test fun nonDefaultPrinterSnapshotReopensWithMatchingProfile() {
        val root = Files.createTempDirectory("preset-other-printer").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val prepare = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default)).prepare
        val other = ProfileCatalog.all.first { it.id != ProfileCatalog.DEFAULT_ID }
        prepare.profileId = other.id
        val saved = File(root, "other-printer.3mf").apply { writeBytes(ThreeMf.write(prepare.toProject())) }
        prepare.newProject()
        prepare.open(saved)
        assertEquals(other.id, prepare.profileId)
        assertNull(prepare.presetSnapshotProblem)
        assertEquals(prepare.presetSnapshot!!.directoryName, prepare.profileDir().name)
    }
}
