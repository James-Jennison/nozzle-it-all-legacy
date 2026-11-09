package com.nozzleitall.desktop.jobs

import net.jamesjennison.klippercompanion.BambuPrintProtocol
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

internal const val GCODE = "gcode"
internal const val BAMBU_BUNDLE = "gcode.3mf"
internal val preparedOutputs = setOf(GCODE, BAMBU_BUNDLE)

internal fun preparedOutput(name: String): String =
    if (name.endsWith(".$BAMBU_BUNDLE")) BAMBU_BUNDLE else GCODE

/** Desktop's LAN adapter currently sends a single external spool without an AMS mapping. */
internal fun bambuLanProblem(file: File): String? {
    val plate = try {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("Metadata/slice_info.config")
            if (entry == null || entry.size !in 1..1_048_576) null
            else zip.getInputStream(entry).use {
                val data = it.readNBytes(1_048_577)
                if (data.size.toLong() != entry.size) null
                else BambuPrintProtocol.slicePlate(data.toString(Charsets.UTF_8))
            }
        }
    } catch (_: IOException) { null }
    return when {
        plate == null || plate.filaments.isEmpty() -> "The Bambu print bundle has no readable filament information. Slice the plate again."
        plate.dynamicNozzleMap -> BambuPrintProtocol.DYNAMIC_NOZZLE_MAP_NOT_SUPPORTED
        plate.filaments.size > 1 -> BambuPrintProtocol.MULTI_MATERIAL_NOT_SUPPORTED
        else -> null
    }
}
