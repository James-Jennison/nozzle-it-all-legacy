package com.nozzleitall.desktop.prepare

import org.json.JSONArray
import org.json.JSONObject

/**
 * Product identities bundled from OrcaSlicer, Bambu Studio, PrusaSlicer and
 * Cura.  These records are not assumed to be cross-slicer tuning.  A selected
 * product inherits a compatible base-material preset from the active machine;
 * the catalog contributes identity and an optional colour only.
 */
object BundledFilamentCatalog {
    data class SourceRef(val catalog: String, val preset: String, val path: String)
    data class Attribution(val catalog: String, val repo: String, val commit: String, val license: String, val root: String)
    data class SourceSummary(val catalog: String, val scannedFiles: Int, val acceptedPresetFiles: Int,
                             val representedProducts: Int, val excludedFiles: Int, val warnings: Int)
    data class Product(
        val id: String,
        val name: String,
        val vendor: String,
        val material: String,
        val baseMaterial: String,
        val colors: List<String>,
        val sources: List<SourceRef>,
    ) {
        fun entry(): FilamentLibrary.Entry = FilamentLibrary.Entry(id, name, vendor, baseMaterial, name)
    }

    private val root: JSONObject by lazy {
        val text = BundledFilamentCatalog::class.java
            .getResourceAsStream("/filament-catalog/index.json")
            ?.readBytes()?.decodeToString() ?: "{}"
        JSONObject(text)
    }
    private val products: List<Product> by lazy {
        val rows = root.optJSONArray("entries") ?: return@lazy emptyList()
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Product(
                row.getString("id"), row.getString("name"), row.getString("vendor"),
                row.getString("material"), row.getString("baseMaterial"), strings(row.optJSONArray("colors")),
                objects(row.optJSONArray("sources")).map {
                    SourceRef(it.getString("catalog"), it.getString("preset"), it.getString("path"))
                },
            )
        }
    }
    private val byId by lazy { products.associateBy { it.id } }
    private val compatibleCache = HashMap<String, List<FilamentLibrary.Entry>>()

    fun all(): List<Product> = products

    fun attributions(): List<Attribution> = root.optJSONObject("catalogs")?.let { catalogs ->
        catalogs.keySet().sorted().map { id -> catalogs.getJSONObject(id).let { row ->
            Attribution(id, row.getString("repo"), row.getString("commit"), row.getString("license"), row.getString("root"))
        } }
    }.orEmpty()

    fun reconciliation(): List<SourceSummary> = root.optJSONObject("reconciliation")?.let { reports ->
        reports.keySet().sorted().map { id -> reports.getJSONObject(id).let { row ->
            SourceSummary(id, row.getInt("scannedFiles"), row.getInt("acceptedPresetFiles"),
                row.getInt("representedProducts"), row.optJSONArray("excluded")?.length() ?: 0,
                row.optJSONArray("warnings")?.length() ?: 0)
        } }
    }.orEmpty()

    /** Products for which the selected machine has a safe base-material preset. */
    fun entriesFor(profileId: String): List<FilamentLibrary.Entry> = synchronized(compatibleCache) {
        compatibleCache.getOrPut(profileId) {
            products.asSequence()
                .filter { nativeBaseFor(profileId, it.id) != null }
                .map(Product::entry)
                .toList()
        }
    }

    /** Exact native preset that supplies tuning for this product, never another aggregate product. */
    fun nativeBaseFor(profileId: String, filamentId: String): FilamentLibrary.Entry? {
        val product = byId[filamentId] ?: return null
        return FilamentLibrary.bestNativeBundledFor(profileId, product.vendor, product.baseMaterial)
    }

    fun profileJson(profileId: String, filamentId: String): JSONObject? {
        val product = byId[filamentId] ?: return null
        val base = nativeBaseFor(profileId, filamentId) ?: return null
        val profile = FilamentLibrary.nativeBundledProfileJson(profileId, base.id) ?: return null
        return JSONObject(profile.toString())
            .put("name", product.name)
            .put("filament_vendor", JSONArray().put(product.vendor))
            .put("filament_type", JSONArray().put(product.baseMaterial))
            .put("filament_settings_id", JSONArray().put(product.id))
            .also { out -> product.colors.firstOrNull()?.let { out.put("default_filament_colour", JSONArray().put(it)) } }
    }

    private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList()
        else (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }

    private fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList()
        else (0 until array.length()).mapNotNull(array::optJSONObject)
}
