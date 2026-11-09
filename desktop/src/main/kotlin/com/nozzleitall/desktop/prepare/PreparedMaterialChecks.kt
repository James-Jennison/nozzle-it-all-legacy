package com.nozzleitall.desktop.prepare

import com.nozzleitall.printer.Capabilities
import com.nozzleitall.printer.Material
import com.nozzleitall.printer.PrinterStatus
import com.nozzleitall.printer.Toolhead
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** The physical inputs used to prepare a file. Stored with the queued copy, not read back from the editor. */
data class PreparedMaterialPlan(val tools: List<Tool>, val incomplete: Boolean, val invalid: Boolean = false) {
    data class Tool(val index: Int, val nozzle: Double?, val type: String?, val tagId: String?)

    fun toJson(): JSONObject = JSONObject().put("incomplete", incomplete).put("invalid", invalid)
        .put("tools", JSONArray(tools.map { tool ->
            JSONObject().put("index", tool.index).put("nozzle", tool.nozzle).put("type", tool.type).put("tagId", tool.tagId)
        }))

    companion object {
        fun fromJson(json: JSONObject): PreparedMaterialPlan {
            val array = json.getJSONArray("tools")
            require(array.length() <= 256) { "Too many prepared tools." }
            val tools = (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Tool(obj.getInt("index").also { require(it in 0..255) },
                    if (obj.has("nozzle")) obj.getDouble("nozzle").also { require(it.isFinite() && it > 0) } else null,
                    obj.optString("type").ifBlank { null }, obj.optString("tagId").ifBlank { null })
            }
            require(tools.map { it.index }.distinct().size == tools.size) { "Duplicate prepared tool." }
            return PreparedMaterialPlan(tools, json.getBoolean("incomplete"), json.getBoolean("invalid"))
        }
    }
}

internal data class PreparedMaterialCheck(val message: String, val blocks: Boolean)

internal fun materialPlanFor(done: SliceState.Done): PreparedMaterialPlan {
    val used = done.preview?.toolsUsed.orEmpty()
    var incomplete = used.isEmpty() || done.preview?.truncated == true
    var invalid = done.materials.any { it.slot <= 0 } ||
        done.materials.map { it.slot }.distinct().size != done.materials.size
    val tools = used.mapNotNull { tool ->
        val slot = done.materials.getOrNull(tool)
        if (slot == null) {
            incomplete = true
            return@mapNotNull null
        }
        val index = slot.toolhead ?: (slot.slot - 1)
        val nozzle = done.result.nozzleDiameters.getOrNull(tool)
        if (tool in done.result.nozzleDiameters.indices && nozzle == null) invalid = true
        if (index !in 0..255) {
            invalid = true
            return@mapNotNull null
        }
        PreparedMaterialPlan.Tool(index, nozzle, slot.type?.trim()?.takeIf { it.isNotBlank() },
            slot.spoolId?.takeIf { slot.fromTag })
    }
    if (tools.map { it.index }.distinct().size != tools.size) invalid = true
    return PreparedMaterialPlan(tools.distinctBy { it.index }, incomplete, invalid)
}

internal fun preparedMaterialChecks(
    plan: PreparedMaterialPlan?,
    status: PrinterStatus,
    capabilities: Capabilities,
): List<PreparedMaterialCheck> = buildList {
    if (plan?.invalid == true) {
        add(PreparedMaterialCheck("The slice contains an invalid nozzle size or material assignment. Correct the slicing setup and slice again.", true))
        return@buildList
    }
    if (plan == null || plan.incomplete)
        add(PreparedMaterialCheck("The G-code tool or material assignments could not all be verified. Check the prepared file.", false))
    if (plan?.tools.isNullOrEmpty()) return@buildList
    if (status.toolheads.isEmpty()) {
        add(PreparedMaterialCheck("The printer did not report toolheads or loaded materials. Check them before sending.", false))
        return@buildList
    }
    for (tool in requireNotNull(plan).tools) {
        val index = tool.index
        val head = status.toolheads.singleOrNull { it.index == index }
        if (head == null) {
            add(PreparedMaterialCheck("The printer did not report a unique toolhead or slot ${index + 1}.",
                capabilities.toolheadState))
            continue
        }
        val nozzle = tool.nozzle
        val reportedNozzle = head.nozzleDiameterMm?.takeIf { it.isFinite() && it > 0 }
        if (nozzle != null && reportedNozzle != null && abs(nozzle - reportedNozzle) > 0.01)
            add(PreparedMaterialCheck("Toolhead ${index + 1} reports a ${reportedNozzle} mm nozzle. This slice used ${nozzle} mm.", true))
        else if (nozzle == null || reportedNozzle == null)
            add(PreparedMaterialCheck("Nozzle size for toolhead ${index + 1} could not be verified.", false))
        val material = head.material
        val loadedType = material?.type?.trim()
        if (capabilities.materialState && !head.loaded)
            add(PreparedMaterialCheck("The printer reports no filament in slot ${index + 1}.", true))
        else if (tool.type.isNullOrBlank() || loadedType.isNullOrBlank())
            add(PreparedMaterialCheck("Filament type for slot ${index + 1} could not be verified.", false))
        else if (!tool.type.equals(loadedType, ignoreCase = true))
            add(PreparedMaterialCheck("Slot ${index + 1} reports ${loadedType}. This slice used ${tool.type}.", true))
        if (tool.tagId != null && material?.fromTag == true && material.spoolId != null && tool.tagId != material.spoolId)
            add(PreparedMaterialCheck("The RFID spool in slot ${index + 1} differs from the spool used for this slice.", true))
    }
}

/** Exclude continuously changing temperatures from the compatibility observation. */
internal fun Toolhead.preflightObservation() = Toolhead(
    index = index, nozzleDiameterMm = nozzleDiameterMm, loaded = loaded,
    material = material?.let { Material(type = it.type, fromTag = it.fromTag, spoolId = it.spoolId) },
)
