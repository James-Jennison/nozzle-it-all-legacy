package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.PrepItem
import com.nozzleitall.desktop.prepare.SliceEngine
import com.nozzleitall.desktop.prepare.SliceState
import com.nozzleitall.project.MeshIO
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeNotNull
import com.nozzleitall.desktop.projects.compareRecovery
import com.nozzleitall.desktop.projects.ProjectLibrary
import com.nozzleitall.desktop.projects.KeepChoiceReviewReason
import com.nozzleitall.project.Mesh
import com.nozzleitall.project.ProjectManifest
import com.nozzleitall.project.ThreeMf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectRevisionTest {
    @Test fun sliceAllRetainsExactPlateResultsUntilTheSetupChanges() {
        assumeNotNull(SliceEngine.locateEngine())
        val root = Files.createTempDirectory("slice-all-plates").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(paths, scope)
        try {
            val repo = generateSequence(File("").absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile }
            val mesh = MeshIO.readStl(File(repo, "site-src/assets/test-cube-20mm.stl").readBytes())
            runBlocking {
                withContext(Dispatchers.Main) {
                    val prepare = app.prepare
                    prepare.items += PrepItem(1, "First plate cube", mesh, 90f, 90f)
                    prepare.addPlate()
                    prepare.items += PrepItem(2, "Second plate cube", mesh, 140f, 140f, plate = 1).apply { sz = 0.5f }
                    prepare.changed()
                    prepare.save()
                    val plateIds = prepare.plateIds.toList()
                    prepare.sliceAll(scope)
                    withTimeout(120_000) { while (prepare.slice is SliceState.Running) delay(25) }
                    assertTrue(prepare.slice.toString(), prepare.slice is SliceState.Done)
                    val second = prepare.slice as SliceState.Done
                    assertEquals(plateIds[1], second.savedPlateId)
                    prepare.showPlate(0)
                    val first = prepare.slice as SliceState.Done
                    assertEquals(plateIds[0], first.savedPlateId)
                    assertNotEquals(first.result.inputSha256, second.result.inputSha256)
                    assertNotEquals(first.result.stats.layers, second.result.stats.layers)
                    prepare.changed()
                    prepare.showPlate(1)
                    assertSame(SliceState.Idle, prepare.slice)
                    assertFalse(prepare.showPreview)
                }
            }
        } finally {
            scope.cancel()
            app.fleet.shutdown()
            root.deleteRecursively()
        }
    }

    @Test fun printProjectResolvesItsArchivedRevisionAndRejectsAnotherPlate() {
        val root = Files.createTempDirectory("history-print-project").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(paths, scope)
        try {
            val prepare = app.prepare
            prepare.addPlate()
            prepare.save()
            val id = requireNotNull(prepare.manifest).projectId
            val plate = prepare.plateIds.last()
            prepare.renamePlate(1, "Renamed plate")
            prepare.save()

            val (archived, project) = app.library.readPrintProject(id, 1, plate)
            assertNotEquals(prepare.file, archived)
            assertEquals("Plate 2", project.manifest!!.plates.last().name)
            assertTrue(runCatching { app.library.readPrintProject(id, 1, "wrong-plate") }.isFailure)
            prepare.openProject(archived, project)
            assertEquals(1L, prepare.manifest!!.revision)
            prepare.renamePlate(1, "Edited from print")
            val copy = prepare.save()
            assertNotEquals(archived, copy)
            assertNotEquals(id, prepare.manifest!!.projectId)
            assertEquals("Plate 2", ThreeMf.read(archived).manifest!!.plates.last().name)
        } finally {
            scope.cancel()
            app.fleet.shutdown()
            root.deleteRecursively()
        }
    }

    @Test fun cleanProjectReplacementAdvancesEditorGeneration() {
        val root = Files.createTempDirectory("project-editor-generation").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        val beforeNew = prepare.editorGeneration
        prepare.newProject()
        assertTrue(prepare.editorGeneration > beforeNew)
        val beforeOpen = prepare.editorGeneration
        prepare.open(head)
        assertTrue(prepare.editorGeneration > beforeOpen)
        assertFalse(prepare.dirty)
    }

    @Test fun crashStableRestoreCopyRemainsInspectableAfterRestart() {
        val root = Files.createTempDirectory("project-restore-stable-part").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        prepare.changed()
        val stable = File(head.parentFile, ".${head.name}.${java.util.UUID.randomUUID()}.part")
            .apply { writeBytes(ThreeMf.write(prepare.toProject())) }
        val recovery = app.library.pendingRecoveries().single()
        assertEquals(stable, recovery.part)
        assertEquals(head, recovery.target)
        assertNull(recovery.problem)
    }

    @Test fun sessionDraftSurvivesOpeningAsASeparateProjectCopy() {
        val root = Files.createTempDirectory("project-session-draft").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        val headBytes = head.readBytes()
        prepare.name = "Edited session"
        prepare.changed()
        val draft = app.library.preserveSessionDraft(prepare.toProject())
        val draftBytes = draft.readBytes()
        assertEquals("Edited session", app.library.sessionDrafts().single().name)
        val copy = app.library.openSessionDraftAsCopy(draft)
        assertNotEquals(ThreeMf.read(head).manifest!!.projectId, ThreeMf.read(copy).manifest!!.projectId)
        assertEquals(1L, ThreeMf.read(copy).manifest!!.revision)
        assertArrayEquals(headBytes, head.readBytes())
        assertArrayEquals(draftBytes, draft.readBytes())
        assertEquals(1, app.library.sessionDrafts().size)
    }

    @Test fun unidentifiedLibraryFilesDoNotBecomeAnotherProjectsRevision() {
        val root = Files.createTempDirectory("project-unidentified").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        val id = ThreeMf.read(head).manifest!!.projectId
        val corrupt = File(head.parentFile, "damaged.3mf").apply { writeText("truncated") }
        assertEquals(listOf(1L), app.library.revisions(id).map { it.revision })
        assertTrue(app.library.list().any { it.file == corrupt && it.problem != null })
    }

    @Test fun firstSaveIsRevisionOneAndUnchangedSaveKeepsTheSameBytes() {
        val root = Files.createTempDirectory("project-first-revision").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        val mesh = Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2))
        prepare.items += PrepItem(1, "Triangle", mesh, 50f, 50f)
        prepare.changed()
        val first = prepare.save()
        val original = ThreeMf.read(first).manifest!!
        assertEquals(1L, original.revision)
        val firstBytes = first.readBytes()
        assertEquals(first, prepare.save())
        assertArrayEquals(firstBytes, first.readBytes())
        prepare.changed()
        prepare.save()
        val second = ThreeMf.read(first).manifest!!
        assertEquals(original.projectId, second.projectId)
        assertEquals(2L, second.revision)
        val history = app.library.revisions(original.projectId)
        assertEquals(listOf(1L, 2L), history.map { it.revision })
        assertArrayEquals(firstBytes, history.first().file.readBytes())
        assertEquals(first, history.last().file)
        val currentBytes = first.readBytes()
        assertThrows(IllegalStateException::class.java) { app.library.save(ThreeMf.read(history.first().file), first) }
        assertArrayEquals(currentBytes, first.readBytes())
    }

    @Test fun interruptedSaveIsInspectableAndAppliedOnlyByExplicitRecovery() {
        val root = Files.createTempDirectory("project-save-recovery").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        val mesh = Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2))
        prepare.items += PrepItem(1, "Triangle", mesh, 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        val original = head.readBytes()
        repeat(25) { prepare.overrides["custom_setting_$it"] = "value_$it" }
        prepare.plateNames[0] = "Changed plate"
        prepare.items.first().settings["perimeter_speed"] = "42"
        prepare.changed()
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(ThreeMf.write(prepare.toProject())) }
        val candidate = app.library.pendingRecoveries().single()
        assertEquals(part, candidate.part)
        assertEquals(2L, candidate.revision)
        assertNull(candidate.problem)
        val comparison = compareRecovery(candidate, prepare.toProject())
        assertTrue(comparison.saved.contains("revision 1"))
        assertTrue(comparison.newer.contains("revision 2"))
        assertEquals(5, comparison.remainingSettings.size)
        assertTrue(comparison.changedProjectFields.any { it.contains("plates[0].name") && it.contains("Changed plate") })
        assertTrue(comparison.changedProjectFields.any { it.contains("perimeter_speed") && it.contains("42") })
        assertTrue(comparison.currentVsSaved!!.contains("25 changed overrides"))
        assertTrue(comparison.currentVsRecovery!!.contains("0 changed overrides"))
        prepare.name = "Distinct unsaved session"
        prepare.plateNames[0] = "Unsaved plate"
        prepare.items.first().settings["perimeter_speed"] = "43"
        prepare.changed()
        val currentComparison = compareRecovery(candidate, prepare.toProject())
        assertTrue(currentComparison.currentVsSavedFields.any { it.contains("plates[0].name") && it.contains("Unsaved plate") })
        assertTrue(currentComparison.currentVsRecoveryFields.any { it.contains("perimeter_speed") && it.contains("43") })
        assertTrue(currentComparison.currentVsRecoveryFields.any { it.contains("name") && it.contains("Distinct unsaved session") })
        assertTrue(comparison.changedProjectFields.any { it.contains("plates[0].name") && it.contains("Changed plate") })
        assertArrayEquals("Inspection must not apply the recovery", original, head.readBytes())
        val recoveryBytes = part.readBytes()
        app.library.keepSavedVersion(candidate)
        assertTrue(ProjectLibrary(paths).pendingRecoveries().single().keptSavedVersion)
        val savedDecision = Files.walk(root.toPath()).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".keep") }.toList().single()
        }
        assertEquals("target-sha256: ${candidate.targetSha256}\nkeep saved version\n", Files.readString(savedDecision))
        assertArrayEquals(original, head.readBytes())
        assertArrayEquals(recoveryBytes, part.readBytes())
        assertEquals(head, app.library.recoverFromPart(candidate))
        assertArrayEquals(part.readBytes(), head.readBytes())
        assertEquals(2L, ThreeMf.read(head).manifest!!.revision)
        assertArrayEquals(original, app.library.revisions(candidate.projectId!!).first().file.readBytes())
        val applied = app.library.pendingRecoveries().single()
        assertTrue("The raw recovery copy remains for audit", applied.part.exists())
        assertTrue(applied.alreadyApplied)
        assertFalse(applied.keptSavedVersion)
        assertNull(applied.problem)
        assertThrows(IllegalStateException::class.java) { app.library.recoverFromPart(applied) }
    }

    @Test fun archivedRevisionWithAContradictoryFilenameIsRejected() {
        val root = Files.createTempDirectory("project-revision-identity").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        val mesh = Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2))
        prepare.items += PrepItem(1, "Triangle", mesh, 50f, 50f)
        prepare.changed(); prepare.save()
        prepare.changed(); prepare.save()
        val id = ThreeMf.read(prepare.file!!).manifest!!.projectId
        val archive = app.library.revisions(id).first().file
        val falseRevision = File(archive.parentFile, "2-${archive.name.substringAfter('-')}")
        archive.copyTo(falseRevision)
        val revisions = app.library.revisions(id)
        assertEquals(listOf(1L, 2L), revisions.filter { it.problem == null }.map { it.revision })
        assertEquals(1, revisions.count { it.problem != null })
        assertEquals(2L, ThreeMf.read(prepare.file!!).manifest!!.revision)
    }

    @Test fun recoveryInspectionRejectsRevisionGapsBeforeApply() {
        val root = Files.createTempDirectory("project-recovery-gap").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        val original = head.readBytes()
        val candidate = ThreeMf.read(head).let { it.copy(manifest = it.manifest!!.copy(revision = 3)) }
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(ThreeMf.write(candidate)) }
        assertNotNull(app.library.pendingRecoveries().single().problem)
        assertThrows(IllegalStateException::class.java) { app.library.recoverFromPart(app.library.pendingRecoveries().single()) }
        assertArrayEquals(original, head.readBytes())
    }

    @Test fun recoveryRefusesBytesChangedAfterInspection() {
        val root = Files.createTempDirectory("project-recovery-changed").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        val before = head.readBytes()
        prepare.changed()
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(ThreeMf.write(prepare.toProject())) }
        val inspected = app.library.pendingRecoveries().single()
        part.writeBytes(ThreeMf.write(ThreeMf.read(part).copy(metadata = mapOf("Title" to "Changed after inspection"))))
        assertThrows(IllegalStateException::class.java) { app.library.recoverFromPart(inspected) }
        assertArrayEquals(before, head.readBytes())
    }

    @Test fun recoveryRefusesAHeadChangedAfterInspection() {
        val root = Files.createTempDirectory("project-recovery-changed-head").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        prepare.changed()
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(ThreeMf.write(prepare.toProject())) }
        val inspected = app.library.pendingRecoveries().single()
        val altered = ThreeMf.read(head).let { it.copy(metadata = it.metadata + ("Title" to "Changed head")) }
        head.writeBytes(ThreeMf.write(altered))
        val changedBytes = head.readBytes()
        val error = assertThrows(IllegalStateException::class.java) { app.library.recoverFromPart(inspected) }
        assertTrue(error.message.orEmpty().contains("Review it again"))
        assertArrayEquals(changedBytes, head.readBytes())
        assertTrue(part.exists())
    }

    @Test fun ordinarySaveCannotStrandAnUnappliedRecovery() {
        val root = Files.createTempDirectory("project-recovery-save-conflict").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        prepare.changed()
        val pending = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(ThreeMf.write(prepare.toProject())) }
        val inspected = app.library.pendingRecoveries().single()
        val original = head.readBytes()
        val competing = prepare.toProject().let { it.copy(metadata = it.metadata + ("Title" to "Competing save")) }
        assertThrows(IllegalStateException::class.java) { app.library.save(competing, head) }
        val alias = File(head.parentFile, "./${head.name}")
        val aliasError = assertThrows(IllegalStateException::class.java) { app.library.save(competing, alias) }
        assertTrue(aliasError.message.orEmpty().contains("interrupted save"))
        assertArrayEquals(original, head.readBytes())
        assertTrue(pending.exists())
        assertNull(app.library.pendingRecoveries().single().problem)
        assertEquals(head, app.library.recoverFromPart(inspected))
    }

    @Test fun recoveryInspectionRejectsHeadWithMissingManifestDigest() {
        val root = Files.createTempDirectory("project-recovery-damaged-head").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        prepare.changed()
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(ThreeMf.write(prepare.toProject())) }
        val entries = ThreeMf.readEntries(ByteArrayInputStream(head.readBytes())).toMutableMap()
        val xml = String(entries.getValue(ThreeMf.MODEL_PATH), Charsets.UTF_8)
        entries[ThreeMf.MODEL_PATH] = xml.replace(Regex("\\s*<metadata name=\"${ProjectManifest.META_MANIFEST_SHA256}\">[^<]*</metadata>"), "").toByteArray()
        head.writeBytes(ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        } }.toByteArray())
        assertNotNull(app.library.pendingRecoveries().single().problem)
        assertThrows(IllegalStateException::class.java) { app.library.recoverFromPart(app.library.pendingRecoveries().single()) }
        assertTrue(part.exists())
    }

    @Test fun inspectedRecoveryCanRestoreAMissingHeadWithoutDeletingTheRecoveryCopy() {
        val root = Files.createTempDirectory("project-missing-head-recovery").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.overrides["perimeter_speed"] = "42"
        prepare.changed()
        val head = prepare.save()
        val original = head.readBytes()
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(original) }
        assertTrue(head.delete())
        val candidate = app.library.pendingRecoveries().single()
        assertNull(candidate.problem)
        val comparison = compareRecovery(candidate)
        assertFalse(comparison.hasSavedHead)
        assertEquals("No saved head", comparison.saved)
        assertTrue(comparison.changedProjectFields.any { it.contains("settings.overrides.perimeter_speed: 42") })
        assertTrue(comparison.changedProjectFields.any { it.contains("plates[0]") })
        assertTrue(comparison.changedProjectFields.none { it.contains("→") })
        prepare.overrides["perimeter_speed"] = "43"
        prepare.changed()
        val currentComparison = compareRecovery(candidate, prepare.toProject())
        assertFalse(currentComparison.hasSavedHead)
        assertTrue(currentComparison.currentSession != null)
        assertTrue(currentComparison.currentVsSavedFields.any { it.contains("settings.overrides.perimeter_speed: 43") })
        assertTrue(currentComparison.currentVsSavedFields.none { it.contains("→") })
        assertTrue(currentComparison.changedProjectFields.any { it.contains("settings.overrides.perimeter_speed: 42") })
        assertTrue(currentComparison.currentVsRecoveryFields.any { it.contains("settings.overrides.perimeter_speed: 42 → 43") })
        app.library.keepSavedVersion(candidate)
        assertTrue(ProjectLibrary(paths).pendingRecoveries().single().keptSavedVersion)
        assertFalse(head.exists())
        assertArrayEquals(original, part.readBytes())
        val decisionFiles = Files.walk(root.toPath()).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".keep") }.toList()
        }
        assertEquals(1, decisionFiles.size)
        assertEquals("target-sha256: missing\nkeep recovery un-restored\n", Files.readString(decisionFiles.single()))
        val differentHead = ThreeMf.read(part).let { it.copy(manifest = it.manifest!!.copy(name = "New saved project")) }
        ThreeMf.writeAtomically(differentHead, head)
        val newlySaved = app.library.pendingRecoveries().single()
        assertEquals(KeepChoiceReviewReason.NO_HEAD_BECAME_SAVED, newlySaved.previousKeepChoiceReason)
        assertFalse(newlySaved.keptSavedVersion)
        assertTrue(head.delete())
        assertEquals(head, app.library.recoverFromPart(candidate))
        assertArrayEquals(original, head.readBytes())
        assertArrayEquals(original, part.readBytes())
    }

    @Test fun keepChoiceShowsNeedsReviewWhenSavedHeadChanges() {
        val root = Files.createTempDirectory("project-recovery-choice-recheck").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        prepare.overrides["perimeter_speed"] = "43"
        prepare.changed()
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(ThreeMf.write(prepare.toProject())) }
        val inspected = app.library.pendingRecoveries().single()
        assertNull(inspected.problem)
        app.library.keepSavedVersion(inspected)
        assertTrue(app.library.pendingRecoveries().single().keptSavedVersion)
        val recoveryBytes = part.readBytes()
        val originalHead = ThreeMf.read(head)
        ThreeMf.writeAtomically(originalHead.copy(manifest = originalHead.manifest!!.copy(name = "Edited saved head")), head)
        val changedHeadBytes = head.readBytes()
        val updated = ProjectLibrary(paths).pendingRecoveries().single()
        assertNull(updated.problem)
        assertFalse(updated.keptSavedVersion)
        assertEquals(KeepChoiceReviewReason.SAVED_HEAD_CHANGED, updated.previousKeepChoiceReason)
        assertArrayEquals(recoveryBytes, part.readBytes())
        assertThrows(IllegalStateException::class.java) { app.library.recoverFromPart(inspected) }
        app.library.keepSavedVersion(updated)
        val rechecked = app.library.pendingRecoveries().single()
        assertTrue(rechecked.keptSavedVersion)
        assertNull(rechecked.previousKeepChoiceReason)
        assertArrayEquals(changedHeadBytes, head.readBytes())
        assertArrayEquals(recoveryBytes, part.readBytes())
        val marker = Files.walk(root.toPath()).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".keep") }.toList().single()
        }
        Files.writeString(marker, "unreadable decision")
        val unverifiable = app.library.pendingRecoveries().single()
        assertEquals(KeepChoiceReviewReason.RECORD_UNVERIFIABLE, unverifiable.previousKeepChoiceReason)
        assertFalse(unverifiable.keptSavedVersion)
    }

    @Test fun keepChoiceShowsMissingSavedHeadAndRejectsStaleRestore() {
        val root = Files.createTempDirectory("project-recovery-choice-missing-head").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        prepare.overrides["perimeter_speed"] = "43"
        prepare.changed()
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(ThreeMf.write(prepare.toProject())) }
        val inspected = app.library.pendingRecoveries().single()
        assertNull(inspected.problem)
        app.library.keepSavedVersion(inspected)
        assertTrue(app.library.pendingRecoveries().single().keptSavedVersion)
        val recoveryBytes = part.readBytes()
        assertTrue(head.delete())
        val missingHead = app.library.pendingRecoveries().single()
        assertEquals(KeepChoiceReviewReason.SAVED_HEAD_MISSING, missingHead.previousKeepChoiceReason)
        assertNull(missingHead.problem)
        assertFalse(missingHead.keptSavedVersion)
        val staleRestore = assertThrows(IllegalStateException::class.java) { app.library.recoverFromPart(inspected) }
        assertTrue(staleRestore.message.orEmpty().contains("saved project changed since recovery was inspected"))
        assertFalse(compareRecovery(missingHead).hasSavedHead)
        app.library.keepSavedVersion(missingHead)
        val keptMissingHead = ProjectLibrary(paths).pendingRecoveries().single()
        assertNull(keptMissingHead.problem)
        assertTrue(keptMissingHead.keptSavedVersion)
        assertNull(keptMissingHead.previousKeepChoiceReason)
        assertFalse(head.exists())
        assertEquals(head, app.library.recoverFromPart(keptMissingHead))
        assertArrayEquals(recoveryBytes, head.readBytes())
        assertArrayEquals(recoveryBytes, part.readBytes())
    }

    @Test fun orphanedRecoveryOpensAsSeparateCopyWithoutRewritingOriginalLineage() {
        val root = Files.createTempDirectory("project-recovery-orphan-copy").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        val originalId = ThreeMf.read(head).manifest!!.projectId
        prepare.overrides["perimeter_speed"] = "43"
        prepare.changed()
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(ThreeMf.write(prepare.toProject())) }
        val originalPartBytes = part.readBytes()
        assertTrue(head.delete())
        val orphan = app.library.pendingRecoveries().single()
        assertTrue(orphan.canOpenAsCopy)
        assertTrue(orphan.problem.orEmpty().contains("parent revision cannot be verified"))
        assertThrows(IllegalStateException::class.java) { app.library.recoverFromPart(orphan) }
        assertThrows(IllegalStateException::class.java) { app.library.keepSavedVersion(orphan) }
        assertFalse(compareRecovery(orphan).hasSavedHead)
        part.writeText("changed after inspection")
        assertThrows(IllegalStateException::class.java) { app.library.openRecoveryAsCopy(orphan) }
        part.writeBytes(originalPartBytes)
        val copy = app.library.openRecoveryAsCopy(orphan)
        val copiedManifest = ThreeMf.read(copy).manifest!!
        assertNotEquals(originalId, copiedManifest.projectId)
        assertEquals(1L, copiedManifest.revision)
        assertEquals("43", copiedManifest.settings.overrides["perimeter_speed"])
        assertFalse(head.exists())
        assertArrayEquals(originalPartBytes, part.readBytes())
        assertFalse(app.library.pendingRecoveries().single().canOpenAsCopy)
        assertThrows(IllegalStateException::class.java) { app.library.openRecoveryAsCopy(orphan) }
        val recordKey = ThreeMf.sha256("$originalId\n${orphan.revision}\n${head.name}\n${orphan.contentSha256}".toByteArray())
        val record = File(paths.projects, ".recovery-decisions/$recordKey.opened")
        assertTrue(record.isFile)
        record.writeText("intent:${copiedManifest.projectId}:${copy.name}\n") // Crash before completion record.
        app.library.moveToTrash(copy)
        assertFalse(app.library.pendingRecoveries().single().canOpenAsCopy)
        assertThrows(IllegalStateException::class.java) { app.library.openRecoveryAsCopy(orphan) }
        record.writeText("intent:${java.util.UUID.randomUUID()}:${copy.name}\n") // Same filename is not enough.
        assertTrue(app.library.pendingRecoveries().single().canOpenAsCopy)
        record.writeText("intent:damaged record\n")
        val invalidRecord = app.library.pendingRecoveries().single()
        assertFalse(invalidRecord.canOpenAsCopy)
        assertTrue(invalidRecord.problem.orEmpty().contains("record cannot be verified"))
        assertArrayEquals(originalPartBytes, part.readBytes())
    }

    @Test fun unreadableUnrelatedProjectDoesNotHideAnOrphanedRecovery() {
        val root = Files.createTempDirectory("project-recovery-unrelated-corrupt").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        prepare.changed()
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(ThreeMf.write(prepare.toProject())) }
        val original = part.readBytes()
        assertTrue(head.delete())
        File(paths.projects, "unrelated-corrupt.3mf").writeText("truncated")
        File(paths.projects, ".trash").apply { mkdirs(); File(this, "old-corrupt.3mf").writeText("truncated") }
        val orphan = app.library.pendingRecoveries().single()
        assertNotNull(orphan.projectId)
        assertTrue(orphan.canOpenAsCopy)
        assertArrayEquals(original, part.readBytes())
    }

    @Test fun missingHeadWithUnreadableArchivedRevisionCanOpenAsSeparateCopy() {
        val root = Files.createTempDirectory("project-recovery-unreadable-history").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed(); val head = prepare.save()
        prepare.changed(); prepare.save()
        val id = ThreeMf.read(head).manifest!!.projectId
        val archive = app.library.revisions(id).first { it.revision == 1L }.file
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(head.readBytes()) }
        val original = part.readBytes()
        archive.writeText("truncated")
        assertTrue(head.delete())
        val orphan = app.library.pendingRecoveries().single()
        assertEquals(2L, orphan.revision)
        assertTrue(orphan.canOpenAsCopy)
        assertTrue(orphan.problem.orEmpty().contains("unreadable revision"))
        assertArrayEquals(original, part.readBytes())
    }

    @Test fun failedCopyIntentWriteDoesNotCreateOrphanedRecoveryProject() {
        val root = Files.createTempDirectory("project-recovery-copy-intent-failure").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        prepare.overrides["perimeter_speed"] = "43"
        prepare.changed()
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(ThreeMf.write(prepare.toProject())) }
        val bytes = part.readBytes()
        assertTrue(head.delete())
        val blocker = File(paths.projects, ".recovery-decisions").apply { writeText("blocked") }
        val orphan = app.library.pendingRecoveries().single()
        assertTrue(orphan.canOpenAsCopy)
        assertThrows(Exception::class.java) { app.library.openRecoveryAsCopy(orphan) }
        assertTrue(paths.projects.listFiles { f -> f.extension.equals("3mf", true) }.orEmpty().isEmpty())
        assertArrayEquals(bytes, part.readBytes())
        assertTrue(blocker.delete())
        assertTrue(app.library.pendingRecoveries().single().canOpenAsCopy)
    }

    @Test fun legacyMissingHeadMarkerDoesNotAuthorizeUnverifiedRestore() {
        val root = Files.createTempDirectory("project-recovery-legacy-missing-marker").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        prepare.changed()
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(ThreeMf.write(prepare.toProject())) }
        assertTrue(head.delete())
        val digest = ThreeMf.sha256(part.readBytes())
        val key = ThreeMf.sha256("${part.absolutePath}\n$digest\nmissing".toByteArray())
        File(paths.projects, ".recovery-decisions").apply { mkdirs(); File(this, "$key.keep").writeText("legacy") }
        val orphan = app.library.pendingRecoveries().single()
        assertTrue(orphan.canOpenAsCopy)
        assertNotNull(orphan.problem)
        assertFalse(orphan.keptSavedVersion)
        assertEquals(KeepChoiceReviewReason.RECORD_UNVERIFIABLE, orphan.previousKeepChoiceReason)
        assertThrows(IllegalStateException::class.java) { app.library.recoverFromPart(orphan) }
        assertFalse(head.exists())
    }

    @Test fun staleRecoveryCanBeInspectedAndCopiedWithoutChangingNewerHistory() {
        val root = Files.createTempDirectory("project-recovery-stale-copy").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        val originalId = ThreeMf.read(head).manifest!!.projectId
        repeat(3) { index ->
            prepare.overrides["perimeter_speed"] = "${40 + index}"
            prepare.changed()
            prepare.save()
        }
        val archivedBefore = app.library.revisions(originalId).filter { it.file != head }.associate { it.revision to it.contentSha256 }
        val older = app.library.revisions(originalId).single { it.revision == 2L && it.file != head }
        val part = File(head.parentFile, ".${head.name}.123.part").apply { writeBytes(older.file.readBytes()) }
        val partBytes = part.readBytes()
        assertTrue(head.delete())
        val stale = app.library.pendingRecoveries().single()
        assertTrue(stale.problem.orEmpty().contains("overlaps newer archived history"))
        assertTrue(stale.canOpenAsCopy)
        assertFalse(compareRecovery(stale).hasSavedHead)
        assertThrows(IllegalStateException::class.java) { app.library.recoverFromPart(stale) }
        val copy = app.library.openRecoveryAsCopy(stale)
        assertNotEquals(originalId, ThreeMf.read(copy).manifest!!.projectId)
        assertEquals(1L, ThreeMf.read(copy).manifest!!.revision)
        assertArrayEquals(partBytes, part.readBytes())
        assertFalse(head.exists())
        assertEquals(archivedBefore, app.library.revisions(originalId).associate { it.revision to it.contentSha256 })
    }

    @Test fun archiveCopyOfStillCurrentHeadIsNotListedTwice() {
        val root = Files.createTempDirectory("project-revision-capture-crash").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        val manifest = ThreeMf.read(head).manifest!!
        com.nozzleitall.desktop.projects.ProjectRevisionArchive(app.paths.projects).capture(head, manifest)
        assertEquals(listOf(1L), app.library.revisions(manifest.projectId).map { it.revision })
    }

    @Test fun trashedHeadRemainsInRevisionHistory() {
        val root = Files.createTempDirectory("project-revision-trash").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed(); val head = prepare.save()
        prepare.changed(); prepare.save()
        val id = ThreeMf.read(head).manifest!!.projectId
        val trashed = app.library.moveToTrash(head)
        assertEquals(listOf(1L, 2L), app.library.revisions(id).map { it.revision })
        assertEquals(trashed, app.library.revisions(id).last().file)
    }

    @Test fun unrelatedCorruptHeadIsReportedWithoutHidingValidHistory() {
        val root = Files.createTempDirectory("project-revision-unrelated-corrupt").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed(); val head = prepare.save()
        prepare.changed(); prepare.save()
        val id = ThreeMf.read(head).manifest!!.projectId
        File(app.library.dir, "unrelated.3mf").writeText("damaged")
        val revisions = app.library.revisions(id)
        assertEquals(listOf(1L, 2L), revisions.filter { it.problem == null }.map { it.revision })
        assertTrue(revisions.none { it.problem != null })
        assertTrue(app.library.list().any { it.file.name == "unrelated.3mf" && it.problem != null })
    }

    @Test fun plateIdentitySurvivesReopenAndRemovingEarlierEmptyPlate() {
        val root = Files.createTempDirectory("project-plate-identity").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.addPlate()
        val originalIds = prepare.plateIds.toList()
        val mesh = Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2))
        prepare.items += PrepItem(1, "Triangle", mesh, 50f, 50f, plate = 1)
        prepare.changed()
        val head = prepare.save()
        assertEquals(originalIds, ThreeMf.read(head).manifest!!.plates.map { it.id })
        prepare.open(head)
        assertEquals(originalIds, prepare.plateIds.toList())
        prepare.removePlate(0)
        assertEquals(listOf(originalIds[1]), prepare.plateIds.toList())
        val beforeUndo = prepare.editorGeneration
        prepare.history.undo()
        assertTrue(prepare.editorGeneration > beforeUndo)
        assertEquals(originalIds, prepare.plateIds.toList())
        val beforeRedo = prepare.editorGeneration
        prepare.history.redo()
        assertTrue(prepare.editorGeneration > beforeRedo)
        assertEquals(listOf(originalIds[1]), prepare.plateIds.toList())
        prepare.save()
        assertEquals(listOf(originalIds[1]), ThreeMf.read(head).manifest!!.plates.map { it.id })
    }

    @Test fun unnamedSinglePlateKeepsItsIdentityWhenReopened() {
        val root = Files.createTempDirectory("project-unnamed-plate-identity").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.plateNames[0] = ""
        val id = prepare.plateIds.single()
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed()
        val head = prepare.save()
        assertEquals(id, ThreeMf.read(head).manifest!!.plates.single().id)
        prepare.open(head)
        assertEquals(id, prepare.plateIds.single())
    }

    @Test fun duplicateOrBlankPlateIdentitiesAreRejectedBeforeOpenMutatesTheWorkspace() {
        val root = Files.createTempDirectory("project-duplicate-plate-id").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        val originalId = prepare.plateIds.single()
        val producer = ProjectManifest.Producer("Nozzle It All", "desktop", "test")
        val base = ProjectManifest("project", 1, "Duplicate", producer, producer, 1)
        for (ids in listOf(listOf("same", "same"), listOf("", "other"))) {
            val manifest = base.copy(plates = ids.mapIndexed { index, id -> ProjectManifest.PlateEntry(index + 1, "Plate ${index + 1}", id = id) })
            val file = File(root, "${ids.first().ifBlank { "blank" }}.3mf").apply {
                writeBytes(ThreeMf.write(com.nozzleitall.project.Project3mf(emptyList(), emptyMap(), manifest)))
            }
            assertThrows(IllegalStateException::class.java) { prepare.open(file) }
            assertEquals(listOf(originalId), prepare.plateIds.toList())
        }
    }

    @Test fun foreignPlateIdentityIsStableAcrossRepeatedOpen() {
        val root = Files.createTempDirectory("project-foreign-plate-id").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val mesh = Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2))
        val foreign = com.nozzleitall.project.Project3mf(listOf(com.nozzleitall.project.ModelObject(1, "Triangle", mesh)),
            mapOf("Title" to "Foreign"), null)
        val source = File(root, "foreign.3mf").apply { writeBytes(ThreeMf.write(foreign)) }
        app.prepare.open(source)
        val first = app.prepare.plateIds.single()
        app.prepare.open(source)
        assertEquals(first, app.prepare.plateIds.single())
    }

    @Test fun rolledBackHeadCannotOverwriteArchivedHistory() {
        val root = Files.createTempDirectory("project-revision-rollback").toFile()
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        val app = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val prepare = app.prepare
        prepare.items += PrepItem(1, "Triangle", Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)), 50f, 50f)
        prepare.changed(); val head = prepare.save()
        repeat(3) { prepare.changed(); prepare.save() }
        val id = ThreeMf.read(head).manifest!!.projectId
        val archived = app.library.revisions(id).filter { it.revision < 4 }
        head.writeBytes(archived.first { it.revision == 2L }.file.readBytes())
        val rolledBack = head.readBytes()
        val next = ThreeMf.read(head).let { it.copy(manifest = it.manifest!!.copy(revision = 3)) }
        assertThrows(IllegalStateException::class.java) { app.library.save(next, head) }
        assertArrayEquals(rolledBack, head.readBytes())
        assertEquals(listOf(1L, 2L, 3L), app.library.revisions(id).map { it.revision }.sorted())
    }
}
