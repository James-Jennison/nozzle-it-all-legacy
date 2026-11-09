package com.nozzleitall.desktop.materials

import com.nozzleitall.desktop.PrinterStore
import com.nozzleitall.desktop.prepare.FilamentLibrary
import com.nozzleitall.desktop.prepare.PrinterLibrary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Durable, NIA-owned filament presets derived from a printer's bundled base profile and a 3DFP product identity. */
class FilamentPresetStore(private val file: File) : FilamentLibrary.CustomProfiles {
    data class Imported(
        val profileKey: String,
        val entry: FilamentLibrary.Entry,
        val colorHex: String?,
        val sourceId: String,
        val website: String,
        val profile: JSONObject,
        val source: String = "3dfp",
        val legacyInherits: String? = null,
        val legacyCompatiblePrinters: List<String> = emptyList(),
        val legacyCompatiblePrints: List<String> = emptyList(),
    )

    private val rows = LinkedHashMap<String, Imported>()

    init { load() }

    /** Reconciles the in-memory view after a migration transaction restores its byte-for-byte backup. */
    @Synchronized fun reload() { rows.clear(); load() }

    @Synchronized override fun entries(profileId: String): List<FilamentLibrary.Entry> = rows.values
        .filter { it.profileKey == profileId || it.profileKey == LEGACY_ANY_PROFILE && legacyBase(profileId, it) != null && legacyCompatible(profileId, it) }
        .map { it.entry }.sortedBy { it.displayName.lowercase() }

    @Synchronized override fun profileJson(profileId: String, filamentId: String): JSONObject? = rows[filamentId]
        ?.let { imported ->
            when {
                imported.profileKey == profileId -> JSONObject(imported.profile.toString())
                imported.profileKey == LEGACY_ANY_PROFILE && legacyCompatible(profileId, imported) -> legacyBase(profileId, imported)?.let { base ->
                    JSONObject(base.toString()).apply {
                        imported.profile.keySet().filterNot { it in LEGACY_METADATA }.forEach { key -> put(key, imported.profile.get(key)) }
                        put("name", imported.entry.name)
                        put("filament_vendor", JSONArray().put(imported.entry.vendor))
                        put("filament_type", JSONArray().put(imported.entry.type))
                        put("filament_settings_id", JSONArray().put(imported.entry.id))
                    }
                }
                else -> null
            }
        }

    @Synchronized fun imported(filamentId: String): Imported? = rows[filamentId]

    /**
     * Creates an identified product preset without inventing tuning. Temperatures, flow and cooling stay inherited from
     * the printer's bundled base-material preset; only product identity and optional catalog color change.
     */
    @Synchronized fun import(profileKey: String, record: FilamentProfileRecord): Imported {
        require(record.usable) { "The catalog result does not identify one unambiguous base material." }
        val base = FilamentLibrary.bestNativeBundledFor(profileKey, "Generic", record.baseMaterial)
            ?: FilamentLibrary.bestNativeBundledFor(profileKey, null, record.baseMaterial)
            ?: throw IllegalArgumentException("This printer profile has no compatible ${record.baseMaterial} preset to derive from.")
        val baseJson = FilamentLibrary.bundledProfileJson(profileKey, base.id)
            ?: throw IllegalStateException("The base filament preset '${base.id}' is unavailable.")
        val sourceIdentity = record.id.ifBlank { record.shortCode }.ifBlank { record.productName }
        val id = "3dfp-" + sha256("$profileKey\n$sourceIdentity\n${record.productName}\n${base.id}").take(24)
        rows[id]?.let { return it }
        val profile = JSONObject(baseJson.toString())
            .put("name", record.productName)
            .put("filament_vendor", JSONArray().put(record.brand.ifBlank { "Generic" }))
            .put("filament_type", JSONArray().put(record.baseMaterial))
            .put("filament_settings_id", JSONArray().put(id))
        record.colorHex?.let { profile.put("default_filament_colour", JSONArray().put(it)) }
        val entry = FilamentLibrary.Entry(id, record.productName, record.brand, record.baseMaterial, base.family)
        val imported = Imported(profileKey, entry, record.colorHex, sourceIdentity, record.website, profile)
        rows[id] = imported
        save()
        return imported
    }

    /**
     * Preserves a user filament preset from an Orca-family data directory. The source preset is stored as a bounded,
     * sanitized override and is completed from each selected printer's known compatible bundled base at use time.
     * This avoids inventing tuning or claiming that an Orca printer-compatibility expression is a NIA printer id.
     */
    @Synchronized fun importLegacy(sourceLabel: String, relativePath: String, sourceProfile: JSONObject): Imported {
        val name = sourceProfile.optString("name").trim().takeIf { it.length in 1..300 }
            ?: relativePath.substringAfterLast('/').substringBeforeLast('.').take(300).takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("The legacy filament preset has no usable name.")
        val type = firstString(sourceProfile.opt("filament_type"))?.trim()?.takeIf { it.length in 1..64 }
            ?: throw IllegalArgumentException("$name has no unambiguous filament type.")
        val vendor = firstString(sourceProfile.opt("filament_vendor"))?.trim()?.take(200).orEmpty().ifBlank { "Custom" }
        val legacyInherits = sourceProfile.optString("inherits").trim().takeIf(String::isNotBlank)
        val legacyCompatiblePrinters = strings(sourceProfile.opt("compatible_printers"))
        val legacyCompatiblePrints = strings(sourceProfile.opt("compatible_prints"))
        val normalized = JSONObject(sourceProfile.toString()).apply {
            LEGACY_METADATA.forEach(::remove)
            put("name", name)
            put("filament_vendor", JSONArray().put(vendor))
            put("filament_type", JSONArray().put(type))
        }
        val identity = "$sourceLabel\n$relativePath\n${normalized}\n${legacyInherits.orEmpty()}\n${legacyCompatiblePrinters.joinToString("\u0000")}\n${legacyCompatiblePrints.joinToString("\u0000")}"
        val id = "legacy-" + sha256(identity).take(24)
        rows[id]?.let { return it }
        val color = firstString(normalized.opt("default_filament_colour"))?.takeIf { Regex("#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?").matches(it) }
        val imported = Imported(LEGACY_ANY_PROFILE, FilamentLibrary.Entry(id, name, vendor, type, ""), color,
            "$sourceLabel:$relativePath", "", normalized, source = "legacy-orca", legacyInherits = legacyInherits,
            legacyCompatiblePrinters = legacyCompatiblePrinters, legacyCompatiblePrints = legacyCompatiblePrints)
        rows[id] = imported
        save()
        return imported
    }

    private fun load() {
        if (!file.isFile || file.length() > 32L * 1024 * 1024) return
        runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("version") != 1) return@runCatching
            val a = root.optJSONArray("presets") ?: return@runCatching
            for (i in 0 until a.length().coerceAtMost(4096)) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { Regex("(?:3dfp|legacy)-[0-9a-f]{24}").matches(it) } ?: continue
                val key = o.optString("profileKey").takeIf { it.length in 1..200 } ?: continue
                val name = o.optString("name").takeIf { it.length in 1..300 } ?: continue
                val vendor = o.optString("vendor").take(200)
                val type = o.optString("type").takeIf { it.length in 1..64 } ?: continue
                val family = o.optString("family").take(200)
                val profile = o.optJSONObject("profile") ?: continue
                rows[id] = Imported(key, FilamentLibrary.Entry(id, name, vendor, type, family),
                    o.optString("colorHex").takeIf(String::isNotBlank), o.optString("sourceId").take(300),
                    o.optString("website").take(2048), profile, o.optString("source", if (id.startsWith("3dfp-")) "3dfp" else "legacy-orca"),
                    o.optString("legacyInherits").takeIf(String::isNotBlank), strings(o.opt("legacyCompatiblePrinters")),
                    strings(o.opt("legacyCompatiblePrints")))
            }
        }
    }

    private fun save() {
        val a = JSONArray(rows.values.map { row -> JSONObject()
            .put("profileKey", row.profileKey).put("id", row.entry.id).put("name", row.entry.name)
            .put("vendor", row.entry.vendor).put("type", row.entry.type).put("family", row.entry.family)
            .putOpt("colorHex", row.colorHex).put("sourceId", row.sourceId).put("website", row.website)
            .put("source", row.source).put("profile", row.profile).putOpt("legacyInherits", row.legacyInherits)
            .put("legacyCompatiblePrinters", JSONArray(row.legacyCompatiblePrinters))
            .put("legacyCompatiblePrints", JSONArray(row.legacyCompatiblePrints)) })
        PrinterStore.atomicWrite(file, JSONObject().put("version", 1).put("presets", a).toString(2), private = false)
    }

    private fun legacyBase(profileId: String, row: Imported): JSONObject? {
        val base = FilamentLibrary.bestNativeBundledFor(profileId, null, row.entry.type)
            ?: FilamentLibrary.bestNativeBundledFor(profileId, "Generic", row.entry.type)
            ?: return null
        return FilamentLibrary.bundledProfileJson(profileId, base.id)
    }

    private fun legacyCompatible(profileId: String, row: Imported): Boolean {
        if (row.legacyCompatiblePrinters.isEmpty()) return true
        val names = PrinterLibrary.of(profileId)?.machines?.map { it.name }?.toSet().orEmpty()
        return row.legacyCompatiblePrinters.any { it in names }
    }

    private fun strings(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).trim().takeIf(String::isNotBlank) }
        is String -> listOf(value.trim()).filter(String::isNotBlank)
        else -> emptyList()
    }

    private fun firstString(value: Any?): String? = when (value) {
        is JSONArray -> value.optString(0).takeIf(String::isNotBlank)
        is String -> value.takeIf(String::isNotBlank)
        else -> null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val LEGACY_ANY_PROFILE = "*legacy-compatible*"
        private val LEGACY_METADATA = setOf("inherits", "compatible_printers", "compatible_prints", "setting_id", "filament_id")
    }
}
