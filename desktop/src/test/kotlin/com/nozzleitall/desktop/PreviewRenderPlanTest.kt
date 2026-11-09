package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.GcodePreview
import com.nozzleitall.desktop.prepare.previewLayerCursor
import com.nozzleitall.desktop.prepare.previewRenderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewRenderPlanTest {
    private fun layer(z: Float, kinds: IntArray): GcodePreview.Layer {
        val segments = FloatArray(kinds.size * 4) { it.toFloat() }
        return GcodePreview.Layer(z, segments, ByteArray(kinds.size), FloatArray(0), ByteArray(kinds.size) { kinds[it].toByte() })
    }

    @Test fun finalPreviewRetainsCoverageFromTheFirstOfOneHundredEightyThreeLayers() {
        val outer = GcodePreview.featureIndex("Outer wall")
        val preview = GcodePreview(List(183) { layer((it + 1) * 0.2f, intArrayOf(outer)) }, false)

        val plan = previewRenderPlan(preview, 182)

        assertEquals((0 until 183).toList(), plan.layers.map { it.layer })
        assertEquals(182, plan.currentLayer)
    }

    @Test fun outlinesRemainContinuousWhileInteriorSamplingStaysBounded() {
        val outer = GcodePreview.featureIndex("Outer wall")
        val infill = GcodePreview.featureIndex("Sparse infill")
        val preview = GcodePreview(List(11) { layer((it + 1) * 0.2f, intArrayOf(outer, *IntArray(100) { infill })) }, false)

        val plan = previewRenderPlan(preview, 10, priorBudget = 50)

        assertTrue(plan.priorSegmentCount <= 50)
        assertEquals((0 until 10).toList(), plan.layers.filter { it.layer < 10 }.map { it.layer })
        plan.layers.filter { it.layer < 10 }.forEach { assertTrue("layer ${it.layer} lost its outer wall", 0 in it.segments) }
        assertEquals(101, plan.layers.single { it.layer == 10 }.segments.size)
    }

    @Test fun rangeExcludesEarlierLayersAndKeepsMoveLimitOnTheUpperLayer() {
        val preview = GcodePreview(List(6) { layer((it + 1) * 0.2f, intArrayOf(0, 0)) }, false)

        val plan = previewRenderPlan(preview, 4, moveLimit = 1, firstLayer = 2)

        assertEquals(listOf(2, 3, 4), plan.layers.map { it.layer })
        assertEquals(1, plan.layers.last().segments.size)
        assertEquals(listOf(4), previewRenderPlan(preview, 4, firstLayer = 9).layers.map { it.layer })
    }

    @Test fun emptyPreviewProducesAnEmptyPlan() {
        val preview = GcodePreview(emptyList(), false)
        val plan = previewRenderPlan(preview, 0)

        assertEquals(-1, plan.currentLayer)
        assertTrue(plan.layers.isEmpty())
        assertEquals(null, previewLayerCursor(preview, 0))
    }

    @Test fun zeroPriorBudgetStillKeepsTheSelectedLayerComplete() {
        val outer = GcodePreview.featureIndex("Outer wall")
        val preview = GcodePreview(List(3) { layer((it + 1) * 0.2f, intArrayOf(outer, outer)) }, false)

        val plan = previewRenderPlan(preview, 2, priorBudget = 0)

        assertEquals(0, plan.priorSegmentCount)
        assertEquals(2, plan.layers.single().layer)
        assertEquals(2, plan.layers.single().segments.size)
    }

    @Test fun moveLimitRevealsOnlyTheRequestedPartOfTheSelectedLayer() {
        val outer = GcodePreview.featureIndex("Outer wall")
        val preview = GcodePreview(listOf(layer(0.2f, IntArray(8) { outer })), false)

        assertEquals(3, previewRenderPlan(preview, 0, moveLimit = 3).layers.single().segments.size)
        assertEquals(0, previewRenderPlan(preview, 0, moveLimit = 0).layers.single().segments.size)
        assertEquals(8, previewRenderPlan(preview, 0, moveLimit = Int.MAX_VALUE).layers.single().segments.size)
    }

    @Test fun exactOutlineBudgetKeepsEveryPriorOutline() {
        val outer = GcodePreview.featureIndex("Outer wall")
        val preview = GcodePreview(List(3) { layer((it + 1) * 0.2f, IntArray(3) { outer }) }, false)

        val plan = previewRenderPlan(preview, 2, priorBudget = 6)

        assertEquals(6, plan.priorSegmentCount)
        assertEquals(listOf(0, 1, 2), plan.layers.map { it.layer })
    }

    @Test fun outlineOverflowIsSampledWithinThePriorBudget() {
        val outer = GcodePreview.featureIndex("Outer wall")
        val preview = GcodePreview(List(3) { layer((it + 1) * 0.2f, IntArray(100) { outer }) }, false)

        val plan = previewRenderPlan(preview, 2, priorBudget = 50)

        assertEquals(50, plan.priorSegmentCount)
        assertEquals(listOf(0, 1, 2), plan.layers.map { it.layer })
        assertEquals(100, plan.layers.last().segments.size)
    }

    @Test fun outlineHeavyHistoryStillRepresentsInteriorPaths() {
        val outer = GcodePreview.featureIndex("Outer wall")
        val infill = GcodePreview.featureIndex("Sparse infill")
        val prior = intArrayOf(*IntArray(100) { outer }, *IntArray(100) { infill })
        val preview = GcodePreview(listOf(layer(0.2f, prior), layer(0.4f, prior), layer(0.6f, intArrayOf(outer))), false)

        val plan = previewRenderPlan(preview, 2, priorBudget = 20)

        assertEquals(20, plan.priorSegmentCount)
        plan.layers.filter { it.layer < 2 }.forEach { layerPlan ->
            assertTrue(layerPlan.segments.any { prior[it] == outer })
        }
        assertTrue(plan.layers.filter { it.layer < 2 }.flatMap { it.segments.toList() }.any { prior[it] == infill })
    }

    @Test fun previewCursorClampsAndExposesSafeLayerMetadata() {
        val preview = GcodePreview(List(3) { layer((it + 1) * 0.2f, intArrayOf(0)) }, false)

        assertEquals(0, previewLayerCursor(preview, -10)?.index)
        assertEquals(2, previewLayerCursor(preview, 99)?.index)
        assertEquals(3, previewLayerCursor(preview, 1)?.count)
        assertEquals(0.4f, previewLayerCursor(preview, 1)?.z)
    }

    @Test fun negativeSelectedLayerClampsToTheCompleteFirstLayer() {
        val outer = GcodePreview.featureIndex("Outer wall")
        val preview = GcodePreview(List(3) { layer((it + 1) * 0.2f, IntArray(4) { outer }) }, false)

        val plan = previewRenderPlan(preview, -99, priorBudget = 1)

        assertEquals(0, plan.currentLayer)
        assertEquals(listOf(0), plan.layers.map { it.layer })
        assertEquals(4, plan.layers.single().segments.size)
        assertEquals(0, plan.priorSegmentCount)
    }

    @Test fun oversizedSelectedLayerClampsToTheCompleteFinalLayer() {
        val outer = GcodePreview.featureIndex("Outer wall")
        val preview = GcodePreview(List(3) { layer((it + 1) * 0.2f, IntArray(4) { outer }) }, false)

        val plan = previewRenderPlan(preview, 99, priorBudget = 2)

        assertEquals(2, plan.currentLayer)
        assertEquals(4, plan.layers.single { it.layer == 2 }.segments.size)
        assertEquals(2, plan.priorSegmentCount)
    }
}
