package com.nozzleitall.desktop.screens

import androidx.compose.runtime.*
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.desktop.PrinterTransferBusyException
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one path from a button to the printer: prepare against the state the user sees, confirm in a dialog that names
 * the action, execute exactly once, and report the outcome in plain words. An unknown outcome blocks further commands
 * (ActionGuard) until "Check printer" has re-read the state.
 */
class ActionFlow(private val entry: PrinterEntry) {
    var pending by mutableStateOf<PreparedAction?>(null)
    var message by mutableStateOf<Pair<String, BannerKind>?>(null)
    var lastAcceptedAction by mutableStateOf<PrinterAction?>(null)
    var busy by mutableStateOf(false)

    fun request(action: PrinterAction) {
        lastAcceptedAction = null
        message = null
        val guard = entry.guard ?: run { message = "${entry.config.identity.displayName} isn't connected." to BannerKind.WARNING; return }
        pending = try { guard.prepare(action, entry.status.value) } catch (e: ActionRefused) { message = (e.message ?: "Not available now.") to BannerKind.WARNING; null }
    }

    suspend fun confirm(beforeExecute: suspend (PreparedAction) -> Unit = {},
                        executionGate: suspend (suspend () -> ActionOutcome) -> ActionOutcome = { it() }): ActionOutcome? {
        val prepared = pending ?: return null
        val guard = entry.guard ?: return null
        pending = null; busy = true
        val outcome = try { withContext(Dispatchers.IO) {
            try { executionGate {
                val preflight = try { beforeExecute(prepared); null }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { e }
                if (preflight != null) ActionOutcome.Rejected(preflight.message ?: "Start record could not be saved; nothing was sent.")
                else if (entry.guard !== guard || entry.retired) ActionOutcome.Rejected("The printer connection changed. Review the command again.")
                else try { guard.execute(prepared, guard.confirm(prepared)) }
                catch (e: ActionRefused) { ActionOutcome.Rejected(e.message ?: "Not sent.") }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { ActionOutcome.Unknown(e.message ?: "The printer outcome is unknown.") }
            } } catch (e: CancellationException) { throw e }
            catch (e: PrinterTransferBusyException) { ActionOutcome.Rejected(e.message ?: "A printer transfer is already in progress.") }
            catch (e: Exception) { ActionOutcome.Unknown(e.message ?: "The action may have reached the printer; inspect it before retrying.") }
        } } finally { busy = false }
        lastAcceptedAction = if (outcome == ActionOutcome.Accepted) prepared.action else null
        message = when (outcome) {
            ActionOutcome.Accepted -> "${Glossary.label("outcome.accepted")}: ${prepared.summary}" to BannerKind.SUCCESS
            is ActionOutcome.Rejected -> "${Glossary.label("outcome.rejected")}. ${outcome.reason}" to BannerKind.WARNING
            is ActionOutcome.Unknown -> "${Glossary.label("outcome.unknown")}. ${outcome.reason}" to BannerKind.DANGER
        }
        return outcome
    }

    suspend fun reconcile() {
        val guard = entry.guard ?: return
        lastAcceptedAction = null
        busy = true
        val reading = try {
            withContext(Dispatchers.IO) { guard.reconcile() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            busy = false
        }
        if (entry.guard !== guard || entry.retired) {
            message = "The printer connection changed while checking. Check the current connection again." to BannerKind.WARNING
            return
        }
        if (reading == null) {
            message = "The printer still can't be checked. Commands stay blocked until it answers." to BannerKind.WARNING
            return
        }
        entry.status.value = reading
        message = if (guard.needsReconcile) "The printer still can't be checked (${reading.state.label}). Commands stay blocked until it answers." to BannerKind.WARNING
            else "Checked: the printer is ${reading.state.label.lowercase()}. You can send commands again." to BannerKind.SUCCESS
    }
}

@Composable
fun rememberActionFlow(entry: PrinterEntry) = remember(entry) { ActionFlow(entry) }

@Composable
fun ActionFlowUi(flow: ActionFlow, entry: PrinterEntry, onDismiss: () -> Unit = {},
                 beforeExecute: suspend (PreparedAction) -> Unit = {},
                 onResult: (PreparedAction, ActionOutcome) -> Unit = { _, _ -> },
                 executionGate: suspend (suspend () -> ActionOutcome) -> ActionOutcome = { it() },
                 executionScope: CoroutineScope? = null) {
    val localScope = rememberCoroutineScope()
    val scope = executionScope ?: localScope
    flow.message?.let { (text, kind) ->
        val guard = entry.guard
        Banner(text, kind, if (guard?.needsReconcile == true) "Check printer" to { scope.launch(Dispatchers.Main) { flow.reconcile() } } else "Dismiss" to { flow.message = null })
    }
    flow.pending?.let { p ->
        val term = Glossary.term(p.action.glossaryId)
        ConfirmDialog(title = "${term.confirm.ifBlank { term.label }} on ${entry.config.identity.displayName}?", body = p.summary,
            confirmLabel = term.confirm.ifBlank { term.label }, destructive = term.destructive,
            detail = "Nozzle checks the printer is still ${p.reviewedState.label.lowercase()} before sending, and never repeats a command on its own.",
            cancelLabel = "Don't send",
            onConfirm = { scope.launch(Dispatchers.Main) {
                val outcome = flow.confirm(beforeExecute, executionGate)
                if (outcome != null) onResult(p, outcome)
            } }, onDismiss = { flow.pending = null; onDismiss() })
    }
}
