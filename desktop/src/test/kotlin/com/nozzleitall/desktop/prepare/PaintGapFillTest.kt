package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Mesh
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

class PaintGapFillTest {
    @Test fun measuresPlacedAreaAndPreservesOtherPaint() {
        assumeNotNull(SliceEngine.locateEngine())
        val mesh = Mesh(
            floatArrayOf(0f, 0f, 20f, 1f, 0f, 20f, 0f, 1f, 20f, 0f, 0f, 0f),
            intArrayOf(0, 1, 2, 0, 3, 1, 1, 3, 2, 2, 3, 0),
            arrayOf("8", null, null, null),
            arrayOf(null, "4", null, null),
            arrayOf(null, null, "4", null),
            arrayOf(null, null, null, "4"),
        )
        val equal = PaintOps.gapFill(mesh, .5, listOf(1.0, 1.0, 1.0))
        assertSame(mesh, equal.mesh)
        val scaled = PaintOps.gapFill(mesh, .51, listOf(2.0, 1.0, 1.0))
        assertSame(mesh, scaled.mesh)
        val filled = PaintOps.gapFill(mesh, .51, listOf(-1.0, 1.0, 1.0))
        assertEquals(1, filled.changedPatches)
        assertFalse(filled.mesh.isPainted)
        assertSame(mesh.vertices, filled.mesh.vertices)
        assertSame(mesh.triangles, filled.mesh.triangles)
        assertArrayEquals(mesh.supportPaint, filled.mesh.supportPaint)
        assertArrayEquals(mesh.seamPaint, filled.mesh.seamPaint)
        assertArrayEquals(mesh.fuzzyPaint, filled.mesh.fuzzyPaint)
    }
}
