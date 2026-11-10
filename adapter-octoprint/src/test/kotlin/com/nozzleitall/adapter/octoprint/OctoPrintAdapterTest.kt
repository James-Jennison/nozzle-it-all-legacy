package com.nozzleitall.adapter.octoprint

import com.nozzleitall.printer.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OctoPrintAdapterTest {
    private fun server(jobState: String = "Printing", commandPolicy: SocketPolicy? = null) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/printer") -> MockResponse().setBody("""{"state":{"flags":{"operational":true,"printing":${jobState == "Printing"},"paused":${jobState == "Paused"}}},"temperature":{"tool0":{"actual":210.2,"target":210},"bed":{"actual":60,"target":60}}}""")
                request.path == "/api/job" && request.method == "GET" -> MockResponse().setBody("""{"job":{"file":{"name":"cube.gcode","display":"cube.gcode"}},"progress":{"completion":42.0,"printTime":600}}""")
                request.path == "/api/job" && request.method == "POST" -> commandPolicy?.let { MockResponse().setSocketPolicy(it) } ?: MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(404)
            }
        }
        start()
    }

    private fun open(s: MockWebServer) = OctoPrintAdapter().open(PrinterConfig(PrinterIdentity("o", "Octo", "Ender 3", PrinterFamily.OCTOPRINT, s.url("/").toString()), OctoPrintAdapter.ID, "key"))

    @Test fun statusMapsOntoTheSharedModel() = server().use { s ->
        val st = open(s).status()
        assertEquals(PrinterState.PRINTING, st.state)
        assertEquals("cube.gcode", st.job!!.fileName)
        assertEquals(0.42f, st.job!!.fraction, 0.001f)
        assertEquals(60.0, st.bed!!.target!!, 0.0)
        assertEquals("key", s.takeRequest().getHeader("X-Api-Key"))
    }

    private class PrintEvidenceFixture : AutoCloseable {
        var printing = true
        var jobPath = "parts/cube.gcode"
        var filePath = jobPath
        var history = ""","prints":{"success":4,"failure":1,"last":{"date":1000,"success":true}}"""
        var fileHash = "original-file-content-hash"
        var historyStatus = 200
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/api/printer") -> MockResponse().setBody(
                        """{"state":{"flags":{"operational":true,"printing":$printing}}}""")
                    request.path == "/api/job" -> MockResponse().setBody(
                        """{"state":"${if (printing) "Printing" else "Operational"}","job":{"file":{"origin":"local","path":"$jobPath","name":"cube.gcode"}},"progress":{"completion":100.0}}""")
                    request.path!!.startsWith("/api/files/local/") -> MockResponse().setResponseCode(historyStatus).setBody(
                        """{"origin":"local","path":"$filePath","hash":"$fileHash"$history}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }

        override fun close() = server.close()
    }

    @Test fun onlyANewExactSuccessfulPrintRecordConfirmsCompletion() = PrintEvidenceFixture().use { fixture ->
        open(fixture.server).use { session ->
            val active = session.status()
            assertEquals(PrinterState.PRINTING, active.state)
            assertEquals("parts/cube.gcode", active.job?.fileName)
            assertNull(active.completedFileName)
            fixture.printing = false
            fixture.history = ""","prints":{"success":5,"failure":1,"last":{"date":2000,"success":true}}"""
            val complete = session.status()
            assertEquals(PrinterState.FINISHED, complete.state)
            assertEquals("parts/cube.gcode", complete.completedFileName)
            assertEquals(PrinterState.FINISHED, session.status().state)
            fixture.printing = true
            assertNull(session.status().completedFileName)
        }
    }

    @Test fun staleOrUnrelatedPrintRecordsDoNotConfirmCompletion() {
        val ambiguous: List<(PrintEvidenceFixture) -> Unit> = listOf(
            {},
            { it.history = ""","prints":{"success":4,"failure":2,"last":{"date":2000,"success":false}}""" },
            { it.history = ""","prints":{"success":6,"failure":1,"last":{"date":2000,"success":true}}""" },
            { it.history = ""","prints":{"success":5,"failure":1,"last":{"date":2000,"success":true}}"""; it.fileHash = "replacement" },
            { it.history = ""","prints":{"success":5,"failure":1,"last":{"date":2000,"success":true}}"""; it.filePath = "other/cube.gcode" },
            { it.historyStatus = 403 }
        )
        for (alter in ambiguous) PrintEvidenceFixture().use { fixture ->
            open(fixture.server).use { session ->
                assertEquals(PrinterState.PRINTING, session.status().state)
                fixture.printing = false
                alter(fixture)
                val idle = session.status()
                assertEquals(PrinterState.READY, idle.state)
                assertNull(idle.completedFileName)
            }
        }
    }

    @Test fun capabilitiesAreHonestAboutWhatTheClientCanDo() {
        val c = OctoPrintAdapter.CAPABILITIES
        assertTrue(c.uploadAndStart && c.pausePrint && c.cancelPrint)
        assertFalse("no camera in this client", c.camera)
        assertFalse(c.uploadJob); assertFalse(c.vendorCloud); assertFalse(c.motion)
    }

    @Test fun supportedActionIsSentOnceAndUnsupportedOnesNeverLeave() = server().use { s ->
        val session = open(s)
        assertEquals(ActionOutcome.Accepted, session.perform(PrinterAction.Pause))
        val before = s.requestCount
        assertTrue(session.perform(PrinterAction.HomeAll) is ActionOutcome.Rejected)
        assertTrue(session.perform(PrinterAction.SetBedTemperature(60)) is ActionOutcome.Rejected)
        assertEquals(before, s.requestCount)
        assertTrue(session.upload(File("x"), "x.gcode") is UploadResult.Failed)
    }

    @Test fun aLostReplyIsUnknownNotSuccess() = server(commandPolicy = SocketPolicy.DISCONNECT_AFTER_REQUEST).use { s ->
        assertTrue(open(s).perform(PrinterAction.Pause) is ActionOutcome.Unknown)
    }
}
