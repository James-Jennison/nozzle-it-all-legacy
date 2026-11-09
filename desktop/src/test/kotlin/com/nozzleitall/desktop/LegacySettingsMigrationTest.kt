package com.nozzleitall.desktop

import com.nozzleitall.desktop.materials.FilamentPresetStore
import com.nozzleitall.desktop.migration.LegacySettingsMigration
import com.nozzleitall.desktop.projects.ProjectLibrary
import com.nozzleitall.project.Mesh
import com.nozzleitall.project.ModelObject
import com.nozzleitall.project.Project3mf
import com.nozzleitall.project.ThreeMf
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LegacySettingsMigrationTest {
    private fun fixture(): Triple<AppPaths, FilamentPresetStore, LegacySettingsMigration> {
        val root = Files.createTempDirectory("nia-legacy-migration").toFile()
        val paths = AppPaths(root.resolve("config"), root.resolve("data"), root.resolve("cache")).ensure()
        val store = FilamentPresetStore(paths.filamentPresets)
        return Triple(paths, store, LegacySettingsMigration(paths, store))
    }

    @Test fun `discovery uses NIA Hybrid Snapmaker Orca order and excludes active target`() {
        val (paths, _, migration) = fixture()
        val home = paths.data.parentFile.resolve("home").apply { mkdirs() }
        val xdg = home.resolve("xdg").apply { mkdirs() }
        fun preset(root: java.io.File, name: String) = root.resolve("user/default/filament/$name.json").apply {
            parentFile.mkdirs(); writeText("""{"name":"$name","filament_type":["PLA"]}""")
        }
        preset(xdg.resolve("nozzle-it-all"), "NIA")
        preset(home.resolve(".var/app/com.orcaslicer.OrcaSlicer/config/OrcaSlicer"), "Hybrid")
        preset(home.resolve(".var/app/io.github.Snapmaker.Snapmaker_Orca/config/Snapmaker_Orca"), "Snap Flatpak")
        preset(xdg.resolve("Snapmaker_Orca"), "Snap")
        preset(xdg.resolve("OrcaSlicer"), "Orca")

        val found = migration.discover(mapOf("XDG_CONFIG_HOME" to xdg.absolutePath), home.absolutePath, "Linux")
        assertEquals(listOf(
            LegacySettingsMigration.Kind.NIA_DESKTOP, LegacySettingsMigration.Kind.HYBRID_ORCA,
            LegacySettingsMigration.Kind.SNAPMAKER_ORCA, LegacySettingsMigration.Kind.SNAPMAKER_ORCA,
            LegacySettingsMigration.Kind.ORCA_SLICER), found.map { it.kind })

        val active = LegacySettingsMigration(AppPaths(xdg.resolve("nozzle-it-all"), paths.data, paths.cache), FilamentPresetStore(paths.filamentPresets))
        assertFalse(active.discover(mapOf("XDG_CONFIG_HOME" to xdg.absolutePath), home.absolutePath, "Linux")
            .any { it.root.canonicalFile == xdg.resolve("nozzle-it-all").canonicalFile })
    }

    @Test fun `prepare sanitizes credentials skips unsupported files and leaves source unchanged`() {
        val (_, _, migration) = fixture()
        val source = Files.createTempDirectory("nia-legacy-source").toFile()
        val filament = source.resolve("user/default/filament/Custom.json").apply {
            parentFile.mkdirs(); writeText("""{
                "name":"Acme PLA","filament_type":["PLA"],"filament_vendor":["Acme"],
                "nozzle_temperature":["217"],"api_key":"do-not-copy",
                "nested":{"refreshToken":"do-not-copy"},
                "reference_url":"https://user:password@example.invalid/preset?mode=lan&access_token=do-not-copy&ok=1#secret"
            }""".trimIndent())
        }
        source.resolve("user/default/process/Quality.json").apply { parentFile.mkdirs(); writeText("""{"name":"Quality","layer_height":"0.16"}""") }
        source.resolve("user/default/machine/U1.json").apply { parentFile.mkdirs(); writeText("""{"name":"U1","printhost_apikey":"do-not-copy"}""") }
        source.resolve("user/default/filament/opaque.db").writeText("do-not-copy")
        val recent = source.resolve("Recent project.3mf").apply { writeText("fixture") }
        source.resolve("Snapmaker_Orca.conf").writeText("""
            dark_color_mode = 1
            [recent_projects]
            001 = ${recent.absolutePath}
        """.trimIndent())
        val original = filament.readText()
        val candidate = LegacySettingsMigration.Source(LegacySettingsMigration.Kind.SNAPMAKER_ORCA, "Snapmaker Orca", source, listOf("Snapmaker_Orca.conf"))

        val prepared = migration.prepare(candidate)

        assertEquals(3, prepared.profiles.size)
        assertEquals(listOf(recent.canonicalFile), prepared.projects)
        assertEquals("dark", prepared.theme)
        assertEquals(2, prepared.sanitizedCount)
        assertEquals(1, prepared.skipped.size)
        val clean = prepared.filaments.single().json
        assertFalse(clean.has("api_key"))
        assertFalse(clean.getJSONObject("nested").has("refreshToken"))
        assertEquals("https://example.invalid/preset?mode=lan&ok=1", clean.getString("reference_url"))
        assertEquals(original, filament.readText())

        val filamentOnly = migration.select(prepared, setOf(LegacySettingsMigration.ImportCategory.FILAMENTS))
        assertEquals(listOf("filament"), filamentOnly.profiles.map { it.category })
        assertTrue(filamentOnly.projects.isEmpty())
        assertNull(filamentOnly.theme)
        assertNotEquals(prepared.digest, filamentOnly.digest)
        assertEquals(setOf(LegacySettingsMigration.ImportCategory.FILAMENTS), filamentOnly.categories)

        val appearanceOnly = migration.select(prepared, setOf(LegacySettingsMigration.ImportCategory.APPEARANCE))
        assertTrue(appearanceOnly.profiles.isEmpty())
        assertTrue(appearanceOnly.projects.isEmpty())
        assertEquals("dark", appearanceOnly.theme)
        assertNotEquals(filamentOnly.digest, appearanceOnly.digest)
        assertNotNull(runCatching { migration.select(prepared, emptySet()) }.exceptionOrNull())
    }

    @Test fun `apply backs up NIA presets imports compatible filament and is repeat safe`() {
        val (paths, store, migration) = fixture()
        paths.filamentPresets.writeText("""{"version":1,"presets":[]}""")
        paths.legacyProcessPresets.writeText("""{"schema":1,"processes":[]}""")
        paths.settings.writeText("""{"theme":"light"}""")
        val source = Files.createTempDirectory("nia-legacy-apply").toFile()
        source.resolve("user/default/filament/Custom.json").apply {
            parentFile.mkdirs(); writeText("""{"name":"Acme PLA","filament_type":["PLA"],"filament_vendor":["Acme"],"nozzle_temperature":["217"],"inherits":"Generic PLA"}""")
        }
        source.resolve("user/default/process/Fine.json").apply { parentFile.mkdirs(); writeText("""{"name":"Fine","layer_height":"0.16"}""") }
        val candidate = LegacySettingsMigration.Source(LegacySettingsMigration.Kind.ORCA_SLICER, "OrcaSlicer", source, listOf("OrcaSlicer.conf"))
        val prepared = migration.prepare(candidate)

        val first = migration.apply(prepared)
        val second = migration.apply(prepared)

        assertNotNull(first.backup)
        assertTrue(first.report.isFile)
        assertFalse(first.repeated)
        assertTrue(second.repeated)
        val imported = store.entries("snapmaker_u1").single { it.name == "Acme PLA" }
        val profile = requireNotNull(store.profileJson("snapmaker_u1", imported.id))
        assertEquals("217", profile.getJSONArray("nozzle_temperature").getString(0))
        assertFalse(profile.has("inherits"))
        assertEquals(1, store.entries("snapmaker_u1").count { it.id == imported.id })
        val report = JSONObject(first.report.readText())
        assertFalse(report.getBoolean("credentialsCopied"))
        assertFalse(report.getBoolean("updaterStateCopied"))
        assertEquals(1, report.getInt("preservedProcessProfiles"))
        assertEquals("{\"version\":1,\"presets\":[]}", first.backup!!.resolve("filament-presets.json").readText())
        assertEquals("{\"schema\":1,\"processes\":[]}", first.backup!!.resolve("legacy-process-presets.json").readText())
        assertEquals("{\"theme\":\"light\"}", first.backup!!.resolve("settings.json").readText())
        assertTrue(first.backup!!.resolve("manifest.json").isFile)
    }

    @Test fun `valid recent 3mf is imported once with a stable migration identity`() {
        val (paths, store, _) = fixture()
        val library = ProjectLibrary(paths)
        val migration = LegacySettingsMigration(paths, store, projects = library, appVersion = "test")
        val source = Files.createTempDirectory("nia-legacy-project").toFile()
        val mesh = Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2))
        val recent = source.resolve("Recent project.3mf").apply {
            writeBytes(ThreeMf.write(Project3mf(listOf(ModelObject(1, "Triangle", mesh)))))
        }
        val original = recent.readBytes()
        source.resolve("OrcaSlicer.conf").writeText("""
            [recent_projects]
            001 = ${recent.absolutePath}
        """.trimIndent())
        val prepared = migration.prepare(LegacySettingsMigration.Source(
            LegacySettingsMigration.Kind.ORCA_SLICER, "OrcaSlicer", source, listOf("OrcaSlicer.conf")))

        val first = migration.apply(prepared)
        val second = migration.apply(prepared)

        assertEquals(1, first.importedProjects)
        assertEquals(0, second.importedProjects)
        assertTrue(second.repeated)
        assertEquals(1, library.list().size)
        assertEquals(1, ThreeMf.read(library.list().single().file).objects.size)
        assertArrayEquals(original, recent.readBytes())
    }

    @Test fun `symlinked preset is skipped and cannot escape archive`() {
        val (paths, _, migration) = fixture()
        val source = Files.createTempDirectory("nia-legacy-symlink-source").toFile()
        val outside = Files.createTempFile("nia-legacy-outside", ".json").toFile().apply {
            writeText("""{"name":"Outside","filament_type":["PLA"]}""")
        }
        val link = source.resolve("user/default/filament/link.json").apply { parentFile.mkdirs() }
        runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.getOrElse { return }
        source.resolve("user/default/filament/valid.json").writeText("""{"name":"Valid","filament_type":["PLA"]}""")
        val original = outside.readBytes()

        val prepared = migration.prepare(LegacySettingsMigration.Source(
            LegacySettingsMigration.Kind.ORCA_SLICER, "OrcaSlicer", source, emptyList()))
        val result = migration.apply(prepared)

        assertEquals(1, prepared.profiles.size)
        assertTrue(prepared.skipped.any { it.contains("symbolic link") })
        assertArrayEquals(original, outside.readBytes())
        assertTrue(result.report.canonicalFile.toPath().startsWith(paths.data.canonicalFile.toPath()))
    }

    @Test fun `malformed credential URL is removed`() {
        val (_, _, migration) = fixture()
        val source = Files.createTempDirectory("nia-legacy-bad-url").toFile()
        source.resolve("user/default/filament/Custom.json").apply {
            parentFile.mkdirs(); writeText("""{"name":"Acme","filament_type":["PLA"],"reference_url":"https://user:password@example.invalid/[broken"}""")
        }
        val prepared = migration.prepare(LegacySettingsMigration.Source(
            LegacySettingsMigration.Kind.ORCA_SLICER, "OrcaSlicer", source, emptyList()))
        assertEquals("", prepared.filaments.single().json.getString("reference_url"))
        assertEquals(1, prepared.sanitizedCount)
    }

    @Test fun `recent project bytes change migration identity`() {
        val (_, _, migration) = fixture()
        val source = Files.createTempDirectory("nia-legacy-project-digest").toFile()
        val recent = source.resolve("Recent.3mf").apply { writeText("first") }
        source.resolve("OrcaSlicer.conf").writeText("[recent_projects]\n001 = ${recent.absolutePath}")
        val candidate = LegacySettingsMigration.Source(LegacySettingsMigration.Kind.ORCA_SLICER, "OrcaSlicer", source, listOf("OrcaSlicer.conf"))
        val first = migration.prepare(candidate)
        recent.writeText("second")
        val second = migration.prepare(candidate)
        assertNotEquals(first.digest, second.digest)
    }

    @Test fun `apply rejects a preset changed after review without mutating NIA state`() {
        val (paths, store, migration) = fixture()
        assertFalse(paths.filamentPresets.exists())
        val source = Files.createTempDirectory("nia-legacy-stale-preset").toFile()
        val preset = source.resolve("user/default/filament/Custom.json").apply {
            parentFile.mkdirs(); writeText("""{"name":"Reviewed","filament_type":["PLA"]}""")
        }
        val prepared = migration.prepare(LegacySettingsMigration.Source(
            LegacySettingsMigration.Kind.ORCA_SLICER, "OrcaSlicer", source, emptyList()))
        preset.writeText("""{"name":"Changed","filament_type":["PETG"]}""")

        val failure = runCatching { migration.apply(prepared) }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("changed after review"))
        assertFalse(paths.filamentPresets.exists())
        assertTrue(store.entries("snapmaker_u1").isEmpty())
        assertFalse(paths.data.resolve("migrations/${prepared.digest}/report.json").exists())
    }

    @Test fun `next apply recovers an interrupted transaction before importing`() {
        val (paths, store, migration) = fixture()
        paths.filamentPresets.writeText("""{"version":1,"presets":[]}""")
        paths.legacyProcessPresets.writeText("""{"version":1,"presets":[]}""")
        paths.settings.writeText("""{"theme":"light"}""")
        val originalFilaments = paths.filamentPresets.readText()
        val originalProcesses = paths.legacyProcessPresets.readText()
        val originalSettings = paths.settings.readText()
        val backup = paths.data.resolve("migration-backups/interrupted").apply { mkdirs() }
        backup.resolve(paths.filamentPresets.name).writeText(originalFilaments)
        backup.resolve(paths.legacyProcessPresets.name).writeText(originalProcesses)
        backup.resolve(paths.settings.name).writeText(originalSettings)
        val rows = org.json.JSONArray(listOf(paths.filamentPresets, paths.legacyProcessPresets, paths.settings).map { target ->
            JSONObject().put("name", target.name).put("existed", true).put("sha256", sha256(target.readBytes()))
        })
        backup.resolve("manifest.json").writeText(JSONObject().put("schema", 1).put("files", rows).toString())
        paths.filamentPresets.writeText("""{"version":1,"presets":[{"id":"legacy-000000000000000000000000"}]}""")
        paths.settings.writeText("""{"theme":"dark"}""")
        val interruptedDigest = "a".repeat(64)
        val interruptedStage = paths.data.resolve(".migration-interrupted").apply { mkdirs() }
        paths.data.resolve("migration-transactions/$interruptedDigest.json").apply {
            parentFile.mkdirs(); writeText(JSONObject().put("schema", 1).put("status", "pending").put("digest", interruptedDigest)
                .put("backup", backup.canonicalPath).put("stage", interruptedStage.canonicalPath)
                .put("projectMigrationKeys", org.json.JSONArray()).toString())
        }
        val source = Files.createTempDirectory("nia-legacy-recovery-source").toFile()
        source.resolve("OrcaSlicer.conf").writeText("dark_color_mode = 1")
        val prepared = migration.prepare(LegacySettingsMigration.Source(
            LegacySettingsMigration.Kind.ORCA_SLICER, "OrcaSlicer", source, listOf("OrcaSlicer.conf")))

        migration.apply(prepared)

        assertEquals(originalFilaments, paths.filamentPresets.readText())
        assertEquals(originalProcesses, paths.legacyProcessPresets.readText())
        assertEquals(originalSettings, paths.settings.readText())
        assertFalse(interruptedStage.exists())
        assertFalse(paths.data.resolve("migration-transactions/$interruptedDigest.json").exists())
        assertTrue(store.entries("snapmaker_u1").isEmpty())
    }

    @Test fun `published migration survives a crash before journal cleanup`() {
        val root = Files.createTempDirectory("nia-legacy-committed-crash").toFile()
        val paths = AppPaths(root.resolve("config"), root.resolve("data"), root.resolve("cache")).ensure()
        val store = FilamentPresetStore(paths.filamentPresets)
        val source = Files.createTempDirectory("nia-legacy-committed-source").toFile()
        source.resolve("user/default/filament/Committed.json").apply {
            parentFile.mkdirs(); writeText("""{"name":"Committed PLA","filament_type":["PLA"]}""")
        }
        val candidate = LegacySettingsMigration.Source(
            LegacySettingsMigration.Kind.ORCA_SLICER, "OrcaSlicer", source, emptyList())
        val cleanupInterrupted = LegacySettingsMigration(paths, store, committedJournalCleanup = { error("simulated cleanup interruption") })
        val prepared = cleanupInterrupted.prepare(candidate)

        val committed = cleanupInterrupted.apply(prepared)

        assertFalse(committed.repeated)
        assertTrue(paths.data.resolve("migrations/${prepared.digest}/report.json").isFile)
        assertTrue(paths.data.resolve("migration-transactions/${prepared.digest}.json").isFile)
        assertTrue(paths.filamentPresets.readText().contains("Committed PLA"))

        val recovered = LegacySettingsMigration(paths, FilamentPresetStore(paths.filamentPresets)).apply(prepared)

        assertTrue(recovered.repeated)
        assertFalse(paths.data.resolve("migration-transactions/${prepared.digest}.json").exists())
        assertTrue(paths.filamentPresets.readText().contains("Committed PLA"))
    }

    @Test fun `recovery rejects symlinked journal paths without touching their targets`() {
        val root = Files.createTempDirectory("nia-legacy-symlink-recovery").toFile()
        val paths = AppPaths(root.resolve("config"), root.resolve("data"), root.resolve("cache")).ensure()
        val store = FilamentPresetStore(paths.filamentPresets)
        val outside = Files.createTempDirectory("nia-legacy-symlink-target").toFile()
        val marker = outside.resolve("keep.txt").apply { writeText("keep") }
        val backupLink = paths.data.resolve("migration-backups/linked").apply {
            parentFile.mkdirs(); Files.createSymbolicLink(toPath(), outside.toPath())
        }
        val interruptedDigest = "b".repeat(64)
        paths.data.resolve("migration-transactions/$interruptedDigest.json").apply {
            parentFile.mkdirs(); writeText(JSONObject().put("schema", 1).put("status", "pending").put("digest", interruptedDigest)
                .put("backup", backupLink.absolutePath).put("stage", JSONObject.NULL)
                .put("projectMigrationKeys", org.json.JSONArray()).toString())
        }
        val source = Files.createTempDirectory("nia-legacy-symlink-source").toFile()
        source.resolve("OrcaSlicer.conf").writeText("dark_color_mode = 1")
        val migration = LegacySettingsMigration(paths, store)
        val prepared = migration.prepare(LegacySettingsMigration.Source(
            LegacySettingsMigration.Kind.ORCA_SLICER, "OrcaSlicer", source, listOf("OrcaSlicer.conf")))

        val failure = runCatching { migration.apply(prepared) }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure?.message.orEmpty().contains("symbolic-link backup path"))
        assertEquals("keep", marker.readText())
        assertTrue(paths.data.resolve("migration-transactions/$interruptedDigest.json").exists())
    }

    @Test fun `mismatched completed report does not suppress interrupted rollback`() {
        val (paths, store, migration) = fixture()
        val original = """{"version":1,"presets":[]}"""
        paths.filamentPresets.writeText(original)
        val backup = paths.data.resolve("migration-backups/mismatch").apply { mkdirs() }
        backup.resolve(paths.filamentPresets.name).writeText(original)
        val rows = org.json.JSONArray().put(JSONObject().put("name", paths.filamentPresets.name).put("existed", true)
            .put("sha256", sha256(original.toByteArray())))
            .put(JSONObject().put("name", paths.legacyProcessPresets.name).put("existed", false))
            .put(JSONObject().put("name", paths.settings.name).put("existed", false))
        backup.resolve("manifest.json").writeText(JSONObject().put("schema", 1).put("files", rows).toString())
        paths.filamentPresets.writeText("""{"version":1,"presets":[{"id":"partial"}]}""")
        val interruptedDigest = "c".repeat(64)
        paths.data.resolve("migrations/$interruptedDigest/report.json").apply {
            parentFile.mkdirs(); writeText(JSONObject().put("schema", 2).put("status", "complete")
                .put("sourceDigest", "d".repeat(64)).toString())
        }
        paths.data.resolve("migration-transactions/$interruptedDigest.json").apply {
            parentFile.mkdirs(); writeText(JSONObject().put("schema", 1).put("status", "pending").put("digest", interruptedDigest)
                .put("backup", backup.canonicalPath).put("stage", JSONObject.NULL)
                .put("projectMigrationKeys", org.json.JSONArray()).toString())
        }
        val source = Files.createTempDirectory("nia-legacy-mismatch-source").toFile()
        source.resolve("OrcaSlicer.conf").writeText("dark_color_mode = 1")
        val prepared = migration.prepare(LegacySettingsMigration.Source(
            LegacySettingsMigration.Kind.ORCA_SLICER, "OrcaSlicer", source, listOf("OrcaSlicer.conf")))

        migration.apply(prepared)

        assertEquals(original, paths.filamentPresets.readText())
        assertFalse(paths.data.resolve("migrations/$interruptedDigest").exists())
        assertFalse(paths.data.resolve("migration-transactions/$interruptedDigest.json").exists())
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun `failed apply restores NIA files and removes newly imported project`() {
        val (paths, store, _) = fixture()
        val filamentBefore = """{"version":1,"presets":[]}"""
        val processBefore = """{"version":1,"presets":[]}"""
        val settingsBefore = """{"theme":"light"}"""
        paths.filamentPresets.writeText(filamentBefore)
        paths.legacyProcessPresets.writeText(processBefore)
        paths.settings.writeText(settingsBefore)
        val library = ProjectLibrary(paths)
        val migration = LegacySettingsMigration(paths, store, projects = library, importTheme = { error("injected theme failure") }, appVersion = "test")
        val source = Files.createTempDirectory("nia-legacy-rollback").toFile()
        source.resolve("user/default/filament/Custom.json").apply {
            parentFile.mkdirs(); writeText("""{"name":"Acme PLA","filament_type":["PLA"]}""")
        }
        val mesh = Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2))
        val recent = source.resolve("Recent.3mf").apply { writeBytes(ThreeMf.write(Project3mf(listOf(ModelObject(1, "Triangle", mesh))))) }
        source.resolve("OrcaSlicer.conf").writeText("dark_color_mode = 1\n[recent_projects]\n001 = ${recent.absolutePath}")
        val prepared = migration.prepare(LegacySettingsMigration.Source(
            LegacySettingsMigration.Kind.ORCA_SLICER, "OrcaSlicer", source, listOf("OrcaSlicer.conf")))

        runCatching { migration.apply(prepared) }.onSuccess { fail("apply unexpectedly succeeded") }

        assertEquals(filamentBefore, paths.filamentPresets.readText())
        assertEquals(processBefore, paths.legacyProcessPresets.readText())
        assertEquals(settingsBefore, paths.settings.readText())
        assertTrue(library.list().isEmpty())
        assertFalse(paths.data.resolve("migrations/${prepared.digest}/report.json").exists())
        assertTrue(store.entries("snapmaker_u1").none { it.name == "Acme PLA" })
    }

    @Test fun `repeated apply is a no-op and does not reapply theme`() {
        val (paths, store, _) = fixture()
        var theme = "system"
        var calls = 0
        val migration = LegacySettingsMigration(paths, store, importTheme = { value -> calls++; theme = value; true })
        val source = Files.createTempDirectory("nia-legacy-repeat-noop").toFile()
        source.resolve("user/default/filament/Custom.json").apply {
            parentFile.mkdirs(); writeText("""{"name":"Acme PLA","filament_type":["PLA"]}""")
        }
        source.resolve("OrcaSlicer.conf").writeText("dark_color_mode = 1")
        val prepared = migration.prepare(LegacySettingsMigration.Source(
            LegacySettingsMigration.Kind.ORCA_SLICER, "OrcaSlicer", source, listOf("OrcaSlicer.conf")))
        migration.apply(prepared)
        theme = "light"
        val before = paths.filamentPresets.readBytes()

        val repeated = migration.apply(prepared)

        assertTrue(repeated.repeated)
        assertEquals(0, repeated.importedFilaments)
        assertEquals("light", theme)
        assertEquals(1, calls)
        assertArrayEquals(before, paths.filamentPresets.readBytes())
    }
}
