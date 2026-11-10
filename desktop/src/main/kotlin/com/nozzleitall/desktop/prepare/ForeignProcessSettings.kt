package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Project3mf
import net.jamesjennison.klippercompanion.SettingsCatalog
import net.jamesjennison.klippercompanion.SettingType
import org.json.JSONException
import org.json.JSONObject

/** Imports only supported scalar Process settings from the Orca/Bambu project configuration. */
internal object ForeignProcessSettings {
    private const val entry = "Metadata/project_settings.config"
    private const val maxConfigBytes = 8 * 1024 * 1024

    // Machine selection, filament/tool mappings, motion, G-code, and settings that change print order
    // require deliberate choices in NIA. Retain their original metadata without applying it.
    internal val supported = linkedMapOf(
        "layer_height" to "Layer height",
        "initial_layer_print_height" to "First layer height",
        "wall_loops" to "Wall count",
        "top_shell_layers" to "Top layers",
        "bottom_shell_layers" to "Bottom layers",
        "sparse_infill_density" to "Infill density",
        "sparse_infill_pattern" to "Infill pattern",
        "enable_support" to "Enable support",
        "support_type" to "Support type",
        "support_style" to "Support style",
        "support_threshold_angle" to "Support overhang angle",
        "support_on_build_plate_only" to "Supports only from the bed",
    )

    data class Import(val values: Map<String, String>, val notice: String)

    fun read(project: Project3mf, nozzleDiameter: Double?): Import? {
        if (project.manifest != null || project.manifestProblem != null) return null
        val bytes = project.passthrough[entry] ?: return null
        if (bytes.size > maxConfigBytes)
            return Import(emptyMap(), "The embedded slicer settings exceed the import limit. The original settings were preserved.")
        val data = try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (_: JSONException) {
            return Import(emptyMap(), "The embedded slicer settings could not be read. The original settings were preserved.")
        }
        val values = linkedMapOf<String, String>()
        val invalid = mutableListOf<String>()
        for ((key, label) in supported) {
            if (!data.has(key)) continue
            val raw = when (val value = data.opt(key)) {
                is String, is Number, is Boolean -> value.toString()
                else -> null
            }
            val accepted = raw?.let { validate(key, it, nozzleDiameter) }
            if (accepted == null) invalid += label else values[key] = accepted
        }
        val unhandled = data.keySet().count { it !in supported }
        val notice = buildString {
            append("Embedded slicer settings: ")
            if (values.isEmpty()) append("no Process settings applied.")
            else append("applied ${values.keys.joinToString { supported.getValue(it) }}.")
            if (invalid.isNotEmpty()) append(" Skipped unsupported values for ${invalid.joinToString()}.")
            if (unhandled > 0) append(" Preserved $unhandled other entries without applying them.")
            append(" Review the printer, nozzle, and Process before slicing.")
        }
        return Import(values, notice)
    }

    private fun validate(key: String, raw: String, nozzleDiameter: Double?): String? {
        if (raw.length > 128 || raw.any(Char::isISOControl)) return null
        val def = SettingsCatalog.get(key)?.takeIf { it.scope == "process" && !it.vector && it.type != SettingType.Text }
            ?: return null
        val value = SettingsCatalog.validate(def, raw) ?: return null
        if (key == "layer_height" || key == "initial_layer_print_height") {
            val diameter = nozzleDiameter?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            val height = value.toDoubleOrNull() ?: return null
            if (height < 0.04 || height > diameter) return null
        }
        return value
    }
}
