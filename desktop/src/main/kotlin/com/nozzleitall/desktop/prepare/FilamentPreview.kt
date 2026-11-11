package com.nozzleitall.desktop.prepare

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.hypot
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/** Immutable inputs captured on Main for the viewport worker. */
internal data class FilamentFrameScene(
    val preview: GcodePreview,
    val plan: PreviewRenderPlan,
    val palette: List<Color>,
    val hiddenTools: Set<Int>,
)

/** Draw recorded extrusion widths and layer heights with per-pixel depth. */
internal fun DrawScope.drawFilamentPreview(
    projector: Projector, scene: FilamentFrameScene, fallback: Color, depth: DepthSurfaceCache, check: () -> Unit,
) {
    val sample = 2f
    val supersampled = Projector(projector.w * sample, projector.h * sample,
        projector.cam.copy(pan = projector.cam.pan * sample), projector.cx, projector.cy)
    val surface = depth.frame(supersampled.w, supersampled.h)
    rasterFilamentPreview(supersampled, scene.preview, scene.plan, scene.palette, fallback, scene.hiddenTools, surface, check)
    check()
    val info = ImageInfo(surface.width, surface.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
    Image.makeRaster(info, surface.pixels, surface.width * 4).use { image ->
        val source = Rect.makeWH(image.width.toFloat(), image.height.toFloat())
        drawIntoCanvas { it.nativeCanvas.drawImageRect(image, source, Rect.makeWH(size.width, size.height), SamplingMode.LINEAR, null, true) }
    }
}

internal fun rasterFilamentPreview(
    projector: Projector,
    preview: GcodePreview,
    plan: PreviewRenderPlan,
    palette: List<Color>,
    fallback: Color,
    hiddenTools: Set<Int>,
    surface: DepthSurface,
    check: () -> Unit = {},
) {
    val vertices = FloatArray(18)
    val triangle = FloatArray(9)
    val colours = IntArray(3)
    val shades = (palette + fallback).map { colour ->
        intArrayOf(lerpColor(colour, Color.Black, 0.32f).toArgb(), colour.toArgb(),
            lerpColor(colour, Color.Black, 0.46f).toArgb())
    }
    fun face(a: Int, b: Int, c: Int, colourA: Int, colourB: Int, colourC: Int) {
        vertices.copyInto(triangle, 0, a * 3, a * 3 + 3)
        vertices.copyInto(triangle, 3, b * 3, b * 3 + 3)
        vertices.copyInto(triangle, 6, c * 3, c * 3 + 3)
        colours[0] = colourA
        colours[1] = colourB
        colours[2] = colourC
        surface.triangle(triangle, 0, colours, 0, projector.eye)
    }
    for (layerPlan in plan.layers) {
        check()
        val layer = preview.layers[layerPlan.layer]
        val height = preview.layerHeight(layerPlan.layer).coerceIn(0f, 2f)
        val edgeZ = layer.z - height * 0.75f
        for ((offset, index) in layerPlan.segments.withIndex()) {
            if (offset and 1023 == 0) check()
            val tool = layer.tools[index].toInt()
            if (tool in hiddenTools) continue
            val at = index * 4
            val x1 = layer.segments[at]
            val y1 = layer.segments[at + 1]
            val x2 = layer.segments[at + 2]
            val y2 = layer.segments[at + 3]
            val length = hypot(x2 - x1, y2 - y1)
            if (length <= 0f || !length.isFinite()) continue
            val radius = layer.widths[index].coerceIn(0f, 20f) * 0.5f
            val dx = (y1 - y2) / length * radius
            val dy = (x2 - x1) / length * radius
            projector.p(x1 + dx, y1 + dy, edgeZ, vertices, 0)
            projector.p(x2 + dx, y2 + dy, edgeZ, vertices, 3)
            projector.p(x1, y1, layer.z, vertices, 6)
            projector.p(x2, y2, layer.z, vertices, 9)
            projector.p(x1 - dx, y1 - dy, edgeZ, vertices, 12)
            projector.p(x2 - dx, y2 - dy, edgeZ, vertices, 15)
            val shade = shades.getOrElse(tool) { shades.last() }
            val left = shade[0]
            val top = shade[1]
            val right = shade[2]
            face(0, 1, 2, left, left, top)
            face(1, 3, 2, left, top, top)
            face(2, 3, 4, top, top, right)
            face(3, 5, 4, top, right, right)
        }
    }
}
