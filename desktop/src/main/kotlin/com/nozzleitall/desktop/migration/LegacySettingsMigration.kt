package com.nozzleitall.desktop.migration

import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.desktop.PrinterStore
import com.nozzleitall.desktop.materials.FilamentPresetStore
import com.nozzleitall.desktop.projects.ProjectLibrary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Explicit, copy-only intake of compatible data from Orca-family settings directories into NIA-owned storage. */
class LegacySettingsMigration(private val paths: AppPaths, private val filaments: FilamentPresetStore,
                              private val processes: LegacyProcessPresetStore? = null,
                              private val projects: ProjectLibrary? = null,
                              private val importTheme: ((String) -> Boolean)? = null,
                              private val appVersion: String = "0.1.0-dev",
                              private val allowedProjectRoots: List<File> = listOf(File(System.getProperty("user.home"))),
                              private val committedJournalCleanup: (File) -> Unit = { Files.deleteIfExists(it.toPath()) }) {
    enum class Kind { NIA_DESKTOP, HYBRID_ORCA, SNAPMAKER_ORCA, ORCA_SLICER }
    enum class ImportCategory { FILAMENTS, PROCESSES_AND_MACHINES, RECENT_PROJECTS, APPEARANCE }
    data class Source(val kind: Kind, val label: String, val root: File, val configNames: List<String>)
    data class Profile(val category: String, val relativePath: String, val json: JSONObject, val sanitized: Boolean)
    data class Prepared(val source: Source, val profiles: List<Profile>, val projects: List<File>, val sourceHashes: Map<String, String>,
                        val projectHashes: Map<String, String>, val theme: String?,
                        val skipped: List<String>, val digest: String,
                        val categories: Set<ImportCategory> = ImportCategory.entries.toSet()) {
        val filaments get() = profiles.filter { it.category == "filament" }
        val preservedOnly get() = profiles.filter { it.category != "filament" }
        val sanitizedCount get() = profiles.count(Profile::sanitized)
    }
    data class Result(val report: File, val backup: File?, val importedFilaments: Int, val importedProcesses: Int,
                      val importedProjects: Int, val importedPreferences: Int, val preservedProfiles: Int,
                      val skipped: Int, val repeated: Boolean)

    fun discover(env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home"),
                 os: String = System.getProperty("os.name")): List<Source> {
        val target = paths.config.canonicalFile
        val candidates = if (os.startsWith("Windows", true)) {
            val config = File(env["APPDATA"] ?: "$home\\AppData\\Roaming")
            listOf(
                Source(Kind.NIA_DESKTOP, "Nozzle It All Desktop", File(config, "nozzle-it-all"), NIA_CONFIGS),
                Source(Kind.SNAPMAKER_ORCA, "Snapmaker Orca", File(config, "Snapmaker_Orca"), SNAPMAKER_CONFIGS),
                Source(Kind.ORCA_SLICER, "OrcaSlicer", File(config, "OrcaSlicer"), ORCA_CONFIGS),
            )
        } else {
            val xdg = File(env["XDG_CONFIG_HOME"]?.takeIf { it.startsWith("/") } ?: "$home/.config")
            listOf(
                Source(Kind.NIA_DESKTOP, "Nozzle It All Desktop", File(xdg, "nozzle-it-all"), NIA_CONFIGS),
                Source(Kind.HYBRID_ORCA, "Hybrid Orca", File(home, ".var/app/com.orcaslicer.OrcaSlicer/config/OrcaSlicer"), ORCA_CONFIGS),
                Source(Kind.SNAPMAKER_ORCA, "Snapmaker Orca", File(home, ".var/app/io.github.Snapmaker.Snapmaker_Orca/config/Snapmaker_Orca"), SNAPMAKER_CONFIGS),
                Source(Kind.SNAPMAKER_ORCA, "Snapmaker Orca", File(xdg, "Snapmaker_Orca"), SNAPMAKER_CONFIGS),
                Source(Kind.ORCA_SLICER, "OrcaSlicer", File(xdg, "OrcaSlicer"), ORCA_CONFIGS),
            )
        }
        return candidates.filter { source ->
            runCatching { source.root.canonicalFile != target && meaningful(source) }.getOrDefault(false)
        }.distinctBy { it.root.canonicalPath }
    }

    fun prepare(source: Source): Prepared {
        require(!Files.isSymbolicLink(source.root.toPath())) { "The selected settings source cannot be a symbolic link." }
        val root = source.root.canonicalFile
        require(root.isDirectory) { "The selected settings source is no longer available." }
        val profiles = mutableListOf<Profile>()
        val skipped = mutableListOf<String>()
        val recentProjects = linkedSetOf<File>()
        val sourceHashes = linkedMapOf<String, String>()
        var theme: String? = null
        val digest = MessageDigest.getInstance("SHA-256")
        var seen = 0
        for (category in PROFILE_CATEGORIES) {
            val categoryRoots = File(root, "user").listFiles()?.filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
                ?.map { File(it, category) }?.filter(File::isDirectory).orEmpty()
            for (folder in categoryRoots) {
                folder.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter(File::isFile).forEach { file ->
                    if (++seen > MAX_FILES) throw IllegalArgumentException("The settings source contains too many preset files.")
                    val relativeFile = runCatching { file.canonicalFile }.getOrNull()
                    if (Files.isSymbolicLink(file.toPath()) || relativeFile == null || !inside(root, relativeFile)) {
                        skipped += "${file.name} — symbolic link or outside selected source"
                        return@forEach
                    }
                    val relative = root.toPath().relativize(relativeFile.toPath()).toString().replace(File.separatorChar, '/')
                    if (relative.split('/').any { it == ".." }) { skipped += "$relative — unsafe path"; return@forEach }
                    digestField(digest, relative)
                    if (!file.name.endsWith(".json", true)) { skipped += "$relative — unsupported file type"; return@forEach }
                    if (file.length() !in 1..MAX_JSON_BYTES) { skipped += "$relative — invalid size"; return@forEach }
                    val bytes = runCatching { file.readBytes() }.getOrElse { skipped += "$relative — unreadable"; return@forEach }
                    sourceHashes[relative] = sha256(bytes)
                    digest.update(bytes)
                    val parsed = runCatching { JSONObject(bytes.decodeToString()) }.getOrElse { skipped += "$relative — invalid JSON"; return@forEach }
                    val sanitized = sanitize(parsed)
                    if (category == "filament" && firstString(sanitized.first.opt("filament_type"))?.length !in 1..64) {
                        skipped += "$relative — missing or ambiguous filament type"
                        return@forEach
                    }
                    profiles += Profile(category, relative, sanitized.first, sanitized.second)
                }
            }
        }
        source.configNames.map { File(root, it) }.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && inside(root, it.canonicalFile) }.forEach { config ->
            digestField(digest, config.name)
            if (config.length() !in 1..MAX_CONFIG_BYTES) { skipped += "${config.name} — invalid config size"; return@forEach }
            val bytes = runCatching { config.readBytes() }.getOrElse { skipped += "${config.name} — unreadable"; return@forEach }
            sourceHashes[config.name] = sha256(bytes)
            digest.update(bytes)
            parseConfig(bytes.decodeToString()).also { known ->
                theme = known.theme ?: theme
                known.projects.forEach { candidate ->
                    val unresolved = File(candidate)
                    val file = if (unresolved.isAbsolute) unresolved else File(root, candidate)
                    val valid = runCatching { file.canonicalFile }.getOrNull()?.takeIf {
                        it.isFile && !Files.isSymbolicLink(file.toPath()) && (listOf(root) + allowedProjectRoots).any { allowed -> inside(allowed.canonicalFile, it) } &&
                            it.name.endsWith(".3mf", true) && it.length() in 1..MAX_PROJECT_BYTES
                    }
                    if (valid != null) recentProjects += valid else skipped += "${config.name} — unavailable recent 3MF: ${File(candidate).name}"
                }
            }
        }
        val projectHashes = linkedMapOf<String, String>()
        recentProjects.sortedBy { it.canonicalPath }.forEach { project ->
            val hash = sha256(project)
            projectHashes[project.canonicalPath] = hash
            digestField(digest, project.canonicalPath)
            digestField(digest, project.length().toString())
            digestField(digest, hash)
        }
        require(profiles.isNotEmpty() || recentProjects.isNotEmpty() || theme != null) { "No compatible presets, projects or preferences were found in this source." }
        return Prepared(source, profiles.sortedBy(Profile::relativePath), recentProjects.toList(), sourceHashes, projectHashes, theme,
            skipped.sorted(), hex(digest.digest()))
    }

    fun apply(prepared: Prepared): Result = withMigrationLock {
        recoverInterruptedMigrations()
        applyLocked(prepared)
    }

    /** Binds the reviewed category choices into the migration identity before anything is written. */
    fun select(prepared: Prepared, categories: Set<ImportCategory>): Prepared {
        require(categories.isNotEmpty()) { "Choose at least one settings category to import." }
        val profiles = prepared.profiles.filter { profile ->
            when (profile.category) {
                "filament" -> ImportCategory.FILAMENTS in categories
                "machine", "process" -> ImportCategory.PROCESSES_AND_MACHINES in categories
                else -> false
            }
        }
        val projects = if (ImportCategory.RECENT_PROJECTS in categories) prepared.projects else emptyList()
        val projectHashes = prepared.projectHashes.filterKeys { path -> projects.any { it.canonicalPath == path } }
        val theme = prepared.theme.takeIf { ImportCategory.APPEARANCE in categories }
        require(profiles.isNotEmpty() || projects.isNotEmpty() || theme != null) {
            "The selected categories contain no compatible data in this source."
        }
        val identity = buildString {
            append(prepared.digest)
            append('\n')
            append(categories.sortedBy { it.name }.joinToString(",") { it.name })
        }
        return prepared.copy(
            profiles = profiles,
            projects = projects,
            projectHashes = projectHashes,
            theme = theme,
            digest = sha256(identity.toByteArray()),
            categories = categories.toSet(),
        )
    }

    private fun applyLocked(prepared: Prepared): Result {
        val destination = File(paths.data, "migrations/${prepared.digest}")
        safeRecoveryPath(paths.data, destination, "destination")
        val report = File(destination, "report.json")
        if (completedReport(report, prepared.digest)) {
            return Result(report, null, 0, 0, 0, 0, prepared.preservedOnly.size, prepared.skipped.size, true)
        }
        val backup = backupCurrentConfiguration()
        val stage = File(paths.data, ".migration-${UUID.randomUUID()}")
        val journal = File(paths.data, "migration-transactions/${prepared.digest}.json")
        val projectMigrationKeys = prepared.projects.map { "${prepared.digest}\n${it.canonicalPath}" }
        val createdProjects = mutableListOf<com.nozzleitall.desktop.projects.LegacyProjectImport>()
        val result = try {
            verifyPreparedSource(prepared)
            prepared.profiles.forEach { profile ->
                val target = File(stage, "profiles/${profile.relativePath}")
                require(inside(stage.canonicalFile, target.canonicalFile)) { "A preset path escaped the migration archive." }
                target.parentFile.mkdirs()
                PrinterStore.atomicWrite(target, profile.json.toString(2), private = false)
            }
            val stagedProjects = prepared.projects.mapIndexed { index, source ->
                val expected = prepared.projectHashes[source.canonicalPath] ?: error("A recent project has no prepared identity.")
                require(sha256(source) == expected) { "Recent project ${source.name} changed after review; review the source again." }
                val target = File(stage, "recent-projects/${index.toString().padStart(3, '0')}/${safeName(source.name)}")
                require(inside(stage.canonicalFile, target.canonicalFile)) { "A recent project path escaped the migration archive." }
                target.parentFile.mkdirs(); Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                require(sha256(target) == expected) { "Recent project ${source.name} changed while being copied." }
                target
            }
            verifyPreparedSource(prepared)
            writeTransactionJournal(journal, prepared.digest, backup, stage, projectMigrationKeys)
            verifyPreparedSource(prepared)
            prepared.filaments.forEach { filaments.importLegacy(prepared.source.label, it.relativePath, it.json) }
            val processResult = processes?.import(prepared.source.label, prepared.profiles)
            val projectResult = importProjects(prepared, stagedProjects, createdProjects)
            val importedPreferences = prepared.theme?.let { if (importTheme?.invoke(it) == true) 1 else 0 } ?: 0
            val allSkipped = prepared.skipped + processResult?.unresolved.orEmpty() + projectResult.skipped
            val summary = JSONObject()
                .put("schema", 2).put("status", "complete").put("sourceKind", prepared.source.kind.name).put("sourceLabel", prepared.source.label)
                .put("sourcePath", prepared.source.root.canonicalPath).put("sourceDigest", prepared.digest)
                .put("selectedCategories", JSONArray(prepared.categories.map { it.name }.sorted()))
                .put("sourceChanged", false).put("credentialsCopied", false).put("updaterStateCopied", false)
                .put("importedFilaments", prepared.filaments.size).put("importedProcessPresets", processResult?.imported ?: 0)
                .put("importedRecentProjects", projectResult.imported).put("importedPreferences", importedPreferences)
                .put("preservedPrinterProfiles", prepared.profiles.count { it.category == "machine" })
                .put("preservedProcessProfiles", prepared.profiles.count { it.category == "process" })
                .put("preservedUnresolvedProcessProfiles", processResult?.unresolved.orEmpty().size)
                .put("rebasedFilaments", JSONArray(prepared.filaments.map { profile -> JSONObject()
                    .put("path", profile.relativePath).put("originalInherits", profile.json.optString("inherits"))
                    .put("compatiblePrinters", profile.json.opt("compatible_printers") ?: JSONArray())
                    .put("note", "Stored as sanitized overrides; activation requires a compatible bundled NIA material base and retained printer constraints.") }))
                .put("sanitizedFiles", prepared.sanitizedCount).put("skipped", JSONArray(allSkipped))
                .put("backup", backup?.absolutePath ?: JSONObject.NULL)
                .put("note", "Only sanitized compatible preset data and verified recent-project copies are archived. Unsupported files and source configuration are excluded and listed in this report. Process presets are activated only when inheritance resolves to exactly one bundled NIA machine and process.")
            PrinterStore.atomicWrite(File(stage, "report.json"), summary.toString(2), private = false)
            destination.parentFile.mkdirs()
            Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Result(report, backup, prepared.filaments.size, processResult?.imported ?: 0, projectResult.imported, importedPreferences,
                prepared.profiles.count { it.category == "machine" } + (prepared.profiles.count { it.category == "process" } - (processResult?.imported ?: 0)).coerceAtLeast(0), allSkipped.size, false)
        } catch (failure: Throwable) {
            val rollback = runCatching {
                createdProjects.asReversed().forEach { projects?.rollbackLegacyImport(it) }
                rollbackTransaction(backup, projectMigrationKeys, destination, stage)
                Files.deleteIfExists(journal.toPath())
            }.exceptionOrNull()
            if (rollback != null) failure.addSuppressed(rollback)
            throw failure
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
        // Publication is the commit boundary. Cleanup failure is recoverable on the next startup and must not turn a
        // committed migration into a caller-visible failure.
        runCatching { committedJournalCleanup(journal) }
        return result
    }

    private fun <T> withMigrationLock(action: () -> T): T {
        val folder = File(paths.data, "migration-transactions").apply { mkdirs() }
        return FileChannel.open(File(folder, ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { action() }
        }
    }

    private fun verifyPreparedSource(prepared: Prepared) {
        val root = prepared.source.root.canonicalFile
        prepared.sourceHashes.forEach { (relative, expected) ->
            val file = File(root, relative)
            require(!Files.isSymbolicLink(file.toPath()) && file.isFile && inside(root, file.canonicalFile) && sha256(file) == expected) {
                "The selected settings source changed after review; review it again before importing."
            }
        }
        prepared.projectHashes.forEach { (path, expected) ->
            val file = File(path)
            require(!Files.isSymbolicLink(file.toPath()) && file.isFile && sha256(file) == expected) {
                "A recent project changed after review; review it again before importing."
            }
        }
    }

    private fun writeTransactionJournal(journal: File, digest: String, backup: File?, stage: File, projectMigrationKeys: List<String>) {
        val value = JSONObject().put("schema", 1).put("status", "pending").put("digest", digest)
            .put("backup", backup?.canonicalPath ?: JSONObject.NULL).put("stage", stage.canonicalPath)
            .put("projectMigrationKeys", JSONArray(projectMigrationKeys))
        PrinterStore.atomicWrite(journal, value.toString(2), private = true)
    }

    private fun recoverInterruptedMigrations() {
        val folder = File(paths.data, "migration-transactions")
        folder.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.sortedBy(File::getName)?.forEach { journal ->
            val value = runCatching { JSONObject(journal.readText()) }.getOrElse { return@forEach }
            if (value.optInt("schema") != 1 || value.optString("status") != "pending") return@forEach
            val digest = value.optString("digest").takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return@forEach
            val destination = File(paths.data, "migrations/$digest")
            safeRecoveryPath(paths.data, destination, "destination")
            if (completedReport(destination.resolve("report.json"), digest)) {
                Files.deleteIfExists(journal.toPath())
                return@forEach
            }
            val backup = value.optString("backup").takeIf(String::isNotBlank)?.let {
                safeRecoveryPath(File(paths.data, "migration-backups"), File(it), "backup")
            }
            val stage = value.optString("stage").takeIf(String::isNotBlank)?.let {
                safeRecoveryPath(paths.data, File(it), "staging").also { safe ->
                    require(safe.name.startsWith(".migration-")) { "An interrupted migration referenced an unsafe staging path." }
                }
            }
            val keys = value.optJSONArray("projectMigrationKeys")?.let { rows ->
                (0 until rows.length()).mapNotNull { rows.optString(it).takeIf { key -> key.startsWith("$digest\n") } }
            }.orEmpty()
            rollbackTransaction(backup, keys, destination, stage)
            Files.deleteIfExists(journal.toPath())
        }
    }

    private fun completedReport(report: File, digest: String): Boolean =
        report.isFile && !Files.isSymbolicLink(report.toPath()) && runCatching { JSONObject(report.readText()) }.getOrNull()?.let { summary ->
            summary.optInt("schema") == 2 && summary.optString("status") == "complete" &&
                summary.optString("sourceDigest") == digest
        } == true

    private fun safeRecoveryPath(root: File, candidate: File, label: String): File {
        val rootPath = root.canonicalFile.toPath()
        val candidatePath = candidate.toPath().toAbsolutePath().normalize()
        require(candidatePath != rootPath && candidatePath.startsWith(rootPath)) {
            "An interrupted migration referenced an unsafe $label path."
        }
        var cursor = rootPath
        rootPath.relativize(candidatePath).forEach { component ->
            cursor = cursor.resolve(component)
            require(!Files.isSymbolicLink(cursor)) { "An interrupted migration referenced a symbolic-link $label path." }
        }
        return candidatePath.toFile()
    }

    private fun rollbackTransaction(backup: File?, projectMigrationKeys: List<String>, destination: File, stage: File?) {
        projectMigrationKeys.asReversed().forEach { projects?.rollbackLegacyMigration(it) }
        val safeBackup = backup?.let { safeRecoveryPath(File(paths.data, "migration-backups"), it, "backup") }
        val safeStage = stage?.let {
            safeRecoveryPath(paths.data, it, "staging").also { candidate ->
                require(candidate.name.startsWith(".migration-")) { "An interrupted migration referenced an unsafe staging path." }
            }
        }
        restoreConfiguration(safeBackup)
        filaments.reload()
        if (destination.exists()) destination.deleteRecursively()
        if (safeStage != null && safeStage.exists()) {
            safeStage.deleteRecursively()
        }
    }

    private data class KnownConfig(val projects: List<String>, val theme: String?)

    private fun parseConfig(text: String): KnownConfig {
        require(text.length <= MAX_CONFIG_BYTES) { "Settings config is too large." }
        val projects = mutableListOf<Pair<String, String>>()
        var section = ""
        var theme: String? = null
        text.lineSequence().take(MAX_CONFIG_LINES + 1).forEachIndexed { index, raw ->
            require(index < MAX_CONFIG_LINES) { "Settings config has too many lines." }
            val line = raw.trim()
            if (line.isBlank() || line.startsWith('#') || line.startsWith(';')) return@forEachIndexed
            if (line.startsWith('[') && line.endsWith(']')) { section = line.substring(1, line.length - 1).trim().lowercase(); return@forEachIndexed }
            val split = line.indexOf('='); if (split <= 0) return@forEachIndexed
            val key = line.substring(0, split).trim(); val value = line.substring(split + 1).trim()
            if (section == "recent_projects" && key.all(Char::isDigit) && value.length in 1..4096) projects += key to value
            if (key == "dark_color_mode") theme = when (value.lowercase()) { "1", "true", "on" -> "dark"; "0", "false", "off" -> "light"; else -> theme }
        }
        return KnownConfig(projects.sortedBy { it.first }.take(MAX_RECENT_PROJECTS).map { it.second }, theme)
    }

    private data class ProjectImport(val imported: Int, val skipped: List<String>)

    private fun importProjects(prepared: Prepared, staged: List<File>, created: MutableList<com.nozzleitall.desktop.projects.LegacyProjectImport>): ProjectImport {
        val library = projects ?: return ProjectImport(0, emptyList())
        val skipped = mutableListOf<String>()
        var imported = 0
        prepared.projects.zip(staged).forEach { (original, project) ->
            runCatching { library.importLegacy(project, appVersion, "${prepared.digest}\n${original.canonicalPath}") }
                .onSuccess { if (it.created) { imported++; created += it } }
                .onFailure { skipped += "recent project ${project.name} — ${it.message ?: "could not be imported"}" }
        }
        return ProjectImport(imported, skipped)
    }

    private fun backupCurrentConfiguration(): File? {
        val targets = listOf(paths.filamentPresets, paths.legacyProcessPresets, paths.settings)
        val parent = File(paths.data, "migration-backups").apply { mkdirs() }
        val stage = File(parent, ".${Instant.now().toEpochMilli()}-${UUID.randomUUID()}.part")
        val destination = File(parent, "${Instant.now().toEpochMilli()}-${UUID.randomUUID()}")
        try {
            stage.mkdirs()
            val inventory = JSONArray()
            targets.forEach { original ->
                val row = JSONObject().put("name", original.name).put("existed", original.isFile)
                if (original.isFile) {
                    val copy = File(stage, original.name)
                    PrinterStore.atomicWrite(copy, original.readText(), private = true)
                    row.put("sha256", sha256(copy))
                }
                inventory.put(row)
            }
            PrinterStore.atomicWrite(File(stage, "manifest.json"), JSONObject()
                .put("schema", 1).put("createdAt", Instant.now().toString()).put("files", inventory).toString(2), private = true)
            Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return destination
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    private fun restoreConfiguration(backup: File?) {
        if (backup == null) return
        val manifest = JSONObject(backup.resolve("manifest.json").readText())
        val rows = manifest.getJSONArray("files")
        val targets = listOf(paths.filamentPresets, paths.legacyProcessPresets, paths.settings).associateBy { it.name }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val target = targets[row.getString("name")] ?: error("Unknown migration backup target")
            if (!row.getBoolean("existed")) Files.deleteIfExists(target.toPath()) else {
                val copy = backup.resolve(target.name)
                require(sha256(copy) == row.getString("sha256")) { "Migration backup failed integrity verification." }
                PrinterStore.atomicWrite(target, copy.readText(), private = true)
            }
        }
    }

    private fun meaningful(source: Source): Boolean = source.configNames.any { File(source.root, it).isFile } ||
        File(source.root, "user").walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
            .any { it.isFile && it.name.endsWith(".json", true) && it.parentFile?.name in PROFILE_CATEGORIES }

    private fun sanitize(source: JSONObject): Pair<JSONObject, Boolean> {
        var changed = false
        fun clean(value: Any?): Any? = when (value) {
            is JSONObject -> JSONObject().also { out -> value.keySet().forEach { key ->
                val child = value.opt(key)
                if (secretKey(key) || child is String && secretValue(child)) changed = true
                else out.put(key, clean(child))
            } }
            is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) {
                val child = value.opt(i)
                if (child is String && secretValue(child)) changed = true else out.put(clean(child))
            } }
            is String -> sanitizeUrl(value).also { if (it != value) changed = true }
            else -> value
        }
        return clean(source) as JSONObject to changed
    }

    private fun firstString(value: Any?): String? = when (value) {
        is JSONArray -> value.optString(0).trim().takeIf(String::isNotBlank)
        is String -> value.trim().takeIf(String::isNotBlank)
        else -> null
    }

    private fun secretKey(raw: String): Boolean {
        val compact = raw.lowercase().filter(Char::isLetterOrDigit)
        return compact in SECRET_KEYS || SECRET_SUFFIXES.any { compact.length > it.length && compact.endsWith(it) }
    }

    private fun secretValue(raw: String): Boolean {
        val value = raw.trim().lowercase()
        return SECRET_PREFIXES.any { value.startsWith(it) && value.length > it.length + 4 } ||
            (raw.contains("-----BEGIN ") && raw.contains("PRIVATE KEY-----"))
    }

    private fun sanitizeUrl(raw: String): String {
        if (!raw.contains("://")) return raw
        return runCatching {
            val uri = URI(raw)
            var queryChanged = false
            val query = uri.rawQuery?.split('&')?.mapNotNull { part ->
                val key = part.substringBefore('=').replace("%5F", "_", true)
                if (secretKey(key) || secretValue(part.substringAfter('=', ""))) { queryChanged = true; null } else part
            }?.joinToString("&")
            if (uri.userInfo == null && uri.fragment == null && !queryChanged) raw
            else URI(uri.scheme, null, uri.host, uri.port, uri.path, query?.ifBlank { null }, null).toString()
        }.getOrElse { "" }
    }

    private fun inside(root: File, child: File): Boolean = child.toPath().startsWith(root.toPath())
    private fun safeName(raw: String): String = raw.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").take(160).ifBlank { "project.3mf" }
    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(1024 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        hex(digest.digest())
    }
    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun digestField(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray()
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val MAX_FILES = 4096
        private const val MAX_JSON_BYTES = 8L * 1024 * 1024
        private const val MAX_CONFIG_BYTES = 8L * 1024 * 1024
        private const val MAX_CONFIG_LINES = 200_000
        private const val MAX_RECENT_PROJECTS = 50
        private const val MAX_PROJECT_BYTES = 2L * 1024 * 1024 * 1024
        private val PROFILE_CATEGORIES = setOf("filament", "process", "machine")
        private val NIA_CONFIGS = listOf("nozzle-it-all.conf", "OrcaSlicer.conf", "OrcaSlicer.ini")
        private val SNAPMAKER_CONFIGS = listOf("Snapmaker_Orca.conf", "Snapmaker_Orca.ini", "OrcaSlicer.conf", "OrcaSlicer.ini")
        private val ORCA_CONFIGS = listOf("OrcaSlicer.conf", "OrcaSlicer.ini")
        private val SECRET_KEYS = setOf("accesscode", "useraccesscode", "password", "token", "accesstoken", "refreshtoken", "apikey",
            "printhostapikey", "printhostpassword", "printhostuser", "clientsecret", "secret", "authorization", "cookie", "cookies",
            "credential", "credentials", "auth", "authblob", "httpauthblob", "bearer", "bearertoken", "privatekey", "clientkey",
            "passphrase", "passwd", "pwd", "pass", "oauth", "oauthtoken", "oauthsecret", "secretkey", "apisecret", "sessiontoken")
        private val SECRET_SUFFIXES = setOf("accesscode", "password", "token", "secret", "apikey", "credential", "credentials", "authblob",
            "cookie", "sessionsecret", "secretkey", "apisecret", "passwd", "oauth")
        private val SECRET_PREFIXES = setOf("bearer ", "aws4-hmac-sha256 ", "sk-", "ghp_", "github_pat_", "xoxb-", "xoxp-", "ya29.")
    }
}
