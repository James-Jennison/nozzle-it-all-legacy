package com.nozzleitall.desktop

import com.nozzleitall.desktop.screens.formatDuration
import com.nozzleitall.desktop.prepare.GcodePreview
import com.nozzleitall.desktop.prepare.PreviewView
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GcodePreviewTest {
    private fun preview(gcode: String): GcodePreview { val f = File.createTempFile("preview", ".gcode"); f.writeText(gcode); return try { GcodePreview.parse(f) } finally { f.delete() } }

    private val sample = """
        ;LAYER_CHANGE
        G1 Z0.2 F600
        ;TYPE:Outer wall
        G1 X0 Y0 F3000
        G1 X10 Y0 E1 F1800
        ;TYPE:Sparse infill
        G1 X10 Y10 E2 F6000
        T1
        ;TYPE:Wipe tower
        G1 X0 Y10 E3 F3600
        ;LAYER_CHANGE
        G1 Z0.4
        ;TYPE:Top surface
        G1 X0 Y0 E4
    """.trimIndent()

    @Test
    fun `preview duration shows days through seconds and keeps the existing summary format`() {
        assertEquals("0s", formatDuration(0.0, includeSeconds = true))
        assertEquals("4m 47s", formatDuration(287.0, includeSeconds = true))
        assertEquals("1h 0s", formatDuration(3600.0, includeSeconds = true))
        assertEquals("1d 2h 3m 4s", formatDuration(93_784.0, includeSeconds = true))
        assertEquals("–", formatDuration(Double.NaN, includeSeconds = true))
        assertEquals("4 min", formatDuration(287.0))
    }

    @Test fun lineTypesAreReadFromTypeComments() {
        val p = preview(sample)
        val names = p.layers.flatMap { l -> l.kinds.map { GcodePreview.FEATURES.getOrNull(it.toInt())?.first } }
        assertEquals(listOf("Outer wall", "Sparse infill", "Prime tower", "Top surface"), names)
    }

    @Test fun speedsAreMillimetresPerSecond() {
        val p = preview(sample)
        assertEquals(listOf(30f, 100f, 60f), p.layers[0].speeds.toList())
        assertEquals(30f to 100f, p.speedRange)
    }

    @Test fun theToolsUsedAreListed() = assertEquals(listOf(0, 1), preview(sample).toolsUsed)

    @Test fun repeatedSpacesInSlicerMovesStillProducePreviewSegments() {
        val parsed = preview(";LAYER_CHANGE\nG1 X0 Y0 F3000\nG1  X10 Y0  E1 F1800\n")
        assertEquals(1, parsed.layers.size)
        assertEquals(1, parsed.layers.single().count)
        assertEquals(listOf(0f, 0f, 10f, 0f), parsed.layers.single().segments.toList())
    }

    @Test fun tabsAndMixedSpacingInSlicerMovesProducePreviewSegments() {
        val parsed = preview(";LAYER_CHANGE\nG1\tX0 Y0 F3000\nG1 \tX10\tY0  E1 F1800\n")
        assertEquals(1, parsed.layers.single().count)
        assertEquals(listOf(0f, 0f, 10f, 0f), parsed.layers.single().segments.toList())
    }

    @Test fun compactGcodeWordsProducePreviewSegments() {
        val parsed = preview(";LAYER_CHANGE\nG1X0Y0F3000\nG1X10Y0E1F1800\n")
        assertEquals(1, parsed.layers.single().count)
        assertEquals(listOf(0f, 0f, 10f, 0f), parsed.layers.single().segments.toList())
    }

    @Test fun nonFiniteAndMalformedCoordinatesSkipTheWholeMove() {
        val parsed = preview(";LAYER_CHANGE\nG92ENaN\nG1 XNaN Y10 E1\nG1 X1e40 Y10 E1\nG1 Xbad Y10 E1\nG1 X10 Y0 E1\n")
        assertEquals(1, parsed.layers.single().count)
        assertEquals(listOf(0f, 0f, 10f, 0f), parsed.layers.single().segments.toList())
        assertTrue(parsed.layers.single().segments.all(Float::isFinite))
    }

    @Test fun skippedMoveDoesNotChangeFeedOrPosition() {
        val parsed = preview(";LAYER_CHANGE\nG1 X0 Y0 F1800\nG1 F6000 Xbad Y20 E1\nG1 X10 Y0 E1\n")
        assertEquals(listOf(0f, 0f, 10f, 0f), parsed.layers.single().segments.toList())
        assertEquals(listOf(30f), parsed.layers.single().speeds.toList())
    }

    @Test fun relativeCoordinateOverflowSkipsMoveWithoutPoisoningFollowingMove() {
        val parsed = preview(";LAYER_CHANGE\nG91\nG1 X3e38 Y0 F1800\nG1 X3e38 Y0 E1\nG90\nG1 X10 Y0 E1\n")
        assertEquals(1, parsed.layers.single().count)
        assertEquals(listOf(3e38f, 0f, 10f, 0f), parsed.layers.single().segments.toList())
    }

    @Test fun commentOnlyGcodeHasNoDrawableLayers() {
        assertTrue(preview(";LAYER_CHANGE\n; no extrusion\n").layers.isEmpty())
    }

    @Test fun purgePreambleDoesNotCreateAPhantomLayerAndMarkerHeightsUseTheNewLayerZ() {
        val parsed = preview("""
            G1 Z0.05
            G1 X0 Y0
            G1 X20 Y0 E1
            ;LAYER_CHANGE
            ;Z:0.25
            G1 Z0.25
            G1 X0 Y0
            G1 X10 Y0 E2
            ;LAYER_CHANGE
            ;Z:0.45
            G1 Z0.45
            G1 X10 Y10 E3
        """.trimIndent())

        assertEquals(2, parsed.layers.size)
        assertEquals(listOf(0.25f, 0.45f), parsed.layers.map { it.z })
        assertEquals(2, parsed.layers.sumOf { it.count })
        assertFalse(parsed.layers.flatMap { it.segments.toList() }.contains(20f))
        assertTrue(parsed.discardedPreamble)
    }

    @Test fun CuraLayerMarkersAlsoSeparatePreviewLayers() {
        val parsed = preview(";LAYER:0\nG1 Z0.2\nG1 X10 Y0 E1\n;LAYER:1\nG1 Z0.4\nG1 X10 Y10 E2\n")
        assertEquals(listOf(0.2f, 0.4f), parsed.layers.map { it.z })
    }

    @Test fun firstExtrusionHeightWinsOverLaterZHopWhenMarkerHasNoZComment() {
        val parsed = preview(";LAYER:0\nG1 Z0.2\nG1 X10 Y0 E1\nG1 Z0.6\nG1 X0 Y0\n;LAYER:1\nG1 Z0.4\nG1 X10 Y10 E2\n")
        assertEquals(listOf(0.2f, 0.4f), parsed.layers.map { it.z })
    }

    @Test fun zCommentBeforeMarkerIsAppliedToTheFollowingLayer() {
        val parsed = preview(";Z:0.25\n;LAYER_CHANGE\nG1 X10 Y0 E1\n;Z:0.45\n;LAYER_CHANGE\nG1 X10 Y10 E2\n")
        assertEquals(listOf(0.25f, 0.45f), parsed.layers.map { it.z })
    }

    @Test fun firstExtrusionValidatesAProvisionalZCommentFromBeforeTheMarker() {
        val parsed = preview(";LAYER:0\n;Z:0.2\nG1 Z0.2\nG1 X10 Y0 E1\n;Z:0.6\n;LAYER:1\nG1 Z0.4\nG1 X10 Y10 E2\n")
        assertEquals(listOf(0.2f, 0.4f), parsed.layers.map { it.z })
    }

    @Test fun firstExtrusionOverridesAProvisionalZCommentWithoutARedundantZMove() {
        val parsed = preview(";LAYER:0\nG1 Z0.2\nG1 X10 Y0 E1\n;Z:0.6\n;LAYER:1\nG1 X10 Y10 E2\n")
        assertEquals(listOf(0.2f, 0.2f), parsed.layers.map { it.z })
    }

    @Test fun zCommentAfterTravelBeforeFirstExtrusionStillLabelsTheCurrentLayer() {
        val parsed = preview(";LAYER:0\nG1 X5 Y5\n;Z:0.2\nG1 Z0.2\nG1 X10 Y0 E1\n")
        assertEquals(listOf(0.2f), parsed.layers.map { it.z })
    }

    @Test fun markerOnlyLayersDoNotCreatePhantomPreviewLayersOrLeakTheirZ() {
        val parsed = preview(";LAYER:0\n;Z:0.2\n;LAYER:1\n;Z:0.4\nG1 Z0.4\nG1 X10 Y10 E1\n")
        assertEquals(1, parsed.layers.size)
        assertEquals(0.4f, parsed.layers.single().z)
    }

    @Test fun unmarkedGcodeKeepsItsPrintableGeometryWithoutPreambleWarning() {
        val parsed = preview("G1 Z0.2\nG1 X10 Y0 E1\nG1 X10 Y10 E2\n")
        assertEquals(1, parsed.layers.size)
        assertFalse(parsed.discardedPreamble)
    }

    @Test fun unknownNamesFallBackSensibly() {
        assertEquals(GcodePreview.featureIndex("Wipe tower"), GcodePreview.featureIndex("Prime tower"))
        assertEquals(GcodePreview.OTHER, GcodePreview.featureIndex("Something new"))
        assertEquals(GcodePreview.featureIndex("Bridge"), GcodePreview.featureIndex("Internal bridge"))
    }

    @Test fun theSpeedColourRunsFromBlueToRed() {
        val slow = GcodePreview.speedColour(0f); val fast = GcodePreview.speedColour(1f)
        assertTrue((slow and 0xFF) > (slow shr 16 and 0xFF)); assertTrue((fast shr 16 and 0xFF) > (fast and 0xFF))
    }

    @Test fun extendedPreviewAttributesAreRetainedPerExtrusion() {
        val parsed = preview("""
            ;LAYER_CHANGE
            G1 Z0.2
            ;WIDTH:0.48
            M106 S128
            M104 S215
            SET_PRESSURE_ADVANCE ADVANCE=0.035
            G1 X10 Y0 E1 F1800
            ;LAYER_CHANGE
            G1 Z0.4
            G1 X10 Y10 E2 F3600
        """.trimIndent())
        val first = parsed.layers.first()
        assertEquals(0.48f, first.widths.single(), 0.0001f)
        assertTrue(parsed.supportsRealisticView)
        assertEquals(128f / 255f * 100f, first.fans.single(), 0.01f)
        assertEquals(215f, first.temperatures.single(), 0.01f)
        assertEquals(0.035f, first.pressureAdvance.single(), 0.0001f)
        assertEquals(0.2f, parsed.value(PreviewView.LAYER_HEIGHT, 1, 0), 0.0001f)
        assertTrue(parsed.value(PreviewView.SPEED, 0, 0) > 0f)
        assertTrue(parsed.value(PreviewView.ACTUAL_SPEED, 0, 0).isNaN())
        assertTrue(parsed.value(PreviewView.FLOW, 0, 0) > 0f)
        assertTrue(parsed.value(PreviewView.LAYER_TIME, 0, 0) > 0f)
    }

    @Test fun realisticViewRequiresRecordedWidthForEveryExtrusion() {
        val mixed = sample.replace(";TYPE:Top surface", ";WIDTH:0.5\n;TYPE:Top surface")
        val parsed = preview(mixed)

        assertFalse(parsed.layers.first().hasExplicitWidths)
        assertTrue(parsed.layers.last().hasExplicitWidths)
        assertFalse(parsed.supportsRealisticView)
    }

    @Test fun previewOffersTheFamiliarDetailedViewSet() {
        assertTrue(PreviewView.entries.size >= 9)
        assertTrue(PreviewView.entries.map { it.label }.containsAll(listOf(
            "Summary", "Line type", "Filament", "Speed", "Actual speed", "Layer height", "Line width", "Flow",
            "Layer time", "Fan speed", "Temperature")))
    }
}
