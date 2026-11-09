package com.nozzleitall.desktop

import com.nozzleitall.desktop.history.JobRecord
import com.nozzleitall.desktop.jobs.LocalJobStore
import com.nozzleitall.desktop.prepare.GuidanceFailureKind
import com.nozzleitall.desktop.prepare.ModelingOps
import com.nozzleitall.desktop.prepare.PreflightGuidance
import com.nozzleitall.desktop.prepare.PrepItem
import com.nozzleitall.desktop.prepare.SliceState
import com.nozzleitall.desktop.settings.ProfileValues
import com.nozzleitall.desktop.settings.SettingsCatalog
import com.nozzleitall.project.Mesh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PreflightGuidanceTest {
    @Test fun localGuidanceReceivesOnlyAllowedInputsAndDoesNotApplyChanges() {
        val root = Files.createTempDirectory("nia-preflight-fixture").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
            val prepare = app.prepare
            val mesh = Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2))
            prepare.items += PrepItem(1, "private-fixture-object", mesh, 80f, 80f)
            prepare.overrides["layer_height"] = "0.2"
            prepare.overrides["outer_wall_speed"] = "invalid-free-text"
            prepare.overrides["machine_start_gcode"] = "unrelated-gcode-fixture"
            val profile = ProfileValues(emptyMap())
            val snapshot = PreflightGuidance.capture(prepare, SettingsCatalog.bundled, profile)
            val networkNamespace = Files.readSymbolicLink(File("/proc/self/ns/net").toPath()).toString()
            val program = File(root, "fixture-program").apply {
                writeText("""#!/bin/sh
set -eu
test ! -e /home
test ! -e /etc
test ! -e "FIXTURE_DIRECTORY"
test "$(readlink /proc/self/ns/net)" != "PARENT_NETWORK"
request=$(cat)
printf '%s' "DOLLARrequest" | grep -Fq '"objects":1'
if printf '%s' "DOLLARrequest" | grep -Eq 'private-fixture-object|unrelated-gcode-fixture|invalid-free-text'; then exit 5; fi
printf '%s' '{"summary":"Review the open edges","suggestions":[{"key":"layer_height","value":"0.16","reason":"Try a finer layer"}]}'
""".replace("FIXTURE_DIRECTORY", root.absolutePath).replace("PARENT_NETWORK", networkNamespace)
                    .replace("DOLLARrequest", '$'.toString() + "request"))
                check(setExecutable(true, true))
            }

            assertEquals(1, snapshot.payload().getInt("version"))
            val draft = PreflightGuidance.analyze(program, snapshot)
            assertEquals("Review the open edges", draft.summary)
            assertEquals("layer_height", draft.suggestions.single().setting.definition.key)
            assertEquals("0.16", draft.suggestions.single().proposed)
            assertEquals("0.2", prepare.setting("layer_height", profile))
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test fun diagnosisUsesTypedFailureWithoutRawHistoryOrSettings() {
        val record = JobRecord("private-job-id", "private-printer-id", "private-printer-name", "private-model.3mf",
            1000, 9000, "failed", 8.0, 12)
        val snapshot = requireNotNull(PreflightGuidance.jobFailure(record, LocalJobStore.Assessment.PARTIAL_SUCCESS))
        val request = snapshot.payload()
        val failure = request.getJSONObject("failure")
        assertEquals(2, request.getInt("version"))
        assertEquals("failure_diagnosis", request.getString("kind"))
        assertEquals("PRINT", failure.getString("kind"))
        assertTrue(failure.getBoolean("machineReportedFailure"))
        assertEquals("PARTIAL_SUCCESS", failure.getString("physicalAssessment"))
        assertEquals(8.0, failure.getDouble("elapsedSeconds"), 0.0)
        assertEquals(12, failure.getInt("totalLayers"))
        assertFalse(request.has("settings"))
        assertFalse(request.has("plate"))
        assertFalse(request.toString().contains("private-"))
        assertNull(PreflightGuidance.jobFailure(record.copy(outcome = "finished"), LocalJobStore.Assessment.SUCCESS))
        assertEquals("OUT_OF_BOUNDS", PreflightGuidance.sliceFailure(GuidanceFailureKind.OUT_OF_BOUNDS)
            .payload().getJSONObject("failure").getString("kind"))
        val failed = SliceState.Failed("A private model is off the plate", GuidanceFailureKind.OUT_OF_BOUNDS)
        val retried = failed.copy()
        assertEquals(failed, retried)
        assertNotSame(failed.diagnosisEvent, retried.diagnosisEvent)

        val root = Files.createTempDirectory("nia-diagnosis-fixture").toFile()
        try {
            val program = File(root, "diagnose").apply {
                writeText("""#!/bin/sh
set -eu
grep -Fq '"kind":"PRINT"'
printf '%s' '{"summary":"Review the material feed before another print.","suggestions":[]}'
""")
                check(setExecutable(true, true))
            }
            val draft = PreflightGuidance.analyze(program, snapshot)
            assertEquals("Review the material feed before another print.", draft.summary)
            assertTrue(draft.suggestions.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun localProgramThatNeverReadsInputStillTimesOut() {
        val start = System.nanoTime()
        val failure = runCatching {
            ModelingOps.runProcess(ProcessBuilder("/bin/sh", "-c", "sleep 30"), timeoutSeconds = 1,
                input = "x".repeat(4096), responseLimit = 1024)
        }.exceptionOrNull()
        assertTrue(failure is ModelingOps.EngineError)
        assertTrue(failure?.message.orEmpty().contains("took too long"))
        assertTrue(System.nanoTime() - start < 10_000_000_000L)
    }

    @Test fun guidanceRejectsUnknownSettingsAndInvalidValues() {
        val definition = SettingsCatalog.bundled.byKey.getValue("layer_height")
        val snapshot = PreflightGuidance.Snapshot(1, 0, 0, true, listOf(PreflightGuidance.Setting(definition, "0.2")))
        fun answer(key: String, value: String) = JSONObject(
            """{"summary":"Review","suggestions":[{"key":"$key","value":"$value","reason":"Fixture"}]}"""
        )
        assertTrue(runCatching { PreflightGuidance.parse(answer("machine_start_gcode", "0.1"), snapshot) }.isFailure)
        assertTrue(runCatching { PreflightGuidance.parse(answer("layer_height", "NaN"), snapshot) }.isFailure)
        assertTrue(runCatching { PreflightGuidance.parse(JSONObject("""{"summary":null,"suggestions":[]}"""), snapshot) }.isFailure)
        assertTrue(runCatching { PreflightGuidance.parse(answer("layer_height", "20%"), snapshot) }.isFailure)
        assertEquals("0.16", PreflightGuidance.parse(answer("layer_height", "0.16"), snapshot).suggestions.single().proposed)
    }
}
