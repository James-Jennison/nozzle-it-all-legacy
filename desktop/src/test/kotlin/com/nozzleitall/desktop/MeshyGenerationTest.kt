package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.GeneratedGeometryStore
import com.nozzleitall.desktop.prepare.GeometryTask
import com.nozzleitall.desktop.prepare.MeshyProvider
import com.nozzleitall.desktop.prepare.MeshySecretService
import com.nozzleitall.desktop.prepare.SliceEngine
import com.nozzleitall.project.Mesh
import com.nozzleitall.project.MeshIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import java.nio.file.Files

class MeshyGenerationTest {
    private val taskId = "018a210d-8ba4-705c-b111-1f1776f7f578"

    @Test fun providerPinsModelAndOnlyAuthorizesTheApiHost() {
        val fixtureKey = "fixture-key-value".toCharArray()
        var responseAsset = "https://assets.meshy.ai/test/model.stl?signature=fixture"
        val calls = mutableListOf<String>()
        val provider = MeshyProvider { uri, method, body, key, _ ->
            calls += method + " " + uri.host
            when {
                method == "POST" -> {
                    assertEquals("api.meshy.ai", uri.host)
                    assertSame(fixtureKey, key)
                    val request = JSONObject(requireNotNull(body).decodeToString())
                    assertEquals("meshy-6", request.getString("ai_model"))
                    assertEquals("preview", request.getString("mode"))
                    assertEquals("fixture shape", request.getString("prompt"))
                    assertEquals("stl", request.getJSONArray("target_formats").getString(0))
                    assertFalse(request.has("model_file"))
                    MeshyProvider.Reply(JSONObject().put("result", taskId).toString().encodeToByteArray())
                }
                uri.host == "api.meshy.ai" -> {
                    assertSame(fixtureKey, key)
                    assertTrue(uri.path.endsWith(taskId))
                    MeshyProvider.Reply(JSONObject().put("id", taskId).put("status", "SUCCEEDED").put("consumed_credits", 20)
                        .put("model_urls", JSONObject().put("stl", responseAsset)).toString().encodeToByteArray(), 9)
                }
                else -> {
                    assertEquals("assets.meshy.ai", uri.host)
                    assertNull(key)
                    MeshyProvider.Reply("fake-stl".encodeToByteArray())
                }
            }
        }
        try {
            assertEquals("Task_A-01", MeshyProvider.taskId("Task_A-01"))
            listOf("../escape", "a".repeat(129), "_first", "a.b", "a/b").forEach { invalid ->
                assertTrue(runCatching { MeshyProvider.taskId(invalid) }.isFailure)
            }
            assertEquals(20, provider.estimatedCredits)
            assertEquals(taskId, provider.submit("fixture shape", fixtureKey))
            val task = provider.status(taskId, fixtureKey)
            assertEquals(GeometryTask.Status.SUCCEEDED, task.status)
            assertEquals(20, task.consumedCredits)
            assertEquals(9L, task.retryAfterSeconds)
            assertEquals("fake-stl", provider.download(task).decodeToString())
            assertEquals(listOf("POST api.meshy.ai", "GET api.meshy.ai", "GET assets.meshy.ai"), calls)
            responseAsset = "https://assets.meshy.ai.invalid/test/model.stl"
            assertTrue(runCatching { provider.status(taskId, fixtureKey) }.isFailure)
            assertEquals(1, calls.count { it == "GET assets.meshy.ai" })
        } finally { fixtureKey.fill('\u0000') }
    }

    @Test fun transportRefusesRedirectsAndBoundsResponsesWithoutContentLength() {
        val uri = URI("https://assets.meshy.ai/fixture/model.stl")
        val redirected = FixtureConnection(uri.toURL(), 302, byteArrayOf())
        assertTrue(runCatching { MeshyProvider.https(uri, "GET", null, null, 8) { redirected } }.isFailure)
        assertFalse(redirected.instanceFollowRedirects)
        assertFalse(redirected.bodyOpened)

        val oversizedDeclared = FixtureConnection(uri.toURL(), 200, ByteArray(9), 9)
        assertTrue(runCatching { MeshyProvider.https(uri, "GET", null, null, 8) { oversizedDeclared } }.isFailure)
        assertFalse(oversizedDeclared.bodyOpened)
        val oversized = FixtureConnection(uri.toURL(), 200, ByteArray(9), 1)
        assertTrue(runCatching { MeshyProvider.https(uri, "GET", null, null, 8) { oversized } }.isFailure)
        assertTrue(oversized.bodyOpened)
        val oversizedWithoutLength = FixtureConnection(uri.toURL(), 200, ByteArray(9))
        assertTrue(runCatching { MeshyProvider.https(uri, "GET", null, null, 8) { oversizedWithoutLength } }.isFailure)
        val allowed = FixtureConnection(uri.toURL(), 200, byteArrayOf(1, 2, 3))
        val reply = MeshyProvider.https(uri, "GET", null, null, 8) { allowed }
        assertArrayEquals(byteArrayOf(1, 2, 3), reply.bytes)
        assertEquals(9L, reply.retryAfterSeconds)
    }

    private class FixtureConnection(url: URL, private val status: Int, private val bytes: ByteArray,
                                    private val declaredLength: Long = -1) : HttpsURLConnection(url) {
        var bodyOpened = false
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate>? = null
        override fun getResponseCode() = status
        override fun getContentLengthLong() = declaredLength
        override fun getHeaderField(name: String?) = if (name == "Retry-After") "9" else null
        override fun getInputStream(): ByteArrayInputStream {
            bodyOpened = true
            return ByteArrayInputStream(bytes)
        }
    }

    @Test fun keychainAdapterSendsSyntheticKeyOnlyThroughStdin() {
        val root = Files.createTempDirectory("nia-meshy-key-fixture").toFile()
        val sample = "fixture-key-value".toCharArray()
        try {
            val tool = File(root, "fixture-secret-tool").apply {
                writeText("""#!/bin/sh
set -eu
case "$1" in
    store)
        test "$#" = 6
        test "$(cat)" = fixture-key-value
        ;;
    lookup)
        test "$#" = 5
        printf '%s\n' fixture-key-value
        ;;
    *) exit 1 ;;
esac
""")
                check(setExecutable(true, true))
            }
            val keychain = MeshySecretService(tool)
            keychain.save(sample)
            val restored = keychain.load()
            try { assertArrayEquals(sample, restored) }
            finally { restored.fill('\u0000') }
            tool.writeText("""#!/bin/sh
head -c 70000 /dev/zero
""")
            val started = System.nanoTime()
            assertTrue(runCatching { keychain.load() }.isFailure)
            assertTrue(System.nanoTime() - started < 10_000_000_000L)
        } finally {
            sample.fill('\u0000')
            root.deleteRecursively()
        }
    }

    @Test fun generatedStlHasLocalProvenanceAndOneUndoablePlateImport() {
        assumeTrue("Requires the pinned native engine", SliceEngine.locateEngine() != null)
        val root = Files.createTempDirectory("nia-meshy-local-geometry").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val bytes = File(source, "site-src/assets/test-cube-20mm.stl").readBytes()
        try {
            val app = AppState(AppPaths(File(root, "config"), File(root, "data"), File(root, "cache")).ensure(), scope)
            val prepare = app.prepare
            val store = prepare.generatedGeometry
            val awaiting = store.begin()
            assertNull(awaiting.taskId)
            assertTrue(runCatching { GeneratedGeometryStore(File(app.paths.data, "meshy-assets")).begin() }.isFailure)
            val saved = store.attach(awaiting, taskId)
            assertEquals(taskId, saved.taskId)
            assertEquals(saved, GeneratedGeometryStore(File(app.paths.data, "meshy-assets")).pending())
            val reviewed = prepare.inspectMeshy(GeneratedGeometryStore.Asset(taskId, bytes, 20), 42.0)
            assertArrayEquals(bytes, store.read(taskId)!!.bytes)
            assertEquals(20, store.read(taskId)!!.credits)
            assertEquals(0, reviewed.repair.after.openEdges)
            val bounds = reviewed.original.bounds()
            assertEquals(42f, (0..2).maxOf { bounds[it + 3] - bounds[it] }, 0.001f)
            assertTrue(prepare.items.isEmpty())
            prepare.addMeshyModel(reviewed, useRepair = true)
            assertSame(reviewed.repaired, prepare.items.single().mesh)
            prepare.history.undo()
            assertTrue(prepare.items.isEmpty())
            assertTrue(runCatching { GeneratedGeometryStore.validateLocalMesh(reviewed.original, 1.0) }.isFailure)
            val degenerate = MeshIO.stlBytes(Mesh(FloatArray(9), IntArray(12)))
            assertTrue(runCatching { GeneratedGeometryStore.scaledModel(degenerate, 42.0) }.isFailure)
            val archive = File(app.paths.data, "meshy-assets/receipt-" + saved.receipt + ".json")
            archive.writeText(JSONObject().put("receipt", saved.receipt).put("taskId", "other-task").toString())
            assertTrue(runCatching { store.clear(saved, "imported") }.isFailure)
            assertEquals(saved, store.pending())
            Files.delete(archive.toPath())
            store.clear(saved, "imported")
            assertNull(store.pending())
            assertEquals("imported", JSONObject(archive.readText()).getString("closedReason"))
            assertArrayEquals(bytes, store.read(taskId)!!.bytes)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
