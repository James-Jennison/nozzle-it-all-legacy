package com.nozzleitall.desktop

import com.nozzleitall.printer.*
import com.nozzleitall.project.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DesktopAcceptanceTest {
    private fun tmp() = Files.createTempDirectory("nozzle").toFile().apply { deleteOnExit() }
    private fun paths() = tmp().let { AppPaths(File(it, "config"), File(it, "data"), File(it, "cache")).ensure() }

    @Test fun textSizeSettingKeepsOlderProfilesAndBoundsInvalidValues() {
        val file = File(tmp(), "settings.json")
        file.writeText("""{"theme":"dark"}""")
        assertEquals(1f, DesktopSettings.load(file).textScale)
        file.writeText(DesktopSettings(textScale = 2f).toJson().toString())
        assertEquals(2f, DesktopSettings.load(file).textScale)
        file.writeText("""{"textScale":9}""")
        assertEquals(1f, DesktopSettings.load(file).textScale)
        file.writeText(JSONObject().put("theme", "neon").put("collapsedSettingGroups",
            org.json.JSONArray(listOf("invalid", "PROCESS:" + "x".repeat(129), "PROCESS:layers"))).toString())
        assertEquals("system", DesktopSettings.load(file).theme)
        assertEquals(setOf("PROCESS:layers"), DesktopSettings.load(file).collapsedSettingGroups)
    }

    @Test fun textSizeSaveFailureKeepsActiveValueAndNoticeUntilSuccessfulRetry() {
        val p = paths()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val fleet = Fleet(p, scope, stockHelper = { null })
        try {
            assertTrue(fleet.updateTextSize(2f))
            assertEquals(2f, DesktopSettings.load(p.settings).textScale)
            assertFalse(fleet.updateTextSize(Float.NaN))
            assertFalse(fleet.updateTextSize(0f))
            assertEquals(2f, DesktopSettings.load(p.settings).textScale)
            assertFalse("Invalid input is not a storage failure", fleet.textSizeSaveProblem.value)
            assertTrue(p.settings.delete())
            assertTrue(p.settings.mkdir())
            File(p.settings, "block-replacement").writeText("synthetic test obstruction")
            assertFalse(fleet.updateTextSize(1.25f))
            assertEquals(2f, fleet.settings.value.textScale)
            assertTrue(fleet.textSizeSaveProblem.value)
            assertTrue(File(p.settings, "block-replacement").delete())
            assertTrue(p.settings.delete())
            assertTrue(fleet.updateTextSize(1.5f))
            assertEquals(1.5f, DesktopSettings.load(p.settings).textScale)
            assertFalse(fleet.textSizeSaveProblem.value)
        } finally {
            fleet.shutdown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun settingGroupExpansionSurvivesRestartAndFailedSaveKeepsThePriorChoice() {
        val p = paths()
        val key = "PROCESS:layers"
        assertTrue(DesktopSettings.load(p.settings).collapsedSettingGroups.isEmpty())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val fleet = Fleet(p, scope)
        try {
            assertTrue(fleet.updateSettingGroupCollapsed(key, true))
            assertFalse(fleet.updateSettingGroupCollapsed("PROCESS:" + "x".repeat(129), true))
            assertEquals(setOf(key), DesktopSettings.load(p.settings).collapsedSettingGroups)
            assertEquals(setOf(key), Fleet(p, scope).let { restarted ->
                try { restarted.settings.value.collapsedSettingGroups } finally { restarted.shutdown() }
            })
            assertTrue(p.settings.delete())
            assertTrue(p.settings.mkdirs())
            File(p.settings, "block-replacement").writeText("synthetic test obstruction")
            assertFalse(fleet.updateSettingGroupCollapsed(key, false))
            assertEquals(setOf(key), fleet.settings.value.collapsedSettingGroups)
            assertTrue(File(p.settings, "block-replacement").delete())
            assertTrue(p.settings.delete())
            assertTrue(fleet.updateSettingGroupCollapsed(key, false))
            assertTrue(DesktopSettings.load(p.settings).collapsedSettingGroups.isEmpty())
        } finally {
            fleet.shutdown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun concurrentSettingsWritersPreserveEverySavedChoice() {
        val p = paths()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val fleet = Fleet(p, scope, stockHelper = { null })
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val changes = (0 until 20).map { index -> java.util.concurrent.Callable {
                start.await()
                fleet.updateSettingGroupCollapsed("PROCESS:group-$index", true)
            } } + listOf(
                java.util.concurrent.Callable { start.await(); fleet.updateTextSize(1.5f) },
                java.util.concurrent.Callable { start.await(); fleet.updateTheme("dark") },
                java.util.concurrent.Callable { start.await(); fleet.updateNotifications(false) },
            )
            val futures = changes.map(pool::submit)
            start.countDown()
            futures.forEach { assertTrue(it.get(30, java.util.concurrent.TimeUnit.SECONDS)) }
            val expectedGroups = (0 until 20).map { "PROCESS:group-$it" }.toSet()
            assertEquals(expectedGroups, fleet.settings.value.collapsedSettingGroups)
            assertEquals(fleet.settings.value, DesktopSettings.load(p.settings))
            assertEquals(1.5f, fleet.settings.value.textScale)
            assertEquals("dark", fleet.settings.value.theme)
            assertFalse(fleet.settings.value.notifications)
        } finally {
            pool.shutdownNow()
            fleet.shutdown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun separateFleetInstancesMergeSavedChoices() {
        val p = paths()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val first = Fleet(p, scope, stockHelper = { null })
        val second = Fleet(p, scope, stockHelper = { null })
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val a = pool.submit<Boolean> { start.await(); first.updateTheme("dark") }
            val b = pool.submit<Boolean> { start.await(); second.updateNotifications(false) }
            start.countDown()
            assertTrue(a.get(30, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(b.get(30, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("dark", DesktopSettings.load(p.settings).theme)
            assertFalse(DesktopSettings.load(p.settings).notifications)
            val savedBytes = p.settings.readBytes()
            assertTrue(first.updateTheme("dark"))
            assertArrayEquals("A persisted no-op must not rewrite another instance's choice", savedBytes, p.settings.readBytes())
            assertFalse(first.settings.value.notifications)
        } finally {
            pool.shutdownNow()
            first.shutdown()
            second.shutdown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun separateProcessSettingsLockFailsWithinBoundAndCanRetry() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").contains("Linux") && File("/usr/bin/python3").canExecute())
        val p = paths()
        val ready = File(p.config, "lock-held.ready")
        val lock = File(p.config, ".settings.lock")
        val holder = ProcessBuilder("/usr/bin/python3", "-c",
            "import fcntl,sys,time; f=open(sys.argv[1],'a+'); fcntl.lockf(f,fcntl.LOCK_EX); open(sys.argv[2],'w').write('ready'); time.sleep(10)",
            lock.absolutePath, ready.absolutePath).start()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val fleet = Fleet(p, scope, stockHelper = { null })
        try {
            repeat(50) { if (!ready.exists() && holder.isAlive) Thread.sleep(100) }
            assertTrue("A separate process must hold the settings lock", ready.exists() && holder.isAlive)
            assertFalse(fleet.updateTheme("dark"))
            assertEquals("system", fleet.settings.value.theme)
            assertTrue(fleet.themeSaveProblem.value)
        } finally {
            holder.destroyForcibly()
            holder.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            try {
                assertTrue(fleet.updateTheme("dark"))
                assertEquals("dark", DesktopSettings.load(p.settings).theme)
            } finally {
                fleet.shutdown()
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
    }

    @Test fun failedGeneralSettingsSaveDoesNotChangeActiveChoice() {
        val p = paths()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val fleet = Fleet(p, scope, stockHelper = { null })
        try {
            assertTrue(p.settings.mkdirs())
            File(p.settings, "block-replacement").writeText("synthetic test obstruction")
            assertFalse(fleet.updateTheme("dark"))
            assertEquals("system", fleet.settings.value.theme)
            assertTrue(fleet.themeSaveProblem.value)
            assertFalse(fleet.updateTheme("system"))
            assertTrue("An unchanged choice still needs a successful disk write", fleet.themeSaveProblem.value)
            assertFalse(fleet.updateTheme("neon"))
            assertTrue(fleet.themeSaveProblem.value)
            assertFalse(fleet.updateNotifications(false))
            assertTrue(fleet.notificationsSaveProblem.value)
            assertFalse(fleet.setStockEnabled(true))
            assertTrue(fleet.stockSaveProblem.value)
            assertFalse(fleet.settings.value.stockU1Enabled)
            assertTrue(File(p.settings, "block-replacement").delete())
            assertTrue(p.settings.delete())
            assertTrue(fleet.updateTheme("dark"))
            assertEquals("dark", DesktopSettings.load(p.settings).theme)
            assertFalse(fleet.themeSaveProblem.value)
            val savedBytes = p.settings.readBytes()
            assertTrue(fleet.updateTheme("dark"))
            assertArrayEquals(savedBytes, p.settings.readBytes())
            assertFalse(fleet.updateTheme("neon"))
            assertFalse("Invalid input is not a storage failure", fleet.themeSaveProblem.value)
            assertTrue("Unrelated save must not hide a failed notification", fleet.notificationsSaveProblem.value)
            assertTrue("Unrelated save must not hide a failed stock setting", fleet.stockSaveProblem.value)
            assertTrue(fleet.updateNotifications(false))
            assertFalse(fleet.notificationsSaveProblem.value)
            assertTrue(fleet.setStockEnabled(true))
            assertFalse(fleet.stockSaveProblem.value)
        } finally {
            fleet.shutdown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun malformedExistingSettingsCannotBeOverwrittenByAnUnrelatedChoice() {
        val p = paths()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val fleet = Fleet(p, scope, stockHelper = { null })
        try {
            assertTrue(fleet.updateTheme("dark"))
            p.settings.writeText("{damaged")
            assertFalse(fleet.updateNotifications(false))
            assertEquals("{damaged", p.settings.readText())
            assertEquals("dark", fleet.settings.value.theme)
            assertTrue(fleet.settings.value.notifications)
            assertTrue(fleet.notificationsSaveProblem.value)
            val damagedTail = """{"theme":"dark"}garbage"""
            p.settings.writeText(damagedTail)
            assertFalse(fleet.updateNotifications(false))
            assertEquals(damagedTail, p.settings.readText())
            for (tail in listOf("// trailing comment", "/* trailing comment */")) {
                val damagedComment = """{"theme":"dark"}""" + tail
                p.settings.writeText(damagedComment)
                assertFalse(fleet.updateNotifications(false))
                assertEquals(damagedComment, p.settings.readText())
            }
            for (tail in listOf("\u0000", "\u0001", "\u000b")) {
                val damagedControl = """{"theme":"dark"}""" + tail
                p.settings.writeText(damagedControl)
                assertFalse(fleet.updateNotifications(false))
                assertEquals(damagedControl, p.settings.readText())
            }
            val invalidUtf8 = """{"theme":"da""".toByteArray() + byteArrayOf(0xff.toByte()) + """rk"}""".toByteArray()
            val damagedEncodings = listOf(
                invalidUtf8,
                """{"theme":"da""".toByteArray() + byteArrayOf(0xc3.toByte()) + """rk"}""".toByteArray(),
                """{"theme":"da""".toByteArray() + byteArrayOf(0xc0.toByte(), 0xaf.toByte()) + """rk"}""".toByteArray(),
                """{"theme":"da""".toByteArray() + byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()) + """rk"}""".toByteArray(),
                byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + """{"theme":"dark"}""".toByteArray(),
            )
            for (damaged in damagedEncodings) {
                p.settings.writeBytes(damaged)
                assertFalse(fleet.updateNotifications(false))
                assertArrayEquals(damaged, p.settings.readBytes())
            }
            p.settings.writeText("""{"theme":"dark"}""" + "\n")
            assertTrue(fleet.updateNotifications(false))
            assertFalse(DesktopSettings.loadExisting(p.settings).notifications)
        } finally {
            fleet.shutdown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun damagedSettingsAreFlaggedOnStartupAndClearAfterRepair() {
        val p = paths()
        val damaged = """{"theme":"dark"}""" + "\u0000"
        p.settings.writeText(damaged)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val fleet = Fleet(p, scope, stockHelper = { null })
        try {
            assertTrue(fleet.settingsLoadProblem.value)
            assertEquals(DesktopSettings(), fleet.settings.value)
            assertFalse(fleet.updateTheme("dark"))
            assertEquals(damaged, p.settings.readText())
            assertTrue(fleet.settingsLoadProblem.value)
            p.settings.writeText("{}")
            assertTrue(fleet.updateTheme("dark"))
            assertFalse(fleet.settingsLoadProblem.value)
        } finally {
            fleet.shutdown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun dataLocationsAreNozzlesOwnAndNeverAnotherSlicers() {
        val linux = AppPaths.resolve(env = emptyMap(), home = "/home/u", os = "Linux")
        assertEquals("/home/u/.config/nozzle-it-all", linux.config.path)
        assertEquals("/home/u/.local/share/nozzle-it-all", linux.data.path)
        assertEquals("/home/u/.cache/nozzle-it-all", linux.cache.path)
        val win = AppPaths.resolve(env = mapOf("APPDATA" to "C:\\Users\\u\\AppData\\Roaming", "LOCALAPPDATA" to "C:\\Users\\u\\AppData\\Local"), home = "C:\\Users\\u", os = "Windows 11")
        assertTrue(win.config.path.contains("Nozzle It All"))
        for (p in listOf(linux, win)) for (dir in listOf(p.config, p.data, p.cache))
            AppPaths.foreignFolderNames.forEach { assertFalse("$dir overlaps $it", dir.path.split('/', '\\').any { seg -> seg.equals(it, true) }) }
    }

    @Test fun printerSecretsAreSeparateAndOwnerOnly() {
        val p = paths()
        val store = PrinterStore(p)
        val cfg = PrinterConfig(PrinterIdentity("a", "Workshop", "Snapmaker U1", PrinterFamily.PAXX_U1, "http://192.168.1.40"), "paxx-lan", "secret-key")
        store.save(listOf(cfg))
        assertFalse(p.printers.readText().contains("secret-key"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(p.secrets.toPath())))
        // Fields written by a newer version survive a save by this one.
        p.printers.writeText(JSONObject(p.printers.readText()).apply { getJSONArray("printers").getJSONObject(0).put("futureField", 7) }.toString())
        store.save(store.load())
        assertEquals(7, JSONObject(p.printers.readText()).getJSONArray("printers").getJSONObject(0).getInt("futureField"))
        assertEquals("secret-key", store.load().single().secret)
    }

    // --- PAXX baseline isolation ---

    @Test fun paxxOnlyFleetNeverRegistersOrStartsStockSupport() {
        val p = paths()
        PrinterStore(p).save(listOf(PrinterConfig(PrinterIdentity("u1", "U1", "Snapmaker U1", PrinterFamily.PAXX_U1, "http://127.0.0.1:9"), "paxx-lan")))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val fleet = Fleet(p, scope)
        assertTrue("Stock support is off by default", !fleet.settings.value.stockU1Enabled)
        assertTrue(fleet.registry.optionalIds.isEmpty())
        assertTrue(fleet.registry.startedOptionalIds.isEmpty())
        fleet.shutdown(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun missingStockSupportOnlyAffectsStockPrinters() {
        val p = paths()
        // Stock support "enabled" but its helper points at nothing; a PAXX printer must still be served by the PAXX adapter.
        p.settings.writeText(DesktopSettings(stockU1Enabled = true).toJson().toString())
        PrinterStore(p).save(listOf(
            PrinterConfig(PrinterIdentity("paxx", "PAXX", "Snapmaker U1", PrinterFamily.PAXX_U1, "http://127.0.0.1:9"), "paxx-lan"),
            PrinterConfig(PrinterIdentity("stock", "Stock", "Snapmaker U1", PrinterFamily.STOCK_U1, "http://127.0.0.1:9"), "stock-u1")))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val fleet = Fleet(p, scope, stockHelper = { null }) // not installed, whatever this machine has in /opt
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && (fleet.printers["paxx"]?.capabilities?.value == null || fleet.printers["stock"]?.problem?.value == null)) Thread.sleep(100)
        assertNotNull("PAXX printer opened through the PAXX adapter", fleet.printers["paxx"]!!.capabilities.value)
        assertFalse(fleet.printers["paxx"]!!.capabilities.value!!.requiresVendorAccount)
        assertNull(fleet.printers["paxx"]!!.problem.value)
        assertNotNull("Stock printer reports why it can't connect", fleet.printers["stock"]!!.problem.value)
        fleet.shutdown(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
