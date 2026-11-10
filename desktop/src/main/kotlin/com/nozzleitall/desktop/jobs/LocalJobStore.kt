package com.nozzleitall.desktop.jobs

import com.nozzleitall.desktop.history.reportedJobPath
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import com.nozzleitall.desktop.history.JobRecord

/** Local, data-only job history. This repository has no printer adapter or command-dispatch dependency. */
class LocalJobStore(private val file: File) {
    enum class Origin { APP, EXTERNAL, LEGACY_UNKNOWN, OBSERVED_UNLINKED }
    enum class MachineState { READY, PRINTING, PAUSED, MACHINE_COMPLETED, CANCELLED, FAILED, UNKNOWN }
    enum class Assessment { SUCCESS, PARTIAL_SUCCESS, FAILED }

    data class Job(val id: String, val origin: Origin, val projectId: String?, val projectRevision: Long?,
                   val plateId: String?, val artifactSha256: String?, val printerId: String?,
                   val assignmentDigest: String?, val sourceJobId: String?, val createdAtMillis: Long)
    data class Attempt(val id: String, val jobId: String, val printerId: String?, val createdAtMillis: Long)
    data class MachineEvent(val id: String, val attemptId: String, val state: MachineState, val reportedAtMillis: Long)
    data class Outcome(val id: String, val attemptId: String, val revision: Long, val assessment: Assessment,
                       val notes: String, val assessedAtMillis: Long)
    data class ObservationReview(val job: Job, val attemptId: String, val latestOutcome: Outcome?)
    data class ActiveQueuedAttempt(val printerId: String, val jobId: String, val attemptId: String,
                                   val fileName: String, val startedAtMillis: Long)

    init { file.parentFile?.mkdirs(); connection { db ->
        db.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
        db.createStatement().use { st -> st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS jobs (
              id TEXT PRIMARY KEY, origin TEXT NOT NULL, project_id TEXT, project_revision INTEGER,
              plate_id TEXT, artifact_sha256 TEXT, printer_id TEXT, assignment_digest TEXT,
              source_job_id TEXT REFERENCES jobs(id), created_at INTEGER NOT NULL
            )
        """) }
        db.createStatement().use { st -> st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS attempts (
              seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE,
              job_id TEXT NOT NULL REFERENCES jobs(id), printer_id TEXT,
              created_at INTEGER NOT NULL
            )
        """) }
        db.createStatement().use { st -> st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS machine_events (
              seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE,
              attempt_id TEXT NOT NULL REFERENCES attempts(id),
              state TEXT NOT NULL, reported_at INTEGER NOT NULL
            )
        """) }
        db.createStatement().use { st -> st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS outcomes (
              id TEXT PRIMARY KEY, attempt_id TEXT NOT NULL REFERENCES attempts(id), revision INTEGER NOT NULL,
              assessment TEXT NOT NULL, notes TEXT NOT NULL, assessed_at INTEGER NOT NULL,
              UNIQUE(attempt_id, revision)
            )
        """) }
        db.createStatement().use { st -> st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS legacy_history (
              legacy_key TEXT PRIMARY KEY, source_sha256 TEXT NOT NULL, payload TEXT NOT NULL,
              job_id TEXT NOT NULL REFERENCES jobs(id), attempt_id TEXT NOT NULL REFERENCES attempts(id)
            )
        """) }
        db.createStatement().use { st -> st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS active_queued_attempts (
              printer_id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES jobs(id),
              attempt_id TEXT NOT NULL UNIQUE REFERENCES attempts(id),
              file_name TEXT NOT NULL, started_at INTEGER NOT NULL
            )
        """) }
        for (table in listOf("jobs", "attempts", "machine_events", "outcomes", "legacy_history")) {
            db.createStatement().use { st ->
                st.executeUpdate("CREATE TRIGGER IF NOT EXISTS ${table}_immutable_update BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT, 'Historical records are immutable'); END")
                st.executeUpdate("CREATE TRIGGER IF NOT EXISTS ${table}_immutable_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT, 'Historical records are immutable'); END")
                val conflict = "EXISTS(SELECT 1 FROM $table WHERE ${if (table == "legacy_history") "legacy_key=NEW.legacy_key" else "id=NEW.id"})" +
                    if (table == "outcomes") " OR EXISTS(SELECT 1 FROM outcomes WHERE attempt_id=NEW.attempt_id AND revision=NEW.revision)"
                    else if (table == "attempts" || table == "machine_events") " OR EXISTS(SELECT 1 FROM $table WHERE seq=NEW.seq)"
                    else ""
                st.executeUpdate("CREATE TRIGGER IF NOT EXISTS ${table}_immutable_replace BEFORE INSERT ON $table WHEN $conflict BEGIN SELECT RAISE(ABORT, 'Historical records are immutable'); END")
            }
        }
        db.createStatement().use { it.execute("COMMIT") }
        } catch (e: Exception) {
            runCatching { db.createStatement().use { it.execute("ROLLBACK") } }.onFailure { e.addSuppressed(it) }
            throw e
        }
    } }

    private fun <T> connection(block: (Connection) -> T): T {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
            db.createStatement().use { it.execute("PRAGMA foreign_keys=ON"); it.execute("PRAGMA busy_timeout=5000") }
            return block(db)
        }
    }

    private fun requireId(id: String) { require(runCatching { UUID.fromString(id) }.isSuccess) { "Expected a UUID identity." } }
    private fun requireDigest(digest: String?) { require(digest == null || Regex("[0-9a-fA-F]{64}").matches(digest)) { "Expected a SHA-256 digest." } }
    private fun uuid(): String = UUID.randomUUID().toString()

    /** Copy legacy JSON history as immutable local records. The source file is never changed. */
    fun importLegacyHistory(source: File): Int {
        if (!source.exists()) return 0
        val rows = JSONArray(source.readText())
        val records = (0 until rows.length()).map { index ->
            val raw = rows.getJSONObject(index)
            require(raw.has("startedAt") && raw.has("endedAt") && raw.has("outcome")) {
                "Legacy history row $index lacks observed time or outcome; source preserved for review."
            }
            require(raw.getLong("startedAt") >= 0 && raw.getLong("endedAt") >= raw.getLong("startedAt")) {
                "Legacy history row $index has an invalid time; source preserved for review."
            }
            require(raw.getString("outcome") in setOf("finished", "cancelled", "failed", "unknown")) {
                "Legacy history row $index has an unknown machine outcome; source preserved for review."
            }
            val payload = raw.toString()
            val record = JobRecord.fromJson(raw)
            require(record.id.isNotBlank() && record.printerId.isNotBlank()) { "Legacy history identity is missing." }
            record to payload
        }
        val occurrences = HashMap<String, Int>()
        check(records.groupBy { it.first.id }.values.none { group -> group.map { it.second }.distinct().size > 1 }) {
            "Legacy history reuses an observation identity with different evidence; source preserved for review."
        }
        val keys = records.map { (record, payload) ->
            val base = "${record.id}|${record.printerId}|${record.startedAtMillis}|${record.endedAtMillis}|${sha256(payload)}"
            val occurrence = (occurrences[base] ?: 0) + 1
            occurrences[base] = occurrence
            "$base|$occurrence"
        }
        return connection { db ->
            db.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            try {
                var inserted = 0
                for ((index, triple) in records.withIndex()) {
                    val (record, payload) = triple
                    if (record.currentObservation) continue // Recovered through recordCurrentObservation, never as LEGACY_UNKNOWN.
                    val key = keys[index]
                    val sha = sha256(payload)
                    val priorObservation = observationWithId(db, record.id)
                    if (priorObservation != null) {
                        check(priorObservation.first == payload) { "Legacy observation identity already has different evidence; source preserved for review." }
                        continue
                    }
                    val existing = db.prepareStatement("SELECT source_sha256 FROM legacy_history WHERE legacy_key=?").use { st ->
                        st.setString(1, key); st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                    }
                    if (existing != null) {
                        check(existing == sha) { "Legacy history changed for an imported identity; review before continuing." }
                        continue
                    }
                    val jobId = stableUuid("legacy-job:$key")
                    val attemptId = stableUuid("legacy-attempt:$key")
                    val eventId = stableUuid("legacy-event:$key")
                    val state = when (record.outcome) {
                        "finished" -> MachineState.MACHINE_COMPLETED
                        "cancelled" -> MachineState.CANCELLED
                        "failed" -> MachineState.FAILED
                        else -> MachineState.UNKNOWN
                    }
                    db.prepareStatement("INSERT INTO jobs(id,origin,printer_id,created_at) VALUES(?,?,?,?)").use { st ->
                        st.setString(1, jobId); st.setString(2, Origin.LEGACY_UNKNOWN.name); st.setString(3, record.printerId)
                        st.setLong(4, record.startedAtMillis.coerceAtLeast(0)); st.executeUpdate()
                    }
                    db.prepareStatement("INSERT INTO attempts(id,job_id,printer_id,created_at) VALUES(?,?,?,?)").use { st ->
                        st.setString(1, attemptId); st.setString(2, jobId); st.setString(3, record.printerId)
                        st.setLong(4, record.startedAtMillis.coerceAtLeast(0)); st.executeUpdate()
                    }
                    db.prepareStatement("INSERT INTO machine_events(id,attempt_id,state,reported_at) VALUES(?,?,?,?)").use { st ->
                        st.setString(1, eventId); st.setString(2, attemptId); st.setString(3, state.name)
                        st.setLong(4, record.endedAtMillis.coerceAtLeast(0)); st.executeUpdate()
                    }
                    db.prepareStatement("INSERT INTO legacy_history(legacy_key,source_sha256,payload,job_id,attempt_id) VALUES(?,?,?,?,?)").use { st ->
                        st.setString(1, key); st.setString(2, sha); st.setString(3, payload)
                        st.setString(4, jobId); st.setString(5, attemptId); st.executeUpdate()
                    }
                    inserted++
                }
                db.createStatement().use { it.execute("COMMIT") }
                inserted
            } catch (e: Exception) {
                runCatching { db.createStatement().use { it.execute("ROLLBACK") } }.onFailure { e.addSuppressed(it) }
                throw e
            }
        }
    }

    fun importedLegacyRecords(): List<Pair<String, JobRecord>> = connection { db ->
        db.prepareStatement("SELECT job_id,payload FROM legacy_history ORDER BY rowid").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to JobRecord.fromJson(org.json.JSONObject(rs.getString(2)))) } }
        }
    }

    /** Current observations already committed to SQLite, for JSON-history crash reconciliation. */
    fun currentObservedRecords(): List<JobRecord> = connection { db ->
        db.prepareStatement("""SELECT l.payload FROM legacy_history l JOIN jobs j ON j.id=l.job_id
            WHERE j.origin<>? ORDER BY l.rowid""").use { st ->
            st.setString(1, Origin.LEGACY_UNKNOWN.name)
            st.executeQuery().use { rs -> buildList {
                while (rs.next()) add(JobRecord.fromJson(org.json.JSONObject(rs.getString(1))))
            } }
        }
    }

    private fun stableUuid(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString()
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** Existing schemas lack an observation-id column; inspect immutable payloads before appending. */
    private fun observationWithId(db: Connection, id: String): Triple<String, String, String>? =
        db.prepareStatement("SELECT payload,job_id,attempt_id FROM legacy_history").use { st ->
            st.executeQuery().use { rs ->
                var found: Triple<String, String, String>? = null
                while (rs.next()) {
                    val payload = rs.getString(1)
                    if (JobRecord.fromJson(JSONObject(payload)).id == id) {
                        val row = Triple(payload, rs.getString(2), rs.getString(3))
                        check(found == null || found.first == row.first) { "Existing observations reuse an identity with different evidence." }
                        if (found == null) found = row
                    }
                }
                found
            }
        }

    /** Resolve a history observation to its original immutable job only when the full evidence still matches. */
    fun jobForObservation(record: JobRecord): Job? = connection { db ->
        val stored = observationWithId(db, record.id) ?: return@connection null
        check(JobRecord.fromJson(JSONObject(stored.first)) == record) {
            "Machine observation changed; review History before reprinting."
        }
        stored.second
    }?.let(::job)

    /** Resolve the exact observed attempt; another attempt on the same job must never receive its assessment. */
    fun reviewForObservation(record: JobRecord): ObservationReview? {
        val identity = connection { db ->
            val stored = observationWithId(db, record.id) ?: return@connection null
            check(JobRecord.fromJson(JSONObject(stored.first)) == record) {
                "Machine observation changed; review History before assessing it."
            }
            stored.second to stored.third
        } ?: return null
        val job = job(identity.first) ?: error("The observed job is missing.")
        check(attempts(job.id).any { it.id == identity.second }) { "The observed attempt is missing from its job." }
        return ObservationReview(job, identity.second, outcomes(identity.second).lastOrNull())
    }

    /** Records an explicitly started queued job before removing its queue entry. */
    fun bindQueuedStart(printerId: String, jobId: String, fileName: String,
                        nowMillis: Long = System.currentTimeMillis()): ActiveQueuedAttempt {
        require(printerId.isNotBlank() && fileName.isNotBlank() && nowMillis >= 0); requireId(jobId)
        return connection { db ->
            db.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            try {
                val existing = activeQueuedAttempt(db, printerId)
                if (existing != null) {
                    check(existing.jobId == jobId && existing.fileName == fileName) { "Another queued job is still active on this printer." }
                    db.createStatement().use { it.execute("COMMIT") }
                    existing
                } else {
                    val origin = db.prepareStatement("SELECT origin FROM jobs WHERE id=?").use { st ->
                        st.setString(1, jobId); st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                    }
                    check(origin == Origin.APP.name) { "Queued job identity is missing or was not created by this app." }
                    val attempt = ActiveQueuedAttempt(printerId, jobId, uuid(), fileName, nowMillis)
                    db.prepareStatement("INSERT INTO attempts(id,job_id,printer_id,created_at) VALUES(?,?,?,?)").use { st ->
                        st.setString(1, attempt.attemptId); st.setString(2, jobId); st.setString(3, printerId)
                        st.setLong(4, nowMillis); st.executeUpdate()
                    }
                    db.prepareStatement("INSERT INTO machine_events(id,attempt_id,state,reported_at) VALUES(?,?,?,?)").use { st ->
                        st.setString(1, uuid()); st.setString(2, attempt.attemptId); st.setString(3, MachineState.PRINTING.name)
                        st.setLong(4, nowMillis); st.executeUpdate()
                    }
                    db.prepareStatement("INSERT INTO active_queued_attempts(printer_id,job_id,attempt_id,file_name,started_at) VALUES(?,?,?,?,?)").use { st ->
                        st.setString(1, printerId); st.setString(2, jobId); st.setString(3, attempt.attemptId)
                        st.setString(4, fileName); st.setLong(5, nowMillis); st.executeUpdate()
                    }
                    db.createStatement().use { it.execute("COMMIT") }
                    attempt
                }
            } catch (e: Exception) {
                runCatching { db.createStatement().use { it.execute("ROLLBACK") } }.onFailure { e.addSuppressed(it) }
                throw e
            }
        }
    }

    fun activeQueuedAttempt(printerId: String): ActiveQueuedAttempt? = connection { activeQueuedAttempt(it, printerId) }

    fun activeQueuedAttempts(): List<ActiveQueuedAttempt> = connection { db ->
        db.prepareStatement("SELECT printer_id,job_id,attempt_id,file_name,started_at FROM active_queued_attempts ORDER BY started_at,printer_id").use { st ->
            st.executeQuery().use { rs -> buildList {
                while (rs.next()) add(ActiveQueuedAttempt(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)))
            } }
        }
    }

    private fun activeQueuedAttempt(db: Connection, printerId: String): ActiveQueuedAttempt? =
        db.prepareStatement("SELECT job_id,attempt_id,file_name,started_at FROM active_queued_attempts WHERE printer_id=?").use { st ->
            st.setString(1, printerId); st.executeQuery().use { rs -> if (!rs.next()) null else ActiveQueuedAttempt(printerId,
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) }
        }

    /** Persist a newly observed ending as machine evidence; it never becomes a physical assessment. */
    fun recordCurrentObservation(record: JobRecord): Pair<String, String> {
        require(record.id.isNotBlank() && record.printerId.isNotBlank())
        require(record.startedAtMillis >= 0 && record.endedAtMillis >= record.startedAtMillis)
        val state = when (record.outcome) {
            "finished" -> MachineState.MACHINE_COMPLETED
            "cancelled" -> MachineState.CANCELLED
            "failed" -> MachineState.FAILED
            "unknown" -> MachineState.UNKNOWN
            else -> error("Unknown observed machine outcome.")
        }
        val payload = record.toJson().toString()
        val sha = sha256(payload)
        val key = "${record.id}|${record.printerId}|${record.startedAtMillis}|${record.endedAtMillis}|$sha|1"
        return connection { db ->
            db.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            try {
                val prior = observationWithId(db, record.id)?.let { stored ->
                    check(stored.first == payload) { "Machine observation identity already has different evidence." }
                    stored.second to stored.third
                }
                if (prior != null) {
                    db.createStatement().use { it.execute("COMMIT") }; prior
                } else {
                    val queued = activeQueuedAttempt(db, record.printerId)
                    val active = queued?.takeIf {
                        reportedJobPath(it.fileName) == reportedJobPath(record.fileName)
                    }
                    // A different file's ending proves nothing about the queued attempt.
                    // Keep its machine events and active binding unchanged for later resolution.
                    val jobId = active?.jobId ?: uuid()
                    val attemptId = active?.attemptId ?: uuid()
                    if (active == null) {
                        db.prepareStatement("INSERT INTO jobs(id,origin,printer_id,created_at) VALUES(?,?,?,?)").use { st ->
                            st.setString(1, jobId); st.setString(2, Origin.OBSERVED_UNLINKED.name)
                            st.setString(3, record.printerId); st.setLong(4, record.startedAtMillis); st.executeUpdate()
                        }
                        db.prepareStatement("INSERT INTO attempts(id,job_id,printer_id,created_at) VALUES(?,?,?,?)").use { st ->
                            st.setString(1, attemptId); st.setString(2, jobId); st.setString(3, record.printerId)
                            st.setLong(4, record.startedAtMillis); st.executeUpdate()
                        }
                    }
                    db.prepareStatement("INSERT INTO machine_events(id,attempt_id,state,reported_at) VALUES(?,?,?,?)").use { st ->
                        st.setString(1, uuid()); st.setString(2, attemptId); st.setString(3, state.name)
                        st.setLong(4, record.endedAtMillis); st.executeUpdate()
                    }
                    db.prepareStatement("INSERT INTO legacy_history(legacy_key,source_sha256,payload,job_id,attempt_id) VALUES(?,?,?,?,?)").use { st ->
                        st.setString(1, key); st.setString(2, sha); st.setString(3, payload)
                        st.setString(4, jobId); st.setString(5, attemptId); st.executeUpdate()
                    }
                    if (active != null) db.prepareStatement("DELETE FROM active_queued_attempts WHERE printer_id=?").use { st ->
                        st.setString(1, record.printerId); st.executeUpdate()
                    }
                    db.createStatement().use { it.execute("COMMIT") }
                    jobId to attemptId
                }
            } catch (e: Exception) {
                runCatching { db.createStatement().use { it.execute("ROLLBACK") } }.onFailure { e.addSuppressed(it) }
                throw e
            }
        }
    }

    fun createJob(origin: Origin, projectId: String? = null, projectRevision: Long? = null, plateId: String? = null,
                  artifactSha256: String? = null, printerId: String? = null, assignmentDigest: String? = null,
                  sourceJobId: String? = null, nowMillis: Long = System.currentTimeMillis(),
                  id: String? = null): Job {
        require(projectRevision == null || projectRevision > 0) { "A project revision must be positive." }
        require((projectId == null) == (projectRevision == null)) { "Project identity and revision must be recorded together." }
        require(plateId == null || projectId != null) { "A plate requires a project revision." }
        require(projectId == null || projectId.isNotBlank())
        require(plateId == null || plateId.isNotBlank())
        require(printerId == null || printerId.isNotBlank())
        require(nowMillis >= 0)
        requireDigest(artifactSha256); requireDigest(assignmentDigest)
        sourceJobId?.let(::requireId)
        id?.let(::requireId)
        val job = Job(id ?: uuid(), origin, projectId, projectRevision, plateId, artifactSha256, printerId,
            assignmentDigest, sourceJobId, nowMillis)
        if (id != null) job(id)?.let { existing ->
            check(existing == job) { "Existing job identity has different contents." }
            return existing
        }
        connection { db -> db.prepareStatement("""
            INSERT INTO jobs(id,origin,project_id,project_revision,plate_id,artifact_sha256,printer_id,
                             assignment_digest,source_job_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)
        """).use { st ->
            st.setString(1, job.id); st.setString(2, job.origin.name); st.setString(3, job.projectId)
            if (job.projectRevision == null) st.setNull(4, java.sql.Types.BIGINT) else st.setLong(4, job.projectRevision)
            st.setString(5, job.plateId); st.setString(6, job.artifactSha256); st.setString(7, job.printerId)
            st.setString(8, job.assignmentDigest); st.setString(9, job.sourceJobId); st.setLong(10, job.createdAtMillis)
            st.executeUpdate()
        } }
        return job
    }

    fun createAttempt(jobId: String, printerId: String?, nowMillis: Long = System.currentTimeMillis()): Attempt {
        requireId(jobId)
        require(printerId == null || printerId.isNotBlank())
        require(nowMillis >= 0)
        val attempt = Attempt(uuid(), jobId, printerId, nowMillis)
        connection { db -> db.prepareStatement("INSERT INTO attempts(id,job_id,printer_id,created_at) VALUES(?,?,?,?)").use { st ->
            st.setString(1, attempt.id); st.setString(2, attempt.jobId); st.setString(3, attempt.printerId)
            st.setLong(4, attempt.createdAtMillis); st.executeUpdate()
        } }
        return attempt
    }

    fun job(id: String): Job? = connection { db ->
        db.prepareStatement("""SELECT origin,project_id,project_revision,plate_id,artifact_sha256,printer_id,
                               assignment_digest,source_job_id,created_at FROM jobs WHERE id=?""").use { st ->
            st.setString(1, id); st.executeQuery().use { rs -> if (!rs.next()) null else Job(id, Origin.valueOf(rs.getString(1)),
                rs.getString(2), rs.getLong(3).takeUnless { rs.wasNull() }, rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9)) }
        }
    }

    fun jobs(): List<Job> = connection { db ->
        db.prepareStatement("SELECT id FROM jobs ORDER BY created_at,id").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }.mapNotNull(::job)

    fun attempts(jobId: String): List<Attempt> = connection { db ->
        db.prepareStatement("SELECT id,printer_id,created_at FROM attempts WHERE job_id=? ORDER BY seq").use { st ->
            st.setString(1, jobId); st.executeQuery().use { rs -> buildList {
                while (rs.next()) add(Attempt(rs.getString(1), jobId, rs.getString(2), rs.getLong(3)))
            } }
        }
    }

    /** An observation is evidence about a machine, never an assessment of the physical print. */
    fun recordMachineState(attemptId: String, state: MachineState, reportedAtMillis: Long = System.currentTimeMillis()): MachineEvent {
        requireId(attemptId)
        require(reportedAtMillis >= 0)
        val event = MachineEvent(uuid(), attemptId, state, reportedAtMillis)
        connection { db -> db.prepareStatement("INSERT INTO machine_events(id,attempt_id,state,reported_at) VALUES(?,?,?,?)").use { st ->
            st.setString(1, event.id); st.setString(2, event.attemptId); st.setString(3, event.state.name)
            st.setLong(4, event.reportedAtMillis); st.executeUpdate()
        } }
        return event
    }

    /** Revision-checked user assessment. Corrections append a new outcome; prior rows remain intact. */
    fun assess(attemptId: String, expectedRevision: Long, assessment: Assessment, notes: String = "",
               nowMillis: Long = System.currentTimeMillis()): Outcome {
        requireId(attemptId); require(expectedRevision >= 0); require(notes.length <= 10_000); require(nowMillis >= 0)
        return connection { db ->
            db.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            try {
                val current = db.prepareStatement("SELECT COALESCE(MAX(revision), 0) FROM outcomes WHERE attempt_id=?").use { st ->
                    st.setString(1, attemptId); st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
                check(current == expectedRevision) { "The assessment changed; review the latest revision." }
                val outcome = Outcome(uuid(), attemptId, current + 1, assessment, notes, nowMillis)
                db.prepareStatement("INSERT INTO outcomes(id,attempt_id,revision,assessment,notes,assessed_at) VALUES(?,?,?,?,?,?)").use { st ->
                    st.setString(1, outcome.id); st.setString(2, outcome.attemptId); st.setLong(3, outcome.revision)
                    st.setString(4, outcome.assessment.name); st.setString(5, outcome.notes); st.setLong(6, outcome.assessedAtMillis)
                    st.executeUpdate()
                }
                db.createStatement().use { it.execute("COMMIT") }
                outcome
            } catch (e: Exception) {
                runCatching { db.createStatement().use { it.execute("ROLLBACK") } }.onFailure { e.addSuppressed(it) }
                throw e
            }
        }
    }

    fun outcomes(attemptId: String): List<Outcome> = connection { db ->
        db.prepareStatement("SELECT id,revision,assessment,notes,assessed_at FROM outcomes WHERE attempt_id=? ORDER BY revision").use { st ->
            st.setString(1, attemptId); st.executeQuery().use { rs -> buildList {
                while (rs.next()) add(Outcome(rs.getString(1), attemptId, rs.getLong(2), Assessment.valueOf(rs.getString(3)), rs.getString(4), rs.getLong(5)))
            } }
        }
    }

    fun machineEvents(attemptId: String): List<MachineEvent> = connection { db ->
        db.prepareStatement("SELECT id,state,reported_at FROM machine_events WHERE attempt_id=? ORDER BY seq").use { st ->
            st.setString(1, attemptId); st.executeQuery().use { rs -> buildList {
                while (rs.next()) add(MachineEvent(rs.getString(1), attemptId, MachineState.valueOf(rs.getString(2)), rs.getLong(3)))
            } }
        }
    }
}
