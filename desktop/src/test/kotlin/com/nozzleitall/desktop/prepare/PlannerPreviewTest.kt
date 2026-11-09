package com.nozzleitall.desktop.prepare

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlannerPreviewTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun nativeRowsCarryDistinctActualMetricsAndSlicerTiming() {
        val gcode = temp.newFile("plate.gcode")
        sidecar(gcode).writeText("NIA_PREVIEW\t1\n$row\nEND\t1\t0\n")

        val preview = requireNotNull(PlannerPreview.read(gcode))
        assertEquals(16, PreviewView.entries.size)
        assertTrue(PreviewView.entries.all(preview::available))
        assertEquals(80f, preview.value(PreviewView.SPEED, 0, 0), 0f)
        assertEquals(32f, preview.value(PreviewView.ACTUAL_SPEED, 0, 0), 0f)
        assertEquals(1000f, preview.value(PreviewView.ACCELERATION, 0, 0), 0f)
        assertEquals(8f, preview.value(PreviewView.JERK, 0, 0), 0f)
        assertEquals(2.5f, preview.value(PreviewView.ACTUAL_FLOW, 0, 0), 0f)
        assertEquals(4.5f, preview.value(PreviewView.LAYER_TIME_LOG, 0, 0), 0f)
        assertEquals(0.8, preview.usage.byTool.getValue(0).seconds, 0.00001)
        assertEquals(0.3, preview.usage.total.filamentMm, 0.00001)
        assertTrue(PreviewView.LAYER_TIME_LOG.fraction(5f, 1f, 10f) > PreviewView.LAYER_TIME.fraction(5f, 1f, 10f))
    }

    @Test fun incompleteInvalidAndOversizeResultsDoNotProvidePlannerMetrics() {
        val gcode = temp.newFile("fallback.gcode")
        gcode.writeText("M83\nG1 Z0.2\nG1 X1 E1 F600\n")
        val file = sidecar(gcode)
        for (text in listOf("NIA_PREVIEW\t1\n$row\n", "NIA_PREVIEW\t1\n$row\nEND\t1\t1\n",
            "NIA_PREVIEW\t2\n$row\nEND\t1\t0\n", "NIA_PREVIEW\t1\n$row\nEND\t2\t0\n")) {
            file.writeText(text)
            assertNull(PlannerPreview.read(gcode))
        }
        file.writeText("NIA_PREVIEW\t1\n" + "1".repeat(513) + "\nEND\t1\t0\n")
        assertNull(PlannerPreview.read(gcode))
        file.writeText("NIA_PREVIEW\t1\n$row\nEND\t1\t0\n")
        assertTrue(file.setLastModified(gcode.lastModified() - 10_000L))
        assertNull(PlannerPreview.read(gcode))
        java.io.RandomAccessFile(file, "rw").use { it.setLength(128L * 1024 * 1024 + 1) }
        assertNull(PlannerPreview.read(gcode))
        assertFalse(GcodePreview.parse(gcode).available(PreviewView.ACTUAL_SPEED))
    }

    private fun sidecar(gcode: File) = File(gcode.parentFile, "${gcode.name}.preview.tsv")

    private val row = listOf("0", "0.2", "0", "0", "10", "0", "0", "Outer wall", "80", "32", "1000",
        "8", "0.2", "0.45", "6.25", "2.5", "4.5", "25", "215", "0.04", "0.3", "0.8").joinToString("\t")
}
