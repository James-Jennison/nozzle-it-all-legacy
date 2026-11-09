package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Mesh
import com.nozzleitall.project.MeshIO
import com.nozzleitall.project.Transform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * NIA's headless modeling bridge. Geometry stays in the pinned Orca/libslic3r engine (`nozzle-engine --model`);
 * the Compose shell supplies interaction, preview and project history without reimplementing mesh algorithms.
 */
object ModelingOps {
    class EngineError(message: String) : Exception(message)

    private const val MAX_RESPONSE_CHARS = 1_048_576
    private const val MAX_DIAGNOSTIC_CHARS = 65_536

    enum class BooleanMode(val wire: String) { UNION("union"), SUBTRACT("subtract"), INTERSECT("intersect") }
    enum class ReliefMode(val label: String) { STANDALONE("New object"), RAISED("Raised"), ENGRAVED("Engraved") }
    data class MeshStats(val triangles: Int, val parts: Int, val openEdges: Int, val volume: Double)
    data class RepairPreview(val before: MeshStats, val after: MeshStats)
    data class Measurement(val distance: Double, val delta: DoubleArray, val angleDegrees: Double?, val diameter: Double?)

    private fun run(request: JSONObject, timeoutSeconds: Long = 120): JSONObject {
        val engine = SliceEngine.locateEngine() ?: throw EngineError("The slicing engine isn't installed with this copy of Nozzle It All.")
        val req = File.createTempFile("nozzle-model", ".json").apply { deleteOnExit(); writeText(request.toString()) }
        try {
            return runProcess(listOf(engine.absolutePath, "--model", req.absolutePath), timeoutSeconds)
        } finally {
            req.delete()
        }
    }

    private data class Capture(val text: String, val truncated: Boolean)

    /** Drains both streams while the process runs so timeout and cancellation remain reachable. */
    internal fun runProcess(command: List<String>, timeoutSeconds: Long = 120): JSONObject =
        runProcess(ProcessBuilder(command), timeoutSeconds)

    internal fun runProcess(builder: ProcessBuilder, timeoutSeconds: Long, input: String? = null,
                            responseLimit: Int = MAX_RESPONSE_CHARS): JSONObject {
        require(builder.command().isNotEmpty()) { "A modeling command is required." }
        val stdin = input?.toByteArray(Charsets.UTF_8)
        require(stdin == null || stdin.size <= 4096) { "The local program input is too large." }
        val process = builder.start()
        val stdout = AtomicReference(Capture("", false))
        val stderr = AtomicReference(Capture("", false))
        val stdoutFailure = AtomicReference<Throwable?>(null)
        val stderrFailure = AtomicReference<Throwable?>(null)
        val stdoutThread = capture(process.inputStream, responseLimit, stdout, stdoutFailure, "nozzle-model-stdout")
        val stderrThread = capture(process.errorStream, MAX_DIAGNOSTIC_CHARS, stderr, stderrFailure, "nozzle-model-stderr")
        val stdinFailure = AtomicReference<IOException?>(null)
        val stdinThread = stdin?.let { bytes ->
            thread(isDaemon = true, name = "nozzle-model-stdin") {
                try { process.outputStream.use { it.write(bytes) } }
                catch (error: IOException) { stdinFailure.set(error) }
            }
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                stopProcessTree(process)
                process.waitFor(5, TimeUnit.SECONDS)
                throw EngineError("The modeling operation took too long and was stopped.")
            }
        } catch (interrupted: InterruptedException) {
            stopProcessTree(process)
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
            Thread.currentThread().interrupt()
            throw EngineError("The modeling operation was cancelled.")
        } finally {
            stdinThread?.join(1_000)
            joinCapture(stdoutThread, process.inputStream)
            joinCapture(stderrThread, process.errorStream)
        }
        if (stdinThread?.isAlive == true || stdinFailure.get() != null)
            throw EngineError("The local program could not receive its input.")
        stdoutFailure.get()?.let { throw EngineError("The modeling engine response couldn't be read: ${it.message ?: it.javaClass.simpleName}") }
        stderrFailure.get()?.let { throw EngineError("The modeling engine diagnostics couldn't be read: ${it.message ?: it.javaClass.simpleName}") }
        val out = stdout.get()
        val err = stderr.get()
        if (out.truncated) throw EngineError("The modeling engine returned an unexpectedly large response.")
        val json = runCatching { JSONObject(out.text) }.getOrElse {
            val detail = diagnostic(err)
            throw EngineError(if (detail == null) "This engine doesn't have the modeling tools yet." else "The modeling engine failed: $detail")
        }
        json.optString("error").takeIf { it.isNotBlank() }?.let { throw EngineError(it) }
        if (process.exitValue() != 0) {
            val detail = diagnostic(err)
            throw EngineError(if (detail == null) "The modeling operation failed." else "The modeling operation failed: $detail")
        }
        return json
    }

    private fun stopProcessTree(process: Process) {
        // A helper may spawn a native child which inherits the pipes. Stop descendants first so capture threads see EOF.
        runCatching { process.descendants().toList().asReversed().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun capture(stream: InputStream, limit: Int, result: AtomicReference<Capture>, failure: AtomicReference<Throwable?>, name: String) =
        thread(start = true, isDaemon = true, name = name) {
            try {
                val text = StringBuilder(minOf(limit, 8192))
                var total = 0L
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        total += count
                        if (text.length < limit) text.append(buffer, 0, minOf(count, limit - text.length))
                    }
                }
                result.set(Capture(text.toString(), total > limit))
            } catch (error: Throwable) { failure.set(error) }
        }

    private fun joinCapture(reader: Thread, stream: InputStream) {
        reader.join(5_000)
        if (reader.isAlive) {
            runCatching { stream.close() }
            reader.interrupt()
            reader.join(1_000)
        }
    }

    private fun diagnostic(capture: Capture): String? = capture.text.trim().takeIf { it.isNotEmpty() }
        ?.replace(Regex("\\s+"), " ")?.take(512)?.let { if (capture.truncated) "$it…" else it }

    private fun workspace(work: File, label: String): File {
        val root = work.canonicalFile.apply { mkdirs() }
        require(root.isDirectory) { "The modeling workspace is unavailable." }
        val dir = Files.createTempDirectory(root.toPath(), "$label-").toFile().canonicalFile
        check(dir.toPath().startsWith(root.toPath()) && dir.parentFile == root) { "The modeling workspace escaped its cache directory." }
        return dir
    }

    private fun deleteWorkspace(dir: File, work: File) {
        val root = work.canonicalFile
        val target = dir.canonicalFile
        check(target.toPath().startsWith(root.toPath()) && target.parentFile == root) { "Refusing to delete outside the modeling cache." }
        target.deleteRecursively()
    }
    private fun stl(mesh: Mesh, dir: File, name: String) = File(dir, name).apply { writeBytes(SliceEngine.stlBytes(mesh)) }
    private fun read(file: File) = MeshIO.readStl(file.readBytes()).also {
        if (it.triangleCount == 0) throw EngineError("The modeling operation produced an empty mesh.")
    }

    internal fun cadMesh(recipe: CadRecipe, work: File): Mesh = cadMesh(JSONObject().put("parameters", recipe.parameters()), work)

    fun stepMesh(source: File, work: File): Mesh {
        require(source.extension.equals("step", true) || source.extension.equals("stp", true)) {
            "Choose a STEP file ending in .step or .stp."
        }
        return cadMesh(JSONObject().put("step", source.absolutePath), work)
    }

    private fun cadMesh(input: JSONObject, work: File): Mesh {
        val dir = workspace(work, "cad")
        return try {
            val output = File(dir, "model.stl")
            run(input.put("op", "cad").put("out", output.absolutePath))
            read(output)
        } finally { deleteWorkspace(dir, work) }
    }

    internal fun exportStep(recipe: CadRecipe, destination: File) {
        publishCadFile(destination) { staged ->
            run(JSONObject().put("op", "cad").put("parameters", recipe.parameters()).put("outStep", staged.absolutePath))
            check(staged.length() > 0) { "The engine returned an empty STEP file." }
        }
    }

    internal fun saveCadRecipe(recipe: CadRecipe, destination: File) =
        publishCadFile(destination) { it.writeText(recipe.parameters().put("version", 1).toString(2)) }

    private fun publishCadFile(destination: File, write: (File) -> Unit) {
        val target = destination.absoluteFile.toPath()
        val staged = Files.createTempFile(target.parent, ".nia-cad-", ".tmp")
        try {
            write(staged.toFile())
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                throw EngineError("Choose a save location that supports atomic file replacement.")
            }
        } finally { Files.deleteIfExists(staged) }
    }

    fun boolean(a: Mesh, b: Mesh, mode: BooleanMode, work: File): Mesh {
        val dir = workspace(work, "boolean")
        return try {
            val out = File(dir, "result.stl")
            run(JSONObject().put("op", "boolean").put("mode", mode.wire)
                .put("modelA", stl(a, dir, "a.stl").absolutePath)
                .put("modelB", stl(b, dir, "b.stl").absolutePath).put("out", out.absolutePath))
            read(out)
        } finally { deleteWorkspace(dir, work) }
    }

    private fun bundledFont(dir: File): File {
        val font = File(dir, "NiaEmboss.ttf")
        val input = ModelingOps::class.java.classLoader.getResourceAsStream("fonts/IBMPlexSans.ttf")
            ?: throw EngineError("The bundled emboss font is missing.")
        input.use { source -> font.outputStream().use { target -> source.copyTo(target) } }
        return font
    }

    fun text(value: String, sizeMm: Double, depthMm: Double, work: File): Mesh {
        require(value.isNotBlank()) { "Enter text to emboss." }
        require(sizeMm.isFinite() && sizeMm > 0.0) { "Text size must be positive." }
        require(depthMm.isFinite() && depthMm > 0.0) { "Text depth must be positive." }
        val dir = workspace(work, "text")
        return try {
            val out = File(dir, "result.stl")
            run(JSONObject().put("op", "text").put("text", value).put("font", bundledFont(dir).absolutePath)
                .put("sizeMm", sizeMm).put("depthMm", depthMm).put("out", out.absolutePath))
            read(out)
        } finally { deleteWorkspace(dir, work) }
    }

    fun svg(source: File, depthMm: Double, toleranceMm: Double, work: File): Mesh {
        require(source.isFile) { "Choose an SVG file." }
        require(depthMm.isFinite() && depthMm > 0.0) { "SVG depth must be positive." }
        require(toleranceMm.isFinite() && toleranceMm > 0.0) { "SVG tolerance must be positive." }
        val dir = workspace(work, "svg")
        return try {
            val local = File(dir, "source.svg").also { source.copyTo(it, overwrite = true) }
            val out = File(dir, "result.stl")
            run(JSONObject().put("op", "svg").put("svg", local.absolutePath).put("depthMm", depthMm)
                .put("toleranceMm", toleranceMm).put("out", out.absolutePath))
            read(out)
        } finally { deleteWorkspace(dir, work) }
    }

    fun simplify(mesh: Mesh, targetTriangles: Int, work: File): Mesh {
        require(targetTriangles in 4 until mesh.triangleCount) { "Choose a target from 4 to ${mesh.triangleCount - 1} triangles." }
        val dir = workspace(work, "simplify")
        return try {
            val out = File(dir, "result.stl")
            run(JSONObject().put("op", "simplify").put("model", stl(mesh, dir, "model.stl").absolutePath)
                .put("targetTriangles", targetTriangles).put("out", out.absolutePath))
            read(out)
        } finally { deleteWorkspace(dir, work) }
    }

    fun repairPreview(mesh: Mesh, work: File): RepairPreview {
        val dir = workspace(work, "repair-preview")
        return try {
            val response = run(JSONObject().put("op", "repair").put("model", stl(mesh, dir, "model.stl").absolutePath).put("apply", false))
            val preview = response.getJSONObject("preview")
            RepairPreview(stats(preview.getJSONObject("before")), stats(preview.getJSONObject("after")))
        } finally { deleteWorkspace(dir, work) }
    }

    fun repair(mesh: Mesh, work: File): Pair<Mesh, RepairPreview> {
        val dir = workspace(work, "repair")
        return try {
            val out = File(dir, "result.stl")
            val response = run(JSONObject().put("op", "repair").put("model", stl(mesh, dir, "model.stl").absolutePath)
                .put("apply", true).put("out", out.absolutePath))
            val preview = response.getJSONObject("preview")
            read(out) to RepairPreview(stats(preview.getJSONObject("before")), stats(preview.getJSONObject("after")))
        } finally { deleteWorkspace(dir, work) }
    }

    fun measure(vararg points: DoubleArray): Measurement {
        require(points.size in 2..3 && points.all { it.size == 3 }) { "Measure needs two or three 3D points." }
        val response = run(JSONObject().put("op", "measure").put("points", JSONArray(points.map { JSONArray(it.toList()) })))
        val delta = response.getJSONArray("delta")
        return Measurement(response.getDouble("distance"), DoubleArray(3) { delta.getDouble(it) },
            response.optDouble("angleDegrees").takeUnless { it.isNaN() },
            response.optDouble("circumcircleDiameter").takeUnless { it.isNaN() })
    }

    fun layOnFace(mesh: Mesh, normal: DoubleArray): Mesh {
        require(normal.size == 3) { "A face normal needs x, y and z." }
        val response = run(JSONObject().put("op", "lay_on_face").put("normal", JSONArray(normal.toList())))
        val matrix = response.getJSONArray("matrix").let { values -> DoubleArray(9) { values.getDouble(it) } }
        val vertices = FloatArray(mesh.vertices.size)
        for (i in mesh.vertices.indices step 3) for (row in 0..2) {
            vertices[i + row] = (matrix[row * 3] * mesh.vertices[i] + matrix[row * 3 + 1] * mesh.vertices[i + 1] +
                matrix[row * 3 + 2] * mesh.vertices[i + 2]).toFloat()
        }
        return Mesh(vertices, mesh.triangles, mesh.paint, mesh.supportPaint, mesh.seamPaint, mesh.fuzzyPaint)
    }

    private fun stats(json: JSONObject) = MeshStats(json.getInt("triangles"), json.getInt("parts"),
        json.getInt("openEdges"), json.getDouble("volume"))

    /** Bakes an object's 3MF placement into its mesh so multi-object operations use what is visible on the plate. */
    fun transform(mesh: Mesh, pose: Transform): Mesh {
        val out = FloatArray(mesh.vertices.size)
        for (i in mesh.vertices.indices step 3) {
            val p = pose.apply(mesh.vertices[i].toDouble(), mesh.vertices[i + 1].toDouble(), mesh.vertices[i + 2].toDouble())
            out[i] = p[0].toFloat(); out[i + 1] = p[1].toFloat(); out[i + 2] = p[2].toFloat()
        }
        return Mesh(out, mesh.triangles, mesh.paint, mesh.supportPaint, mesh.seamPaint, mesh.fuzzyPaint)
    }

    fun translate(mesh: Mesh, x: Double, y: Double, z: Double): Mesh {
        val out = mesh.vertices.copyOf()
        for (i in out.indices step 3) {
            out[i] = (out[i] + x).toFloat()
            out[i + 1] = (out[i + 1] + y).toFloat()
            out[i + 2] = (out[i + 2] + z).toFloat()
        }
        return Mesh(out, mesh.triangles, mesh.paint, mesh.supportPaint, mesh.seamPaint, mesh.fuzzyPaint)
    }
}
