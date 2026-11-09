package com.nozzleitall.desktop.materials

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

data class FilamentProfileRecord(
    val id: String = "",
    val shortCode: String = "",
    val brand: String = "",
    val material: String = "",
    val materialType: String = "",
    val color: String = "",
    val rgb: String = "",
    val website: String = "",
) {
    val productName: String
        get() = listOfNotNull(brand, material, materialType.takeUnless { material.contains(it, ignoreCase = true) })
            .filter { it.isNotBlank() }.joinToString(" ")

    val baseMaterial: String
        get() {
            val declared = materialFamilies(material)
            if (declared.size == 1) return declared.single()
            if (declared.size > 1) return ""
            val subtype = materialFamilies(materialType)
            if (subtype.size == 1) return subtype.single()
            if (subtype.size > 1) return ""
            return material.trim()
        }

    val usable get() = productName.isNotBlank() && baseMaterial.isNotBlank()

    val colorHex: String?
        get() = rgb.trim().removePrefix("#").takeIf { Regex("[0-9A-Fa-f]{6}").matches(it) }?.uppercase()?.let { "#$it" }

    companion object {
        private val known = mapOf(
            "pla" to "PLA", "pla+" to "PLA", "petg" to "PETG", "tpu" to "TPU", "asa" to "ASA",
            "abs" to "ABS", "pc" to "PC", "polycarbonate" to "PC", "pa" to "PA", "nylon" to "PA",
            "pva" to "PVA", "hips" to "HIPS",
        )

        internal fun materialFamilies(value: String): Set<String> = Regex("[A-Za-z0-9+]+")
            .findAll(value.lowercase(Locale.ROOT)).mapNotNull { match ->
                val token = match.value
                known[token] ?: token.takeIf { it.startsWith("pa") && it.drop(2).isNotEmpty() && it.drop(2).all(Char::isDigit) }?.let { "PA" }
            }.toSet()
    }
}

data class FilamentProfilesReply(val records: List<FilamentProfileRecord> = emptyList(), val error: String? = null) {
    val ok get() = error == null
}

/** Bounded, user-initiated access to 3dfilamentprofiles.com. The API key is sent only in X-API-Key. */
class FilamentProfilesClient(
    private val send: (HttpRequest) -> Pair<Int, ByteArray> = defaultSender(),
) {
    companion object {
        const val ENDPOINT = "https://3dfilamentprofiles.com/api/v1/filaments"
        const val MAX_RESPONSE = 2 * 1024 * 1024

        private fun defaultSender(): (HttpRequest) -> Pair<Int, ByteArray> {
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()
            return { request ->
                val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
                response.statusCode() to response.body().use { it.readNBytes(MAX_RESPONSE + 1) }
            }
        }

        fun searchUrl(query: String, limit: Int = 25): URI {
            val bounded = limit.coerceIn(1, 50)
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20")
            return URI.create("$ENDPOINT?q=$encoded&limit=$bounded")
        }

        fun parse(body: String): FilamentProfilesReply = runCatching {
            val parser = JSONTokener(body)
            val root = parser.nextValue()
            require(parser.nextClean() == '\u0000') { "trailing data" }
            val rows = when (root) {
                is JSONArray -> root
                is JSONObject -> listOf("filaments", "data", "results").firstNotNullOfOrNull { root.optJSONArray(it) }
                else -> null
            } ?: error("missing filament result array")
            val records = (0 until rows.length().coerceAtMost(500)).mapNotNull { index ->
                val item = rows.optJSONObject(index) ?: return@mapNotNull null
                fun field(vararg names: String): String = names.firstNotNullOfOrNull { name ->
                    (item.opt(name) as? String)?.trim()?.takeIf(String::isNotEmpty)
                }.orEmpty()
                FilamentProfileRecord(
                    field("id", "filament_id"), field("short_code", "shortCode"),
                    field("brand_name", "brand", "manufacturer"), field("material", "material_name"),
                    field("material_type", "materialType", "type"), field("color", "colour", "color_name"),
                    field("rgb", "hex", "color_hex"), field("website", "url", "record_url"),
                ).takeIf(FilamentProfileRecord::usable)
            }
            val unique = LinkedHashMap<String, FilamentProfileRecord>()
            records.forEach { record ->
                // Stable provider identity wins; normalized product identity is only the fallback for records
                // without an id or short code. Equal duplicates collapse first-wins; conflicts fail closed.
                val key = record.id.takeIf(String::isNotBlank)?.let { "id:${it.lowercase(Locale.ROOT)}" }
                    ?: record.shortCode.takeIf(String::isNotBlank)?.let { "short:${it.lowercase(Locale.ROOT)}" }
                    ?: "product:" + listOf(record.brand, record.material, record.materialType, record.colorHex.orEmpty())
                        .joinToString("\u0000") { it.lowercase(Locale.ROOT) }
                val existing = unique[key]
                require(existing == null || existing == record) { "conflicting duplicate filament records" }
                unique.putIfAbsent(key, record)
            }
            FilamentProfilesReply(unique.values.toList())
        }.getOrElse { FilamentProfilesReply(error = "3D Filament Profiles returned an unreadable response.") }
    }

    fun search(query: String, apiKey: String, limit: Int = 25): FilamentProfilesReply {
        if (query.isBlank()) return FilamentProfilesReply(error = "Enter a filament brand or product to search.")
        if (apiKey.isBlank()) return FilamentProfilesReply(error = "A 3D Filament Profiles API key is required.")
        return runCatching {
            val request = HttpRequest.newBuilder(searchUrl(query.trim(), limit)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").header("X-API-Key", apiKey).GET().build()
            val (status, bytes) = send(request)
                if (bytes.size > MAX_RESPONSE)
                    FilamentProfilesReply(error = "3D Filament Profiles returned a response that was too large.")
                else if (status !in 200..299)
                    FilamentProfilesReply(error = when (status) {
                        401, 403 -> "3D Filament Profiles rejected the saved API key."
                        429 -> "3D Filament Profiles is rate limiting requests. Try again later."
                        else -> "3D Filament Profiles returned HTTP $status."
                    })
                else parse(bytes.toString(StandardCharsets.UTF_8))
        }.getOrElse { FilamentProfilesReply(error = "3D Filament Profiles could not be reached.") }
    }
}

interface CredentialStore {
    val available: Boolean
    fun load(): String?
    fun save(secret: String): Boolean
    fun delete(): Boolean
}

/** Linux Secret Service through libsecret's native client. Secrets are never command-line arguments or log output. */
class LinuxSecretServiceStore(
    private val command: String = "secret-tool",
    private val runner: (List<String>, ByteArray?) -> Pair<Int, ByteArray> = ::runSecretTool,
) : CredentialStore {
    private val attributes = listOf("application", "nozzle-it-all", "service", "3dfilamentprofiles", "account", "api-key")
    // secret-tool has no stable version command: libsecret 0.20 exits non-zero for both --version and --help even
    // though the native client is installed and usable. Availability means that the executable can be started; the
    // actual Secret Service operation still fails closed in load/save/delete if the session keyring is unavailable.
    override val available: Boolean by lazy { runner(listOf(command, "--help"), null).first >= 0 }

    override fun load(): String? {
        if (!available) return null
        val (code, output) = runner(listOf(command, "lookup") + attributes, null)
        return output.fillAndDecode(code == 0)
    }

    override fun save(secret: String): Boolean {
        if (!available || secret.isBlank()) return false
        val bytes = (secret + "\n").toByteArray()
        return try {
            runner(listOf(command, "store", "--label=Nozzle It All - 3D Filament Profiles API key") + attributes, bytes).first == 0
        } finally { bytes.fill(0) }
    }

    override fun delete(): Boolean = available && runner(listOf(command, "clear") + attributes, null).first == 0

    private fun ByteArray.fillAndDecode(success: Boolean): String? = try {
        if (success) toString(StandardCharsets.UTF_8).trimEnd('\r', '\n').takeIf(String::isNotBlank) else null
    } finally { fill(0) }

    companion object {
        internal fun runSecretTool(args: List<String>, input: ByteArray?, timeoutMillis: Long = 10_000): Pair<Int, ByteArray> {
            val process = try {
                ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            } catch (_: java.io.IOException) {
                return -1 to ByteArray(0)
            }
            if (input == null) process.outputStream.close() else process.outputStream.use { it.write(input) }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                process.inputStream.close()
                return -1 to ByteArray(0)
            }
            return process.exitValue() to process.inputStream.use { it.readNBytes(64 * 1024) }
        }
    }
}

object UnavailableCredentialStore : CredentialStore {
    override val available = false
    override fun load(): String? = null
    override fun save(secret: String) = false
    override fun delete() = false
}

fun systemCredentialStore(os: String = System.getProperty("os.name")): CredentialStore =
    if (os.startsWith("Linux", ignoreCase = true)) LinuxSecretServiceStore() else UnavailableCredentialStore

class FilamentProfilesCatalog(
    val credentials: CredentialStore = systemCredentialStore(),
    private val client: FilamentProfilesClient = FilamentProfilesClient(),
) {
    fun hasCredential() = credentials.load() != null
    fun saveCredential(secret: String) = credentials.save(secret)
    fun clearCredential() = credentials.delete()
    fun search(query: String): FilamentProfilesReply {
        val secret = credentials.load() ?: return FilamentProfilesReply(error = "Save a 3D Filament Profiles API key in Settings first.")
        return client.search(query, secret)
    }
}
