package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Project3mf
import com.nozzleitall.project.ProjectManifest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs

internal data class LayerAction(val id: String, val layer: Int, val z: Double, val kind: Kind, val text: String = "") {
    enum class Kind(val label: String) { PAUSE("Pause print"), FILAMENT("Change filament"), CUSTOM("Custom G-code") }

    init {
        require(UUID.fromString(id).toString() == id) { "Invalid layer action identity." }
        require(layer > 0 && z.isFinite() && z > 0 && z <= 10000) { "Invalid layer action position." }
        require(if (kind == Kind.CUSTOM) validCommands(text) else text.isEmpty()) { "Invalid layer action commands." }
    }

    companion object {
        const val MAX_ACTIONS = 100
        private const val KEY = "nia_layer_actions"
        private const val MARKER = ";NIA_LAYER_ACTION "

        fun read(plate: ProjectManifest.PlateEntry): List<LayerAction> {
            if (!plate.unknown.has(KEY)) return emptyList()
            val raw = plate.unknown.getJSONArray(KEY)
            require(raw.length() <= MAX_ACTIONS) { "A plate contains too many layer actions." }
            return (0 until raw.length()).map { i ->
                val item = raw.getJSONObject(i)
                LayerAction(item.getString("id"), item.getInt("layer"), item.getDouble("z"),
                    Kind.valueOf(item.getString("kind")), item.optString("text"))
            }.also { actions ->
                require(actions.map { it.id }.distinct().size == actions.size &&
                    actions.map { it.layer }.distinct().size == actions.size) { "A plate contains duplicate layer actions." }
            }
        }

        fun write(unknown: JSONObject, actions: List<LayerAction>): JSONObject = JSONObject(unknown.toString()).apply {
            if (actions.isEmpty()) remove(KEY) else put(KEY, JSONArray(actions.map { action ->
                JSONObject().put("id", action.id).put("layer", action.layer).put("z", action.z)
                    .put("kind", action.kind.name).put("text", action.text)
            }))
        }

        fun validCommands(text: String): Boolean =
            text.length in 1..2048 && text.any { !it.isWhitespace() } &&
                text.all { it == '\n' || it in ' '..'~' } &&
                "NIA_LAYER_ACTION" !in text && text.lineSequence().none {
                    val line = it.trimStart()
                    line.startsWith(";LAYER_CHANGE") || line.startsWith("; CHANGE_LAYER") ||
                        line.startsWith(";LAYER:") || line.startsWith(";Z:")
                }

        fun command(kind: Kind, machine: JSONObject, text: String): String? {
            val pause = machine.optString("machine_pause_gcode").trim()
            val code = when (kind) {
                Kind.PAUSE -> pause
                Kind.FILAMENT -> pause.takeIf { it == "M600" }.orEmpty()
                Kind.CUSTOM -> text.trim()
            }
            return code.takeIf(::validCommands)
        }

        fun fromSlice(project: Project3mf): List<LayerAction> {
            val plates = project.manifest?.plates.orEmpty()
            val actions = plates.flatMap(::read)
            require(actions.isEmpty() || plates.size == 1) { "A slice with layer actions must name exactly one plate." }
            return actions
        }

        /** The slicer may otherwise put an event on the nearest layer after a geometry or profile edit. */
        fun verifyExport(file: File, actions: List<LayerAction>): String? {
            if (actions.isEmpty()) return null
            val expected = actions.associateBy { it.id }
            val seen = mutableSetOf<String>()
            var layer = 0
            var z: Double? = null
            file.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith(";LAYER_CHANGE") || line.startsWith("; CHANGE_LAYER") || line.startsWith(";LAYER:")) {
                        layer++
                        z = null
                    } else if (line.startsWith(";Z:")) z = line.substringAfter(':').trim().toDoubleOrNull()
                    else if (line.startsWith(MARKER)) {
                        val id = line.removePrefix(MARKER).trim()
                        val action = expected[id] ?: return "The slice contains an unexpected layer action."
                        if (!seen.add(id) || action.layer != layer || z?.let { abs(it - action.z) <= 0.0001 } != true)
                            return "Layer action at layer ${action.layer} moved. Review its position and slice again."
                    }
                }
            }
            val missing = actions.firstOrNull { it.id !in seen } ?: return null
            return "The slice did not include the layer action at layer ${missing.layer}. Review its position and slice again."
        }
    }
}
