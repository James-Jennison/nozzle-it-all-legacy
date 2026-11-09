package com.nozzleitall.desktop.prepare

import java.io.File

/**
 * Sliced output, reduced to what the preview draws: per layer, the extruding moves (x1,y1,x2,y2) and which toolhead made
 * each. Travel moves are kept separately so they can be shown on request. Bounded so a huge file can't exhaust memory.
 */
class GcodePreview(val layers: List<Layer>, val truncated: Boolean, val discardedPreamble: Boolean = false) {
    /** Per-extrusion attributes retained for Orca-familiar preview colouring. */
    class Layer(val z: Float, val segments: FloatArray, val tools: ByteArray, val travel: FloatArray,
                val kinds: ByteArray = ByteArray(tools.size), val speeds: FloatArray = FloatArray(tools.size),
                val widths: FloatArray = FloatArray(tools.size) { 0.4f },
                val fans: FloatArray = FloatArray(tools.size),
                val temperatures: FloatArray = FloatArray(tools.size),
                val pressureAdvance: FloatArray = FloatArray(tools.size),
                val hasExplicitWidths: Boolean = false, val planner: PlannerLayer? = null) { val count get() = tools.size }

    class PlannerLayer(
        val actualSpeed: FloatArray,
        val acceleration: FloatArray,
        val jerk: FloatArray,
        val height: FloatArray,
        val flow: FloatArray,
        val actualFlow: FloatArray,
        val layerTime: Float,
        val filamentMm: FloatArray,
        val moveSeconds: FloatArray,
    )

    val plannerBacked: Boolean by lazy { layers.isNotEmpty() && layers.all { it.planner != null } }

    fun available(view: PreviewView): Boolean = !view.requiresPlanner || plannerBacked

    val supportsRealisticView: Boolean by lazy { layers.isNotEmpty() && layers.all { it.hasExplicitWidths } }

    /** The line types the slicer names (`;TYPE:` comments), in the order the legend lists them. */
    val kindsUsed: List<Int> by lazy { val seen = BooleanArray(FEATURES.size + 1); layers.forEach { l -> l.kinds.forEach { seen[it.toInt()] = true } }; seen.indices.filter { seen[it] } }
    /** The toolheads that printed anything. */
    val toolsUsed: List<Int> by lazy { val seen = java.util.TreeSet<Int>(); layers.forEach { l -> l.tools.forEach { seen += it.toInt() } }; seen.toList() }
    /** Slowest and fastest extrusion in the file, mm/s (for the speed view's scale). */
    val speedRange: Pair<Float, Float> by lazy {
        var lo = Float.MAX_VALUE; var hi = 0f
        layers.forEach { l -> l.speeds.forEach { if (it > 0f) { if (it < lo) lo = it; if (it > hi) hi = it } } }
        if (hi == 0f) 0f to 0f else lo to hi
    }
    val layerTimes: FloatArray by lazy { FloatArray(layers.size) { li ->
        val layer = layers[li]
        var seconds = 0f
        for (i in 0 until layer.count) {
            val p = i * 4
            val dx = layer.segments[p + 2] - layer.segments[p]
            val dy = layer.segments[p + 3] - layer.segments[p + 1]
            val speed = layer.speeds[i]
            if (speed > 0f) seconds += kotlin.math.sqrt(dx * dx + dy * dy) / speed
        }
        seconds
    } }

    data class Usage(var seconds: Double = 0.0, var pathMm: Double = 0.0, var filamentMm: Double = 0.0) {
        fun add(seconds: Float, pathMm: Float, filamentMm: Float) {
            this.seconds += seconds
            this.pathMm += pathMm
            this.filamentMm += filamentMm
        }
    }

    class UsageTotals(val total: Usage, val byKind: Map<Int, Usage>, val byTool: Map<Int, Usage>)

    val usage: UsageTotals by lazy {
        val total = Usage()
        val byKind = mutableMapOf<Int, Usage>()
        val byTool = mutableMapOf<Int, Usage>()
        layers.forEach { layer ->
            val planner = layer.planner
            repeat(layer.count) { i ->
                val at = i * 4
                val dx = layer.segments[at + 2] - layer.segments[at]
                val dy = layer.segments[at + 3] - layer.segments[at + 1]
                val path = kotlin.math.hypot(dx, dy)
                val speed = layer.speeds[i]
                val seconds = planner?.moveSeconds?.get(i) ?: if (speed > 0f) path / speed else 0f
                val filament = planner?.filamentMm?.get(i) ?: 0f
                total.add(seconds, path, filament)
                byKind.getOrPut(layer.kinds[i].toInt()) { Usage() }.add(seconds, path, filament)
                byTool.getOrPut(layer.tools[i].toInt()) { Usage() }.add(seconds, path, filament)
            }
        }
        UsageTotals(total, byKind, byTool)
    }

    fun layerHeight(layerIndex: Int): Float = when {
        layerIndex !in layers.indices -> 0f
        layerIndex == 0 -> layers[0].z.coerceAtLeast(0f)
        else -> (layers[layerIndex].z - layers[layerIndex - 1].z).coerceAtLeast(0f)
    }

    fun value(view: PreviewView, layerIndex: Int, segmentIndex: Int): Float {
        val layer = layers[layerIndex]
        val planner = layer.planner
        return when (view) {
            PreviewView.SPEED -> layer.speeds[segmentIndex]
            PreviewView.ACTUAL_SPEED -> planner?.actualSpeed?.get(segmentIndex) ?: Float.NaN
            PreviewView.ACCELERATION -> planner?.acceleration?.get(segmentIndex) ?: Float.NaN
            PreviewView.JERK -> planner?.jerk?.get(segmentIndex) ?: Float.NaN
            PreviewView.LAYER_HEIGHT -> planner?.height?.get(segmentIndex) ?: layerHeight(layerIndex)
            PreviewView.LINE_WIDTH -> layer.widths[segmentIndex]
            PreviewView.FLOW -> planner?.flow?.get(segmentIndex)
                ?: layer.speeds[segmentIndex] * layer.widths[segmentIndex] * layerHeight(layerIndex)
            PreviewView.ACTUAL_FLOW -> planner?.actualFlow?.get(segmentIndex) ?: Float.NaN
            PreviewView.LAYER_TIME, PreviewView.LAYER_TIME_LOG -> planner?.layerTime ?: layerTimes[layerIndex]
            PreviewView.FAN_SPEED -> layer.fans[segmentIndex]
            PreviewView.TEMPERATURE -> layer.temperatures[segmentIndex]
            PreviewView.PRESSURE_ADVANCE -> layer.pressureAdvance[segmentIndex]
            PreviewView.SUMMARY, PreviewView.LINE_TYPE, PreviewView.TOOL -> 0f
        }
    }

    private val ranges = java.util.concurrent.ConcurrentHashMap<PreviewView, Pair<Float, Float>>()

    fun valueRange(view: PreviewView): Pair<Float, Float> = ranges.computeIfAbsent(view, ::calculateRange)

    private fun calculateRange(view: PreviewView): Pair<Float, Float> {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        layers.forEachIndexed { li, layer -> repeat(layer.count) { si ->
            val value = value(view, li, si)
            if (value.isFinite()) { lo = minOf(lo, value); hi = maxOf(hi, value) }
        } }
        return if (lo == Float.MAX_VALUE) 0f to 0f else lo to hi
    }

    companion object {
        const val MAX_SEGMENTS = 4_000_000
        private val COMPACT_WORD_BOUNDARY = Regex("(?<=[0-9.])(?=[XYZEF])")
        /** Slicer line types, with the colour each is drawn in (ARGB). The last index, [OTHER], is anything unnamed. */
        val FEATURES: List<Pair<String, Long>> = listOf(
            "Outer wall" to 0xFFFF7D38, "Inner wall" to 0xFFFFD84A, "Overhang wall" to 0xFF3D5BFF, "Sparse infill" to 0xFFC0463E, "Internal solid infill" to 0xFFB46AD8,
            "Top surface" to 0xFFE8433F, "Bottom surface" to 0xFF2FB5A8, "Bridge" to 0xFF5AA9E6, "Gap infill" to 0xFFF2F2F2, "Support" to 0xFF58C46A,
            "Support interface" to 0xFF2E8B57, "Skirt" to 0xFF9AA5B1, "Brim" to 0xFFC7B299, "Prime tower" to 0xFFE19AC8, "Custom" to 0xFF8C8C8C)
        val OTHER: Int = FEATURES.size

        /** The index for a `;TYPE:` name; slicers differ a little in wording (Wipe tower, Internal bridge, Floating vertical shell). */
        fun featureIndex(name: String): Int {
            val n = name.trim()
            FEATURES.indexOfFirst { it.first.equals(n, true) }.takeIf { it >= 0 }?.let { return it }
            return when {
                n.contains("wipe tower", true) || n.contains("prime tower", true) -> FEATURES.indexOfFirst { it.first == "Prime tower" }
                n.contains("bridge", true) -> FEATURES.indexOfFirst { it.first == "Bridge" }
                n.contains("overhang", true) -> FEATURES.indexOfFirst { it.first == "Overhang wall" }
                n.contains("support", true) -> FEATURES.indexOfFirst { it.first == "Support" }
                else -> OTHER
            }
        }

        /** A colour along blue → green → yellow → red for [t] in 0..1 (ARGB). */
        fun speedColour(t: Float): Long {
            val x = t.coerceIn(0f, 1f)
            val (r, g, b) = when {
                x < 0.33f -> { val k = x / 0.33f; Triple(0.20f, 0.35f + 0.50f * k, 1.0f - 0.55f * k) }
                x < 0.66f -> { val k = (x - 0.33f) / 0.33f; Triple(0.20f + 0.75f * k, 0.85f + 0.05f * k, 0.45f - 0.30f * k) }
                else -> { val k = (x - 0.66f) / 0.34f; Triple(0.95f, 0.90f - 0.65f * k, 0.15f - 0.05f * k) }
            }
            return 0xFF000000 or ((r * 255).toLong() shl 16) or ((g * 255).toLong() shl 8) or (b * 255).toLong()
        }

        fun parse(file: File): GcodePreview {
            val layers = ArrayList<Layer>()
            var seg = FloatArrayBuilder(); var tools = ByteArrayBuilder(); var travel = FloatArrayBuilder(); var kinds = ByteArrayBuilder(); var speeds = FloatArrayBuilder()
            var widths = FloatArrayBuilder(); var fans = FloatArrayBuilder(); var temperatures = FloatArrayBuilder(); var pressureAdvance = FloatArrayBuilder()
            var kind = OTHER; var feed = 0f; var width = 0.4f; var fan = 0f; var temperature = 0f; var pressure = 0f
            var x = 0f; var y = 0f; var z = 0f; var e = 0f; var relativeE = false; var absolute = true; var tool = 0
            // The engine writes a new width tag only when the extrusion width changes, including across layers.
            var widthKnown = false
            var allWidthsKnown = true
            var physicalZKnown = false
            var layerZ = -1f; var pendingMarkerZ: Float? = null; var layerZProvisional = false
            var layerHasExtrusion = false
            var total = 0; var truncated = false; var markerSeen = false; var discardedPreamble = false
            fun resetLayer() {
                seg = FloatArrayBuilder(); tools = ByteArrayBuilder(); travel = FloatArrayBuilder()
                kinds = ByteArrayBuilder(); speeds = FloatArrayBuilder()
                widths = FloatArrayBuilder(); fans = FloatArrayBuilder(); temperatures = FloatArrayBuilder(); pressureAdvance = FloatArrayBuilder()
                allWidthsKnown = true
                layerHasExtrusion = false
            }
            fun flush() { if (tools.size > 0 || travel.size > 0) layers += Layer(if (layerZ >= 0f) layerZ else z, seg.toArray(), tools.toArray(), travel.toArray(), kinds.toArray(), speeds.toArray(), widths.toArray(), fans.toArray(), temperatures.toArray(), pressureAdvance.toArray(), allWidthsKnown); resetLayer() }
            file.useLines { lines -> for (raw in lines) {
                if (raw.startsWith(";LAYER_CHANGE") || raw.startsWith("; CHANGE_LAYER") || raw.startsWith(";LAYER:")) {
                    // Start G-code may extrude a purge line before the first real layer. Once markers exist it is not a
                    // printable model layer and must not create a phantom slider entry or distort the model framing.
                    // Record the discard so atypical G-code never loses pre-marker geometry without a visible warning.
                    if (markerSeen) flush() else {
                        markerSeen = true
                        discardedPreamble = tools.size > 0
                        resetLayer()
                    }
                    layerZ = pendingMarkerZ ?: -1f
                    // A comment before a marker is useful but provisional: the first real extrusion height wins if a
                    // producer actually meant that comment for the layer that just ended.
                    layerZProvisional = pendingMarkerZ != null
                    pendingMarkerZ = null
                    continue
                }
                if (raw.startsWith(";Z:")) {
                    raw.substringAfter(':').trim().toFloatOrNull()?.takeIf(Float::isFinite)?.let {
                        // Orca normally writes Z immediately after the marker. Some producers write it immediately
                        // before the next marker. Treat the latter as provisional until the following layer's first
                        // extrusion confirms its physical height, so a trailing current-layer comment cannot relabel it.
                        if (markerSeen && !layerHasExtrusion) {
                            layerZ = it
                            layerZProvisional = false
                        } else pendingMarkerZ = it
                    }
                    continue
                }
                if (raw.startsWith(";TYPE:")) { kind = featureIndex(raw.substring(6)); continue }
                if (raw.startsWith(";WIDTH:")) { raw.substringAfter(':').trim().toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }?.let { width = it; widthKnown = true }; continue }
                val line = raw.substringBefore(';').trim(); if (line.isEmpty()) continue
                val words = line.replace(COMPACT_WORD_BOUNDARY, " ").split(' ', '\t')
                val cmd = words[0]
                when {
                    cmd == "G90" -> absolute = true
                    cmd == "G91" -> absolute = false
                    cmd == "M82" -> relativeE = false
                    cmd == "M83" -> relativeE = true
                    cmd == "M107" -> fan = 0f
                    cmd == "M106" -> words.firstOrNull { it.startsWith("S") }?.substring(1)?.toFloatOrNull()?.let { fan = (it / 255f * 100f).coerceIn(0f, 100f) }
                    cmd == "M104" || cmd == "M109" -> words.firstOrNull { it.startsWith("S") }?.substring(1)?.toFloatOrNull()?.let { temperature = it }
                    cmd == "SET_PRESSURE_ADVANCE" -> words.firstOrNull { it.startsWith("ADVANCE=") }?.substringAfter('=')?.toFloatOrNull()?.let { pressure = it }
                    cmd.length in 2..3 && cmd[0] == 'T' && cmd.substring(1).all(Char::isDigit) -> tool = cmd.substring(1).toInt()
                    cmd == "G92" -> words.forEach { if (it.startsWith("E")) e = it.substring(1).toFloatOrNull()?.takeIf(Float::isFinite) ?: e }
                    cmd == "G0" || cmd == "G1" -> {
                        var nx = x; var ny = y; var nz = z; var ne: Float? = null; var nextFeed = feed
                        var invalidMove = false; var moveHasZ = false
                        words.drop(1).forEach { w ->
                            if (w.length < 2) return@forEach // G-code may separate words with repeated spaces or tabs.
                            if (w[0] !in "XYZEF") return@forEach
                            val v = w.substring(1).toFloatOrNull()?.takeIf(Float::isFinite)
                            if (v == null) { invalidMove = true; return@forEach }
                            when (w[0]) { 'X' -> nx = if (absolute) v else x + v; 'Y' -> ny = if (absolute) v else y + v; 'Z' -> { nz = if (absolute) v else z + v; moveHasZ = true }; 'E' -> ne = v; 'F' -> nextFeed = v / 60f } }
                        if (invalidMove || !nx.isFinite() || !ny.isFinite() || !nz.isFinite() || !nextFeed.isFinite()) continue
                        if (moveHasZ) physicalZKnown = true
                        feed = nextFeed
                        val extruding = ne?.let { if (relativeE) it > 0f else it > e } ?: false
                        ne?.let { if (!relativeE) e = it }
                        if (nz != z) z = nz
                        if ((nx != x || ny != y) && total < MAX_SEGMENTS) {
                            if (extruding) {
                                // Capture the first extrusion height so later Z-hops cannot relabel the layer.
                                // A pre-marker Z comment is only a hint. The first drawable extrusion is the
                                // authoritative height even when the producer omits a redundant Z move.
                                if (layerZ < 0f || (layerZProvisional && physicalZKnown)) layerZ = nz
                                layerZProvisional = false
                                layerHasExtrusion = true
                                seg.add(x, y, nx, ny); tools.add(tool.toByte()); kinds.add(kind.toByte()); speeds.add(feed)
                                allWidthsKnown = allWidthsKnown && widthKnown
                                widths.add(width); fans.add(fan); temperatures.add(temperature); pressureAdvance.add(pressure); total++
                            } else travel.add(x, y, nx, ny)
                        } else if (total >= MAX_SEGMENTS) truncated = true
                        x = nx; y = ny
                    }
                }
            } }
            flush()
            return GcodePreview(layers.filter { it.count > 0 }, truncated, discardedPreamble)
        }
    }
}

/** How the preview colours the print. */
enum class PreviewView(val label: String, val units: String = "", val requiresPlanner: Boolean = false) {
    SUMMARY("Summary"), LINE_TYPE("Line type"), TOOL("Filament"), SPEED("Speed", "mm/s"),
    ACTUAL_SPEED("Actual speed", "mm/s", true), ACCELERATION("Acceleration", "mm/s²", true), JERK("Jerk", "mm/s", true),
    LAYER_HEIGHT("Layer height", "mm"), LINE_WIDTH("Line width", "mm"), FLOW("Flow", "mm³/s"),
    ACTUAL_FLOW("Actual flow", "mm³/s", true), LAYER_TIME("Layer time", "s"), LAYER_TIME_LOG("Layer time (log)", "s"),
    FAN_SPEED("Fan speed", "%"), TEMPERATURE("Temperature", "°C"), PRESSURE_ADVANCE("Pressure advance");

    fun fraction(value: Float, low: Float, high: Float): Float = when {
        !value.isFinite() || high <= low -> 0.5f
        this == LAYER_TIME_LOG -> (kotlin.math.ln1p(value) - kotlin.math.ln1p(low)) /
            (kotlin.math.ln1p(high) - kotlin.math.ln1p(low))
        else -> (value - low) / (high - low)
    }
}

class FloatArrayBuilder(initialCapacity: Int = 1024) { private var a = FloatArray(initialCapacity); var size = 0; private set
    fun add(vararg v: Float) { if (size + v.size > a.size) a = a.copyOf(maxOf(a.size * 2, size + v.size)); v.copyInto(a, size); size += v.size }
    fun toArray() = a.copyOf(size) }
class ByteArrayBuilder(initialCapacity: Int = 256) { private var a = ByteArray(initialCapacity); var size = 0; private set
    fun add(v: Byte) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun toArray() = a.copyOf(size) }
