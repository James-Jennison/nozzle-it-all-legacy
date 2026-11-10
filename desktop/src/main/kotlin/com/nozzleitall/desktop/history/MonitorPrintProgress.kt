package com.nozzleitall.desktop.history

import androidx.compose.runtime.mutableStateOf
import com.nozzleitall.printer.JobProgress
import com.nozzleitall.printer.PrinterState
import com.nozzleitall.printer.PrinterStatus

internal data class PrintMilestone(val label: String, val fileName: String)

/** One printer's latest observed job. Neither a percentage nor a stale reading establishes an outcome. */
internal class MonitorPrintProgress {
    val active = mutableStateOf<JobProgress?>(null)
    val finished = mutableStateOf<String?>(null)
    private var highest = 0f
    private var observedStart = false

    fun observe(status: PrinterStatus): List<PrintMilestone> {
        val job = status.job?.takeIf { it.fileName.isNotBlank() }
        if (status.state.isActiveJob && job != null) {
            val current = active.value
            if (reportedJobPath(current?.fileName) != reportedJobPath(job.fileName)) {
                highest = 0f
                observedStart = false
            }
            finished.value = null
            active.value = job
            val events = mutableListOf<PrintMilestone>()
            if (status.state == PrinterState.PRINTING && !observedStart) {
                events += PrintMilestone("started", job.fileName)
                observedStart = true
            }
            val progress = job.fraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: highest
            for (threshold in listOf(25, 50, 75)) {
                if (highest < threshold / 100f && progress >= threshold / 100f)
                    events += PrintMilestone("$threshold%", job.fileName)
            }
            highest = maxOf(highest, progress)
            return events
        }
        if (status.state in reconnectingStates || status.state.isActiveJob) return emptyList()
        val completed = reportedJobPath(status.completedFileName)
        if (status.state == PrinterState.FINISHED && completed == null) return emptyList()
        val file = active.value?.fileName
        if (status.state == PrinterState.FINISHED && file != null && reportedJobPath(file) != completed)
            return emptyList()
        active.value = null
        if (status.state != PrinterState.FINISHED) {
            finished.value = null
            return emptyList()
        }
        if (file != null && reportedJobPath(file) == completed) {
            finished.value = file
            return listOf(PrintMilestone("finished", file))
        }
        if (reportedJobPath(finished.value) != completed) finished.value = null
        return emptyList()
    }

    companion object {
        val reconnectingStates = setOf(PrinterState.OFFLINE, PrinterState.CONNECTING, PrinterState.UNKNOWN)
    }
}
