package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.ProjectManifest
import org.junit.Assert.assertEquals
import org.junit.Test

class ObjectPartLabelTest {
    @Test
    fun `object tree uses familiar modifier names`() {
        assertEquals("Negative volume · Box", objectPartLabel(part("negative", "box")))
        assertEquals("Modifier · Cylinder", objectPartLabel(part("modifier", "cylinder")))
        assertEquals("Support blocker · Sphere", objectPartLabel(part("blocker", "sphere")))
        assertEquals("Support enforcer · Box", objectPartLabel(part("enforcer", "box")))
        assertEquals("Part · Box", objectPartLabel(part("part", "box")))
    }

    private fun part(kind: String, shape: String) = ProjectManifest.ObjectPart(
        kind = kind,
        shape = shape,
        center = listOf(0.0, 0.0, 0.0),
        size = listOf(1.0, 1.0, 1.0),
    )
}
