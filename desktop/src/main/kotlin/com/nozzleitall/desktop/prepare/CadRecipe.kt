package com.nozzleitall.desktop.prepare

import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** The dimensions of a centered sketch, an extrusion, and an optional centered through hole.
 * A circle uses [widthMm] as its diameter. Its [heightMm] is an unused default, excluded from the file.
 */
internal data class CadRecipe(
    val circular: Boolean = false,
    val widthMm: Double = 20.0,
    val heightMm: Double = 20.0,
    val depthMm: Double = 10.0,
    val holeMm: Double = 0.0,
) {
    init {
        require(widthMm.isFinite() && widthMm > 0 && heightMm.isFinite() && heightMm > 0 &&
            depthMm.isFinite() && depthMm > 0 && holeMm.isFinite() && holeMm >= 0 &&
            holeMm < if (circular) widthMm else minOf(widthMm, heightMm)) {
            "Use positive dimensions and keep the through hole smaller than the sketch."
        }
    }

    fun parameters(): JSONObject = JSONObject().put("shape", if (circular) "circle" else "rectangle")
        .put("depthMm", depthMm).apply {
            if (circular) put("diameterMm", widthMm) else put("widthMm", widthMm).put("heightMm", heightMm)
            if (holeMm > 0) put("holeDiameterMm", holeMm)
        }

    companion object {
        fun read(file: File): CadRecipe {
            val bytes = file.inputStream().use { it.readNBytes(4097) }
            require(bytes.size <= 4096) { "The CAD recipe is too large." }
            return try {
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                require(json.opt("version") == 1) { "This CAD recipe version is unsupported." }
                val circular = when (json.getString("shape")) {
                    "circle" -> true
                    "rectangle" -> false
                    else -> throw IllegalArgumentException("This CAD sketch shape is unsupported.")
                }
                CadRecipe(circular, json.getDouble(if (circular) "diameterMm" else "widthMm"),
                    if (circular) 20.0 else json.getDouble("heightMm"), json.getDouble("depthMm"),
                    if (json.has("holeDiameterMm")) json.getDouble("holeDiameterMm") else 0.0)
            } catch (_: JSONException) {
                throw IllegalArgumentException("The CAD recipe is invalid or missing a dimension.")
            }
        }
    }
}
