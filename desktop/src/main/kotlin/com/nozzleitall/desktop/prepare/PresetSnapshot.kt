package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.ProjectManifest
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest

/** The profile files used when this project was saved. Bundled updates are reviewed before replacing them. */
internal data class PresetSnapshot(
    val profileId: String,
    val directoryName: String,
    val files: Map<String, String>,
    val filamentProfiles: Map<String, String>,
) {
    init {
        require(directoryName.matches(Regex("[A-Za-z0-9_.@-]{1,200}")) && !directoryName.startsWith('.')) {
            "Invalid saved preset identity."
        }
        require(files.keys == PROFILE_FILES.toSet()) { "The saved preset is incomplete." }
        require(files.values.all { validProfile(it) }) { "The saved preset has an invalid or overly nested profile file." }
        require(filamentProfiles.size <= 16 && filamentProfiles.keys.all { it.matches(Regex("[A-Za-z0-9_.@-]{1,200}")) }) { "Invalid saved filament identity." }
        require(filamentProfiles.values.all { validProfile(it) }) { "The saved filament preset is invalid or overly nested." }
    }

    fun appliesTo(id: String, directory: File): Boolean = profileId == id && directory.name == directoryName

    /** A comparison of saved values with the currently bundled files; no new value is applied by reading it. */
    fun changes(current: PresetSnapshot): List<String> {
        val changed = mutableListOf<String>()
        fun compare(group: String, old: String?, new: String?) {
            if (old == new) return
            if (old == null || new == null) {
                changed += "$group: ${if (old == null) "new" else "no longer bundled"}"
                return
            }
            val before = JSONObject(old)
            val after = JSONObject(new)
            (before.keySet() + after.keySet()).toSortedSet().forEach { key ->
                val priorValue = before.opt(key)
                val nextValue = after.opt(key)
                if (!before.has(key) || !after.has(key) || !equivalent(priorValue, nextValue)) {
                    val previous = if (before.has(key)) priorValue.toString() else "not set"
                    val available = if (after.has(key)) nextValue.toString() else "not set"
                    changed += "$group · $key: $previous → $available"
                }
            }
        }
        PROFILE_FILES.forEach { compare(it.removeSuffix(".json"), files[it], current.files[it]) }
        (filamentProfiles.keys + current.filamentProfiles.keys).toSortedSet().forEach { id ->
            compare("filament $id", filamentProfiles[id], current.filamentProfiles[id])
        }
        return changed
    }

    /** Writes only fixed filenames into a content-addressed cache and verifies reuse. */
    fun materialize(cache: File): File {
        val hash = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            hash.update(bytes)
        }
        add(profileId); add(directoryName)
        files.toSortedMap().forEach { (name, value) -> add(name); add(value) }
        filamentProfiles.toSortedMap().forEach { (name, value) -> add(name); add(value) }
        val digest = hash.digest().joinToString("") { "%02x".format(it) }
        val dir = File(cache, "preset-snapshots/$digest/$directoryName").apply { mkdirs() }
        PROFILE_FILES.forEach { name ->
            val expected = files.getValue(name).toByteArray(Charsets.UTF_8)
            val target = File(dir, name)
            if (!target.isFile || !target.readBytes().contentEquals(expected)) {
                val temporary = File(dir, ".$name.${java.util.UUID.randomUUID()}.part")
                try {
                    temporary.writeBytes(expected)
                    java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } finally { temporary.delete() }
            }
            check(target.readBytes().contentEquals(expected)) { "The saved preset cache could not be verified." }
        }
        return dir
    }

    fun toJson(): JSONObject = JSONObject().put("profileId", profileId).put("directoryName", directoryName)
        .put("files", JSONObject(files)).put("filamentProfiles", JSONObject(filamentProfiles))

    companion object {
        private const val MAX_FILE_CHARS = 512_000
        private const val MAX_JSON_DEPTH = 64
        private val PROFILE_FILES = listOf("machine.json", "process.json", "filament.json")

        private fun validProfile(text: String): Boolean = text.length <= MAX_FILE_CHARS && nestingAllowedBeforeParse(text) && runCatching {
            depthAllowed(JSONObject(text), 0)
        }.getOrDefault(false)

        // org.json descends recursively while parsing, so reject excessive nesting first.
        private fun nestingAllowedBeforeParse(text: String): Boolean {
            var depth = 0
            var quoted = false
            var escaped = false
            for (char in text) {
                if (quoted) {
                    if (escaped) escaped = false
                    else when (char) {
                        '\\' -> escaped = true
                        '"' -> quoted = false
                    }
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> if (++depth > MAX_JSON_DEPTH) return false
                    '}', ']' -> if (--depth < 0) return false
                }
            }
            return !quoted && depth == 0
        }

        private fun depthAllowed(value: Any?, depth: Int): Boolean = when {
            depth > MAX_JSON_DEPTH -> false
            value is JSONObject -> value.keySet().all { depthAllowed(value.opt(it), depth + 1) }
            value is JSONArray -> (0 until value.length()).all { depthAllowed(value.opt(it), depth + 1) }
            else -> true
        }

        private fun equivalent(before: Any?, after: Any?): Boolean = when {
            before is JSONObject && after is JSONObject -> before.keySet() == after.keySet() &&
                before.keySet().all { equivalent(before.opt(it), after.opt(it)) }
            before is JSONArray && after is JSONArray -> before.length() == after.length() &&
                (0 until before.length()).all { equivalent(before.opt(it), after.opt(it)) }
            before is Number && after is Number -> runCatching {
                BigDecimal(before.toString()).compareTo(BigDecimal(after.toString())) == 0
            }.getOrDefault(false)
            else -> before == after
        }

        fun capture(profileId: String, directory: File, materials: List<ProjectManifest.MaterialSlot>): PresetSnapshot {
            val key = FilamentLibrary.keyForDir(directory.name)
            // Foreign or removed filament ids already fall back to the machine's base filament; retain their
            // identity in the project manifest without inventing a bundled definition for them.
            val filaments = materials.mapNotNull { it.filamentProfile }.distinct().mapNotNull { id ->
                FilamentLibrary.profileJson(key, id)?.toString()?.let { id to it }
            }.toMap()
            return PresetSnapshot(profileId, directory.name, PROFILE_FILES.associateWith { File(directory, it).readText() }, filaments)
        }

        fun fromExtensions(extensions: JSONObject): PresetSnapshot? {
            if (!extensions.has("desktop")) return null
            val desktop = extensions.getJSONObject("desktop")
            if (!desktop.has("presetSnapshot")) return null
            val saved = desktop.getJSONObject("presetSnapshot")
            val files = saved.getJSONObject("files")
            val filaments = saved.getJSONObject("filamentProfiles")
            fun strings(json: JSONObject) = json.keySet().associateWith { json.getString(it) }
            return PresetSnapshot(saved.getString("profileId"), saved.getString("directoryName"), strings(files), strings(filaments))
        }

        fun withSnapshot(extensions: JSONObject, snapshot: PresetSnapshot): JSONObject {
            val copy = JSONObject(extensions.toString())
            val desktop = if (copy.has("desktop")) JSONObject(copy.getJSONObject("desktop").toString()) else JSONObject()
            desktop.put("presetSnapshot", snapshot.toJson())
            copy.put("desktop", desktop)
            return copy
        }
    }
}
