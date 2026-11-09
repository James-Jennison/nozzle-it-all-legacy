package com.nozzleitall.desktop

import com.nozzleitall.desktop.settings.Scope
import com.nozzleitall.desktop.settings.UserPresetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UserPresetStoreTest {
    @Test fun `user presets persist and only exact user records can be overwritten or deleted`() {
        val file = File(Files.createTempDirectory("nia-user-presets-").toFile(), "user-presets.json")
        val store = UserPresetStore(file)
        val saved = store.save(Scope.PROCESS, "snapmaker_u1", "u1-04", "base-process", "My process",
            mapOf("layer_height" to "0.18", "wall_loops" to "3"))

        assertTrue(saved.id.startsWith("user-"))
        assertEquals(saved, UserPresetStore(file).find(saved.id))
        val overwritten = store.save(Scope.PROCESS, "snapmaker_u1", "u1-04", "base-process", "My process",
            mapOf("layer_height" to "0.16"), overwriteId = saved.id)
        assertEquals(saved.id, overwritten.id)
        assertEquals("0.16", UserPresetStore(file).find(saved.id)?.values?.get("layer_height"))

        assertThrows(IllegalArgumentException::class.java) {
            store.save(Scope.PROCESS, "snapmaker_u1", "u1-04", "base-process", "Other", emptyMap(), overwriteId = "system-preset")
        }
        assertFalse(store.delete("system-preset"))
        assertTrue(store.delete(saved.id))
        assertTrue(UserPresetStore(file).entries(Scope.PROCESS, "snapmaker_u1", "u1-04").isEmpty())
    }

    @Test fun `names are unique within one scope and machine context but not across contexts`() {
        val store = UserPresetStore(File(Files.createTempDirectory("nia-user-preset-names-").toFile(), "presets.json"))
        store.save(Scope.FILAMENT, "snapmaker_u1", "u1-04", "pla", "Workshop PLA", mapOf("filament_flow_ratio" to "0.98"))
        assertThrows(IllegalArgumentException::class.java) {
            store.save(Scope.FILAMENT, "snapmaker_u1", "u1-04", "petg", "workshop pla", emptyMap())
        }
        assertNotNull(store.save(Scope.FILAMENT, "snapmaker_u1", "u1-06", "pla", "Workshop PLA", emptyMap()))
    }

    @Test fun `failed persistence never changes live preset state`() {
        val file = File(Files.createTempDirectory("nia-user-preset-failure-").toFile(), "presets.json")
        val store = UserPresetStore(file)
        val saved = store.save(Scope.PROCESS, "snapmaker_u1", "u1-04", "base-process", "Before failure",
            mapOf("layer_height" to "0.2"))

        assertTrue(file.delete())
        assertTrue(file.mkdir())

        assertThrows(Exception::class.java) {
            store.save(Scope.PROCESS, "snapmaker_u1", "u1-04", "base-process", "Not saved",
                mapOf("layer_height" to "0.1"))
        }
        assertEquals(listOf(saved), store.entries(Scope.PROCESS, "snapmaker_u1", "u1-04"))

        assertThrows(Exception::class.java) { store.delete(saved.id) }
        assertEquals(saved, store.find(saved.id))
    }
}
