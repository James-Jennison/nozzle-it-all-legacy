package com.nozzleitall.desktop

import com.nozzleitall.desktop.jobs.LocalJobStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class LocalJobStoreTest {
    @Test fun legacyHistoryImportIsIdempotentPreservesSourceAndSeparatesMachineFromPhysicalOutcome() {
        val dir = Files.createTempDirectory("nia-legacy-history").toFile()
        val source = File(dir, "history.json")
        val a = com.nozzleitall.desktop.history.JobRecord("old-1", "printer", "CC", "part.gcode", 100, 200, "finished", 10.0, 4)
        com.nozzleitall.desktop.history.JobHistoryStore(source).save(listOf(a))
        val original = source.readBytes()
        val store = LocalJobStore(File(dir, "jobs.sqlite"))
        assertEquals(1, store.importLegacyHistory(source))
        assertEquals(0, LocalJobStore(File(dir, "jobs.sqlite")).importLegacyHistory(source))
        assertArrayEquals(original, source.readBytes())
        val imported = store.importedLegacyRecords().single()
        assertEquals(a, imported.second)
        assertEquals(LocalJobStore.Origin.LEGACY_UNKNOWN, store.job(imported.first)!!.origin)
        val attempt = store.attempts(imported.first).single()
        assertEquals(LocalJobStore.MachineState.MACHINE_COMPLETED, store.machineEvents(attempt.id).single().state)
        assertTrue(store.outcomes(attempt.id).isEmpty())
        source.writeText(org.json.JSONArray(listOf(a.copy(id = "new-2"), a).map { it.toJson() }).toString())
        assertEquals(1, store.importLegacyHistory(source))
        assertEquals(2, store.importedLegacyRecords().size)
        source.writeText(org.json.JSONArray(listOf(a.copy(outcome = "failed"), a).map { it.toJson() }).toString())
        val conflictingSource = source.readBytes()
        assertThrows(Exception::class.java) { store.importLegacyHistory(source) }
        assertArrayEquals(conflictingSource, source.readBytes())
        assertEquals(2, store.importedLegacyRecords().size)
    }

    @Test fun observedIdentityCannotBeReusedForDifferentMachineEvidence() {
        val file = Files.createTempDirectory("nia-observed-id-conflict").resolve("jobs.sqlite").toFile()
        val store = LocalJobStore(file)
        val first = com.nozzleitall.desktop.history.JobRecord("observation", "p", "P", "part.gcode", 100, 200,
            "finished", null, null)
        val ids = store.recordCurrentObservation(first)
        assertEquals(ids, LocalJobStore(file).recordCurrentObservation(first))
        assertThrows(IllegalStateException::class.java) {
            store.recordCurrentObservation(first.copy(outcome = "failed"))
        }
        assertEquals(listOf(first), store.currentObservedRecords())
        assertEquals(1, store.machineEvents(ids.second).size)
    }

    @Test fun reprintLookupRequiresExactObservationAndRetainsOriginalJob() {
        val file = Files.createTempDirectory("nia-reprint-source").resolve("jobs.sqlite").toFile()
        val store = LocalJobStore(file)
        val digest = "a".repeat(64)
        val source = store.createJob(LocalJobStore.Origin.APP, artifactSha256 = digest)
        store.bindQueuedStart("p", source.id, "part.gcode", 100)
        val observed = com.nozzleitall.desktop.history.JobRecord("observation-reprint", "p", "P", "part.gcode",
            100, 200, "finished", 10.0, 4)
        store.recordCurrentObservation(observed)
        assertEquals(source.id, store.jobForObservation(observed)?.id)
        assertEquals(digest, store.jobForObservation(observed)?.artifactSha256)
        assertThrows(IllegalStateException::class.java) { store.jobForObservation(observed.copy(outcome = "failed")) }
        assertNull(store.jobForObservation(observed.copy(id = "missing")))
    }

    @Test fun observationAssessmentTargetsTheExactAttemptAndPersistsAcrossReopen() {
        val file = Files.createTempDirectory("nia-history-assessment").resolve("jobs.sqlite").toFile()
        val store = LocalJobStore(file)
        val job = store.createJob(LocalJobStore.Origin.APP, artifactSha256 = "a".repeat(64))
        val unrelated = store.createAttempt(job.id, "p", 50)
        val observedAttempt = store.bindQueuedStart("p", job.id, "part.gcode", 100)
        val observation = com.nozzleitall.desktop.history.JobRecord("observed-result", "p", "P", "part.gcode",
            100, 200, "finished", 10.0, 3)
        store.recordCurrentObservation(observation)
        val review = store.reviewForObservation(observation)!!
        assertEquals(job.id, review.job.id)
        assertEquals(observedAttempt.attemptId, review.attemptId)
        assertNull(review.latestOutcome)
        val saved = store.assess(review.attemptId, 0, LocalJobStore.Assessment.PARTIAL_SUCCESS, "Corner lifted", 300)
        assertEquals(saved, LocalJobStore(file).reviewForObservation(observation)?.latestOutcome)
        assertTrue(store.outcomes(unrelated.id).isEmpty())
        assertThrows(IllegalStateException::class.java) {
            store.reviewForObservation(observation.copy(outcome = "failed"))
        }
    }

    @Test fun legacyHistoryRejectsEndingBeforeStartWithoutChangingSource() {
        val dir = Files.createTempDirectory("nia-invalid-history-time").toFile()
        val source = File(dir, "history.json").apply {
            writeText("""[{"id":"bad","printerId":"p","fileName":"part.gcode","startedAt":200,"endedAt":100,"outcome":"finished"}]""")
        }
        val original = source.readBytes()
        val store = LocalJobStore(File(dir, "jobs.sqlite"))
        assertThrows(Exception::class.java) { store.importLegacyHistory(source) }
        assertArrayEquals(original, source.readBytes())
        assertTrue(store.importedLegacyRecords().isEmpty())
    }

    @Test fun invalidLegacyHistoryCannotPartiallyImportOrBeRewritten() {
        val dir = Files.createTempDirectory("nia-bad-legacy").toFile()
        val source = File(dir, "history.json").apply { writeText("[{\"id\":\"one\",\"printerId\":\"p\",\"fileName\":\"a\"},{}]") }
        val original = source.readBytes()
        val store = LocalJobStore(File(dir, "jobs.sqlite"))
        assertThrows(Exception::class.java) { store.importLegacyHistory(source) }
        assertArrayEquals(original, source.readBytes())
        assertTrue(store.importedLegacyRecords().isEmpty())
    }

    @Test fun aQueuedStartKeepsItsAttemptAcrossReopenAndEndObservation() {
        val dir = Files.createTempDirectory("nia-active-attempt").toFile()
        val file = File(dir, "jobs.sqlite")
        val store = LocalJobStore(file)
        val job = store.createJob(LocalJobStore.Origin.APP, artifactSha256 = "a".repeat(64), printerId = "p")
        val active = store.bindQueuedStart("p", job.id, "nozzle/part.gcode", 100)
        assertEquals(active, LocalJobStore(file).activeQueuedAttempt("p"))
        val observed = com.nozzleitall.desktop.history.JobRecord("observation-1", "p", "P", "nozzle/part.gcode", 100, 200,
            "finished", 10.0, 3)
        assertEquals(job.id to active.attemptId, LocalJobStore(file).recordCurrentObservation(observed))
        assertNull(LocalJobStore(file).activeQueuedAttempt("p"))
        assertEquals(listOf(LocalJobStore.MachineState.PRINTING, LocalJobStore.MachineState.MACHINE_COMPLETED),
            store.machineEvents(active.attemptId).map { it.state })
        assertTrue(store.outcomes(active.attemptId).isEmpty())
        val history = File(dir, "history.json")
        com.nozzleitall.desktop.history.JobHistoryStore(history).save(listOf(observed))
        assertEquals(0, store.importLegacyHistory(history))
        assertEquals(LocalJobStore.Origin.APP, store.job(job.id)!!.origin)
        assertEquals(1, store.jobs().size)
    }

    @Test fun aDifferentObservedFilenameDoesNotConsumeTheQueuedJobAsItsOutcome() {
        val file = Files.createTempDirectory("nia-active-mismatch").resolve("jobs.sqlite").toFile()
        val store = LocalJobStore(file)
        val queued = store.createJob(LocalJobStore.Origin.APP, artifactSha256 = "a".repeat(64), printerId = "p")
        val active = store.bindQueuedStart("p", queued.id, "nozzle/first.gcode", 100)
        val observed = com.nozzleitall.desktop.history.JobRecord("observation-2", "p", "P", "other/first.gcode", 100, 200,
            "finished", null, null)
        val recorded = store.recordCurrentObservation(observed)
        assertNotEquals(queued.id, recorded.first)
        assertEquals(listOf(LocalJobStore.MachineState.PRINTING), store.machineEvents(active.attemptId).map { it.state })
        assertEquals(LocalJobStore.Origin.OBSERVED_UNLINKED, store.job(recorded.first)!!.origin)
        assertEquals(active, store.activeQueuedAttempt("p"))
        store.recordCurrentObservation(observed.copy(id = "queued-later", fileName = "nozzle/first.gcode", endedAtMillis = 300))
        assertNull(store.activeQueuedAttempt("p"))
        assertEquals(listOf(LocalJobStore.MachineState.PRINTING, LocalJobStore.MachineState.MACHINE_COMPLETED),
            store.machineEvents(active.attemptId).map { it.state })
    }
    @Test fun jobAndAttemptsKeepStableIdentitiesAcrossReopenAndReprint() {
        val file = Files.createTempDirectory("nia-local-jobs").resolve("jobs.sqlite").toFile()
        val store = LocalJobStore(file)
        val digest = "a".repeat(64)
        val job = store.createJob(LocalJobStore.Origin.APP, "project", 3, "plate", digest, "printer", digest, nowMillis = 100)
        val firstAttempt = store.createAttempt(job.id, "printer", 110)
        val secondAttempt = store.createAttempt(job.id, "printer", 110)
        val reprint = store.createJob(LocalJobStore.Origin.APP, "project", 3, "plate", digest, "printer", digest,
            sourceJobId = job.id, nowMillis = 200)
        val reopened = LocalJobStore(file)
        assertEquals(job, reopened.job(job.id))
        assertEquals(listOf(firstAttempt, secondAttempt), reopened.attempts(job.id))
        assertEquals(job.id, reopened.job(reprint.id)?.sourceJobId)
        assertNotEquals(job.id, reprint.id)
        assertNotEquals(firstAttempt.id, secondAttempt.id)
    }

    @Test fun machineCompletionCannotAssessThePhysicalOutcome() {
        val store = LocalJobStore(Files.createTempDirectory("nia-local-outcomes").resolve("jobs.sqlite").toFile())
        val job = store.createJob(LocalJobStore.Origin.EXTERNAL, printerId = "printer")
        val attempt = store.createAttempt(job.id, "printer")
        val started = store.recordMachineState(attempt.id, LocalJobStore.MachineState.PRINTING, 100)
        val event = store.recordMachineState(attempt.id, LocalJobStore.MachineState.MACHINE_COMPLETED, 100)
        assertEquals(listOf(started, event), store.machineEvents(attempt.id))
        assertTrue(store.outcomes(attempt.id).isEmpty())
        val first = store.assess(attempt.id, 0, LocalJobStore.Assessment.PARTIAL_SUCCESS, "One corner lifted", 1000)
        val second = store.assess(attempt.id, 1, LocalJobStore.Assessment.SUCCESS, "Rechecked", 2000)
        assertEquals(listOf(first, second), store.outcomes(attempt.id))
        assertEquals(listOf(started, event), store.machineEvents(attempt.id))
        assertThrows(IllegalStateException::class.java) {
            store.assess(attempt.id, 1, LocalJobStore.Assessment.FAILED)
        }
        assertEquals(listOf(first, second), store.outcomes(attempt.id))
    }

    @Test fun unknownReferencesAndInvalidSnapshotsDoNotCreateRecords() {
        val store = LocalJobStore(Files.createTempDirectory("nia-local-constraints").resolve("jobs.sqlite").toFile())
        assertThrows(IllegalArgumentException::class.java) {
            store.createJob(LocalJobStore.Origin.APP, projectId = "project", projectRevision = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.createJob(LocalJobStore.Origin.APP, artifactSha256 = "not-a-digest")
        }
        val job = store.createJob(LocalJobStore.Origin.EXTERNAL)
        assertThrows(java.sql.SQLException::class.java) {
            store.createJob(LocalJobStore.Origin.APP, sourceJobId = "00000000-0000-0000-0000-000000000001")
        }
        val attempt = store.createAttempt(job.id, null)
        assertThrows(java.sql.SQLException::class.java) {
            store.recordMachineState("00000000-0000-0000-0000-000000000001", LocalJobStore.MachineState.READY)
        }
        assertEquals(listOf(attempt), store.attempts(job.id))
    }

    @Test fun deterministicJobIdentityRejectsDifferentPrinterAssignment() {
        val store = LocalJobStore(Files.createTempDirectory("nia-job-collision").resolve("jobs.sqlite").toFile())
        val id = java.util.UUID.randomUUID().toString()
        val original = store.createJob(LocalJobStore.Origin.APP, printerId = "a", nowMillis = 100, id = id)
        assertEquals(original, store.createJob(LocalJobStore.Origin.APP, printerId = "a", nowMillis = 100, id = id))
        assertThrows(IllegalStateException::class.java) {
            store.createJob(LocalJobStore.Origin.APP, printerId = "b", nowMillis = 100, id = id)
        }
    }

    @Test fun simultaneousAssessmentsWithTheSameExpectedRevisionCannotBothCommit() {
        val file = Files.createTempDirectory("nia-outcome-race").resolve("jobs.sqlite").toFile()
        val store = LocalJobStore(file)
        val attempt = store.createAttempt(store.createJob(LocalJobStore.Origin.EXTERNAL).id, null)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { n -> pool.submit<Boolean> {
                start.await()
                runCatching { LocalJobStore(file).assess(attempt.id, 0, LocalJobStore.Assessment.SUCCESS, "review $n") }.isSuccess
            } }
            start.countDown()
            assertEquals(listOf(false, true), results.map { it.get() }.sorted())
            assertEquals(listOf(1L), LocalJobStore(file).outcomes(attempt.id).map { it.revision })
        } finally { pool.shutdownNow() }
    }

    @Test fun historyRowsRejectUpdatesAndDeletesEvenThroughSql() {
        val file = Files.createTempDirectory("nia-immutable-history").resolve("jobs.sqlite").toFile()
        val store = LocalJobStore(file)
        val job = store.createJob(LocalJobStore.Origin.EXTERNAL)
        val attempt = store.createAttempt(job.id, null)
        val event = store.recordMachineState(attempt.id, LocalJobStore.MachineState.MACHINE_COMPLETED)
        val outcome = store.assess(attempt.id, 0, LocalJobStore.Assessment.FAILED)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
            for ((table, id) in listOf("jobs" to job.id, "attempts" to attempt.id,
                                     "machine_events" to event.id, "outcomes" to outcome.id)) {
                assertThrows(java.sql.SQLException::class.java) {
                    db.prepareStatement("DELETE FROM $table WHERE id=?").use { st -> st.setString(1, id); st.executeUpdate() }
                }
                assertThrows(java.sql.SQLException::class.java) {
                    db.prepareStatement("UPDATE $table SET id=? WHERE id=?").use { st -> st.setString(1, id); st.setString(2, id); st.executeUpdate() }
                }
            }
            assertThrows(java.sql.SQLException::class.java) {
                db.prepareStatement("INSERT OR REPLACE INTO machine_events(id,attempt_id,state,reported_at) VALUES(?,?,?,?)").use { st ->
                    st.setString(1, event.id); st.setString(2, attempt.id); st.setString(3, "FAILED"); st.setLong(4, 0); st.executeUpdate()
                }
            }
            assertThrows(java.sql.SQLException::class.java) {
                db.prepareStatement("INSERT OR REPLACE INTO outcomes(id,attempt_id,revision,assessment,notes,assessed_at) VALUES(?,?,?,?,?,?)").use { st ->
                    st.setString(1, java.util.UUID.randomUUID().toString()); st.setString(2, attempt.id); st.setLong(3, outcome.revision)
                    st.setString(4, "SUCCESS"); st.setString(5, "replacement"); st.setLong(6, 0); st.executeUpdate()
                }
            }
        }
        assertEquals(job, LocalJobStore(file).job(job.id))
        assertEquals(listOf(outcome), LocalJobStore(file).outcomes(attempt.id))
    }
}
