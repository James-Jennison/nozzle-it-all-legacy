package com.nozzleitall.desktop.prepare

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CadPluginCatalogTest {
    @Test fun bundledListingsVerifyAndUseTheExistingPluginStore() {
        val listings = CadPluginCatalog.load()
        assertEquals(setOf("round-spacer", "rectangular-drill-guide"), listings.map { it.plugin.id }.toSet())
        val root = Files.createTempDirectory("nia-marketplace-store").toFile()
        try {
            val listing = listings.first()
            val plugin = listing.plugin
            val store = CadPluginStore(root)
            assertTrue(store.inventory().installed.isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                store.install(plugin, listing.packageBytes + byteArrayOf(0x20), listing.digest)
            }
            assertTrue(store.inventory().installed.isEmpty())
            store.install(plugin, listing.packageBytes, listing.digest)
            assertArrayEquals(listing.packageBytes, root.resolve("${plugin.id}.niaplugin").readBytes())
            val restarted = CadPluginStore(root)
            assertEquals(plugin.recipe, restarted.load(restarted.inventory().installed.single()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun tamperedArtifactAndMismatchedListingFailClosed() {
        fun read(name: String): ByteArray =
            requireNotNull(javaClass.getResourceAsStream("/plugins/$name")).use { it.readBytes() }
        assertThrows(IllegalArgumentException::class.java) {
            CadPluginCatalog.load { name, _ ->
                read(name).let { if (name == "round-spacer.niaplugin") it + byteArrayOf(0x20) else it }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CadPluginCatalog.load { name, _ ->
                read(name).let { if (name == "catalog.json") it.toString(Charsets.UTF_8).replace("\"release\": 1", "\"release\": 2").toByteArray() else it }
            }
        }
    }
}
