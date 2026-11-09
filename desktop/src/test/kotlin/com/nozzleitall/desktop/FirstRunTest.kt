package com.nozzleitall.desktop

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import com.nozzleitall.desktop.screens.setupWizardDraft
import com.nozzleitall.desktop.screens.setupWizardSteps
import com.nozzleitall.desktop.screens.setupWizardReachableSteps
import com.nozzleitall.desktop.screens.SetupStep
import com.nozzleitall.desktop.screens.resizedFilamentProfiles
import com.nozzleitall.desktop.screens.setupCompatibleFilaments
import com.nozzleitall.desktop.screens.setupFilamentVendorGroups
import com.nozzleitall.desktop.screens.setupFilamentMaterialTypeGroups
import com.nozzleitall.desktop.screens.setupFilamentVendorKey
import com.nozzleitall.desktop.screens.setupMachineVendorGroups
import com.nozzleitall.desktop.screens.setupResolvedFilamentProfiles
import com.nozzleitall.desktop.screens.setupInstalledFilamentIds
import com.nozzleitall.desktop.screens.setupFilamentProfileKey
import com.nozzleitall.desktop.screens.FilamentCatalogRequest
import com.nozzleitall.desktop.screens.catalogRequestIsCurrent
import com.nozzleitall.desktop.screens.searchFilamentCatalog
import com.nozzleitall.desktop.screens.compatibilityProfileForDiscoveredPrinter
import com.nozzleitall.desktop.prepare.ProfileCatalog
import com.nozzleitall.desktop.prepare.PrinterProfileInfo
import com.nozzleitall.desktop.prepare.PrinterLibrary
import com.nozzleitall.desktop.prepare.PrepItem
import com.nozzleitall.desktop.prepare.MaterialObjectUsage
import com.nozzleitall.desktop.prepare.materialObjectAssignments
import com.nozzleitall.desktop.materials.FilamentProfileRecord
import com.nozzleitall.desktop.materials.FilamentProfilesReply
import com.nozzleitall.desktop.materials.FilamentPresetStore
import com.nozzleitall.printer.ConnectionRoute
import com.nozzleitall.printer.Material
import com.nozzleitall.printer.PrinterConfig
import com.nozzleitall.printer.PrinterFamily
import com.nozzleitall.printer.PrinterIdentity
import com.nozzleitall.printer.PrinterState
import com.nozzleitall.printer.PrinterStatus
import com.nozzleitall.printer.Toolhead
import com.nozzleitall.project.Mesh
import kotlinx.coroutines.runBlocking

class FirstRunTest {
    private fun state(root: File): Pair<AppState, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val paths = AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure()
        return AppState(paths, scope) to scope
    }

    private fun close(state: AppState, scope: CoroutineScope) {
        state.environment.close()
        state.connector.stop()
        state.fleet.shutdown()
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `onboarding keeps slicing setup before optional physical connection`() {
        assertEquals(
            listOf("Welcome", "Machine", "Filaments", "Process", "Connect", "Review"),
            setupWizardSteps(),
        )
        assertEquals(
            listOf("Welcome", "Machine", "Filaments"),
            setupWizardReachableSteps(SetupStep.FILAMENT),
        )
    }

    @Test
    fun `machine browser groups printer presets by canonical vendor`() {
        fun profile(id: String, vendor: String, model: String) = PrinterProfileInfo(
            id, vendor, model, "$vendor $model", 250f, 250f, 250f, 1, listOf("gcode"), "export-only",
        )
        val profiles = listOf(
            profile("snap-u1", "Snapmaker", "U1"),
            profile("snap-a350", " snapmaker ", "A350"),
            profile("prusa-mk4s", "Prusa Research", "MK4S"),
            profile("other", "", "Custom machine"),
        )

        val groups = setupMachineVendorGroups(profiles)

        assertEquals(listOf("snapmaker", "prusa research", "other"), groups.map { it.key })
        assertEquals(listOf("Snapmaker", "Prusa Research", "Other"), groups.map { it.vendor })
        assertEquals(listOf("A350", "U1"), groups.first().machines.map { it.model })
        assertEquals(profiles.map { it.id }.toSet(), groups.flatMap { it.machines }.map { it.id }.toSet())
    }

    @Test
    fun `fresh profile keeps onboarding visible while optional connection setup opens`() {
        val (state, scope) = state(Files.createTempDirectory("nia-first-run-").toFile())
        try {
            assertTrue(state.firstRunVisible)
            assertNull(state.addPrinterRequest)

            state.startPrinterSetup(autoScan = true, fromFirstRun = true)
            assertTrue(state.firstRunVisible)
            assertEquals(AddPrinterRequest(autoScan = true, returnToFirstRunOnCancel = true, compatibilityProfileId = null), state.addPrinterRequest)

            state.finishPrinterSetup(null)
            assertTrue(state.firstRunVisible)
            assertNull(state.addPrinterRequest)
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `dismiss is temporary and setup can be reopened`() {
        val root = Files.createTempDirectory("nia-first-run-dismiss-").toFile()
        val (state, scope) = state(root)
        try {
            state.dismissFirstRun()
            assertFalse(state.firstRunVisible)
            assertFalse(state.fleet.settings.value.firstRunComplete)
            state.reopenFirstRun()
            assertTrue(state.firstRunVisible)
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `finished slicing setup is persisted independently of a physical printer`() {
        val root = Files.createTempDirectory("nia-slicing-setup-").toFile()
        val (first, firstScope) = state(root)
        val setup = SlicingSetup("generic_klipper", "Workshop printer", "0.4", null, 4,
            mapOf(1 to "filament-a", 2 to "filament-b"), customOffline = true,
            bedWidthMm = 270f, bedDepthMm = 270f, heightMm = 270f, gcodeFlavor = "marlin2")
        try {
            first.finishFirstRun(setup)
            assertFalse(first.firstRunVisible)
            assertTrue(first.fleet.order.value.isEmpty())
            assertEquals(setup, DesktopSettings.load(first.paths.settings).slicingSetup)
            assertEquals("generic_klipper", first.prepare.profileId)
            assertEquals("0.4", first.prepare.nozzle)
            assertEquals(270f to 270f, first.prepare.bed)
            assertEquals("270.0", first.prepare.machineJson().getString("printable_height"))
            assertEquals(4, first.prepare.machineJson().getJSONArray("nozzle_diameter").length())
            assertEquals("marlin2", first.prepare.machineJson().getString("gcode_flavor"))
        } finally {
            close(first, firstScope)
        }
        val (restarted, restartedScope) = state(root)
        try {
            assertEquals(setup, restarted.fleet.settings.value.slicingSetup)
            assertEquals("generic_klipper", restarted.prepare.profileId)
            assertEquals("0.4", restarted.prepare.nozzle)
            assertEquals("marlin2", restarted.fleet.settings.value.slicingSetup?.gcodeFlavor)
            assertTrue(restarted.fleet.order.value.isEmpty())
        } finally {
            close(restarted, restartedScope)
        }
    }

    @Test
    fun `finishing setup invalidates observed slice readiness when profile values already match`() {
        val (state, scope) = state(Files.createTempDirectory("nia-first-run-readiness-").toFile())
        val observer = SnapshotStateObserver { callback -> callback() }
        var readinessInvalidations = 0
        try {
            // The bundled default profile can already be visible before onboarding. Installing that
            // same profile still has to invalidate Compose's readiness/advice reads immediately.
            val setup = SlicingSetup(
                profileId = state.prepare.profileId,
                name = "Installed default",
                nozzle = state.prepare.nozzle,
                processId = state.prepare.processId,
            )
            observer.start()
            observer.observeReads(Unit, { readinessInvalidations++ }) {
                state.prepare.hasSlicingSetup
            }

            state.finishFirstRun(setup)
            Snapshot.sendApplyNotifications()

            assertEquals(1, readinessInvalidations)
            assertTrue(state.prepare.hasSlicingSetup)
            assertFalse(state.firstRunVisible)
        } finally {
            observer.stop()
            close(state, scope)
        }
    }

    @Test
    fun `custom output dialect is validated and legacy custom setup defaults to klipper`() {
        val invalid = SlicingSetup(
            profileId = "generic_klipper",
            name = "Invalid custom machine",
            nozzle = "0.4",
            customOffline = true,
            bedWidthMm = 250f,
            bedDepthMm = 250f,
            heightMm = 250f,
            gcodeFlavor = "unknown-firmware",
        )
        assertFalse(invalid.isValid())

        val legacy = org.json.JSONObject()
            .put("profileId", "generic_klipper")
            .put("name", "Legacy custom machine")
            .put("nozzle", "0.4")
            .put("customOffline", true)
            .put("bedWidthMm", 250)
            .put("bedDepthMm", 250)
            .put("heightMm", 250)

        assertEquals("klipper", SlicingSetup.fromJson(legacy)?.gcodeFlavor)
        assertTrue(SlicingSetup.fromJson(legacy)?.isValid() == true)
    }

    @Test
    fun `base object and painted regions remain independently assignable to material slots`() {
        val (state, scope) = state(Files.createTempDirectory("nia-material-mapping-").toFile())
        try {
            state.prepare.applySlicingSetup(
                SlicingSetup(
                    profileId = "generic_klipper",
                    name = "Three toolheads",
                    nozzle = "0.4",
                    toolheads = 3,
                    customOffline = true,
                    bedWidthMm = 250f,
                    bedDepthMm = 250f,
                    heightMm = 250f,
                ),
            )
            val item = PrepItem(
                1,
                "Badge",
                Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2)),
                50f,
                50f,
                slot = 1,
                paintSlots = listOf(1),
            )
            state.prepare.items += item

            state.prepare.setObjectSlot(item, 2)
            state.prepare.setPaintSlot(item, 1, 3)

            assertEquals(2, item.slot)
            assertEquals(3, item.slotFor(1))
            val usage = listOf(MaterialObjectUsage(item.name, item.slot, setOf(item.slotFor(1))))
            assertEquals(listOf("Badge"), materialObjectAssignments(2, usage))
            assertEquals(listOf("Badge"), materialObjectAssignments(3, usage))
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `installed filament library persists separately from initial toolhead assignments`() {
        val root = Files.createTempDirectory("nia-installed-filaments-").toFile()
        val (state, scope) = state(root)
        val setup = SlicingSetup(
            profileId = "generic_klipper",
            name = "Workshop printer",
            nozzle = "0.4",
            toolheads = 2,
            filamentProfiles = mapOf(1 to "pla-basic", 2 to "petg-basic"),
            customOffline = true,
            bedWidthMm = 250f,
            bedDepthMm = 250f,
            heightMm = 250f,
            installedFilamentProfiles = setOf("pla-basic", "petg-basic", "tpu-95a"),
        )
        try {
            state.finishFirstRun(setup)

            assertEquals(setup, DesktopSettings.load(state.paths.settings).slicingSetup)
            assertEquals(setOf("pla-basic", "petg-basic", "tpu-95a"), setupWizardDraft(setup).installedFilamentProfiles)
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `prepare offers installed filament presets instead of every compatible preset`() {
        val (state, scope) = state(Files.createTempDirectory("nia-prepare-filaments-").toFile())
        try {
            val compatible = state.prepare.filamentLibrary()
            assertTrue(compatible.size > 1)
            val installed = compatible.first()
            state.prepare.applySlicingSetup(
                SlicingSetup(
                    profileId = state.prepare.profileId,
                    name = "Filtered library",
                    nozzle = state.prepare.nozzle,
                    toolheads = 1,
                    filamentProfiles = mapOf(1 to installed.id),
                    installedFilamentProfiles = setOf(installed.id),
                ),
            )

            assertEquals(listOf(installed.id), state.prepare.filamentLibrary().map { it.id })
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `legacy slicing setup derives installed filaments from toolhead mappings`() {
        val legacy = org.json.JSONObject()
            .put("profileId", "generic_klipper")
            .put("name", "Legacy printer")
            .put("toolheads", 2)
            .put("filamentProfiles", org.json.JSONObject().put("1", "pla-basic").put("2", "petg-basic"))

        val restored = SlicingSetup.fromJson(legacy)

        assertEquals(setOf("pla-basic", "petg-basic"), restored?.installedFilamentProfiles)
    }

    @Test
    fun `removing a linked printer leaves the installed slicing setup intact`() {
        val root = Files.createTempDirectory("nia-independent-setup-").toFile()
        val (state, scope) = state(root)
        val setup = SlicingSetup(
            profileId = "snapmaker_u1",
            name = "U1 slicing setup",
            nozzle = "0.4",
            processId = null,
            toolheads = 4,
            filamentProfiles = mapOf(1 to "generic-pla"),
        )
        try {
            state.finishFirstRun(setup)
            val linked = PrinterConfig(
                PrinterIdentity("linked-u1", "Workshop U1", "Snapmaker U1", PrinterFamily.PAXX_U1, "192.0.2.1", setup.profileId),
                EXPORT_ONLY,
            )
            assertTrue(state.fleet.add(linked))

            state.fleet.remove(linked.identity.id)

            assertTrue(state.fleet.order.value.isEmpty())
            assertEquals(setup, state.fleet.settings.value.slicingSetup)
            assertEquals(setup, DesktopSettings.load(state.paths.settings).slicingSetup)
            assertTrue(state.fleet.settings.value.firstRunComplete)
            assertFalse(state.firstRunVisible)
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `setup wizard reentry retains the exact saved slicing choices`() {
        val setup = SlicingSetup(
            profileId = "generic_klipper",
            name = "Workshop CoreXY",
            nozzle = "0.6",
            processId = "custom-draft-process",
            toolheads = 6,
            filamentProfiles = mapOf(1 to "pla-fast", 2 to "petg-cf", 6 to "tpu-95a"),
            customOffline = true,
            bedWidthMm = 310f,
            bedDepthMm = 320f,
            heightMm = 330f,
        )

        val draft = setupWizardDraft(setup)

        assertEquals(setup.profileId, draft.profileId)
        assertEquals(setup.name, draft.setupName)
        assertEquals(setup.nozzle, draft.nozzle)
        assertEquals(setup.processId, draft.processId)
        assertEquals(setup.toolheads, draft.customTools)
        assertEquals(setup.filamentProfiles, draft.filamentProfiles)
        assertEquals("310.0", draft.bedWidth)
        assertEquals("320.0", draft.bedDepth)
        assertEquals("330.0", draft.buildHeight)
        assertTrue(draft.customOffline)
    }

    @Test
    fun `explicit custom toolhead resize fills new slots and removes orphaned mappings`() {
        assertEquals(
            mapOf(1 to "pla", 2 to "petg", 3 to "tpu"),
            resizedFilamentProfiles(mapOf(1 to "pla", 2 to "petg", 6 to "old"), 3, "tpu"),
        )
        assertEquals(
            mapOf(1 to "pla"),
            resizedFilamentProfiles(mapOf(1 to "pla", 2 to "petg", 6 to "old"), 1, "tpu"),
        )
    }

    @Test
    fun `assigned default filaments are installed even before an explicit selection`() {
        assertEquals(
            setOf("selected-pla", "default-petg", "default-tpu"),
            setupInstalledFilamentIds(
                mapOf("selected-pla" to true, "removed-abs" to false),
                mapOf(1 to "default-petg", 2 to "default-tpu"),
            ),
        )
    }

    @Test
    fun `untouched preselected machine resolves every toolhead to its named default filament`() {
        val machine = requireNotNull(PrinterLibrary.of("snapmaker_u1")).machineFor("0.4")
        val choices = setupCompatibleFilaments("snapmaker_u1", machine)

        val resolved = setupResolvedFilamentProfiles(emptyMap(), 4, machine, choices)

        assertEquals((1..4).associateWith { machine.defaultFilament }, resolved)
        assertTrue(choices.any { it.id == resolved[1] && it.displayName == "Snapmaker PLA Basic" })
        assertEquals(machine.defaultFilament, choices.first().id)
    }

    @Test
    fun `wizard includes imported brand presets for the exact machine instead of only generic fallbacks`() {
        val file = Files.createTempDirectory("nia-first-run-brands-").resolve("filament-presets.json").toFile()
        val store = FilamentPresetStore(file)
        val machine = requireNotNull(PrinterLibrary.of("snapmaker_u1")).machineFor("0.4")
        val key = setupFilamentProfileKey("snapmaker_u1", machine)
        val imported = store.import(key, FilamentProfileRecord(
            id = "polyterra-pla-purple",
            brand = "Polymaker",
            material = "PolyTerra PLA",
            materialType = "Matte",
            rgb = "#663399",
        ))

        val available = com.nozzleitall.desktop.prepare.FilamentLibrary.bundledForProfile(key) + store.entries(key)
        val choices = setupCompatibleFilaments("snapmaker_u1", machine, available)

        assertEquals("snapmaker_u1@${machine.id}", key)
        assertTrue(choices.any { it.id == imported.entry.id && it.displayName == "Polymaker PolyTerra PLA Matte" })
        assertTrue(choices.indexOfFirst { it.id == imported.entry.id } < choices.indexOfFirst { it.vendor == "Generic" })
    }

    @Test
    fun `compatible filament chooser groups presets by vendor in slicer order`() {
        val machine = requireNotNull(PrinterLibrary.of("snapmaker_u1")).machineFor("0.4")
        val choices = setupCompatibleFilaments("snapmaker_u1", machine)

        val groups = setupFilamentVendorGroups(choices)

        assertEquals("Snapmaker", groups.first().vendor)
        assertEquals("Generic", groups.last().vendor)
        assertEquals(choices.map { it.id }.toSet(), groups.flatMap { it.filaments }.map { it.id }.toSet())
        assertTrue(groups.all { group -> group.filaments.all { it.vendor == group.vendor } })
        assertEquals(machine.defaultFilament, groups.first().filaments.first().id)
        groups.forEach { group ->
            val materialTypes = setupFilamentMaterialTypeGroups(group.filaments)
            assertEquals(group.filaments.map { it.type.trim().ifBlank { "Other" }.lowercase() }.toSet().size, materialTypes.size)
            assertEquals(group.filaments.map { it.id }.toSet(), materialTypes.flatMap { it.filaments }.map { it.id }.toSet())
        }
    }

    @Test
    fun `vendor browser canonicalizes case whitespace and blank vendor names without losing presets`() {
        val entries = listOf(
            com.nozzleitall.desktop.prepare.FilamentLibrary.Entry("poly-1", "Polymaker PLA", "Polymaker", "PLA", "PLA"),
            com.nozzleitall.desktop.prepare.FilamentLibrary.Entry("poly-2", "Polymaker PETG", " polymaker ", "PETG", "PETG"),
            com.nozzleitall.desktop.prepare.FilamentLibrary.Entry("poly-3", "Polymaker TPU", "POLYMAKER", "TPU", "TPU"),
            com.nozzleitall.desktop.prepare.FilamentLibrary.Entry("other-1", "Unbranded PLA", "   ", "PLA", "PLA"),
            com.nozzleitall.desktop.prepare.FilamentLibrary.Entry("generic-1", "Generic PLA", " generic ", "PLA", "PLA"),
        )

        val ordered = setupCompatibleFilaments("test", null, entries)
        val groups = setupFilamentVendorGroups(ordered)

        assertEquals(listOf("polymaker", "other", "generic"), groups.map { it.key })
        assertEquals(listOf("Polymaker", "Other", "Generic"), groups.map { it.vendor })
        assertEquals(3, groups.first().filaments.size)
        assertEquals(entries.map { it.id }.toSet(), groups.flatMap { it.filaments }.map { it.id }.toSet())
        assertEquals("polymaker", setupFilamentVendorKey("  PolyMaker  "))
        assertEquals(listOf("petg", "pla", "tpu"), setupFilamentMaterialTypeGroups(groups.first().filaments).map { it.key })
    }

    @Test
    fun `untouched machine default mappings persist through first run and restart`() {
        val root = Files.createTempDirectory("nia-default-filaments-").toFile()
        val machine = requireNotNull(PrinterLibrary.of("snapmaker_u1")).machineFor("0.4")
        val resolved = setupResolvedFilamentProfiles(
            emptyMap(),
            4,
            machine,
            setupCompatibleFilaments("snapmaker_u1", machine),
        )
        val setup = SlicingSetup("snapmaker_u1", "Snapmaker U1", "0.4", machine.defaultProcess, 4, resolved)
        val (first, firstScope) = state(root)
        try {
            first.finishFirstRun(setup)
            assertEquals((1..4).associateWith { machine.defaultFilament }, DesktopSettings.load(first.paths.settings).slicingSetup?.filamentProfiles)
        } finally {
            close(first, firstScope)
        }
        val (restarted, restartedScope) = state(root)
        try {
            assertEquals((1..4).associateWith { machine.defaultFilament }, restarted.fleet.settings.value.slicingSetup?.filamentProfiles)
        } finally {
            close(restarted, restartedScope)
        }
    }

    @Test
    fun `catalog replies and imports are rejected after slot machine nozzle toolhead stage or close changes`() {
        val request = FilamentCatalogRequest(7, 2, "snapmaker_u1@u1-04")

        assertTrue(catalogRequestIsCurrent(request, 7, 2, "snapmaker_u1@u1-04"))
        // Every setup-context mutation calls resetCatalog: generation changes and the active slot/context are cleared.
        assertFalse(catalogRequestIsCurrent(request, 8, 2, "snapmaker_u1@u1-04"))
        assertFalse(catalogRequestIsCurrent(request, 7, null, null))
        assertFalse(catalogRequestIsCurrent(request, 7, 2, "prusa_generic@mk4s-04"))
        assertFalse(catalogRequestIsCurrent(request, 7, 3, "snapmaker_u1@u1-04"))
    }

    @Test
    fun `catalog search failures become retryable replies instead of escaping the coroutine`() = runBlocking {
        val reply = searchFilamentCatalog("Polymaker") { throw IllegalStateException("catalog unavailable") }

        assertEquals(emptyList<FilamentProfileRecord>(), reply.records)
        assertEquals("catalog unavailable", reply.error)
        assertFalse(reply.ok)
    }

    @Test
    fun `catalog search preserves successful records`() = runBlocking {
        val record = FilamentProfileRecord(id = "pla-1", brand = "Polymaker", material = "PLA")

        val reply = searchFilamentCatalog("Polymaker") { FilamentProfilesReply(listOf(record)) }

        assertEquals(listOf(record), reply.records)
        assertTrue(reply.ok)
    }

    @Test
    fun `slicing setup rejects filament mappings beyond its configured toolheads`() {
        val (state, scope) = state(Files.createTempDirectory("nia-invalid-setup-").toFile())
        try {
            val invalid = SlicingSetup(
                profileId = "generic_klipper",
                name = "One tool printer",
                nozzle = "0.4",
                toolheads = 1,
                filamentProfiles = mapOf(1 to "pla", 2 to "orphaned"),
                customOffline = true,
                bedWidthMm = 250f,
                bedDepthMm = 250f,
                heightMm = 250f,
            )

            assertFalse(state.fleet.saveSlicingSetup(invalid))
            assertNull(state.fleet.settings.value.slicingSetup)
            assertFalse(state.fleet.settings.value.firstRunComplete)
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `slicing setup rejects a toolhead assignment outside its installed filament library`() {
        val setup = SlicingSetup(
            profileId = "generic_klipper",
            name = "Workshop printer",
            nozzle = "0.4",
            toolheads = 1,
            filamentProfiles = mapOf(1 to "pla-basic"),
            installedFilamentProfiles = setOf("petg-basic"),
        )

        assertFalse(setup.isValid())
    }

    @Test
    fun `explicit RFID spool profile mapping survives restart`() {
        val root = Files.createTempDirectory("nia-rfid-profile-").toFile()
        val (first, firstScope) = state(root)
        try {
            assertTrue(first.fleet.saveRfidFilamentProfile("spool-42", "polymaker-polyflex-tpu95"))
            assertEquals("polymaker-polyflex-tpu95", first.fleet.rfidFilamentProfile("spool-42"))
        } finally {
            close(first, firstScope)
        }

        val (restarted, restartedScope) = state(root)
        try {
            assertEquals("polymaker-polyflex-tpu95", restarted.fleet.rfidFilamentProfile("spool-42"))
            assertTrue(restarted.fleet.saveRfidFilamentProfile("spool-42", null))
            assertNull(restarted.fleet.rfidFilamentProfile("spool-42"))
        } finally {
            close(restarted, restartedScope)
        }
    }

    @Test
    fun `external RFID and NFC reader choices are distinct and survive restart`() {
        val root = Files.createTempDirectory("nia-spool-reader-").toFile()
        val rfid = com.nozzleitall.desktop.prepare.SpoolReader.RFID
        val nfc = com.nozzleitall.desktop.prepare.SpoolReader.NFC
        val rfidTag = requireNotNull(rfid.tag(" 0042\r\n"))
        val nfcTag = requireNotNull(nfc.tag("0042"))
        assertNull(rfid.tag("https://example.invalid/tag"))
        assertNull(nfc.tag("00\n42"))
        assertNull(nfc.tag("x".repeat(96) + "y"))
        val saved: File
        val (first, firstScope) = state(root)
        try {
            val prepare = first.prepare
            prepare.profileId = "snapmaker_u1"
            val slot = prepare.materials().first()
            val profile = prepare.filamentLibrary().first()
            assertNull(prepare.readerProfile(rfidTag))
            assertEquals("The selected filament profile is unavailable.", prepare.applyReaderSpool(slot, rfidTag, "missing"))
            assertEquals(slot, prepare.materials().first())
            assertEquals("Mapped reader spool to slot ${slot.slot}. Save the project to keep this slot assignment.", prepare.applyReaderSpool(slot, rfidTag, profile.id))
            val mapped = prepare.materials().first()
            assertEquals(rfidTag, mapped.spoolId)
            assertFalse(mapped.fromTag)
            assertEquals(profile.id, mapped.filamentProfile)
            assertEquals(slot.colorHex, mapped.colorHex)
            assertEquals("The slot changed. Review the mapping again.", prepare.applyReaderSpool(slot, nfcTag, profile.id))
            assertNull(prepare.readerProfile(nfcTag))
            assertTrue(first.fleet.saveRfidFilamentProfile(rfidTag, "printer-report"))
            assertEquals(profile.id, prepare.readerProfile(rfidTag))
            assertEquals("Mapped reader spool to slot ${slot.slot}. Save the project to keep this slot assignment.", prepare.applyReaderSpool(mapped, nfcTag, profile.id))
            saved = prepare.save()
            prepare.newProject()
            assertNull(prepare.materials().first().spoolId)
        } finally {
            close(first, firstScope)
        }
        val (restarted, restartedScope) = state(root)
        try {
            restarted.prepare.open(saved)
            val reopened = restarted.prepare.materials()
            assertEquals(nfcTag, reopened.first().spoolId)
            assertFalse(reopened.first().fromTag)
            assertEquals(restarted.prepare.readerProfile(nfcTag), reopened.first().filamentProfile)
            assertEquals("This tag is invalid or already assigned to another slot.",
                restarted.prepare.applyReaderSpool(reopened[1], nfcTag, requireNotNull(reopened.first().filamentProfile)))
            assertEquals(reopened, restarted.prepare.materials())
            assertEquals("printer-report", restarted.fleet.rfidFilamentProfile(rfidTag))
            assertEquals(reopened.first().filamentProfile, restarted.prepare.readerProfile(rfidTag))
            assertEquals(restarted.prepare.readerProfile(rfidTag), restarted.prepare.readerProfile(nfcTag))

            val original = com.nozzleitall.project.ThreeMf.read(saved)
            val manifest = requireNotNull(original.manifest)
            val duplicate = root.resolve("duplicate-reader.3mf")
            com.nozzleitall.project.ThreeMf.writeAtomicallyNew(original.copy(manifest = manifest.copy(
                materials = manifest.materials.map { if (it.slot == 2) it.copy(spoolId = nfcTag) else it }
            )), duplicate)
            restarted.prepare.open(duplicate)
            assertTrue(restarted.prepare.materials().none { it.spoolId == nfcTag })
            assertTrue(restarted.prepare.notice.orEmpty().contains("more than one slot"))
        } finally {
            close(restarted, restartedScope)
        }
    }

    @Test
    fun `reported RFID spool resolves to its saved slicing profile without redefining the printer connection`() {
        val (state, scope) = state(Files.createTempDirectory("nia-rfid-resolution-").toFile())
        try {
            val config = PrinterConfig(
                PrinterIdentity("mapped-printer", "Mapped printer", "Test printer", PrinterFamily.EXPORT_ONLY, "", "generic_klipper"),
                EXPORT_ONLY,
            )
            assertTrue(state.fleet.add(config))
            state.prepare.choosePrinter(config.identity.id)
            state.fleet.printers.getValue(config.identity.id).status.value = PrinterStatus(
                PrinterState.READY,
                ConnectionRoute.NONE,
                toolheads = listOf(Toolhead(0, material = Material(
                    vendor = "Polymaker",
                    type = "TPU",
                    subType = "95A",
                    colorHex = "#111111",
                    fromTag = true,
                    spoolId = "rfid-42",
                    sku = "PB02002",
                ))),
            )
            assertTrue(state.fleet.saveRfidFilamentProfile("rfid-42", "polymaker-tpu95"))

            val material = state.prepare.materials().single()

            assertEquals(0, material.toolhead)
            assertEquals("rfid-42", material.spoolId)
            assertEquals("PB02002", material.sku)
            assertEquals("polymaker-tpu95", material.filamentProfile)
            assertTrue(material.fromTag)
            assertEquals("generic_klipper", state.fleet.printers.getValue(config.identity.id).config.identity.profileId)

            assertEquals("Synced 1 of 1 slots from Mapped printer.", state.prepare.syncFromPrinter())
            state.fleet.printers.getValue(config.identity.id).status.value = PrinterStatus(
                PrinterState.OFFLINE, ConnectionRoute.NONE,
            )
            val offline = state.prepare.materials().single { it.slot == material.slot }
            assertEquals(material.filamentProfile, offline.filamentProfile)
            assertEquals(material.spoolId, offline.spoolId)
            assertEquals(material.sku, offline.sku)
            assertTrue(offline.fromTag)
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `explicit first run completion survives restart without changing other settings`() {
        val root = Files.createTempDirectory("nia-first-run-save-").toFile()
        val (first, firstScope) = state(root)
        try {
            assertTrue(first.fleet.updateTheme("dark"))
            first.completeFirstRun()
            assertFalse(first.firstRunVisible)
            assertTrue(DesktopSettings.load(first.paths.settings).firstRunComplete)
            assertEquals("dark", DesktopSettings.load(first.paths.settings).theme)
        } finally {
            close(first, firstScope)
        }

        val (restarted, restartedScope) = state(root)
        try {
            assertFalse(restarted.firstRunVisible)
            assertEquals("dark", restarted.fleet.settings.value.theme)
        } finally {
            close(restarted, restartedScope)
        }
    }

    @Test
    fun `workspace printer setup is direct and does not masquerade as first run`() {
        val (state, scope) = state(Files.createTempDirectory("nia-workspace-printer-").toFile())
        try {
            state.completeFirstRun()
            state.startPrinterSetup(autoScan = false)
            assertEquals(AddPrinterRequest(autoScan = false, compatibilityProfileId = null), state.addPrinterRequest)
            state.finishPrinterSetup(null)
            assertNull(state.addPrinterRequest)
            assertFalse(state.firstRunVisible)
            assertEquals(Destination.PREPARE, state.destination)
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `standalone physical printer setup never inherits the installed slicing setup`() {
        val (state, scope) = state(Files.createTempDirectory("nia-linked-setup-").toFile())
        try {
            assertNull(state.fleet.settings.value.slicingSetup)
            state.startPrinterSetup(autoScan = false)
            assertNull(requireNotNull(state.addPrinterRequest).compatibilityProfileId)
            state.finishPrinterSetup(null)

            state.finishFirstRun(SlicingSetup("prusa_generic", "Prusa setup", "0.4", toolheads = 1))
            state.startPrinterSetup(autoScan = false)
            assertNull(requireNotNull(state.addPrinterRequest).compatibilityProfileId)
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `selecting a physical printer never changes the installed slicing configuration`() {
        val (state, scope) = state(Files.createTempDirectory("nia-independent-selection-").toFile())
        try {
            val setup = SlicingSetup("prusa_generic", "Prusa setup", "0.4", toolheads = 1)
            state.finishFirstRun(setup)
            val physical = PrinterConfig(
                PrinterIdentity("u1-connection", "Workshop U1", "Snapmaker U1", PrinterFamily.EXPORT_ONLY,
                    "", "snapmaker_u1"),
                EXPORT_ONLY,
            )
            assertTrue(state.fleet.add(physical))

            state.prepare.choosePrinter(physical.identity.id)

            assertEquals(physical.identity.id, state.prepare.printerId)
            assertEquals("prusa_generic", state.prepare.profileId)
            assertEquals(setup, state.fleet.settings.value.slicingSetup)
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `failed first run completion stays visible and does not persist completion`() {
        val root = Files.createTempDirectory("nia-first-run-fail-").toFile()
        val (state, scope) = state(root)
        try {
            assertTrue(state.paths.settings.mkdir())
            state.completeFirstRun()
            assertTrue(state.firstRunVisible)
            assertFalse(state.fleet.settings.value.firstRunComplete)
            assertEquals("Your welcome choice could not be saved. Try again before continuing.", state.prepare.notice)
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `failed printer persistence does not add an in-memory printer`() {
        val root = Files.createTempDirectory("nia-printer-save-fail-").toFile()
        val (state, scope) = state(root)
        try {
            assertTrue(state.paths.printers.mkdir())
            val config = com.nozzleitall.printer.PrinterConfig(
                com.nozzleitall.printer.PrinterIdentity(
                    "failed-printer", "Failed printer", "Export target",
                    com.nozzleitall.printer.PrinterFamily.EXPORT_ONLY, "", "generic_klipper",
                ),
                EXPORT_ONLY,
            )
            assertFalse(state.fleet.add(config))
            assertTrue(state.fleet.order.value.isEmpty())
            assertFalse(state.fleet.printers.containsKey(config.identity.id))
            assertTrue(state.firstRunVisible)
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `active printer setup request is not silently replaced`() {
        val (state, scope) = state(Files.createTempDirectory("nia-printer-request-").toFile())
        try {
            state.startPrinterSetup(autoScan = false, fromFirstRun = true)
            state.startPrinterSetup(autoScan = true, fromFirstRun = false)
            assertEquals(AddPrinterRequest(autoScan = false, returnToFirstRunOnCancel = true, compatibilityProfileId = null), state.addPrinterRequest)
            state.finishPrinterSetup(null)
            assertTrue(state.firstRunVisible)
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `onboarding connection request carries only an explicit compatibility association`() {
        val (state, scope) = state(Files.createTempDirectory("nia-linked-printer-request-").toFile())
        try {
            state.startPrinterSetup(autoScan = true, fromFirstRun = true, compatibilityProfileId = "snapmaker_u1")
            assertEquals(AddPrinterRequest(true, true, "snapmaker_u1"), state.addPrinterRequest)
            assertTrue(state.firstRunVisible)
            state.finishPrinterSetup(null)
            assertTrue(state.firstRunVisible)
        } finally {
            close(state, scope)
        }
    }

    @Test
    fun `discovery preserves an explicit compatibility association and otherwise infers one`() {
        assertEquals(
            "snapmaker_u1",
            compatibilityProfileForDiscoveredPrinter("snapmaker_u1", "Prusa MK4S"),
        )
        assertEquals(
            ProfileCatalog.search("Prusa MK4S").first().id,
            compatibilityProfileForDiscoveredPrinter(null, "Prusa MK4S"),
        )
        assertNull(compatibilityProfileForDiscoveredPrinter(null, "Unknown homemade printer"))
    }
}
