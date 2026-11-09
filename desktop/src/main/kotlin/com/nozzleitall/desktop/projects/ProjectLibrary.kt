package com.nozzleitall.desktop.projects

import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.desktop.prepare.PresetSnapshot
import com.nozzleitall.project.MeshIO
import com.nozzleitall.project.*
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

data class ProjectSummary(val file: File, val name: String, val projectId: String?, val modifiedMillis: Long, val objectCount: Int, val printerModel: String?,
                          val problem: String? = null)

data class LegacyProjectImport(val file: File, val created: Boolean)

enum class KeepChoiceReviewReason { NO_HEAD_BECAME_SAVED, SAVED_HEAD_CHANGED, SAVED_HEAD_MISSING, RECORD_UNVERIFIABLE }

data class ProjectRecoveryCandidate(val part: File, val target: File, val projectId: String?, val revision: Long?, val problem: String?,
                                    val contentSha256: String? = null, val alreadyApplied: Boolean = false,
                                    val targetSha256: String? = null, val keptSavedVersion: Boolean = false,
                                    val previousKeepChoiceReason: KeepChoiceReviewReason? = null,
                                    val canOpenAsCopy: Boolean = false) {
    val previousKeepChoiceNeedsReview get() = previousKeepChoiceReason != null
}

/**
 * The user's projects: one canonical 3MF (with the Nozzle manifest inside) per project in the projects folder.
 * Deleting moves a project to a trash folder instead of erasing it.
 */
class ProjectLibrary(private val paths: AppPaths) {
    /** Preserves an Android export byte-for-byte; conversion into editable 3MF is a separate reviewed operation. */
    fun importAndroidArchiveCopy(source: File): AndroidArchiveCopies.Copy =
        AndroidArchiveCopies(File(paths.data, "android-archive-copies")).import(source)
    val dir get() = paths.projects
    private val trash get() = File(paths.projects, ".trash")
    private val drafts get() = File(paths.projects, ".drafts")
    private val recoveryDecisions get() = File(paths.projects, ".recovery-decisions")
    private val revisionArchive = ProjectRevisionArchive(paths.projects)

    private fun displayName(manifestName: String?, title: String?, fallback: String): String =
        manifestName?.trim()?.takeIf { it.isNotEmpty() }
            ?: title?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallback

    companion object { private val processLock = Any() }

    private fun <T> withLibraryLock(action: () -> T): T = synchronized(processLock) {
        dir.mkdirs()
        FileChannel.open(File(dir, ".library.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { action() }
        }
    }

    private fun projectFiles(folder: File): List<File> = folder.listFiles { f -> f.isFile && f.name.endsWith(".3mf", true) }?.toList() ?: emptyList()

    private fun syncDirectory(folder: File) {
        com.nozzleitall.desktop.LocalFileDurability.syncDirectory(folder)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun legacyKeptSavedMarker(part: File, partDigest: String, targetDigest: String?): File {
        val key = ThreeMf.sha256("${part.absolutePath}\n$partDigest\n${targetDigest ?: "missing"}".toByteArray())
        return File(recoveryDecisions, "$key.keep")
    }

    private fun pathBoundKeepDecisionFile(part: File, partDigest: String): File {
        val key = ThreeMf.sha256("${part.absolutePath}\n$partDigest".toByteArray())
        return File(recoveryDecisions, "$key.keep")
    }

    private fun keepDecisionFile(projectId: String, revision: Long, targetName: String, partDigest: String): File {
        val key = ThreeMf.sha256("$projectId\n$revision\n$targetName\n$partDigest".toByteArray())
        return File(recoveryDecisions, "$key.keep")
    }

    private fun openedCopyFile(projectId: String, revision: Long, targetName: String, partDigest: String): File {
        val key = ThreeMf.sha256("$projectId\n$revision\n$targetName\n$partDigest".toByteArray())
        return File(recoveryDecisions, "$key.opened")
    }

    private fun writeOpenedCopyRecord(record: File, value: String) {
        recoveryDecisions.mkdirs()
        val temporary = File(recoveryDecisions, ".${UUID.randomUUID()}.part")
        try {
            temporary.writeText(value)
            FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary.toPath(), record.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(recoveryDecisions)
        } finally { temporary.delete() }
    }

    private fun keepDecisionText(targetDigest: String?): String =
        "target-sha256: ${targetDigest ?: "missing"}\n" +
            (if (targetDigest == null) "keep recovery un-restored\n" else "keep saved version\n")

    private fun keepDecisionState(part: File, projectId: String, revision: Long, targetName: String,
                                  partDigest: String, targetDigest: String?): Pair<Boolean, KeepChoiceReviewReason?> {
        val stable = keepDecisionFile(projectId, revision, targetName, partDigest)
        val former = pathBoundKeepDecisionFile(part, partDigest)
        val record = when {
            stable.isFile -> stable
            former.isFile -> former
            else -> null
        }
        if (record != null) {
            val text = runCatching { record.readText() }.getOrNull()
                ?: return false to KeepChoiceReviewReason.RECORD_UNVERIFIABLE
            if (text == keepDecisionText(targetDigest)) return true to null
            val match = Regex("^target-sha256: (missing|[0-9a-f]{64})\\n(keep recovery un-restored|keep saved version)\\n$").matchEntire(text)
                ?: return false to KeepChoiceReviewReason.RECORD_UNVERIFIABLE
            val recordedTarget = match.groupValues[1].takeUnless { it == "missing" }
            val recordedKind = match.groupValues[2]
            if (recordedKind != if (recordedTarget == null) "keep recovery un-restored" else "keep saved version")
                return false to KeepChoiceReviewReason.RECORD_UNVERIFIABLE
            return false to when {
                recordedTarget == null -> KeepChoiceReviewReason.NO_HEAD_BECAME_SAVED
                targetDigest == null -> KeepChoiceReviewReason.SAVED_HEAD_MISSING
                else -> KeepChoiceReviewReason.SAVED_HEAD_CHANGED
            }
        }
        // Existing target-bound markers remain valid for the exact state they recorded.
        if (legacyKeptSavedMarker(part, partDigest, targetDigest).isFile) {
            return if (targetDigest == null) false to KeepChoiceReviewReason.RECORD_UNVERIFIABLE else true to null
        }
        return false to null
    }

    private fun verifiedProjectId(file: File): String {
        val project = try { ThreeMf.read(file, ReadLimits(maxTriangles = 50_000_000)) }
        catch (e: Exception) { error("Can't verify project identities while ${file.name} cannot be opened: ${e.message}") }
        check(project.manifestProblem == null && project.manifest != null) {
            "Can't verify project identities while ${file.name} has missing or damaged Nozzle details."
        }
        val manifest = requireNotNull(project.manifest)
        check(manifest.projectId.isNotBlank()) { "${file.name} has no valid project identity." }
        check(manifest.revision >= 1) { "The saved project has no valid revision." }
        val recordedDigest = project.metadata[ProjectManifest.META_MANIFEST_SHA256]
        check(recordedDigest != null && recordedDigest.equals(ThreeMf.sha256(manifest.canonicalText().toByteArray()), ignoreCase = true)) {
            "Can't verify project identities while ${file.name} has mismatched Nozzle details."
        }
        return manifest.projectId
    }

    fun list(): List<ProjectSummary> = withLibraryLock { (dir.listFiles { f -> f.isFile && f.name.endsWith(".3mf", true) } ?: emptyArray()).map { f ->
        try {
            val p = ThreeMf.read(f, ReadLimits(maxTriangles = 50_000_000))
            val digestProblem = p.manifest?.let { manifest ->
                val recorded = p.metadata[ProjectManifest.META_MANIFEST_SHA256]
                if (recorded != null && recorded.equals(ThreeMf.sha256(manifest.canonicalText().toByteArray()), ignoreCase = true)) null
                else "The project's Nozzle details have no valid recorded digest."
            }
            val presetProblem = p.manifest?.let { manifest ->
                runCatching { PresetSnapshot.fromExtensions(manifest.extensions) }.exceptionOrNull()?.let {
                    "Saved preset needs review: ${it.message ?: "invalid snapshot"}"
                }
            }
            ProjectSummary(f, displayName(p.manifest?.name, p.metadata["Title"], f.nameWithoutExtension),
                p.manifest?.projectId ?: p.metadata[ProjectManifest.META_PROJECT_ID], f.lastModified(), p.objects.size,
                p.manifest?.printer?.model, listOfNotNull(p.manifestProblem, digestProblem, presetProblem).distinct()
                    .joinToString(" ").ifBlank { null })
        } catch (e: Exception) {
            ProjectSummary(f, f.nameWithoutExtension, null, f.lastModified(), 0, null, e.message ?: "This project can't be opened.")
        }
    }.sortedByDescending { it.modifiedMillis } }

    /** Saves a session snapshot outside the project heads before another version replaces the editor. */
    fun preserveSessionDraft(project: Project3mf): File = withLibraryLock {
        check(project.manifest != null) { "The current session has no project identity to preserve." }
        drafts.mkdirs()
        syncDirectory(dir)
        val target = File(drafts, "${System.currentTimeMillis()}-${UUID.randomUUID()}.3mf")
        ThreeMf.writeAtomically(project, target)
        syncDirectory(drafts)
        target
    }

    fun sessionDrafts(): List<ProjectSummary> = withLibraryLock {
        projectFiles(drafts).map { file ->
            try {
                val project = ThreeMf.read(file, ReadLimits())
                val manifest = project.manifest ?: error(project.manifestProblem ?: "This draft has no Nozzle project details.")
                ProjectSummary(file, displayName(manifest.name, project.metadata["Title"], "Untitled project"), manifest.projectId, file.lastModified(), project.objects.size,
                    manifest.printer?.model, project.manifestProblem)
            } catch (e: Exception) {
                ProjectSummary(file, file.nameWithoutExtension, null, file.lastModified(), 0, null,
                    e.message ?: "This draft cannot be read.")
            }
        }.sortedByDescending { it.modifiedMillis }
    }

    /** Copies a preserved session into its own project identity; the draft file stays untouched. */
    fun openSessionDraftAsCopy(draft: File): File = withLibraryLock {
        check(draft.isFile && draft.canonicalFile.parentFile == drafts.canonicalFile) {
            "Only a preserved session draft can be opened as a copy."
        }
        val project = ThreeMf.read(draft, ReadLimits())
        check(project.manifestProblem == null) { project.manifestProblem ?: "The draft cannot be opened." }
        val manifest = requireNotNull(project.manifest) { "The draft has no project details." }
        val name = "${displayName(manifest.name, project.metadata["Title"], "Untitled project")} (session draft)"
        val target = fileFor(name)
        saveUnlocked(project.copy(manifest = manifest.copy(projectId = UUID.randomUUID().toString(),
            revision = 1, name = name, modifiedAtMillis = System.currentTimeMillis())), target)
        target
    }

    fun fileFor(name: String): File {
        val base = name.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").trim().ifBlank { "Untitled" }.take(80)
        var f = File(dir, "$base.3mf"); var n = 2
        while (f.exists()) { f = File(dir, "$base ($n).3mf"); n++ }
        return f
    }

    fun newManifest(name: String, version: String) = ProjectManifest(UUID.randomUUID().toString(), 1, name,
        ProjectManifest.Producer("Nozzle It All", "desktop", version), ProjectManifest.Producer("Nozzle It All", "desktop", version), System.currentTimeMillis())

    /** Brings any STL/OBJ/3MF into the library as a new Nozzle project. The original file is never modified. */
    fun import(source: File, version: String): File = importInternal(source, version, null).file

    /** Repeat-safe migration of one legacy recent 3MF. Non-NIA files receive a stable migration-bound identity. */
    fun importLegacy(source: File, version: String, migrationKey: String): LegacyProjectImport =
        importInternal(source, version, UUID.nameUUIDFromBytes(migrationKey.toByteArray()).toString())

    /** Removes only a file created by the current failed migration transaction. */
    fun rollbackLegacyImport(result: LegacyProjectImport) {
        if (!result.created) return
        withLibraryLock {
            val target = result.file.canonicalFile
            require(target.parentFile == dir.canonicalFile && !Files.isSymbolicLink(result.file.toPath())) {
                "Refusing to roll back a project outside the NIA library."
            }
            Files.deleteIfExists(target.toPath())
            syncDirectory(dir)
        }
    }

    /** Removes a project created for a deterministic migration identity after an interrupted migration. */
    fun rollbackLegacyMigration(migrationKey: String) {
        val projectId = UUID.nameUUIDFromBytes(migrationKey.toByteArray()).toString()
        withLibraryLock {
            projectFiles(dir).filter { verifiedProjectId(it) == projectId }.forEach { target ->
                require(target.canonicalFile.parentFile == dir.canonicalFile && !Files.isSymbolicLink(target.toPath())) {
                    "Refusing to recover a migrated project outside the NIA library."
                }
                Files.deleteIfExists(target.toPath())
            }
            syncDirectory(dir)
        }
    }

    private fun importInternal(source: File, version: String, fallbackProjectId: String?): LegacyProjectImport {
        val project = if (source.name.endsWith(".3mf", true)) {
            val p = ThreeMf.read(source, ReadLimits(maxTriangles = 50_000_000))
            check(p.manifestProblem == null) {
                "${p.manifestProblem} The original file was left unchanged."
            }
            check(p.manifest != null || p.metadata.keys.none { it.startsWith("nozzle:", ignoreCase = true) }) {
                "This 3MF contains Nozzle project identity but its project details are missing. The original file was left unchanged."
            }
            p.manifest?.let { manifest ->
                val recordedDigest = p.metadata[ProjectManifest.META_MANIFEST_SHA256]
                check(recordedDigest != null && recordedDigest.equals(ThreeMf.sha256(manifest.canonicalText().toByteArray()), ignoreCase = true)) {
                    "This 3MF's Nozzle project details differ from its recorded digest. The original file was left unchanged."
                }
            }
            val lastPlate = maxOf(p.plateNames.lastIndex, p.objects.mapNotNull { it.plate }.maxOrNull() ?: 0)
            p.copy(manifest = p.manifest ?: newManifest(source.nameWithoutExtension, version).copy(
                projectId = fallbackProjectId ?: UUID.randomUUID().toString(), plates = (0..lastPlate).map { index ->
                ProjectManifest.PlateEntry(index + 1, p.plateNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "Plate ${index + 1}",
                    p.objects.filter { (it.plate ?: 0) == index }.map {
                        ProjectManifest.ObjectEntry(it.id, it.name, it.filament, settings = it.settings)
                    }, id = UUID.randomUUID().toString())
            }))
        } else {
            val mesh = MeshIO.read(source)
            Project3mf(listOf(ModelObject(1, source.nameWithoutExtension, mesh)), mapOf("Title" to source.nameWithoutExtension),
                newManifest(source.nameWithoutExtension, version).copy(plates = listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, source.nameWithoutExtension, 1)),
                    id = UUID.randomUUID().toString()))))
        }
        return withLibraryLock {
            val manifest = requireNotNull(project.manifest)
            val projectId = manifest.projectId
            check(projectId.isNotBlank()) { "The imported project's identity is blank. The original file was left unchanged." }
            check(manifest.revision >= 1) { "The imported project has no valid revision." }
            val existing = (projectFiles(dir) + projectFiles(trash)).firstOrNull { verifiedProjectId(it) == projectId }
            if (existing != null) {
                if (fallbackProjectId != null) return@withLibraryLock LegacyProjectImport(existing, false)
                error("Project $projectId already exists as ${existing.name}. The original file was left unchanged.")
            }
            check(revisionArchive.archived(projectId).isEmpty()) {
                "Project $projectId still has archived history. The original file was left unchanged."
            }
            check(pendingRecoveriesUnlocked().none { it.projectId == projectId }) {
                "Project $projectId has an interrupted save awaiting review. The original file was left unchanged."
            }
            val target = fileFor(source.nameWithoutExtension)
            ThreeMf.writeAtomically(project, target)
            syncDirectory(dir)
            LegacyProjectImport(target, true)
        }
    }

    fun save(project: Project3mf, file: File) = withLibraryLock { saveUnlocked(project, file) }

    fun saveNew(project: Project3mf, name: String): File = withLibraryLock {
        val target = fileFor(name)
        saveUnlocked(project, target)
        target
    }

    private fun saveUnlocked(project: Project3mf, file: File, preparedBytes: File? = null,
                             allowReviewedMissingHead: Boolean = false) {
        check(file.canonicalFile.parentFile == dir.canonicalFile && file.name.endsWith(".3mf", true)) {
            "A project can only be saved in this library's projects folder."
        }
        val manifest = requireNotNull(project.manifest) { "A Nozzle project needs an identity before saving." }
        check(manifest.projectId.isNotBlank()) { "A saved project needs a nonblank identity." }
        check(manifest.revision >= 1) { "A saved project needs a positive revision." }
        val id = manifest.projectId
        val archived = revisionArchive.archived(id)
        check(archived.none { it.problem != null }) { "Project $id has an unreadable archived revision; its history must be resolved before saving." }
        check((projectFiles(dir) + projectFiles(trash)).none { it.canonicalFile != file.canonicalFile && verifiedProjectId(it) == id }) {
            "Project $id already exists in another library file."
        }
        val hadHead = file.exists()
        if (hadHead) {
            check(verifiedProjectId(file) == id) { "Saving would replace a different project." }
            if (preparedBytes == null) check(pendingRecoveriesUnlocked().none {
                it.target.canonicalFile == file.canonicalFile && it.projectId == id && it.problem == null && !it.alreadyApplied
            }) { "This project has an interrupted save awaiting review. Resolve it before saving." }
            val previous = requireNotNull(ThreeMf.read(file, ReadLimits(maxTriangles = 50_000_000)).manifest)
            check(manifest.revision == previous.revision + 1) { "A save must create the next project revision." }
            check(archived.none { it.revision > previous.revision }) { "The saved head is older than its archived history." }
            check(archived.filter { it.revision == previous.revision }.all { it.contentSha256 == sha256(file) }) {
                "The saved head conflicts with its archived revision."
            }
            revisionArchive.capture(file, previous)
        } else {
            val expected = archived.maxOfOrNull { it.revision }?.plus(1) ?: 1L
            check(manifest.revision == expected || (allowReviewedMissingHead && preparedBytes != null &&
                manifest.revision > expected && archived.none { it.revision >= manifest.revision })) {
                "A new project head must follow its archived history."
            }
            check(preparedBytes != null || pendingRecoveriesUnlocked().none { it.projectId == id }) {
                "Project $id has an interrupted save awaiting review."
            }
        }
        if (preparedBytes == null) {
            if (hadHead) ThreeMf.writeAtomically(project, file)
            else ThreeMf.writeAtomicallyNew(project, file)
        }
        else if (hadHead) Files.move(preparedBytes.toPath(), file.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        else Files.createLink(file.toPath(), preparedBytes.toPath())
        syncDirectory(dir)
    }

    internal fun revisions(projectId: String): List<ProjectRevisionArchive.Revision> = withLibraryLock {
        val archived = revisionArchive.archived(projectId)
        val current = (projectFiles(dir) + projectFiles(trash)).mapNotNull { file ->
            try {
                val project = ThreeMf.read(file, ReadLimits(maxTriangles = 50_000_000))
                val manifest = project.manifest
                when {
                    manifest?.projectId == projectId -> {
                        val recorded = project.metadata[ProjectManifest.META_MANIFEST_SHA256]
                        if (recorded == null || !recorded.equals(ThreeMf.sha256(manifest.canonicalText().toByteArray()), ignoreCase = true))
                            ProjectRevisionArchive.Revision(projectId, manifest.revision, "", file, "This project head has no valid manifest digest.")
                        else revisionArchive.current(file, manifest)
                    }
                    manifest == null -> null // Unidentified files are reported by list(), not attributed to every project.
                    else -> null
                }
            } catch (_: Exception) { null } // The library list retains the file and its diagnostic.
        }
        // A crash after capture but before head replacement leaves a copy of the still-current head
        // in the archive. Keep that copy for retry, but do not show the same revision twice.
        archived.filterNot { old -> old.problem == null && current.any {
            it.problem == null && it.revision == old.revision && it.projectId == old.projectId && it.contentSha256 == old.contentSha256
        } } + current
    }

    /** Returns only the exact readable revision, with its plate identity checked before the editor changes. */
    internal fun readPrintProject(projectId: String, revision: Long, plateId: String): Pair<File, Project3mf> {
        val entry = revisions(projectId).filter { it.revision == revision && it.problem == null }.singleOrNull()
            ?: error("The exact project revision is unavailable.")
        check(sha256(entry.file) == entry.contentSha256) { "The project revision changed while opening." }
        val project = ThreeMf.read(entry.file)
        val manifest = requireNotNull(project.manifest) { "The project revision has no manifest." }
        check(manifest.projectId == projectId && manifest.revision == revision &&
            manifest.plates.any { (it.id ?: ProjectManifest.legacyPlateId(projectId, it.index)) == plateId } &&
            sha256(entry.file) == entry.contentSha256) { "The exact print plate could not be verified." }
        return entry.file to project
    }

    /** A crashed save can leave its readable .part beside the still-valid head. Inspection never applies it. */
    fun pendingRecoveries(): List<ProjectRecoveryCandidate> = withLibraryLock { pendingRecoveriesUnlocked() }

    private fun pendingRecoveriesUnlocked(): List<ProjectRecoveryCandidate> {
        // Both autosave timestamps and the UUID used for a crash-stable Restore copy are recoverable .part names.
        val pattern = Regex("^\\.(.+\\.3mf)\\.(?:[0-9]+|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.part$", RegexOption.IGNORE_CASE)
        return (dir.listFiles { f -> f.isFile && f.name.endsWith(".part") } ?: emptyArray()).map { part ->
            val name = pattern.matchEntire(part.name)?.groupValues?.get(1)
            if (name == null) ProjectRecoveryCandidate(part, File(dir, part.name), null, null, "Unrecognized project recovery filename.")
            else {
                val target = File(dir, name)
                try {
                    val candidate = ThreeMf.read(part, ReadLimits(maxTriangles = 50_000_000))
                    val manifest = candidate.manifest ?: error(candidate.manifestProblem ?: "The recovery copy has no Nozzle project identity.")
                    verifiedProjectId(part)
                    if (target.exists()) verifiedProjectId(target)
                    val current = if (target.exists()) ThreeMf.read(target, ReadLimits(maxTriangles = 50_000_000)).manifest else null
                    val otherCopy = (projectFiles(dir) + projectFiles(trash)).filter { it != target }
                        .firstOrNull { runCatching { verifiedProjectId(it) }.getOrNull() == manifest.projectId }
                    val archived = revisionArchive.archived(manifest.projectId)
                    val expectedWithoutHead = archived.maxOfOrNull { it.revision }?.plus(1) ?: 1L
                    val partDigest = sha256(part)
                    val targetDigest = if (target.exists()) sha256(target) else null
                    val applied = current != null && current.projectId == manifest.projectId && current.revision == manifest.revision && targetDigest == partDigest
                    val keepDecision = keepDecisionState(part, manifest.projectId, manifest.revision, target.name, partDigest, targetDigest)
                    val recordedKeepForMissingHead = keepDecisionFile(manifest.projectId, manifest.revision, target.name, partDigest).isFile ||
                        pathBoundKeepDecisionFile(part, partDigest).isFile
                    val openedRecord = openedCopyFile(manifest.projectId, manifest.revision, target.name, partDigest)
                    val recordText = if (openedRecord.isFile) runCatching { openedRecord.readText().trim() }.getOrNull() else null
                    val uuidPattern = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                    val intentCopyId = recordText?.let { Regex("^intent:($uuidPattern):.+$").matchEntire(it)?.groupValues?.get(1) }
                    val completeCopyId = recordText?.let { Regex("^complete:($uuidPattern):.+$").matchEntire(it)?.groupValues?.get(1) }
                    val openRecordInvalid = openedRecord.isFile && intentCopyId == null && completeCopyId == null
                    val alreadyOpened = completeCopyId != null || (intentCopyId != null &&
                        (projectFiles(dir) + projectFiles(trash)).any {
                            runCatching { verifiedProjectId(it) }.getOrNull() == intentCopyId
                        })
                    val previouslyReviewedMissingHead = current == null &&
                        (keepDecision.second == KeepChoiceReviewReason.SAVED_HEAD_MISSING ||
                            (keepDecision.first && recordedKeepForMissingHead)) &&
                        archived.none { it.revision >= manifest.revision }
                    val orphanedMissingHead = current == null &&
                        (manifest.revision != expectedWithoutHead || archived.any { it.problem != null }) &&
                        !previouslyReviewedMissingHead
                    val problem = when {
                        otherCopy != null -> "The project identity is already held by ${otherCopy.name}."
                        current != null && current.projectId != manifest.projectId -> "The saved project has a different identity."
                        current != null && archived.any { it.revision > current.revision } -> "The saved project is older than its archived history."
                        applied -> null
                        current != null && manifest.revision != current.revision + 1 -> "The recovery copy is not the next revision of the saved project."
                        orphanedMissingHead && alreadyOpened ->
                            "An Open as copy choice was recorded for this recovery. The original recovery file remains."
                        orphanedMissingHead && openRecordInvalid ->
                            "A previous Open as copy record cannot be verified. The recovery file remains; resolve the record before making another copy."
                        orphanedMissingHead && archived.any { it.problem != null } ->
                            "The project's archived history has an unreadable revision. Inspect this recovery and open it as a separate project copy."
                        orphanedMissingHead && archived.any { it.revision >= manifest.revision } ->
                            "This recovery overlaps newer archived history. Inspect it and open it as a separate project copy."
                        orphanedMissingHead && keepDecision.second == KeepChoiceReviewReason.RECORD_UNVERIFIABLE ->
                            "The saved head is missing and the previous Keep record cannot be verified. Open the recovery as a separate project copy."
                        orphanedMissingHead ->
                            "The saved head is missing and this recovery's parent revision cannot be verified. Open it as a separate project copy."
                        archived.any { it.problem != null } -> "The project's archived history has an unreadable revision."
                        previouslyReviewedMissingHead -> null
                        current == null && manifest.revision != expectedWithoutHead -> "The recovery copy does not follow the project's archived history."
                        else -> null
                    }
                    ProjectRecoveryCandidate(part, target, manifest.projectId, manifest.revision,
                        problem, partDigest, applied, targetDigest,
                        !applied && keepDecision.first, keepDecision.second.takeUnless { applied },
                        orphanedMissingHead && otherCopy == null && !alreadyOpened && !openRecordInvalid)
                } catch (e: Exception) { ProjectRecoveryCandidate(part, target, null, null, e.message ?: "The recovery copy cannot be read.") }
            }
        }
    }

    /** Persists the choice without deleting the recoverable copy or changing the saved head. */
    fun keepSavedVersion(inspected: ProjectRecoveryCandidate) = withLibraryLock {
        val current = pendingRecoveriesUnlocked().firstOrNull { it.part == inspected.part }
            ?: error("This recovery copy is no longer in the library.")
        check(current.problem == null && !current.alreadyApplied &&
            current.contentSha256 == inspected.contentSha256 && current.targetSha256 == inspected.targetSha256) {
            "The saved project or recovery copy changed. Compare them again."
        }
        val marker = keepDecisionFile(requireNotNull(current.projectId), requireNotNull(current.revision),
            current.target.name, requireNotNull(current.contentSha256))
        val decision = keepDecisionText(current.targetSha256)
        recoveryDecisions.mkdirs()
        syncDirectory(dir)
        if (!marker.isFile || runCatching { marker.readText() }.getOrNull() != decision) {
            val temporary = File(recoveryDecisions, ".${UUID.randomUUID()}.part")
            try {
                temporary.writeText(decision)
                FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
                Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                syncDirectory(recoveryDecisions)
            } finally { temporary.delete() }
        }
    }

    /** Recovers an orphaned part under a new identity while retaining its original bytes and unresolved lineage. */
    fun openRecoveryAsCopy(inspected: ProjectRecoveryCandidate): File = withLibraryLock {
        val current = pendingRecoveriesUnlocked().firstOrNull { it.part == inspected.part }
            ?: error("This recovery copy is no longer in the library.")
        check(current.canOpenAsCopy && current.target == inspected.target &&
            current.contentSha256 == inspected.contentSha256 && current.targetSha256 == inspected.targetSha256) {
            "The recovery copy or library changed. Review it again."
        }
        val project = ThreeMf.read(current.part, ReadLimits(maxTriangles = 50_000_000))
        check(verifiedProjectId(current.part) == current.projectId && project.manifest?.revision == current.revision) {
            "The recovery copy changed during inspection. Review it again."
        }
        check(sha256(current.part) == inspected.contentSha256) {
            "The recovery copy changed during inspection. Review it again."
        }
        val manifest = requireNotNull(project.manifest)
        val name = "${displayName(manifest.name, project.metadata["Title"], "Untitled project")} (recovered copy)"
        val target = fileFor(name)
        val copyId = UUID.randomUUID().toString()
        val record = openedCopyFile(manifest.projectId, manifest.revision, current.target.name,
            requireNotNull(current.contentSha256))
        writeOpenedCopyRecord(record, "intent:$copyId:${target.name}\n")
        saveUnlocked(project.copy(manifest = manifest.copy(projectId = copyId,
            revision = 1, name = name, modifiedAtMillis = System.currentTimeMillis())), target)
        writeOpenedCopyRecord(record, "complete:$copyId:${target.name}\n")
        target
    }

    /** Applies only the exact bytes inspected by the user, while preserving the original recovery copy. */
    fun recoverFromPart(inspected: ProjectRecoveryCandidate): File = withLibraryLock {
        val candidate = pendingRecoveriesUnlocked().firstOrNull { it.part == inspected.part }
            ?: error("This recovery copy is not in the library.")
        check(candidate.target == inspected.target && candidate.targetSha256 == inspected.targetSha256) {
            "The saved project changed since recovery was inspected. Review it again."
        }
        check(candidate.problem == null) { candidate.problem ?: "The recovery copy cannot be applied." }
        check(!candidate.alreadyApplied) { "This recovery copy has already been applied." }
        check(candidate.contentSha256 != null && candidate.contentSha256 == inspected.contentSha256) {
            "The recovery copy changed since it was inspected. Review it again."
        }
        val stable = File(dir, ".${candidate.target.name}.${UUID.randomUUID()}.part")
        try {
            Files.copy(candidate.part.toPath(), stable.toPath())
            FileChannel.open(stable.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            check(sha256(stable) == candidate.contentSha256) { "The recovery copy changed during application. Review it again." }
            val project = ThreeMf.read(stable, ReadLimits(maxTriangles = 50_000_000))
            check(verifiedProjectId(stable) == candidate.projectId && project.manifest?.revision == candidate.revision) {
                "The recovery copy's identity changed during application."
            }
            saveUnlocked(project, candidate.target, stable, allowReviewedMissingHead = candidate.targetSha256 == null)
            candidate.target
        } finally { stable.delete() }
    }

    fun moveToTrash(file: File): File = withLibraryLock {
        check(file.isFile && file.canonicalFile.parentFile == dir.canonicalFile) { "Only a saved library project can be moved to its trash." }
        trash.mkdirs()
        val dest = File(trash, "${UUID.randomUUID().toString().replace("-", "")}--${file.name}")
        check(!dest.exists()) { "Couldn't reserve a unique trash entry." }
        Files.move(file.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(trash)
        syncDirectory(dir)
        dest
    }

    fun restore(trashed: File): File = withLibraryLock {
        check(trashed.isFile && trashed.canonicalFile.parentFile == trash.canonicalFile) { "Only a project in this library's trash can be restored." }
        val id = verifiedProjectId(trashed)
        check(projectFiles(dir).none { verifiedProjectId(it) == id }) { "Project $id already exists in the library." }
        check(projectFiles(trash).filter { it != trashed }.none { verifiedProjectId(it) == id }) {
            "Project $id has another trash copy; resolve that duplicate before restoring."
        }
        val originalName = (if (Regex("^[0-9a-f]{32}--").containsMatchIn(trashed.name)) trashed.name.substringAfter("--")
            else Regex("^[0-9]+-(.+\\.3mf)$", RegexOption.IGNORE_CASE).matchEntire(trashed.name)?.groupValues?.get(1)
        )?.takeIf { it.endsWith(".3mf", true) }
            ?: error("This trash entry has no original project filename.")
        val dest = File(dir, originalName)
        check(dest.canonicalFile.parentFile == dir.canonicalFile && !dest.exists()) {
            "The original project filename is already in use."
        }
        Files.move(trashed.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(dir)
        syncDirectory(trash)
        dest
    }
}
