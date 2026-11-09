package com.nozzleitall.desktop.jobs

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import com.nozzleitall.desktop.LocalFileDurability

/** Content-addressed, local prepared files. This store has no printer-command dependency. */
class PreparedArtifactStore(private val dir: File) {
    data class Record(val id: String, val sha256: String, val bytes: Long, val projectId: String?,
                      val projectRevision: Long?, val plateId: String?, val targetProfileId: String?,
                      val engineBuild: String?, val inputDigest: String?, val createdAtMillis: Long, val output: String = GCODE)

    private val blobs = File(dir, "blobs")
    private val database = File(dir, "artifacts.sqlite")

    init {
        blobs.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { db ->
            db.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout=5000")
                st.execute("BEGIN IMMEDIATE")
                st.execute("""CREATE TABLE IF NOT EXISTS artifacts (
                    id TEXT PRIMARY KEY, sha256 TEXT NOT NULL, bytes INTEGER NOT NULL,
                    project_id TEXT, project_revision INTEGER, plate_id TEXT,
                    target_profile_id TEXT, engine_build TEXT, input_digest TEXT, created_at INTEGER NOT NULL,
                    output TEXT NOT NULL DEFAULT 'gcode'
                )""")
                val hasOutput = st.executeQuery("PRAGMA table_info(artifacts)").use { rows ->
                    generateSequence { if (rows.next()) rows.getString("name") else null }.any { it == "output" }
                }
                if (!hasOutput) st.execute("ALTER TABLE artifacts ADD COLUMN output TEXT NOT NULL DEFAULT 'gcode'")
                st.execute("CREATE TRIGGER IF NOT EXISTS artifacts_immutable_update BEFORE UPDATE ON artifacts BEGIN SELECT RAISE(ABORT, 'Artifacts are immutable'); END")
                st.execute("CREATE TRIGGER IF NOT EXISTS artifacts_immutable_delete BEFORE DELETE ON artifacts BEGIN SELECT RAISE(ABORT, 'Artifacts are immutable'); END")
                st.execute("CREATE TRIGGER IF NOT EXISTS artifacts_immutable_replace BEFORE INSERT ON artifacts WHEN EXISTS(SELECT 1 FROM artifacts WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT, 'Artifacts are immutable'); END")
                st.execute("COMMIT")
            }
        }
    }

    fun prepare(source: File, projectId: String? = null, projectRevision: Long? = null,
                plateId: String? = null, targetProfileId: String? = null,
                engineBuild: String? = null, inputDigest: String? = null,
                nowMillis: Long = System.currentTimeMillis(), id: String? = null): Record {
        require(source.isFile && source.length() > 0) { "Prepared source is missing or empty." }
        require((projectId == null) == (projectRevision == null)) { "Project ID and revision must be supplied together." }
        require(projectRevision == null || projectRevision > 0)
        require(plateId == null || projectId != null)
        require(inputDigest == null || Regex("[0-9a-f]{64}").matches(inputDigest))
        require(nowMillis >= 0)
        require(id == null || runCatching { UUID.fromString(id) }.isSuccess)
        val tmp = File(blobs, ".${UUID.randomUUID()}.part")
        try {
            source.copyTo(tmp, overwrite = false)
            java.io.RandomAccessFile(tmp, "rw").use { it.fd.sync() }
            val hash = digest(tmp)
            val output = preparedOutput(source.name)
            val target = File(blobs, "$hash.$output")
            if (target.exists()) {
                check(target.isFile && target.length() == tmp.length() && digest(target) == hash) { "Prepared blob digest conflict." }
            } else {
                try { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                catch (e: java.nio.file.FileAlreadyExistsException) {
                    check(target.isFile && target.length() == tmp.length() && digest(target) == hash) { "Prepared blob digest conflict." }
                }
                syncDirectory(blobs)
            }
            val record = Record(id ?: UUID.randomUUID().toString(), hash, target.length(), projectId, projectRevision,
                plateId, targetProfileId, engineBuild, inputDigest, nowMillis, output)
            if (id != null) record(id)?.let { existing ->
                check(existing == record) { "Existing prepared artifact identity has different contents." }
                return existing
            }
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { db ->
                db.prepareStatement("""INSERT INTO artifacts(id,sha256,bytes,project_id,project_revision,plate_id,
                    target_profile_id,engine_build,input_digest,created_at,output) VALUES(?,?,?,?,?,?,?,?,?,?,?)""").use { st ->
                    st.setString(1, record.id); st.setString(2, record.sha256); st.setLong(3, record.bytes)
                    st.setString(4, record.projectId)
                    if (record.projectRevision == null) st.setNull(5, java.sql.Types.BIGINT) else st.setLong(5, record.projectRevision)
                    st.setString(6, record.plateId); st.setString(7, record.targetProfileId); st.setString(8, record.engineBuild)
                    st.setString(9, record.inputDigest); st.setLong(10, record.createdAtMillis); st.setString(11, record.output); st.executeUpdate()
                }
            }
            return record
        } finally { tmp.delete() }
    }

    fun record(id: String): Record? = DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { db ->
        db.prepareStatement("""SELECT sha256,bytes,project_id,project_revision,plate_id,target_profile_id,
            engine_build,input_digest,created_at,output FROM artifacts WHERE id=?""").use { st ->
            st.setString(1, id); st.executeQuery().use { rs -> if (!rs.next()) null else Record(id, rs.getString(1), rs.getLong(2),
                rs.getString(3), rs.getLong(4).takeUnless { rs.wasNull() }, rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getLong(9), rs.getString(10)) }
        }
    }

    /** A digest may have several records with different project or slice provenance. */
    fun recordsByDigest(sha256: String): List<Record> {
        require(Regex("[0-9a-f]{64}").matches(sha256)) { "Invalid prepared artifact digest." }
        val ids = DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { db ->
            db.prepareStatement("SELECT id FROM artifacts WHERE sha256=? ORDER BY created_at,id").use { st ->
                st.setString(1, sha256)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }
        return ids.mapNotNull(::record)
    }

    /** Kept for callers that need only a blob; lineage-sensitive callers inspect every record. */
    fun recordByDigest(sha256: String): Record? = recordsByDigest(sha256).firstOrNull()

    fun verifiedFile(record: Record): File {
        require(Regex("[0-9a-f]{64}").matches(record.sha256)) { "Invalid prepared artifact digest." }
        require(record.output in preparedOutputs) { "Unsupported prepared output format." }
        val file = File(blobs, "${record.sha256}.${record.output}")
        check(file.isFile && file.length() == record.bytes && digest(file) == record.sha256) { "Prepared artifact is missing or changed." }
        return file
    }

    /** Unindexed blobs remain on disk for recovery inspection; nothing is deleted automatically. */
    fun orphanBlobs(): List<File> = DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { db ->
        val indexed = db.createStatement().use { st -> st.executeQuery("SELECT DISTINCT sha256,output FROM artifacts").use { rs -> buildSet {
            while (rs.next()) add(rs.getString(1) + "." + rs.getString(2))
        } } }
        blobs.listFiles()?.filter { it.isFile && preparedOutputs.any { output -> it.name.endsWith(".$output") } && it.name !in indexed } ?: emptyList()
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) {
            val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n)
        } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun syncDirectory(path: File) {
        LocalFileDurability.syncDirectory(path)
    }
}
