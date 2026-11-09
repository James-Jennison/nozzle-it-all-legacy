package com.nozzleitall.desktop.prepare

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import javax.net.ssl.HttpsURLConnection

internal class GeometryGenerationError(message: String) : Exception(message)

internal interface GeometryProvider {
    val name: String
    val model: String
    val estimatedCredits: Int
    fun submit(prompt: String, key: CharArray): String
    fun status(id: String, key: CharArray): GeometryTask
    fun download(task: GeometryTask): ByteArray
}

internal class GeometryTask(
    val id: String,
    val status: Status,
    val progress: Int,
    val consumedCredits: Int?,
    val asset: URI?,
    val retryAfterSeconds: Long?,
) {
    enum class Status { PENDING, IN_PROGRESS, SUCCEEDED, FAILED, CANCELED }
}

internal class MeshyProvider(
    private val exchange: Exchange = Exchange { uri, method, body, key, limit -> https(uri, method, body, key, limit) },
) : GeometryProvider {
    class Reply(val bytes: ByteArray, val retryAfterSeconds: Long? = null)

    fun interface Exchange {
        fun request(uri: URI, method: String, body: ByteArray?, key: CharArray?, limit: Int): Reply
    }

    override val name = "Meshy"
    override val model = "meshy-6"
    override val estimatedCredits = 20

    override fun submit(prompt: String, key: CharArray): String {
        require(prompt.isNotBlank() && prompt.codePointCount(0, prompt.length) <= 800) {
            "Enter a prompt of up to 800 characters."
        }
        val body = JSONObject().put("mode", "preview").put("ai_model", model).put("prompt", prompt)
            .put("should_remesh", true).put("topology", "triangle").put("target_polycount", 30_000)
            .put("target_formats", JSONArray(listOf("stl"))).toString().encodeToByteArray()
        val result = parse(exchange.request(endpoint(), "POST", body, key, MAX_JSON).bytes)
        return taskId(result.opt("result"))
    }

    override fun status(id: String, key: CharArray): GeometryTask {
        val expected = taskId(id)
        val response = exchange.request(endpoint(expected), "GET", null, key, MAX_JSON)
        val result = parse(response.bytes)
        if (taskId(result.opt("id")) != expected) throw GeometryGenerationError("Meshy returned a different task.")
        val status = when (result.opt("status")) {
            "PENDING" -> GeometryTask.Status.PENDING
            "IN_PROGRESS" -> GeometryTask.Status.IN_PROGRESS
            "SUCCEEDED" -> GeometryTask.Status.SUCCEEDED
            "FAILED" -> GeometryTask.Status.FAILED
            "CANCELED", "CANCELLED" -> GeometryTask.Status.CANCELED
            else -> throw GeometryGenerationError("Meshy returned an unsupported task status.")
        }
        val asset = if (status == GeometryTask.Status.SUCCEEDED)
            allowedAsset(result.optJSONObject("model_urls")?.opt("stl")) else null
        return GeometryTask(expected, status, result.optInt("progress", 0).coerceIn(0, 100),
            (result.opt("consumed_credits") as? Number)?.toDouble()?.takeIf {
                it.isFinite() && it in 0.0..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0
            }?.toInt(), asset, response.retryAfterSeconds)
    }

    override fun download(task: GeometryTask): ByteArray {
        require(task.status == GeometryTask.Status.SUCCEEDED) { "The Meshy task is still in progress." }
        val uri = allowedAsset(task.asset?.toString())
        return exchange.request(uri, "GET", null, null, MAX_ASSET).bytes
    }

    companion object {
        private const val MAX_JSON = 64 * 1024
        const val MAX_ASSET = 32 * 1024 * 1024
        private const val API = "api.meshy.ai"
        private const val ASSETS = "assets.meshy.ai"

        private val taskSegment = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")

        fun taskId(raw: Any?): String = (raw as? String)?.takeIf(taskSegment::matches)
            ?: throw GeometryGenerationError("Meshy returned no valid task ID.")

        private fun endpoint(id: String? = null): URI =
            URI("https", API, "/openapi/v2/text-to-3d" + (id?.let { "/" + it } ?: ""), null)

        private fun allowedAsset(raw: Any?): URI {
            val text = raw as? String ?: throw GeometryGenerationError("Meshy returned no STL download.")
            val uri = runCatching { URI(text) }.getOrNull()
            if (uri == null || uri.scheme != "https" || !ASSETS.equals(uri.host, true) ||
                uri.port !in setOf(-1, 443) || uri.rawUserInfo != null || uri.rawFragment != null ||
                !uri.path.endsWith(".stl", true)) throw GeometryGenerationError("Meshy returned an invalid STL download address.")
            return uri
        }

        private fun parse(bytes: ByteArray): JSONObject = try {
            JSONObject(bytes.decodeToString())
        } catch (_: Exception) {
            throw GeometryGenerationError("Meshy returned an invalid task response.")
        }

        internal fun https(uri: URI, method: String, body: ByteArray?, key: CharArray?, limit: Int,
                           open: (URI) -> HttpsURLConnection = { it.toURL().openConnection() as HttpsURLConnection }): Reply {
            val connection = try { open(uri) }
            catch (_: Exception) { throw GeometryGenerationError("The Meshy connection could not be opened.") }
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                if (key != null) {
                    check(uri.host == API)
                    connection.setRequestProperty("Authorization", "Bearer " + key.concatToString())
                }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                if (status !in 200..299) throw GeometryGenerationError(when (status) {
                    400, 403 -> "Meshy rejected the request."
                    401 -> "Meshy rejected the saved API key."
                    402 -> "Meshy reported a plan or credit limit."
                    404 -> "Meshy could not find this task or file."
                    429 -> "Meshy is rate-limiting requests."
                    else -> "Meshy could not complete the request."
                })
                if (connection.contentLengthLong > limit) throw GeometryGenerationError("The Meshy response is too large.")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                return connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline)
                            throw GeometryGenerationError("The Meshy response took too long.")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > limit) throw GeometryGenerationError("The Meshy response is too large.")
                        output.write(buffer, 0, count)
                    }
                    Reply(output.toByteArray(), connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 })
                }
            } catch (error: GeometryGenerationError) {
                throw error
            } catch (_: Exception) {
                throw GeometryGenerationError(if (method == "POST")
                    "Meshy did not confirm the request. Check the account for a task before trying again."
                else "The Meshy request did not complete.")
            } finally { connection.disconnect() }
        }
    }
}

internal interface GeometryKeychain {
    fun save(key: CharArray)
    fun load(): CharArray
}

internal class MeshySecretService(private val client: File = File("/usr/bin/secret-tool")) : GeometryKeychain {
    private val attributes = listOf("application", "nozzle-it-all", "provider", "meshy")

    override fun save(key: CharArray) {
        require(valid(key)) { "Enter a valid Meshy API key." }
        val process = start(listOf("store", "--label=Nozzle It All Meshy") + attributes, keepOutput = false)
        val bytes = ByteArray(key.size) { key[it].code.toByte() }
        try {
            process.outputStream.use { it.write(bytes) }
            finish(process)
        } catch (_: Exception) {
            throw GeometryGenerationError("The system keychain could not save the Meshy key.")
        } finally {
            bytes.fill(0)
            if (process.isAlive) process.destroyForcibly()
        }
    }

    override fun load(): CharArray {
        val process = start(listOf("lookup") + attributes, keepOutput = true)
        val closed = ByteArray(0)
        val output = AtomicReference<ByteArray?>(null)
        val reader = thread(isDaemon = true, name = "nozzle-meshy-keychain") {
            val bytes = runCatching { process.inputStream.use { it.readNBytes(4097) } }.getOrNull()
            if (bytes != null && !output.compareAndSet(null, bytes)) bytes.fill(0)
        }
        try {
            finish(process)
            reader.join(1_000)
            val bytes = output.get() ?: throw GeometryGenerationError("The system keychain returned no Meshy key.")
            if (reader.isAlive || bytes.size > 4096) throw GeometryGenerationError("The system keychain returned an invalid Meshy key.")
            var length = bytes.size
            while (length > 0 && (bytes[length - 1] == 10.toByte() || bytes[length - 1] == 13.toByte())) length--
            val key = CharArray(length) { (bytes[it].toInt() and 0xff).toChar() }
            if (!valid(key)) {
                key.fill(0.toChar())
                throw GeometryGenerationError("Save a Meshy API key in the system keychain first.")
            }
            return key
        } catch (error: GeometryGenerationError) {
            throw error
        } catch (_: Exception) {
            throw GeometryGenerationError("The system keychain could not provide the Meshy key.")
        } finally {
            output.getAndSet(closed)?.fill(0)
            if (process.isAlive) process.destroyForcibly()
            runCatching { reader.join(1_000) }
        }
    }

    private fun start(args: List<String>, keepOutput: Boolean): Process {
        if (!client.canExecute()) throw GeometryGenerationError("Meshy requires the Linux Secret Service client.")
        return try {
            ProcessBuilder(listOf(client.absolutePath) + args)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(if (keepOutput) ProcessBuilder.Redirect.PIPE else ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: Exception) { throw GeometryGenerationError("The system keychain could not be opened.") }
    }

    private fun finish(process: Process) {
        if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0)
            throw GeometryGenerationError("The system keychain did not confirm the operation.")
    }

    private fun valid(key: CharArray): Boolean = key.size in 8..512 && key.all { it in '!'..'~' }
}
