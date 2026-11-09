package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** SliceEngine's engine-independent parts: G-code statistics and the native engine request (the Web App's format). */
class SliceEngineTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private fun gcode(vararg lines: String) = Files.createTempFile("stats", ".gcode").toFile().apply { writeText(lines.joinToString("\n")); deleteOnExit() }

    @Test fun toolChangesCountOnlySwitchesToADifferentTool() {
        assertEquals(2, SliceEngine.parseStats(gcode("T0", "G1 X1", "T0", "T1", "G1 X2", "T0")).toolChanges)
        // Single-material start G-code selecting T0 twice is not a tool change.
        assertEquals(0, SliceEngine.parseStats(gcode("T0", "T0")).toolChanges)
    }

    @Test fun multiToolRecipeMatchesTheSharedFixture() {
        val fixture = JSONObject(File(root, "schemas/fixtures/multitool.json").readText())
        val input = fixture.getJSONObject("input")
        val slotsJson = input.getJSONArray("slots")
        val slots = (0 until slotsJson.length()).map { i -> slotsJson.getJSONObject(i).let { ProjectManifest.MaterialSlot(i + 1, it.optString("type"), colorHex = it.optString("colorHex")) } }
        val temps = (0 until slotsJson.length()).associate { i -> i + 1 to slotsJson.getJSONObject(i).let { if (it.has("nozzleC")) it.getInt("nozzleC") else null } }
        val got = SliceEngine.multiToolOverrides(input.getDouble("diameter"), slots, { temps[it.slot] }, input.getInt("fallbackNozzleC"))
        val expected = fixture.getJSONObject("expected").let { e -> e.keySet().associateWith { e.getString(it) } }
        assertEquals(expected, got)
    }

    @Test fun nativeRequestPlacesObjectsRelativeToTheBedCentre() {
        val cache = Files.createTempDirectory("prof").toFile()
        val dir = ProfileCatalog.materialize(cache, "snapmaker_u1")
        val (bedW, bedD) = SliceEngine.bedOf(File(dir, "machine.json").readText())
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        val b = cube.bounds()
        val obj = ModelObject(1, "cube", cube, Transform.translate(bedW / 2 - (b[0] + b[3]) / 2, bedD / 2 - (b[1] + b[4]) / 2, 0.0))
        val job = Files.createTempDirectory("job").toFile()
        val text = SliceEngine.nativeRequest(SliceRequest(Project3mf(listOf(obj)), dir, QualityPreset.STANDARD, false, 15,
            listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF"))), job, File(job, "plate.gcode"))
        val lines = text.trim().lines().map { it.split('\t') }
        assertEquals(listOf("machine.json", "process.json", "filament.json"), lines.filter { it[0] == "profile" }.map { File(it[1]).name })
        assertEquals(mapOf("layer_height" to "0.2", "sparse_infill_density" to "15%", "enable_support" to "0"), lines.filter { it[0] == "set" }.associate { it[1] to it[2] })
        val o = lines.single { it[0] == "object" }
        assertEquals(0.0, o[2].toDouble(), 1e-6); assertEquals(0.0, o[3].toDouble(), 1e-6)
        assertEquals(0.0, o[4].toDouble(), 1e-9); assertEquals(1.0, o[5].toDouble(), 1e-9); assertEquals("0", o[6])
        assertEquals(cube.triangleCount, MeshIO.readStl(File(o[1]).readBytes()).triangleCount)
    }

    @Test fun nativeEngineIsFoundNextToTheAppFirstEvenWithoutItsExecutableBit() {
        // Packaged app resources lose the executable bit; the engine is still found (and made runnable).
        val resources = Files.createTempDirectory("res").toFile()
        val bundled = File(resources, SliceEngine.NATIVE_ENGINE_NAME).apply { writeText("#!/bin/sh\n"); setExecutable(false) }
        val env = mapOf("NOZZLE_HOME" to Files.createTempDirectory("home").toFile().absolutePath, "NOZZLE_ENGINE" to "/nonexistent")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bundled.readBytes())
            .joinToString("") { "%02x".format(it) }
        val found = SliceEngine.locateNative(env, { k -> if (k == "compose.application.resources.dir") resources.absolutePath else null }, digest)
        assertEquals(true, found != null && found.canExecute() && found.readText() == bundled.readText())
    }

    @Test fun packagedAppWithMissingEngineDoesNotUseADeveloperFallback() {
        val packageRoot = Files.createTempDirectory("missing-engine-package").toFile()
        val resources = File(packageRoot, "app/resources").apply { mkdirs() }
        val fallback = Files.createTempFile("other-engine", "").toFile().apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        File(packageRoot, "lib/engine/${SliceEngine.NATIVE_ENGINE_NAME}").apply {
            parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true)
        }
        val found = SliceEngine.locateNative(mapOf("NOZZLE_ENGINE" to fallback.absolutePath), { key ->
            if (key == "compose.application.resources.dir") resources.absolutePath else null
        }, "0".repeat(64))
        assertEquals(null, found)
    }

    @Test fun packagedAppRejectsAnEngineThatDoesNotMatchItsBundledPin() {
        val resources = Files.createTempDirectory("wrong-packaged-engine").toFile()
        File(resources, SliceEngine.NATIVE_ENGINE_NAME).apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        val found = SliceEngine.locateNative(emptyMap(), { key ->
            if (key == "compose.application.resources.dir") resources.absolutePath else null
        }, "0".repeat(64))
        assertEquals(null, found)
    }

    @Test fun identicalNativeInputsHaveTheSameDigestAcrossRunDirectories() {
        assumeTrue(System.getProperty("os.name").contains("Linux"))
        val root = Files.createTempDirectory("slice-digest").toFile()
        val fake = File(root, "fake-engine").apply {
            writeText("""#!/bin/sh
printf 'progress -5\n'
out=$(head -n 1 "${'$'}1" | cut -f 2)
printf 'G28\n' > "${'$'}out"
""")
            setExecutable(true)
        }
        val profile = ProfileCatalog.materialize(File(root, "profiles"), "snapmaker_u1")
        val mesh = Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2))
        val req = SliceRequest(Project3mf(listOf(ModelObject(1, "triangle", mesh))), profile,
            QualityPreset.STANDARD, false, 15, listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF")))
        val engine = SliceEngine(fake, File(root, "slices"))
        val first = engine.slice(req) { _, _ -> } as SliceOutcome.Done
        val second = engine.slice(req) { _, _ -> } as SliceOutcome.Done
        assertTrue(first.inputSha256 != null)
        assertEquals(first.inputSha256, second.inputSha256)
        assertEquals(first.engineSha256, second.engineSha256)
    }

    @Test fun failedNativeEngineReportsItsExitCodeAlongsideTheLastDiagnostic() {
        assumeTrue(System.getProperty("os.name").contains("Linux"))
        val scratch = Files.createTempDirectory("slice-failure").toFile()
        val fake = File(scratch, "fake-engine").apply {
            writeText("#!/bin/sh\necho 'Initializing StaticPrintConfigs' >&2\nexit 139\n")
            setExecutable(true)
        }
        val profile = ProfileCatalog.materialize(File(scratch, "profiles"), "snapmaker_u1")
        val mesh = Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2))
        val req = SliceRequest(Project3mf(listOf(ModelObject(1, "triangle", mesh))), profile,
            QualityPreset.STANDARD, false, 15, listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF")))
        val result = SliceEngine(fake, File(scratch, "slices")).slice(req) { _, _ -> }
        assertTrue(result is SliceOutcome.Failed)
        assertTrue((result as SliceOutcome.Failed).message.contains("code 139"))
        assertTrue(result.message.contains("Last engine output:"))
        assertTrue(result.message.contains("Initializing StaticPrintConfigs"))

        fake.writeText("#!/bin/sh\necho 'A single-nozzle filament map names an unavailable nozzle.' >&2\nexit 1\n")
        val invalidMap = SliceEngine(fake, File(scratch, "slices")).slice(req) { _, _ -> }
        assertTrue(invalidMap is SliceOutcome.Failed)
        assertTrue((invalidMap as SliceOutcome.Failed).message.contains("code 1"))
        assertTrue(invalidMap.message.contains("A single-nozzle filament map names an unavailable nozzle."))
    }
}
