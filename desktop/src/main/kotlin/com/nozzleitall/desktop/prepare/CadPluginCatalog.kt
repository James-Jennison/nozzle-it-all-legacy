package com.nozzleitall.desktop.prepare

import org.json.JSONObject

/** Catalog shipped with the Desktop runtime. Each listing pins the exact manifest reviewed for install. */
internal object CadPluginCatalog {
    data class Listing(val plugin: CadPlugin, val summary: String, val packageBytes: ByteArray, val digest: String)

    fun load(read: (String, Int) -> ByteArray = ::resource): List<Listing> {
        val index = read("catalog.json", 16384)
        require(index.size <= 16384) { "The plugin catalog is too large." }
        val document = JSONObject(index.toString(Charsets.UTF_8))
        require(document.opt("version") == 1 && document.keySet() == setOf("version", "listings")) {
            "Unsupported plugin catalog."
        }
        val rows = document.getJSONArray("listings")
        require(rows.length() in 1..32) { "Invalid plugin catalog size." }
        val seen = mutableSetOf<String>()
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            require(row.keySet() == setOf("id", "release", "summary", "sha256")) { "Invalid catalog listing." }
            val id = row.getString("id")
            val summary = row.getString("summary")
            val digest = row.getString("sha256")
            val release = row.opt("release") as? Int
            require(release != null && release > 0 && CadPlugin.idPattern.matches(id) && seen.add(id) &&
                summary.length in 1..160 && summary.none(Char::isISOControl) && digest.matches(Regex("[0-9a-f]{64}"))) {
                "Invalid or duplicate catalog listing."
            }
            val bytes = read("$id.niaplugin", 8192)
            val actual = CadPlugin.sha256(bytes)
            require(bytes.size <= 8192 && actual == digest) { "A marketplace plugin failed verification." }
            val plugin = CadPlugin.decode(bytes)
            require(plugin.id == id && release == plugin.release) { "Marketplace plugin identity mismatch." }
            Listing(plugin, summary, bytes, digest)
        }
    }

    private fun resource(name: String, limit: Int): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/plugins/$name")) { "A bundled marketplace resource is missing." }
            .use { it.readNBytes(limit + 1) }
}
