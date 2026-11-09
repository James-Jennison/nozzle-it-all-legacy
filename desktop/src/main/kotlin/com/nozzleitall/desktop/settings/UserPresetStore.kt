package com.nozzleitall.desktop.settings

import com.nozzleitall.desktop.PrinterStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** NIA-owned user presets. Bundled, imported, and vendor presets never enter this store and therefore cannot be deleted here. */
class UserPresetStore(private val file: File) {
    data class Preset(
        val id: String,
        val scope: Scope,
        val profileId: String,
        val contextId: String,
        val baseId: String,
        val name: String,
        val values: Map<String, String>,
    )

    private val rows = linkedMapOf<String, Preset>()

    init { load() }

    @Synchronized
    fun entries(scope: Scope, profileId: String, contextId: String): List<Preset> = rows.values
        .filter { it.scope == scope && it.profileId == profileId && it.contextId == contextId }
        .sortedBy { it.name.lowercase() }

    @Synchronized fun find(id: String?): Preset? = id?.let(rows::get)

    /** Saves a new preset or overwrites one exact user preset. A system/imported id can never be an overwrite target. */
    @Synchronized
    fun save(
        scope: Scope,
        profileId: String,
        contextId: String,
        baseId: String,
        name: String,
        values: Map<String, String>,
        overwriteId: String? = null,
    ): Preset {
        val cleanName = name.trim()
        require(cleanName.length in 1..120) { "Enter a preset name between 1 and 120 characters." }
        require(profileId.isNotBlank() && contextId.isNotBlank() && baseId.isNotBlank()) { "The preset context is incomplete." }
        val overwrite = overwriteId?.let { id -> rows[id] ?: throw IllegalArgumentException("Only a NIA user preset can be overwritten.") }
        require(overwrite == null || overwrite.scope == scope && overwrite.profileId == profileId && overwrite.contextId == contextId) {
            "The selected user preset belongs to a different machine or settings type."
        }
        val duplicate = rows.values.firstOrNull {
            it.id != overwrite?.id && it.scope == scope && it.profileId == profileId && it.contextId == contextId && it.name.equals(cleanName, true)
        }
        require(duplicate == null) { "A user preset named '$cleanName' already exists." }
        val id = overwrite?.id ?: "user-" + sha256(UUID.randomUUID().toString()).take(24)
        val row = Preset(id, scope, profileId, contextId, baseId, cleanName,
            values.filterKeys { it.length in 1..160 }.mapValues { it.value.take(64 * 1024) }.toSortedMap())
        val candidate = LinkedHashMap(rows).apply { put(id, row) }
        persist(candidate.values)
        rows.clear()
        rows.putAll(candidate)
        return row
    }

    /** Returns false for every non-user, missing, bundled, vendor, or imported preset id. */
    @Synchronized
    fun delete(id: String): Boolean {
        if (!id.startsWith("user-") || id !in rows) return false
        val candidate = LinkedHashMap(rows).apply { remove(id) }
        persist(candidate.values)
        rows.clear()
        rows.putAll(candidate)
        return true
    }

    private fun load() {
        if (!file.isFile || file.length() > 32L * 1024 * 1024) return
        runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("version") != 1) return@runCatching
            val presets = root.optJSONArray("presets") ?: return@runCatching
            for (i in 0 until presets.length().coerceAtMost(4096)) {
                val o = presets.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { USER_ID.matches(it) } ?: continue
                val scope = Scope.entries.firstOrNull { it.id == o.optString("scope") } ?: continue
                val profileId = o.optString("profileId").takeIf { it.length in 1..200 } ?: continue
                val contextId = o.optString("contextId").takeIf { it.length in 1..300 } ?: continue
                val baseId = o.optString("baseId").takeIf { it.length in 1..300 } ?: continue
                val name = o.optString("name").takeIf { it.length in 1..120 } ?: continue
                val source = o.optJSONObject("values") ?: continue
                val values = source.keySet().filter { it.length in 1..160 }.associateWith { source.optString(it).take(64 * 1024) }
                rows[id] = Preset(id, scope, profileId, contextId, baseId, name, values)
            }
        }
    }

    private fun persist(candidate: Collection<Preset>) {
        val presets = JSONArray(candidate.sortedBy { it.id }.map { row ->
            JSONObject().put("id", row.id).put("scope", row.scope.id).put("profileId", row.profileId)
                .put("contextId", row.contextId).put("baseId", row.baseId).put("name", row.name)
                .put("values", JSONObject(row.values))
        })
        PrinterStore.atomicWrite(file, JSONObject().put("version", 1).put("presets", presets).toString(2), private = false)
    }

    companion object {
        private val USER_ID = Regex("user-[0-9a-f]{24}")
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
