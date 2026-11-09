package com.nozzleitall.desktop

import com.nozzleitall.desktop.jobs.LocalJobStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectPlateProgressTest {
    @Test fun machineCompletionAndPhysicalAcceptanceStayDistinct() {
        val root = Files.createTempDirectory("plate-progress").toFile()
        val paths = AppPaths(File(root, "c"), File(root, "d"), File(root, "k")).ensure()
        val store = LocalJobStore(File(paths.data, "jobs.sqlite"))
        val job = store.createJob(LocalJobStore.Origin.APP, projectId = "project", projectRevision = 1,
            plateId = "plate-a")
        val attempt = store.createAttempt(job.id, "printer")
        val fleet = Fleet(paths, CoroutineScope(SupervisorJob()), stockHelper = { null })
        try {
            assertFalse(fleet.projectPlateProgress("project", 1).getValue("plate-a").machineCompleted)
            store.recordMachineState(attempt.id, LocalJobStore.MachineState.MACHINE_COMPLETED)
            val machineOnly = fleet.projectPlateProgress("project", 1).getValue("plate-a")
            assertTrue(machineOnly.machineCompleted)
            assertFalse(machineOnly.physicallyAccepted)
            store.assess(attempt.id, 0, LocalJobStore.Assessment.SUCCESS)
            val accepted = fleet.projectPlateProgress("project", 1).getValue("plate-a")
            assertTrue(accepted.machineCompleted)
            assertTrue(accepted.physicallyAccepted)
            val nextRevision = store.createJob(LocalJobStore.Origin.APP, projectId = "project", projectRevision = 2,
                plateId = "plate-a")
            store.createAttempt(nextRevision.id, "printer")
            val newer = fleet.projectPlateProgress("project", 2).getValue("plate-a")
            assertFalse(newer.machineCompleted)
            assertFalse(newer.physicallyAccepted)
        } finally { fleet.shutdown() }
    }
}
