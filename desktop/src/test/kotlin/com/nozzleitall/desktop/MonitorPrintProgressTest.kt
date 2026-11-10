package com.nozzleitall.desktop

import com.nozzleitall.desktop.history.MonitorPrintProgress
import com.nozzleitall.printer.ConnectionRoute
import com.nozzleitall.printer.JobProgress
import com.nozzleitall.printer.PrinterState
import com.nozzleitall.printer.PrinterStatus
import org.junit.Assert.*
import org.junit.Test

class MonitorPrintProgressTest {
    private fun status(state: PrinterState, job: JobProgress? = null, completed: String? = null) =
        PrinterStatus(state, ConnectionRoute.LAN, job, completedFileName = completed)

    @Test fun milestonesDoNotReplayAndOnlyExactExplicitFinishCompletes() {
        val tracker = MonitorPrintProgress()
        val job = JobProgress("plates/cube.gcode", 0f, remainingSeconds = 120.0)
        fun labels(p: Float) = tracker.observe(status(PrinterState.PRINTING, job.copy(fraction = p))).map { it.label }
        assertFalse(DesktopSettings().printMilestones)
        assertEquals(listOf("started"), labels(0f))
        assertEquals(listOf("25%", "50%"), labels(.5f))
        assertTrue(tracker.observe(status(PrinterState.OFFLINE)).isEmpty())
        assertEquals(.5f, tracker.active.value?.fraction)
        assertEquals(listOf("75%"), labels(1f))
        assertNull(tracker.finished.value)
        assertTrue(labels(.7f).isEmpty())
        assertTrue(tracker.observe(status(PrinterState.READY)).isEmpty())
        assertTrue(tracker.observe(status(PrinterState.FINISHED, completed = job.fileName)).isEmpty())
        assertEquals(listOf("started"), labels(0f))
        assertTrue(tracker.observe(status(PrinterState.FINISHED, completed = "other/cube.gcode")).isEmpty())
        assertNull(tracker.finished.value)
        labels(0f)
        assertEquals(listOf("finished"), tracker.observe(status(PrinterState.FINISHED, completed = job.fileName)).map { it.label })
        assertEquals(job.fileName, tracker.finished.value)
        assertTrue(tracker.observe(status(PrinterState.FINISHED, completed = job.fileName)).isEmpty())
    }
}
