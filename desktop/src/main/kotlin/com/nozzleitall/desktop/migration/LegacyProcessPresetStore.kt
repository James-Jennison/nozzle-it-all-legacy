package com.nozzleitall.desktop.migration

import com.nozzleitall.desktop.PrinterStore
import com.nozzleitall.desktop.prepare.PrinterLibrary
import com.nozzleitall.desktop.prepare.ProfileCatalog
import net.jamesjennison.klippercompanion.SettingsCatalog
import net.jamesjennison.klippercompanion.SettingType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Sanitized Orca-family process presets resolved onto an exact bundled NIA machine and process. */
class LegacyProcessPresetStore(private val file: File) : PrinterLibrary.CustomProcesses {
    data class Imported(val imported: Int, val unresolved: List<String>)

    private fun records(): List<PrinterLibrary.CustomProcess> = runCatching {
        val root = JSONObject(file.readText())
        val rows = root.optJSONArray("presets") ?: JSONArray()
        (0 until rows.length()).mapNotNull { index -> decode(rows.optJSONObject(index) ?: return@mapNotNull null) }
    }.getOrDefault(emptyList())

    override fun entries(profileId: String, machineId: String): List<PrinterLibrary.CustomProcess> =
        records().filter { it.profileId == profileId && it.machineId == machineId }.sortedBy { it.name.lowercase() }

    override fun find(profileId: String, id: String): PrinterLibrary.CustomProcess? =
        records().firstOrNull { it.profileId == profileId && it.id == id }

    fun import(sourceLabel: String, profiles: List<LegacySettingsMigration.Profile>): Imported {
        val machines = profiles.filter { it.category == "machine" }.associateBy { it.json.optString("name") }
        val existing = records().associateByTo(linkedMapOf()) { it.id }
        val unresolved = mutableListOf<String>()
        var imported = 0
        profiles.filter { it.category == "process" }.forEach { profile ->
            val process = profile.json
            val compatible = strings(process.opt("compatible_printers"))
            val customMatches = compatible.mapNotNull(machines::get).distinctBy { it.relativePath }
            if (customMatches.size > 1) {
                unresolved += "${profile.relativePath} — compatible printers identify multiple custom machines"
                return@forEach
            }
            val customMachine = customMatches.singleOrNull()
            val customMachineName = customMachine?.json?.optString("name").orEmpty()
            val baseMachineName = customMachine?.json?.optString("inherits").orEmpty()
            val directNames = compatible.filter { it != customMachineName }
            if (customMachine != null && directNames.isNotEmpty()) {
                unresolved += "${profile.relativePath} — custom and direct compatible printers conflict"
                return@forEach
            }
            val targetNames = if (customMachine != null) setOf(baseMachineName).filter(String::isNotBlank).toSet() else directNames.toSet()
            val candidates = ProfileCatalog.all.asSequence().flatMap { info ->
                val library = PrinterLibrary.of(info.id)
                if (library == null) emptySequence() else library.machines.asSequence()
                    .filter { machine -> machine.name in targetNames }.map { machine -> Triple(info.id, library, machine) }
            }.toList()
            val selected = candidates.singleOrNull()
            if (selected == null) {
                unresolved += "${profile.relativePath} — compatible printer does not resolve to one bundled NIA machine"
                return@forEach
            }
            val (profileId, library, machine) = selected
            val inherited = process.optString("inherits")
            val base = machine.processes.mapNotNull { id -> library.processes[id]?.let { id to it } }
                .singleOrNull { (id, item) -> id == inherited || item.name == inherited }
            if (base == null) {
                unresolved += "${profile.relativePath} — inherited process does not resolve within ${machine.name}"
                return@forEach
            }
            val processOverrides = overrides(process)
            if (processOverrides.length() == 0) {
                unresolved += "${profile.relativePath} — no process overrides remain after compatibility metadata is removed"
                return@forEach
            }
            val rawMachineOverrides = customMachine?.json?.let(::overrides) ?: JSONObject()
            val unsafeMachineKeys = rawMachineOverrides.keySet() - SAFE_MACHINE_OVERRIDE_KEYS
            if (unsafeMachineKeys.isNotEmpty()) {
                unresolved += "${profile.relativePath} — machine overrides require review: ${unsafeMachineKeys.sorted().joinToString(", ")}"
                return@forEach
            }
            val machineOverrides = JSONObject().also { out -> rawMachineOverrides.keySet().forEach { key -> out.put(key, rawMachineOverrides.opt(key)) } }
            val identity = "$profileId\n${machine.id}\n${base.first}\n${profile.relativePath}\n${process.toString()}"
            val id = "legacy-" + sha256(identity).take(24)
            val name = process.optString("name").ifBlank { File(profile.relativePath).nameWithoutExtension }
            existing[id] = PrinterLibrary.CustomProcess(id, profileId, machine.id, base.first, name,
                process.optString("layer_height").ifBlank { base.second.layerHeight }, sourceLabel, processOverrides, machineOverrides)
            imported++
        }
        if (imported > 0) save(existing.values.toList())
        return Imported(imported, unresolved.sorted())
    }

    internal data class LinkedProcess(val name: String, val applied: Int, val skipped: Int)

    internal fun link(
        sourceLabel: String, profile: LegacySettingsMigration.Profile, profileId: String, machineId: String, baseProcessId: String,
    ): LinkedProcess {
        require(profile.category == "process") { "Select an archived Process preset." }
        val library = requireNotNull(PrinterLibrary.of(profileId)) { "Select a printer with a bundled Process library in Prepare." }
        val machine = requireNotNull(library.machines.singleOrNull { it.id == machineId }) { "The selected nozzle variant is unavailable." }
        require(baseProcessId in machine.processes) { "Choose a bundled Process for this printer and nozzle." }
        val base = library.processes.getValue(baseProcessId)
        val source = overrides(profile.json)
        val values = JSONObject()
        for (key in source.keySet()) {
            val def = SettingsCatalog.get(key) ?: continue
            if (def.scope != "process" || def.vector || def.type == SettingType.Text || key == "print_sequence") continue
            val raw = source.opt(key)
            if (raw !is String && raw !is Number && raw !is Boolean) continue
            val text = raw.toString()
            if (text.length > 128 || text.any(Char::isISOControl)) continue
            val value = SettingsCatalog.validate(def, text) ?: continue
            if (key == "layer_height" || key == "initial_layer_print_height") {
                val nozzle = machine.nozzle.toDoubleOrNull() ?: continue
                val height = value.toDoubleOrNull() ?: continue
                if (height !in 0.04..nozzle) continue
            }
            values.put(key, value)
        }
        require(values.length() > 0) { "No compatible Process settings remain for the selected printer and nozzle." }
        val name = profile.json.optString("name").filterNot(Char::isISOControl).trim().take(100)
            .ifBlank { "Linked Process preset" }
        val identity = "$profileId\n$machineId\n$baseProcessId\n${profile.relativePath}\n${profile.json}"
        val id = "legacy-" + sha256(identity).take(24)
        val records = records().associateByTo(linkedMapOf()) { it.id }
        records[id] = PrinterLibrary.CustomProcess(id, profileId, machineId, baseProcessId, name,
            values.optString("layer_height").ifBlank { base.layerHeight }, sourceLabel, values, JSONObject())
        save(records.values.toList())
        return LinkedProcess(name, values.length(), source.length() - values.length())
    }

    private fun save(rows: List<PrinterLibrary.CustomProcess>) {
        val json = JSONObject().put("version", 1).put("presets", JSONArray(rows.sortedBy { it.id }.map(::encode)))
        PrinterStore.atomicWrite(file, json.toString(2), private = false)
    }

    private fun encode(row: PrinterLibrary.CustomProcess) = JSONObject()
        .put("id", row.id).put("profileId", row.profileId).put("machineId", row.machineId)
        .put("baseProcessId", row.baseProcessId).put("name", row.name).put("layerHeight", row.layerHeight)
        .put("source", row.source).put("process", row.processOverrides).put("machine", row.machineOverrides)

    private fun decode(row: JSONObject): PrinterLibrary.CustomProcess? = runCatching {
        val id = row.getString("id"); require(id.matches(Regex("legacy-[0-9a-f]{24}")))
        PrinterLibrary.CustomProcess(id, row.getString("profileId"), row.getString("machineId"),
            row.getString("baseProcessId"), row.getString("name"), row.optString("layerHeight"),
            row.optString("source"), row.getJSONObject("process"), row.optJSONObject("machine") ?: JSONObject())
    }.getOrNull()

    private fun overrides(source: JSONObject): JSONObject = JSONObject().also { out ->
        source.keySet().filterNot { it in METADATA }.forEach { key -> out.put(key, source.opt(key)) }
    }

    private fun strings(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).trim().takeIf(String::isNotEmpty) }
        is String -> listOf(value.trim()).filter(String::isNotEmpty)
        else -> emptyList()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private val METADATA = setOf("name", "inherits", "compatible_printers", "compatible_printers_condition", "from",
            "instantiation", "setting_id", "type", "version", "description")
        private val SAFE_MACHINE_OVERRIDE_KEYS = setOf("retraction_length", "retraction_speed", "deretraction_speed",
            "retract_restart_extra", "retract_before_wipe", "retract_when_changing_layer", "wipe", "wipe_distance")
    }
}
