package com.nozzleitall.desktop.prepare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.project.Mesh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

internal data class ReviewedGeometry(
    val taskId: String,
    val original: Mesh,
    val repaired: Mesh,
    val repair: ModelingOps.RepairPreview,
    val credits: Int?,
    val sizeMm: Double,
)

@Composable
internal fun MeshyGenerationDialog(prepare: PrepareState, onDismiss: () -> Unit) {
    val colors = Nz.colors
    val scope = rememberCoroutineScope()
    val provider = remember { MeshyProvider() }
    val store = prepare.generatedGeometry
    val keychain = remember { MeshySecretService() }
    var pending by remember { mutableStateOf<GeneratedGeometryStore.Pending?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("80") }
    var quote by remember { mutableStateOf<Pair<String, Double>?>(null) }
    var resumeId by remember { mutableStateOf("") }
    var model by remember { mutableStateOf<ReviewedGeometry?>(null) }
    var useRepair by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val size = target.toDoubleOrNull()?.takeIf { it in 1.0..500.0 }

    suspend fun refresh() {
        pending = runInterruptible(Dispatchers.IO) { store.pending() }
        loaded = true
    }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = null
        message = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: GeometryGenerationError) { failure = error.message }
            catch (_: Exception) { failure = "The Meshy operation could not be completed." }
            finally { busy = false }
        }
    }

    suspend fun finishTask(id: String, sizeMm: Double, existingKey: CharArray? = null) {
        val cached = runInterruptible(Dispatchers.IO) { store.read(id) }
        val asset = cached ?: run {
            val key = existingKey ?: runInterruptible(Dispatchers.IO) { keychain.load() }
            try {
                withTimeoutOrNull(240_000) {
                    var task = runInterruptible(Dispatchers.IO) { provider.status(id, key) }
                    while (task.status == GeometryTask.Status.PENDING || task.status == GeometryTask.Status.IN_PROGRESS) {
                        message = "Meshy task " + task.progress + "%"
                        delay((task.retryAfterSeconds ?: 5).coerceIn(1, 240) * 1000)
                        task = runInterruptible(Dispatchers.IO) { provider.status(id, key) }
                    }
                    if (task.status != GeometryTask.Status.SUCCEEDED)
                        throw GeometryGenerationError("Meshy reported that this task did not complete.")
                    GeneratedGeometryStore.Asset(id,
                        runInterruptible(Dispatchers.IO) { provider.download(task) }, task.consumedCredits)
                } ?: throw GeometryGenerationError("Local waiting stopped. Resume the same task to check its status.")
            } finally { if (existingKey == null) key.fill('\u0000') }
        }
        model = runInterruptible(Dispatchers.IO) { prepare.inspectMeshy(asset, sizeMm) }
        useRepair = true
        message = "Model downloaded and checked locally."
    }

    LaunchedEffect(store) {
        try { refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = "The local Meshy request receipt could not be read." }
    }

    DialogWindow(onCloseRequest = onDismiss, title = "Meshy 3D generation",
        state = rememberDialogState(width = 660.dp, height = 700.dp)) {
        Column(Modifier.fillMaxSize().background(colors.surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("Meshy text-to-3D", Nz.type.title)
                Txt("Save your own Meshy key in the Linux system keychain. The dialog shows the generation cost before submitting.",
                    Nz.type.bodySmall, colors.textMuted)
                Field("Meshy API key", keyInput, { keyInput = it.take(512) }, Modifier.fillMaxWidth(), secret = true)
                NzButton("Save key in system keychain", {
                    val key = keyInput.toCharArray()
                    keyInput = ""
                    perform {
                        try { runInterruptible(Dispatchers.IO) { keychain.save(key) } }
                        finally { key.fill('\u0000') }
                        message = "Meshy key saved in the system keychain."
                    }
                }, enabled = !busy && keyInput.isNotBlank(), kind = ButtonKind.SECONDARY)
                if (loaded && pending == null && model == null && quote == null) {
                    Field("Describe a model", prompt, { prompt = it.take(1600) }, Modifier.fillMaxWidth())
                }
                if (model == null && quote == null) Field("Longest dimension after local import (mm)", target, { target = it.take(12) }, Modifier.fillMaxWidth())
                if (loaded && pending == null && model == null && quote == null) {
                    NzButton("Review generation", {
                        if (size != null) quote = prompt.trim() to size
                    }, enabled = !busy && size != null && prompt.isNotBlank() && prompt.codePointCount(0, prompt.length) <= 800,
                        kind = ButtonKind.SECONDARY)
                }
                quote?.let { (approvedPrompt, approvedSize) ->
                    Card(Modifier.fillMaxWidth()) {
                        Txt("Provider " + provider.name + " · Model " + provider.model, Nz.type.label)
                        Txt("Estimated cost " + provider.estimatedCredits + " Meshy API credits", Nz.type.body)
                        Txt("Meshy (api.meshy.ai) receives the shown prompt and generation options. Authentication uses your saved Meshy key. Model files, images, print settings and the local import size stay on this computer.", Nz.type.bodySmall)
                        Txt(approvedPrompt, Nz.type.body)
                        Txt("Mesh preview · remeshed triangles · STL · target 30,000 faces", Nz.type.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NzButton("Back", { quote = null }, enabled = !busy, kind = ButtonKind.QUIET)
                            NzButton("Confirm and generate", {
                                quote = null
                                perform {
                                    val key = runInterruptible(Dispatchers.IO) { keychain.load() }
                                    try {
                                        val receipt = runInterruptible(Dispatchers.IO) { store.begin() }
                                        pending = receipt
                                        val id = runInterruptible(Dispatchers.IO) {
                                            val task = provider.submit(approvedPrompt, key)
                                            store.attach(receipt, task).taskId ?: error("Missing task")
                                        }
                                        refresh()
                                        finishTask(id, approvedSize, key)
                                    } finally { key.fill('\u0000') }
                                }
                            }, enabled = !busy && pending == null, kind = ButtonKind.PRIMARY, testTag = "meshy-confirm-generate")
                        }
                    }
                }
                pending?.let { receipt ->
                    if (model == null) Txt("If a local copy is unavailable, resume checks the task ID with Meshy (api.meshy.ai) and downloads the generated STL from assets.meshy.ai.", Nz.type.bodySmall)
                    if (model == null && receipt.taskId != null) {
                        Txt("Saved Meshy task " + receipt.taskId, Nz.type.bodySmall)
                        NzButton("Resume saved task", {
                            if (size != null) perform { finishTask(receipt.taskId, size) }
                        }, enabled = !busy && size != null, kind = ButtonKind.SECONDARY)
                    } else if (model == null) {
                        Txt("A request was recorded locally before Meshy returned its task ID. Check the Meshy account before starting a new generation.",
                            Nz.type.bodySmall, colors.textMuted)
                        Field("Existing Meshy task ID", resumeId, { resumeId = it.take(128) }, Modifier.fillMaxWidth())
                        NzButton("Resume existing task", {
                            if (size != null) perform {
                                val id = MeshyProvider.taskId(resumeId.trim())
                                runInterruptible(Dispatchers.IO) { store.attach(receipt, id) }
                                refresh()
                                finishTask(id, size)
                            }
                        }, enabled = !busy && size != null && resumeId.isNotBlank(), kind = ButtonKind.SECONDARY)
                    }
                    if (model == null) NzButton("Clear local receipt after checking Meshy", {
                        perform {
                            runInterruptible(Dispatchers.IO) { store.clear(receipt, "owner-cleared") }
                            refresh()
                        }
                    }, enabled = !busy, kind = ButtonKind.QUIET)
                }
                model?.let { reviewed ->
                    Txt("Local model review", Nz.type.title)
                    reviewed.credits?.let { Txt("Meshy reported " + it + " consumed credits.", Nz.type.bodySmall) }
                    Txt("Original open edges " + reviewed.repair.before.openEdges + " · Repaired open edges " +
                        reviewed.repair.after.openEdges, Nz.type.body)
                    Toggle("Use the locally repaired mesh", useRepair, { useRepair = it })
                    val mesh = if (useRepair) reviewed.repaired else reviewed.original
                    val camera = remember(reviewed) { ViewCamera().apply { distance = 260f } }
                    val preview = remember(mesh, colors.accent) {
                        PlateObject(0, "Meshy preview", mesh, colors.accent, 60f, 60f, 0f,
                            90f / reviewed.sizeMm.toFloat(), false)
                    }
                    PlateViewer(120f, 120f, listOf(preview), camera, colors.surfaceSunken, colors.line,
                        Modifier.fillMaxWidth().height(180.dp), onSelect = {})
                    NzButton("Add to active plate", {
                        val receipt = pending ?: return@NzButton
                        if (receipt.taskId != reviewed.taskId) return@NzButton
                        val repaired = useRepair
                        perform {
                            if (runInterruptible(Dispatchers.IO) { store.pending() } != receipt)
                                throw GeometryGenerationError("The saved task changed. Resume the current task.")
                            prepare.addMeshyModel(reviewed, repaired)
                            model = null
                            message = "Model added to the active plate."
                            runInterruptible(Dispatchers.IO) { store.clear(receipt, "imported") }
                            refresh()
                        }
                    }, enabled = !busy, kind = ButtonKind.PRIMARY, testTag = "meshy-import-validated")
                }
                message?.let { Txt(it, Nz.type.bodySmall, colors.textMuted) }
                failure?.let { Txt(it, Nz.type.bodySmall, colors.danger) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                NzButton(if (busy) "Close local view" else "Close", { keyInput = ""; onDismiss() }, kind = ButtonKind.QUIET)
            }
        }
    }
}
