package com.nozzleitall.desktop

import com.nozzleitall.desktop.screens.ActionFlow
import com.nozzleitall.printer.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ActionFlowTest {
    private class FakeSession : PrinterSession {
        override val identity = PrinterIdentity("p", "Test", "Test", PrinterFamily.KLIPPER, "http://127.0.0.1:1")
        override val capabilities = Capabilities(pausePrint = true)
        var performed = 0
        override fun status() = PrinterStatus(PrinterState.PRINTING, ConnectionRoute.LAN, observedAtMillis = 1000L)
        override fun cameras() = emptyList<CameraEndpoint>()
        override fun snapshot(camera: CameraEndpoint) = ByteArray(0)
        override fun upload(file: File, remoteName: String, progress: UploadProgress) = UploadResult.Failed("unused")
        override fun perform(action: PrinterAction): ActionOutcome { performed++; return ActionOutcome.Accepted }
        override fun close() { }
    }

    @Test fun busyTransferRejectsBeforeDispatchAndCancellationPropagates() = runBlocking {
        val session = FakeSession()
        val entry = PrinterEntry(PrinterConfig(session.identity, "fake"))
        entry.session = session
        entry.guard = ActionGuard(session) { 1000L }
        entry.status.value = session.status()
        val flow = ActionFlow(entry)
        flow.request(PrinterAction.Pause)
        val busy = flow.confirm(executionGate = { throw PrinterTransferBusyException("Wait for the transfer.") })
        assertTrue(busy is ActionOutcome.Rejected)
        assertEquals(0, session.performed)
        assertFalse(flow.busy)
        flow.request(PrinterAction.Pause)
        assertThrows(CancellationException::class.java) {
            runBlocking { flow.confirm(executionGate = { throw CancellationException("cancelled") }) }
        }
        assertEquals(0, session.performed)
        assertFalse(flow.busy)
    }
}
