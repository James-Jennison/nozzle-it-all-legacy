package com.nozzleitall.desktop

import com.nozzleitall.desktop.materials.FilamentProfileRecord
import com.nozzleitall.desktop.materials.FilamentPresetStore
import com.nozzleitall.desktop.materials.FilamentProfilesClient
import com.nozzleitall.desktop.materials.LinuxSecretServiceStore
import com.nozzleitall.desktop.materials.UnavailableCredentialStore
import com.nozzleitall.desktop.materials.systemCredentialStore
import com.nozzleitall.desktop.prepare.FilamentLibrary
import com.nozzleitall.desktop.prepare.BundledFilamentCatalog
import com.nozzleitall.desktop.prepare.PrinterLibrary
import com.nozzleitall.desktop.prepare.ProfileCatalog
import com.nozzleitall.desktop.screens.catalogVendorGroups
import com.nozzleitall.desktop.screens.catalogMaterialTypeGroups
import org.junit.Assert.*
import org.junit.Test
import java.net.http.HttpRequest
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class FilamentProfilesCatalogTest {
    @Test fun bundledSlicerCatalogRetainsAllFourSourcesAndGroupsProducts() {
        val products = BundledFilamentCatalog.all()
        assertTrue(products.size >= 800)
        assertTrue(products.map { it.vendor.lowercase() }.toSet().size >= 90)
        assertEquals(setOf("orca", "bambu", "prusa", "cura"),
            products.flatMap { it.sources }.map { it.catalog }.toSet())
        assertTrue(products.all { it.id.startsWith("catalog-") && it.name.isNotBlank() && it.vendor.isNotBlank() })
        assertEquals(products.size, products.map { it.id }.toSet().size)
        val groups = catalogVendorGroups(products)
        assertEquals(products.map { it.vendor.trim().ifBlank { "Other" }.lowercase() }.toSet().size, groups.size)
        assertEquals(groups.size, groups.map { it.first.lowercase() }.toSet().size)
        groups.forEach { (_, vendorProducts) ->
            val materialTypes = catalogMaterialTypeGroups(vendorProducts)
            assertEquals(vendorProducts.map { it.baseMaterial.trim().ifBlank { "Other" }.lowercase() }.toSet().size, materialTypes.size)
            assertEquals(vendorProducts.map { it.id }.toSet(), materialTypes.flatMap { it.products }.map { it.id }.toSet())
        }
    }

    @Test fun bundledCatalogShipsPinnedAttributionAndCompleteSourceReconciliation() {
        val attributions = BundledFilamentCatalog.attributions()
        assertEquals(setOf("orca", "bambu", "prusa", "cura"), attributions.map { it.catalog }.toSet())
        assertEquals(setOf("AGPL-3.0", "CC0-1.0"), attributions.map { it.license }.toSet())
        attributions.forEach {
            assertTrue(it.repo.startsWith("https://"))
            assertTrue(it.commit.matches(Regex("[0-9a-f]{40}")))
            assertTrue(it.root.isNotBlank())
        }

        val summaries = BundledFilamentCatalog.reconciliation()
        assertEquals(attributions.map { it.catalog }.toSet(), summaries.map { it.catalog }.toSet())
        summaries.forEach {
            assertTrue("${it.catalog} must scan source files", it.scannedFiles > 0)
            assertEquals("${it.catalog} must account for every scanned file",
                it.scannedFiles, it.acceptedPresetFiles + it.excludedFiles)
            assertTrue("${it.catalog} must contribute products", it.representedProducts > 0)
            assertTrue(it.representedProducts <= BundledFilamentCatalog.all().size)
        }
    }

    @Test fun everyMachineCompatibleCatalogProductChangesIdentityOnly() {
        val identityKeys = setOf("name", "filament_vendor", "filament_type", "filament_settings_id", "default_filament_colour")
        val machineKeys = ProfileCatalog.all.flatMap { profile ->
            PrinterLibrary.of(profile.id)?.machines?.map { machine -> "${profile.id}@${machine.id}" }
                ?: listOf(profile.id)
        }
        val productById = BundledFilamentCatalog.all().associateBy { it.id }
        assertTrue(machineKeys.isNotEmpty())
        machineKeys.forEach { key ->
            val products = BundledFilamentCatalog.entriesFor(key)
            products.forEach { productEntry ->
                val product = productById.getValue(productEntry.id)
                val native = BundledFilamentCatalog.nativeBaseFor(key, product.id)
                assertNotNull("$key must have a native base for ${product.name}", native)
                assertTrue("$key must use ${product.vendor} or Generic tuning for ${product.name}",
                    native!!.vendor.equals(product.vendor, true) || native.vendor.equals("Generic", true))
                val base = FilamentLibrary.nativeBundledProfileJson(key, native.id)
                val derived = BundledFilamentCatalog.profileJson(key, product.id)
                assertNotNull("$key native preset ${native.id} must be readable", base)
                assertNotNull("$key derived preset ${product.id} must be readable", derived)
                assertEquals(product.name, derived!!.getString("name"))
                assertEquals(product.vendor, derived.getJSONArray("filament_vendor").getString(0))
                assertEquals(product.baseMaterial, derived.getJSONArray("filament_type").getString(0))
                assertEquals(product.id, derived.getJSONArray("filament_settings_id").getString(0))
                assertEquals(base!!.keySet() - identityKeys, derived.keySet() - identityKeys)
                (base.keySet() - identityKeys).forEach { setting ->
                    assertEquals("$key ${product.name} must retain native $setting",
                        base.opt(setting).toString(), derived.opt(setting).toString())
                }
            }
            val compatibleIds = products.map { it.id }.toSet()
            val nativeTypes = FilamentLibrary.nativeBundledForProfile(key).map { it.type.lowercase() }.toSet()
            BundledFilamentCatalog.all().filter { candidate ->
                candidate.baseMaterial.lowercase() !in nativeTypes
            }.forEach { incompatible ->
                assertFalse("$key must hide incompatible ${incompatible.name}", incompatible.id in compatibleIds)
                assertNull(BundledFilamentCatalog.profileJson(key, incompatible.id))
            }
        }
    }

    @Test fun bundledProductUsesTheSelectedMachinesNativeBaseTuning() {
        val key = "snapmaker_u1"
        val product = BundledFilamentCatalog.all().first { it.vendor.equals("Elegoo", true) && it.baseMaterial == "PLA" }
        assertTrue(BundledFilamentCatalog.entriesFor(key).any { it.id == product.id })
        val generated = BundledFilamentCatalog.profileJson(key, product.id)!!
        assertEquals(product.name, generated.getString("name"))
        assertEquals(product.vendor, generated.getJSONArray("filament_vendor").getString(0))
        assertEquals("PLA", generated.getJSONArray("filament_type").getString(0))
        assertTrue(generated.has("nozzle_temperature"))
    }
    @Test fun parsesCandidate30ResponseShapesAndKeepsBrandIdentity() {
        val reply = FilamentProfilesClient.parse("""{"data":[
          {"filament_id":"42","brand_name":"Polymaker","material":"PolyLite PLA Pro","material_type":"PLA+","color_hex":"be38f3"},
          {"id":"ambiguous","brand":"Example","material":"PLA PETG"},
          {"id":"missing-name"}
        ]}""")
        assertTrue(reply.ok)
        assertEquals(1, reply.records.size)
        val row = reply.records.single()
        assertEquals("Polymaker PolyLite PLA Pro PLA+", row.productName)
        assertEquals("PLA", row.baseMaterial)
        assertEquals("#BE38F3", row.colorHex)
    }

    @Test fun recognizesMaterialFamiliesWithoutGuessingAmbiguousProducts() {
        assertEquals("PA", FilamentProfileRecord(material = "PA12-CF").baseMaterial)
        assertEquals("PC", FilamentProfileRecord(material = "Polycarbonate").baseMaterial)
        assertEquals("", FilamentProfileRecord(material = "PLA/PETG").baseMaterial)
        assertFalse(FilamentProfileRecord(brand = "Example", material = "PLA/PETG").usable)
    }

    @Test fun malformedUnexpectedAndAmbiguousCatalogResponsesFailClosed() {
        listOf("not-json", "{}", "{\"data\":{}}", "{\"results\":[{\"brand\":\"Example\",\"material\":\"PLA/PETG\"}]}").forEach { body ->
            val reply = FilamentProfilesClient.parse(body)
            if (body.contains("PLA/PETG")) {
                assertTrue(reply.ok)
                assertTrue(reply.records.isEmpty())
            } else {
                assertFalse(reply.ok)
                assertNotNull(reply.error)
            }
        }
    }

    @Test fun duplicateCatalogProductsAreCollapsedWithoutReordering() {
        val reply = FilamentProfilesClient.parse("""{"filaments":[
          {"id":"same","brand":"Polymaker","material":"PLA"},
          {"id":"same","brand":"Polymaker","material":"PLA"},
          {"short_code":"second","brand":"Overture","material":"PETG"}
        ]}""")
        assertTrue(reply.ok)
        assertEquals(listOf("Polymaker", "Overture"), reply.records.map { it.brand })
    }

    @Test fun conflictingDuplicateIdentitiesFailClosedAcrossEveryIdentityKey() {
        val fixtures = listOf(
            """[{"id":"same","brand":"A","material":"PLA"},{"id":"same","brand":"B","material":"PLA"}]""",
            """[{"short_code":"same","brand":"A","material":"PLA"},{"short_code":"same","brand":"B","material":"PLA"}]""",
            """[{"brand":"A","material":"PLA"},{"brand":"A","material":"PLA","website":"different"}]""",
            """[{"id":"one","short_code":"left","brand":"A","material":"PLA"},{"id":"one","short_code":"right","brand":"A","material":"PLA"}]""",
        )
        fixtures.forEach { rows ->
            val reply = FilamentProfilesClient.parse("""{"filaments":$rows}""")
            assertFalse(reply.ok)
            assertEquals("3D Filament Profiles returned an unreadable response.", reply.error)
        }
        val distinctStableIds = FilamentProfilesClient.parse("""{"filaments":[
          {"id":"one","brand":"A","material":"PLA"},{"id":"two","brand":"A","material":"PLA"}
        ]}""")
        assertTrue(distinctStableIds.ok)
        assertEquals(listOf("one", "two"), distinctStableIds.records.map { it.id })
    }

    @Test fun searchUrlIsBoundedEncodedAndNeverContainsCredential() {
        val url = FilamentProfilesClient.searchUrl("Polymaker PLA+ / Silk", 999).toString()
        assertEquals("https://3dfilamentprofiles.com/api/v1/filaments?q=Polymaker%20PLA%2B%20%2F%20Silk&limit=50", url)
        assertFalse(url.contains("api", ignoreCase = true) && url.contains("secret-value"))
    }

    @Test fun catalogTransportSeparatesAuthenticationRateLimitSizeAndNetworkFailuresWithoutEchoingCredential() {
        val sentinel = "not-a-real-key-SENTINEL"
        lateinit var request: HttpRequest
        fun reply(status: Int, body: ByteArray = ByteArray(0)) = FilamentProfilesClient { sent ->
            request = sent
            status to body
        }.search("Polymaker", sentinel)

        assertTrue(reply(401).error!!.contains("rejected"))
        assertEquals(sentinel, request.headers().firstValue("X-API-Key").orElse(null))
        assertFalse(request.uri().toString().contains(sentinel))
        assertTrue(reply(429).error!!.contains("rate limiting"))
        assertTrue(reply(500).error!!.contains("HTTP 500"))
        assertTrue(reply(200, ByteArray(FilamentProfilesClient.MAX_RESPONSE + 1)).error!!.contains("too large"))
        assertTrue(FilamentProfilesClient { throw IllegalStateException(sentinel) }.search("Polymaker", sentinel).error!!.contains("could not be reached"))
        listOf(reply(401), reply(429), reply(500)).forEach { assertFalse(it.error!!.contains(sentinel)) }
    }

    @Test fun linuxSecretServiceRoundTripsThroughStdinOnlyAndNeverLeaksTheSecret() {
        val calls = mutableListOf<Pair<List<String>, ByteArray?>>()
        var stored = ByteArray(0)
        val sentinel = "not-a-real-key-SENTINEL"
        val store = LinuxSecretServiceStore("secret-tool") { args, input ->
            calls += args to input?.copyOf()
            when (args.getOrNull(1)) {
                "--help" -> 2 to ByteArray(0) // libsecret's real client reports usage with a non-zero status.
                "store" -> { stored = input!!.copyOf(); 0 to ByteArray(0) }
                "lookup" -> 0 to stored.copyOf()
                else -> 0 to ByteArray(0)
            }
        }
        assertTrue(store.available)
        assertTrue(store.save(sentinel))
        assertEquals(sentinel, store.load())
        val save = calls.single { it.first.getOrNull(1) == "store" }
        assertFalse(calls.flatMap { it.first }.any { it.contains(sentinel) })
        assertEquals("$sentinel\n", save.second!!.decodeToString())
        assertFalse(calls.filter { it.first.getOrNull(1) != "store" }.any { it.second?.decodeToString()?.contains(sentinel) == true })
    }

    @Test fun secretServiceFailuresAndUnsupportedPlatformsFailClosed() {
        val failing = LinuxSecretServiceStore("secret-tool") { args, _ ->
            if (args.getOrNull(1) == "--help") 2 to ByteArray(0) else 7 to "must-not-be-used".toByteArray()
        }
        assertFalse(failing.save("not-a-real-key"))
        assertNull(failing.load())
        assertFalse(failing.delete())
        assertSame(UnavailableCredentialStore, systemCredentialStore("Windows 11"))
        assertFalse(UnavailableCredentialStore.available)
        assertFalse(UnavailableCredentialStore.save("not-a-real-key"))
    }

    @Test fun missingSecretToolIsUnavailableWithoutThrowing() {
        val missing = LinuxSecretServiceStore("/definitely/not/a/secret-tool")
        assertFalse(missing.available)
        assertFalse(missing.save("not-a-real-key"))
        assertNull(missing.load())
    }

    @Test fun timedOutSecretServiceHelperIsKilledAndReturnsNoOutput() {
        val started = System.nanoTime()
        val result = LinuxSecretServiceStore.runSecretTool(
            listOf("/bin/sh", "-c", "sleep 30"),
            "not-a-real-key-SENTINEL\n".toByteArray(),
            timeoutMillis = 100,
        )
        assertEquals(-1, result.first)
        assertEquals(0, result.second.size)
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5)
    }

    @Test fun importedProductDerivesFromBundledMaterialAndSurvivesReload() {
        val dir = Files.createTempDirectory("nia-filament-presets").toFile()
        val file = dir.resolve("filament-presets.json")
        val store = FilamentPresetStore(file)
        FilamentLibrary.installCustomProfiles(store)
        try {
            val imported = store.import("snapmaker_u1@snapmaker_u1_0_4_nozzle", FilamentProfileRecord(
                id = "polyterra-pla-purple", brand = "Polymaker", material = "PolyTerra PLA", materialType = "Matte", rgb = "#663399"))
            assertEquals("Polymaker PolyTerra PLA Matte", imported.entry.displayName)
            assertEquals("PLA", imported.entry.type)
            assertEquals("Polymaker", imported.profile.getJSONArray("filament_vendor").getString(0))
            assertEquals("PLA", imported.profile.getJSONArray("filament_type").getString(0))
            assertEquals("#663399", imported.profile.getJSONArray("default_filament_colour").getString(0))
            assertTrue(imported.profile.has("nozzle_temperature"))

            val again = store.import("snapmaker_u1@snapmaker_u1_0_4_nozzle", FilamentProfileRecord(
                id = "polyterra-pla-purple", brand = "Polymaker", material = "PolyTerra PLA", materialType = "Matte", rgb = "#663399"))
            assertEquals(imported.entry.id, again.entry.id)
            assertEquals(1, store.entries("snapmaker_u1@snapmaker_u1_0_4_nozzle").size)
            assertTrue(store.entries("elegoo_centauri_carbon_cosmos_afc").isEmpty())

            val reloaded = FilamentPresetStore(file)
            val found = reloaded.entries("snapmaker_u1@snapmaker_u1_0_4_nozzle").single()
            assertEquals(imported.entry, found)
            assertNotNull(reloaded.profileJson("snapmaker_u1@snapmaker_u1_0_4_nozzle", imported.entry.id))
        } finally {
            FilamentLibrary.installCustomProfiles(null)
        }
    }

    @Test fun incompatibleCatalogMaterialCreatesNoPreset() {
        val file = Files.createTempDirectory("nia-incompatible-filament").resolve("filament-presets.json").toFile()
        val store = FilamentPresetStore(file)
        val before = file.takeIf { it.isFile }?.readBytes()
        val failure = assertThrows(IllegalArgumentException::class.java) {
            store.import("snapmaker_u1@snapmaker_u1_0_4_nozzle", FilamentProfileRecord(
                id = "unsupported", brand = "Example", material = "PEEK"))
        }
        assertTrue(failure.message!!.contains("no compatible PEEK preset"))
        assertTrue(store.entries("snapmaker_u1@snapmaker_u1_0_4_nozzle").isEmpty())
        assertArrayEquals(before, file.takeIf { it.isFile }?.readBytes())
    }
}
