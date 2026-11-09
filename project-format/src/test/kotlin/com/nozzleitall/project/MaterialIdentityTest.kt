package com.nozzleitall.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialIdentityTest {
    @Test fun `rfid spool profile and object region mapping survive project manifest round trip`() {
        val producer = ProjectManifest.Producer("Nozzle It All", "desktop", "test")
        val source = ProjectManifest("p", 1, "RFID", producer, producer, 1,
            plates = listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(
                ProjectManifest.ObjectEntry(7, "Painted model", materialSlot = 1, paintSlots = listOf(2, 1)),
            ))),
            materials = listOf(
                ProjectManifest.MaterialSlot(1, "PLA", "Polymaker", "Basic", "#BE38F3", 0,
                    filamentProfile = "polymaker-pla", spoolId = "rfid-123", sku = "PA02001", fromTag = true),
                ProjectManifest.MaterialSlot(2, "TPU", "Polymaker", "95A", "#111111", 1,
                    filamentProfile = "polymaker-tpu95", spoolId = "rfid-456", sku = "PB02002", fromTag = true),
            ))
        val restored = ProjectManifest.parse(source.toJson())
        val material = restored.materials.first()
        assertEquals("rfid-123", material.spoolId)
        assertEquals("PA02001", material.sku)
        assertEquals("polymaker-pla", material.filamentProfile)
        assertTrue(material.fromTag)
        assertEquals(0, material.toolhead)
        assertEquals(1, restored.plates.single().objects.single().materialSlot)
        assertEquals(listOf(2, 1), restored.plates.single().objects.single().paintSlots)
        assertEquals("rfid-456", restored.materials[1].spoolId)
        assertEquals(1, restored.materials[1].toolhead)
    }
}
