package com.nozzleitall.desktop.prepare

import org.junit.Assert.assertEquals
import org.junit.Test

class FilamentPickerLabelsTest {
    @Test
    fun `native preset replaces duplicate catalog rows without hiding imported tuning`() {
        val native = FilamentLibrary.Entry("generic_pla_high_speed", "Generic PLA High Speed", "Generic", "PLA", "")
        val catalog = native.copy(id = "catalog-a")
        val alias = catalog.copy(id = "catalog-b", name = "Generic PLA High Speed @Other Machine")
        val imported = native.copy(id = "legacy-a")
        assertEquals(listOf(native, imported), filamentPickerOptions(listOf(catalog, native, alias, imported)))
        assertEquals(listOf(imported, catalog), filamentPickerOptions(listOf(imported, catalog, alias)))
    }

    @Test
    fun `separates vendor from product so each slot remains distinguishable`() {
        assertEquals("Polymaker" to "General PLA Family",
            filamentPickerLabels("Polymaker", "Polymaker General PLA Family"))
        assertEquals("Snapmaker" to "PLA Basic",
            filamentPickerLabels("Snapmaker", "Snapmaker PLA Basic"))
    }

    @Test
    fun `keeps a complete fallback when no vendor is available`() {
        assertEquals(null to "Custom Long Material Name",
            filamentPickerLabels("", "Custom Long Material Name"))
    }
}
