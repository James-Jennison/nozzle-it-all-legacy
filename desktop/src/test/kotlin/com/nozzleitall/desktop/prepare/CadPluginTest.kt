package com.nozzleitall.desktop.prepare

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

class CadPluginTest {
    private val manifest = """
        {"api":1,"kind":"cad-recipe","id":"round-spacer","name":"Round spacer","release":1,
        "recipe":{"shape":"circle","diameterMm":20,"depthMm":5,"holeDiameterMm":4}}
    """.trimIndent()

    @Test fun recipeOnlyPluginUsesKnownDimensions() {
        val plugin = CadPlugin.decode(manifest.toByteArray())
        assertEquals(CadRecipe(true, 20.0, 20.0, 5.0, 4.0), plugin.recipe)
        assertEquals(plugin, CadPlugin.decode(plugin.encode().toByteArray()))
    }

    @Test fun rejectsUnknownCapabilityAndUnsafeOrMalformedInput() {
        val invalid = listOf(
            manifest.replace("\"api\":1", "\"api\":2"),
            manifest.replace("\"kind\":\"cad-recipe\"", "\"kind\":\"script\""),
            manifest.replace("round-spacer", "../other"),
            manifest.replace("\"depthMm\":5", "\"depthMm\":5,\"command\":\"run\""),
            manifest.replace("\"depthMm\":5", "\"depthMm\":5000"),
            manifest.replace("\"holeDiameterMm\":4", "\"holeDiameterMm\":25"),
            manifest + "\u0000{}",
            manifest + " {}",
            manifest.replace(""" "api":1 """.trim(), """ "api":1,"api":1 """.trim()),
            manifest.replace(""" "depthMm":5 """.trim(), """ "depthMm":0 """.trim()),
            manifest.replace(""" "depthMm":5 """.trim(), """ "depthMm":"5" """.trim()),
            manifest + " ".repeat(8192),
        )
        invalid.forEach { assertTrue(runCatching { CadPlugin.decode(it.toByteArray()) }.isFailure) }
        assertTrue(runCatching { CadPlugin.decode(byteArrayOf(0xC3.toByte(), 0x28)) }.isFailure)
        assertEquals(CadPlugin.decode(manifest.toByteArray()), CadPlugin.decode((manifest + "  \n").toByteArray()))
    }

    @Test fun localInstallSurvivesRestartAndCannotReplaceExistingIdentity() {
        val root = Files.createTempDirectory("nia-cad-plugin").toFile()
        try {
            val store = CadPluginStore(root)
            val plugin = CadPlugin.decode(manifest.toByteArray())
            assertTrue(store.inventory().installed.isEmpty())
            store.install(plugin)
            val duplicate = CadPlugin.decode(manifest.replace("Round spacer", "Different name").toByteArray())
            assertThrows(FileAlreadyExistsException::class.java) { store.install(duplicate) }
            val restarted = CadPluginStore(root)
            val installed = restarted.inventory().installed.single()
            assertEquals(plugin, installed.plugin)
            assertTrue(installed.enabled)
            assertEquals(plugin.recipe, restarted.load(installed))
            restarted.setEnabled(installed, false)
            assertFalse(CadPluginStore(root).inventory().installed.single().enabled)
            assertThrows(PluginStorageProblem::class.java) { store.load(installed) }
            assertEquals(plugin, store.inventory().installed.single().plugin)
            restarted.setEnabled(installed, true)
            assertEquals(plugin.recipe, store.load(installed))

            restarted.setEnabled(installed, false)
            val target = root.resolve("round-spacer.niaplugin")
            target.appendText(" ")
            val changed = store.inventory().installed.single()
            assertEquals(plugin, changed.plugin)
            assertNotEquals(installed.digest, changed.digest)
            assertFalse(changed.enabled)
            assertThrows(PluginStorageProblem::class.java) { store.load(installed) }
            assertThrows(PluginStorageProblem::class.java) { store.setEnabled(installed, true) }
            assertThrows(PluginStorageProblem::class.java) { store.load(changed) }
            restarted.setEnabled(changed, true)
            assertEquals(plugin.recipe, store.load(changed))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun malformedPluginFilesCountTowardTheInventoryLimit() {
        val root = Files.createTempDirectory("nia-cad-plugin-limit").toFile()
        try {
            repeat(128) { root.resolve("malformed-$it.niaplugin").writeText("{}") }
            val store = CadPluginStore(root)
            assertEquals(128, store.inventory().unreadable)
            assertThrows(PluginStorageProblem::class.java) { store.install(CadPlugin.decode(manifest.toByteArray())) }
            assertFalse(root.resolve("round-spacer.niaplugin").exists())
        } finally {
            root.deleteRecursively()
        }
    }

}
