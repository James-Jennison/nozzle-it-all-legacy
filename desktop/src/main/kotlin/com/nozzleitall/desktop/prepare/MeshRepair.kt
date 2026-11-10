package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Mesh
import kotlin.math.abs
import kotlin.math.sqrt

/** STL drops vertex indices. Restore exact shared vertices before inspecting the repaired surface. */
internal object MeshRepair {
    data class Result(val mesh: Mesh, val openEdges: Int, val nonManifoldEdges: Int)

    private data class Point(val x: Double, val y: Double, val z: Double) {
        operator fun minus(other: Point) = Point(x - other.x, y - other.y, z - other.z)
        fun cross(other: Point) = Point(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
        fun dot(other: Point) = x * other.x + y * other.y + z * other.z
    }

    private data class Edge(val from: Int, val to: Int) {
        fun reversed() = Edge(to, from)
    }

    fun indexed(mesh: Mesh): Mesh {
        val points = LinkedHashMap<Point, Int>()
        fun coord(value: Float) = if (value == 0f) 0.0 else value.toDouble()
        val indices = IntArray(mesh.vertexCount) { i ->
            val p = i * 3
            points.getOrPut(Point(coord(mesh.vertices[p]), coord(mesh.vertices[p + 1]), coord(mesh.vertices[p + 2]))) {
                points.size
            }
        }
        val coords = FloatArray(points.size * 3)
        points.keys.forEachIndexed { i, point ->
            coords[i * 3] = point.x.toFloat()
            coords[i * 3 + 1] = point.y.toFloat()
            coords[i * 3 + 2] = point.z.toFloat()
        }
        val triangles = IntArray(mesh.triangles.size) { indices[mesh.triangles[it]] }
        return Mesh(coords, triangles, mesh.paint, mesh.supportPaint, mesh.seamPaint, mesh.fuzzyPaint)
    }

    fun closePlanarHole(mesh: Mesh): Result {
        val indexed = indexed(mesh)
        val coords = indexed.vertices
        val triangles = indexed.triangles
        fun point(vertex: Int) = Point(coords[vertex * 3].toDouble(), coords[vertex * 3 + 1].toDouble(), coords[vertex * 3 + 2].toDouble())
        val edges = HashMap<Edge, Int>()
        for (face in triangles.indices step 3) {
            for (side in 0..2) {
                val edge = Edge(triangles[face + side], triangles[face + (side + 1) % 3])
                edges[edge] = (edges[edge] ?: 0) + 1
            }
        }
        val boundary = edges.filter { (edge, uses) -> uses == 1 && edge.reversed() !in edges }.keys
        val badEdges = edges.keys.filter { it.from == it.to || edges.getValue(it) > 1 }
            .map { if (it.from <= it.to) it else it.reversed() }.toSet().size
        val unchanged = Result(indexed, boundary.size, badEdges)
        // Only cap a single consistently oriented, strictly convex planar loop. Other defects stay visible.
        if (mesh.hasAnyPaint || boundary.size !in 3..64 || badEdges != 0) return unchanged
        val next = HashMap<Int, Int>()
        for (edge in boundary) {
            if (next.put(edge.to, edge.from) != null) return unchanged
        }
        val start = next.keys.min()
        val loop = ArrayList<Int>(boundary.size)
        var vertex = start
        repeat(boundary.size) {
            if (vertex in loop) return unchanged
            loop += vertex
            vertex = next[vertex] ?: return unchanged
        }
        if (vertex != start) return unchanged

        val origin = point(start)
        val polygon = loop.map { point(it) - origin }
        val normal = polygon[1].cross(polygon[2])
        val normalLength = sqrt(normal.dot(normal))
        val tolerance = 1e-5 * normalLength
        if (normalLength == 0.0 || polygon.any { abs(it.dot(normal)) > tolerance })
            return unchanged
        // Restrict this fallback to a missing outer face, with no existing faces on the cap plane.
        // This avoids closing sheets, bridging an interior opening, or placing a cap over other faces.
        val distances = DoubleArray(indexed.vertexCount) { (point(it) - origin).dot(normal) }
        if (distances.none { it < -tolerance } || distances.any { it > tolerance } ||
            triangles.indices.step(3).any { i -> (0..2).all { abs(distances[triangles[i + it]]) <= tolerance } })
            return unchanged
        for (i in polygon.indices) {
            val j = (i + 1) % polygon.size
            val side = polygon[j] - polygon[i]
            if (polygon.indices.any { it != i && it != j && side.cross(polygon[it] - polygon[i]).dot(normal) <= 0.0 })
                return unchanged
        }
        if ((2 until loop.lastIndex).any { Edge(start, loop[it]) in edges || Edge(loop[it], start) in edges })
            return unchanged
        val cap = IntArray((loop.size - 2) * 3) { i ->
            when (i % 3) {
                0 -> start
                1 -> loop[i / 3 + 1]
                else -> loop[i / 3 + 2]
            }
        }
        return Result(Mesh(coords, triangles + cap), 0, 0)
    }
}
