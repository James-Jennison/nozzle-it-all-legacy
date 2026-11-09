package com.nozzleitall.desktop.prepare

import java.io.BufferedReader
import java.io.File
import java.io.IOException

/** A complete, bounded Desktop sidecar from the native planner. A failed export leaves plain G-code usable. */
internal object PlannerPreview {
    private const val HEADER = "NIA_PREVIEW\t1"
    private const val MAX_MOVES = 600_000
    private const val MAX_LAYERS = 20_000
    private const val MAX_FILE_BYTES = 128L * 1024 * 1024
    private const val MAX_ROW_LENGTH = 512
    private const val FIELD_COUNT = 22

    fun read(gcode: File): GcodePreview? {
        val sidecar = File(gcode.parentFile, "${gcode.name}.preview.tsv")
        if (!gcode.isFile || !sidecar.isFile || sidecar.length() !in 1..MAX_FILE_BYTES ||
            sidecar.lastModified() < gcode.lastModified()) return null
        return try {
            sidecar.bufferedReader().use { input ->
                if (input.readRow() != HEADER) return null
                val layers = linkedMapOf<Int, LayerBuilder>()
                var count = 0
                while (true) {
                    val line = input.readRow() ?: return null
                    val fields = line.split('\t')
                    if (fields.firstOrNull() == "END") {
                        if (fields != listOf("END", count.toString(), "0") || count == 0 || input.readRow() != null) return null
                        break
                    }
                    if (++count > MAX_MOVES || fields.size != FIELD_COUNT) return null
                    val id = fields[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                    val z = fields[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
                    val tool = fields[6].toIntOrNull()?.takeIf { it in 0..127 } ?: return null
                    if (id !in layers && layers.size == MAX_LAYERS) return null
                    val layer = layers.getOrPut(id) { LayerBuilder(z) }
                    if (kotlin.math.abs(layer.z - z) > 0.001f || !layer.add(fields, tool)) return null
                }
                if (layers.values.any { !it.hasConsistentTime }) return null
                GcodePreview(layers.values.sortedBy { it.z }.map { it.build() }, truncated = false)
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun BufferedReader.readRow(): String? {
        val row = StringBuilder(160)
        while (true) {
            val char = read()
            if (char == -1) return if (row.isEmpty()) null else throw IOException("Unterminated preview row")
            if (char == '\n'.code) return row.toString()
            if (row.length == MAX_ROW_LENGTH) throw IOException("Oversized preview row")
            row.append(char.toChar())
        }
    }

    private class LayerBuilder(val z: Float) {
        private val xy = FloatArrayBuilder(32)
        private val tools = ByteArrayBuilder(8)
        private val kinds = ByteArrayBuilder(8)
        private val speed = FloatArrayBuilder(8)
        private val actualSpeed = FloatArrayBuilder(8)
        private val acceleration = FloatArrayBuilder(8)
        private val jerk = FloatArrayBuilder(8)
        private val height = FloatArrayBuilder(8)
        private val width = FloatArrayBuilder(8)
        private val flow = FloatArrayBuilder(8)
        private val actualFlow = FloatArrayBuilder(8)
        private val fan = FloatArrayBuilder(8)
        private val temperature = FloatArrayBuilder(8)
        private val pressure = FloatArrayBuilder(8)
        private val filament = FloatArrayBuilder(8)
        private val moveSeconds = FloatArrayBuilder(8)
        private var layerSeconds: Float? = null
        private var summedMoveSeconds = 0.0
        val hasConsistentTime: Boolean
            get() {
                val total = layerSeconds ?: return false
                return summedMoveSeconds <= total + maxOf(0.01, total * 0.001)
            }
        private var widthsRecorded = true

        fun add(fields: List<String>, tool: Int): Boolean {
            val values = FloatArray(FIELD_COUNT)
            for (i in 2 until FIELD_COUNT) {
                if (i == 6 || i == 7) continue
                values[i] = fields[i].toFloatOrNull()?.takeIf { it in -100_000_000f..100_000_000f } ?: return false
            }
            if ((8..16).any { values[it] < 0f } || values[17] !in 0f..100f ||
                values[19] < 0f || values[21] < 0f) return false
            val seconds = layerSeconds
            if (seconds != null && seconds != values[16]) return false
            layerSeconds = values[16]
            summedMoveSeconds += values[21]
            xy.add(values[2], values[3], values[4], values[5])
            tools.add(tool.toByte())
            kinds.add(GcodePreview.featureIndex(fields[7]).toByte())
            speed.add(values[8])
            actualSpeed.add(values[9])
            acceleration.add(values[10])
            jerk.add(values[11])
            height.add(values[12])
            width.add(values[13])
            widthsRecorded = widthsRecorded && values[13] > 0f
            flow.add(values[14])
            actualFlow.add(values[15])
            fan.add(values[17])
            temperature.add(values[18])
            pressure.add(values[19])
            filament.add(values[20].coerceAtLeast(0f))
            moveSeconds.add(values[21])
            return true
        }

        fun build() = GcodePreview.Layer(z, xy.toArray(), tools.toArray(), FloatArray(0), kinds.toArray(),
            speed.toArray(), width.toArray(), fan.toArray(), temperature.toArray(), pressure.toArray(),
            hasExplicitWidths = widthsRecorded,
            planner = GcodePreview.PlannerLayer(actualSpeed.toArray(), acceleration.toArray(), jerk.toArray(),
                height.toArray(), flow.toArray(), actualFlow.toArray(), requireNotNull(layerSeconds), filament.toArray(), moveSeconds.toArray()))
    }
}
