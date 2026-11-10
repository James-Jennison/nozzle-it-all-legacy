package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Mesh
import org.json.JSONArray

/** Keep painted facets and their winding intact so even subdivided Orca facet paint remains valid. */
internal class SimplifyPaint(private val source: Mesh) {
    private data class Point(val x: Float, val y: Float, val z: Float) : Comparable<Point> {
        override fun compareTo(other: Point) = compareValuesBy(this, other, Point::x, Point::y, Point::z)
    }
    private data class Face(val points: List<Point>, val first: Int) {
        val key get() = List(3) { points[(first + it) % 3] }
    }
    private val paint = listOf(source.paint, source.supportPaint, source.seamPaint, source.fuzzyPaint)
    private val painted = HashMap<List<Point>, Pair<Int, Int>>()
    private val protected = LinkedHashSet<Point>()

    init {
        for (i in 0 until source.triangleCount) {
            if (paint.none { it?.get(i) != null }) continue
            val face = face(source, i)
            check(painted.put(face.key, i to face.first) == null) { "Overlapping painted facets cannot be simplified safely." }
            protected.addAll(face.points)
        }
        // Keeping only the painted end of a cylinder can let the opposite planar face
        // collapse to one point. Anchor both end faces because Z can be mirrored.
        val bounds = source.bounds()
        val directions = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
        for (z in setOf(bounds[2], bounds[5])) {
            val extremes = arrayOfNulls<Point>(directions.size)
            for (i in 0 until source.triangleCount) {
                if ((0..2).any { source.vertices[source.triangles[i * 3 + it] * 3 + 2] != z }) continue
                for (point in face(source, i).points) {
                    directions.forEachIndexed { j, (x, y) ->
                        val best = extremes[j]
                        if (best == null || point.x.toDouble() * x + point.y.toDouble() * y >
                            best.x.toDouble() * x + best.y.toDouble() * y) extremes[j] = point
                    }
                }
            }
            protected.addAll(extremes.filterNotNull())
        }
    }

    fun protectedVertices() = JSONArray(protected.map { listOf(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) })

    fun restore(result: Mesh): Mesh {
        val output = paint.map { if (it == null) null else arrayOfNulls<String>(result.triangleCount) }
        val triangles = result.triangles.copyOf()
        val remaining = HashMap(painted)
        for (i in 0 until result.triangleCount) {
            val face = face(result, i)
            if (face.key !in painted) continue
            val (sourceIndex, sourceFirst) = remaining.remove(face.key)
                ?: throw ModelingOps.EngineError("Simplify duplicated a painted facet.")
            val rotation = (face.first - sourceFirst + 3) % 3
            for (corner in 0..2) triangles[i * 3 + corner] = result.triangles[i * 3 + (corner + rotation) % 3]
            for (kind in paint.indices) output[kind]?.set(i, paint[kind]?.get(sourceIndex))
        }
        if (remaining.isNotEmpty()) throw ModelingOps.EngineError("Simplify would remove or alter painted facets.")
        return Mesh(result.vertices, triangles, output[0], output[1], output[2], output[3])
    }

    private fun face(mesh: Mesh, index: Int): Face {
        fun normalized(value: Float) = if (value == 0f) 0f else value
        val points = List(3) { corner ->
            val i = mesh.triangles[index * 3 + corner] * 3
            Point(normalized(mesh.vertices[i]), normalized(mesh.vertices[i + 1]), normalized(mesh.vertices[i + 2]))
        }
        return Face(points, points.indices.minBy { points[it] })
    }
}
