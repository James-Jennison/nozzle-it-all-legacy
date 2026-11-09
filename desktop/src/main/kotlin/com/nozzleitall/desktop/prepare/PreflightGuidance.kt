package com.nozzleitall.desktop.prepare

import com.nozzleitall.desktop.history.JobRecord
import com.nozzleitall.desktop.jobs.LocalJobStore
import com.nozzleitall.desktop.settings.ProfileValues
import com.nozzleitall.desktop.settings.SettingDef
import com.nozzleitall.desktop.settings.SettingsCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class GuidanceFailureKind(val label: String) {
    PRESET("Preset unavailable"),
    ENGINE_UNAVAILABLE("Slicing engine unavailable"),
    EMPTY_PLATE("No model on the active plate"),
    OUT_OF_BOUNDS("Model outside the printable area"),
    INPUTS("Slice inputs could not be prepared"),
    CALIBRATION("Calibration instructions could not be applied"),
    SLICE("Slicing did not complete"),
    PRINT("A print failure was recorded"),
}

/** A read-only request for an explicitly selected local analysis executable. */
internal object PreflightGuidance {
    private val keys = listOf("layer_height", "wall_loops", "sparse_infill_density", "enable_support", "outer_wall_speed")
    private val numericValue = Regex("-?[0-9]+(?:\\.[0-9]+)?%?")

    data class Setting(val definition: SettingDef, val value: String)
    data class Failure(
        val kind: GuidanceFailureKind,
        val machineReportedFailure: Boolean? = null,
        val physicalAssessment: LocalJobStore.Assessment? = null,
        val elapsedSeconds: Double? = null,
        val totalLayers: Int? = null,
    ) {
        fun payload() = JSONObject().put("kind", kind.name).put("label", kind.label).apply {
            machineReportedFailure?.let { put("machineReportedFailure", it) }
            physicalAssessment?.let { put("physicalAssessment", it.name) }
            elapsedSeconds?.let { put("elapsedSeconds", it) }
            totalLayers?.let { put("totalLayers", it) }
        }
    }

    data class Snapshot(
        val objects: Int,
        val offPlate: Int,
        val openEdges: Long,
        val machineConfigured: Boolean,
        val settings: List<Setting>,
        val failure: Failure? = null,
    ) {
        fun payload(): JSONObject {
            val request = JSONObject().put("version", if (failure == null) 1 else 2)
            if (failure != null) return request
                .put("kind", "failure_diagnosis")
                .put("task", "Explain the recorded failure. Distinguish observations from possible causes and give a safe next inspection. Avoid definitive physical diagnoses and printer commands. Return a JSON object with summary and an empty suggestions list.")
                .put("failure", failure.payload())
            return request
                .put("task", "Explain preflight concerns and optional process-setting improvements. Return a JSON object with summary and suggestions. Each suggestion has key, value and reason. Use only supplied keys. Do not imply that a print is ready.")
                .put("plate", JSONObject().put("objects", objects).put("offPlate", offPlate)
                    .put("openEdges", openEdges).put("machineConfigured", machineConfigured))
                .put("settings", JSONArray(settings.map { (def, value) ->
                    JSONObject().put("key", def.key).put("label", def.label).put("units", def.units.orEmpty()).put("value", value)
                }))
        }
    }

    data class Suggestion(val setting: Setting, val proposed: String, val reason: String)
    data class Draft(val summary: String, val suggestions: List<Suggestion>)

    fun capture(prepare: PrepareState, catalog: SettingsCatalog, profile: ProfileValues): Snapshot {
        val plate = prepare.plateItems()
        val settings = keys.mapNotNull { key ->
            val def = catalog.byKey[key] ?: return@mapNotNull null
            val value = prepare.setting(key, profile) ?: return@mapNotNull null
            if (valid(def, value)) Setting(def, value) else null
        }
        return Snapshot(plate.size, prepare.outOfBounds().size, plate.sumOf { it.openEdges.toLong() },
            prepare.hasSlicingSetup, settings)
    }

    fun sliceFailure(kind: GuidanceFailureKind) = failureSnapshot(Failure(kind))

    fun jobFailure(record: JobRecord, assessment: LocalJobStore.Assessment?): Snapshot? {
        val reported = record.outcome == "failed"
        if (!reported && assessment != LocalJobStore.Assessment.FAILED &&
            assessment != LocalJobStore.Assessment.PARTIAL_SUCCESS) return null
        return failureSnapshot(Failure(GuidanceFailureKind.PRINT, reported, assessment,
            record.elapsedSeconds?.takeIf { it.isFinite() && it >= 0 },
            record.totalLayers?.takeIf { it >= 0 }))
    }

    private fun failureSnapshot(failure: Failure) = Snapshot(0, 0, 0, false, emptyList(), failure)

    private fun valid(def: SettingDef, value: String): Boolean =
        value.length in 1..24 && numericValue.matches(value) &&
            (!value.endsWith('%') || def.baseType == "percent" || def.baseType == "float_or_percent") &&
            value.removeSuffix("%").toDoubleOrNull()?.isFinite() == true && def.problem(value) == null

    fun analyze(program: File, snapshot: Snapshot): Draft {
        val executable = program.canonicalFile
        require(executable == program && executable.isFile && executable.canExecute()) {
            "Choose an executable local guidance program."
        }
        val sandbox = File("/usr/bin/bwrap")
        require(sandbox.canExecute()) { "Local guidance requires the Linux bubblewrap sandbox." }
        val builder = ProcessBuilder(
            sandbox.absolutePath, "--unshare-all", "--die-with-parent", "--new-session", "--cap-drop", "ALL",
            "--ro-bind", "/usr", "/usr", "--symlink", "usr/bin", "/bin", "--symlink", "usr/lib", "/lib",
            "--symlink", "usr/lib64", "/lib64", "--tmpfs", "/tmp", "--proc", "/proc", "--dev", "/dev",
            "--ro-bind", executable.absolutePath, "/guidance-program", "--chdir", "/tmp",
            "--clearenv", "--setenv", "PATH", "/usr/bin:/bin", "--setenv", "TMPDIR", "/tmp", "/guidance-program",
        )
        builder.environment().clear()
        val answer = ModelingOps.runProcess(builder, timeoutSeconds = 45, input = snapshot.payload().toString(),
            responseLimit = 16_384)
        return parse(answer, snapshot)
    }

    internal fun parse(answer: JSONObject, snapshot: Snapshot): Draft {
        val summary = (answer.opt("summary") as? String).orEmpty().trim()
        require(summary.length in 1..500) { "The guidance program returned an invalid summary." }
        val items = answer.optJSONArray("suggestions")
            ?: throw IllegalArgumentException("The guidance program returned no suggestions list.")
        require(items.length() <= 3) { "The guidance program returned too many suggestions." }
        val allowed = snapshot.settings.associateBy { it.definition.key }
        val seen = HashSet<String>()
        val suggestions = (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            val key = (item.opt("key") as? String).orEmpty()
            val setting = allowed[key] ?: throw IllegalArgumentException("The guidance program suggested an unknown setting.")
            val value = (item.opt("value") as? String).orEmpty()
            val reason = (item.opt("reason") as? String).orEmpty().trim()
            require(seen.add(key) && valid(setting.definition, value) && reason.length in 1..300) {
                "The guidance program returned an invalid setting suggestion."
            }
            Suggestion(setting, value, reason)
        }
        return Draft(summary, suggestions)
    }
}
