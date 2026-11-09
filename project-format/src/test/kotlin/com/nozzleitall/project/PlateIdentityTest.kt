package com.nozzleitall.project

import org.junit.Assert.*
import org.junit.Test

class PlateIdentityTest {
    @Test fun legacyManifestWithoutPlateIdsKeepsItsCanonicalDigest() {
        val producer = ProjectManifest.Producer("Nozzle It All", "desktop", "old")
        val old = ProjectManifest("old-project", 1, "Old", producer, producer, 1,
            plates = listOf(ProjectManifest.PlateEntry(1, "First"), ProjectManifest.PlateEntry(2, "Second")))
        val canonical = old.canonicalText()
        val reopened = ProjectManifest.parse(canonical)
        assertEquals(canonical, reopened.canonicalText())
        assertNull(reopened.plates.first().id)
        assertEquals(ProjectManifest.legacyPlateId("old-project", 2), ProjectManifest.legacyPlateId("old-project", 2))
        assertNotEquals(ProjectManifest.legacyPlateId("old-project", 1), ProjectManifest.legacyPlateId("old-project", 2))
    }

    @Test fun newPlateIdsRoundTripWithoutChangingOrder() {
        val producer = ProjectManifest.Producer("Nozzle It All", "desktop", "test")
        val manifest = ProjectManifest("project", 1, "Test", producer, producer, 1,
            plates = listOf(ProjectManifest.PlateEntry(1, "Front", id = "plate-front"),
                ProjectManifest.PlateEntry(2, "Back", id = "plate-back")))
        assertEquals(listOf("plate-front", "plate-back"), ProjectManifest.parse(manifest.canonicalText()).plates.map { it.id })
    }
}
