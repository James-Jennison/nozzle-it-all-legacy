package com.nozzleitall.desktop.prepare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.nozzleitall.desktop.settings.DenseInput
import com.nozzleitall.desktop.ui.*
import java.nio.file.FileAlreadyExistsException

@Composable
internal fun CadPluginPanel(store: CadPluginStore, onUse: (CadRecipe) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (!open) {
        NzButton("CAD plugins", { open = true }, kind = ButtonKind.QUIET, testTag = "cad-plugins")
        return
    }
    var inventory by remember(store) { mutableStateOf(runCatching(store::inventory)) }
    var pending by remember(store) { mutableStateOf<PendingPlugin?>(null) }
    var catalogOpen by remember(store) { mutableStateOf(false) }
    var notice by remember(store) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Txt("CAD plugins", Nz.type.label)
        Txt("Recipe plugins fill editable CAD dimensions. Review the recipe before adding a model to the plate.", Nz.type.bodySmall)
        if (inventory.isFailure) Txt("The local plugin list could not be read.", Nz.type.bodySmall, Nz.colors.danger)
        inventory.getOrNull()?.let { list ->
            if (list.installed.isEmpty()) Txt("No CAD plugins installed.", Nz.type.bodySmall)
            if (list.unreadable > 0) Txt("${list.unreadable} plugin files could not be read.", Nz.type.bodySmall, Nz.colors.danger)
            list.installed.forEach { installed ->
                val plugin = installed.plugin
                Txt("${plugin.name} · v${plugin.release}", Nz.type.label)
                val enabled = installed.enabled
                Row {
                    NzButton("Load recipe", {
                        runCatching { store.load(installed) }.onSuccess {
                            onUse(it)
                            notice = null
                        }.onFailure {
                            notice = pluginStorageMessage(it, "The plugin could not be loaded.")
                            inventory = runCatching(store::inventory)
                        }
                    }, enabled = enabled, kind = ButtonKind.SECONDARY)
                    NzButton(if (enabled) "Disable plugin" else "Enable plugin", {
                        runCatching { store.setEnabled(installed, !enabled) }.onSuccess {
                            notice = if (enabled) "Plugin disabled." else "Plugin enabled."
                        }.onFailure {
                            notice = pluginStorageMessage(it, "The plugin state could not be changed.")
                        }
                        inventory = runCatching(store::inventory)
                    }, kind = ButtonKind.QUIET)
                }
            }
        }
        NzButton("Review local plugin…", {
            chooseFiles("Review local plugin", listOf("niaplugin"), false).firstOrNull()?.let { file ->
                val result = runCatching {
                    val bytes = CadPlugin.readBytes(file)
                    PendingPlugin(CadPlugin.decode(bytes), bytes, CadPlugin.sha256(bytes))
                }
                pending = result.getOrNull()
                catalogOpen = false
                notice = if (result.isFailure) "The file is not a supported CAD plugin." else null
            }
        }, kind = ButtonKind.SECONDARY)
        NzButton(if (catalogOpen) "Close marketplace" else "Browse bundled marketplace", {
            catalogOpen = !catalogOpen
            pending = null
            notice = null
        }, kind = ButtonKind.SECONDARY, testTag = "cad-plugin-marketplace")
        if (catalogOpen) CadPluginMarketplace(inventory.getOrNull()?.installed.orEmpty()) { listing ->
            pending = PendingPlugin(listing.plugin, listing.packageBytes, listing.digest)
            catalogOpen = false
            notice = null
        }
        pending?.let { selected ->
            val plugin = selected.plugin
            val recipe = plugin.recipe
            Txt("${plugin.name} · ${plugin.id} · v${plugin.release}", Nz.type.label)
            val sketch = if (recipe.circular) "Circle · ${recipe.widthMm} mm diameter"
                else "Rectangle · ${recipe.widthMm} × ${recipe.heightMm} mm"
            Txt("$sketch · ${recipe.depthMm} mm extrusion", Nz.type.bodySmall)
            if (recipe.holeMm > 0) Txt("Through hole · ${recipe.holeMm} mm diameter", Nz.type.bodySmall)
            NzButton("Install reviewed plugin", {
                runCatching {
                    store.install(plugin, selected.packageBytes, selected.digest)
                }.onSuccess {
                    notice = "Plugin installed."
                    pending = null
                    inventory = runCatching(store::inventory)
                }.onFailure { notice = pluginStorageMessage(it, "The plugin could not be installed.") }
            }, kind = ButtonKind.PRIMARY)
            NzButton("Cancel", { pending = null }, kind = ButtonKind.QUIET)
        }
        notice?.let { Txt(it, Nz.type.bodySmall) }
        NzButton("Close plugins", { open = false }, kind = ButtonKind.QUIET)
    }
}

private fun pluginStorageMessage(failure: Throwable, fallback: String): String = when (failure) {
    is FileAlreadyExistsException -> "A plugin with this ID is already installed."
    is PluginStorageProblem -> failure.message ?: fallback
    else -> fallback
}

private data class PendingPlugin(val plugin: CadPlugin, val packageBytes: ByteArray, val digest: String)

@Composable
private fun CadPluginMarketplace(installed: List<CadPluginStore.Installed>, onReview: (CadPluginCatalog.Listing) -> Unit) {
    val catalog = remember { runCatching { CadPluginCatalog.load() } }
    var query by remember { mutableStateOf("") }
    Txt("Bundled plugin marketplace", Nz.type.label)
    Txt("NIA Desktop includes this catalog for offline browsing and installation.", Nz.type.bodySmall)
    if (catalog.isFailure) {
        Txt("The bundled catalog could not be verified.", Nz.type.bodySmall, Nz.colors.danger)
        return
    }
    DenseInput(query, { query = it.take(80) }, "Search marketplace", Modifier.fillMaxWidth())
    val matching = catalog.getOrThrow().filter { it.plugin.name.contains(query.trim(), ignoreCase = true) ||
        it.summary.contains(query.trim(), ignoreCase = true) }
    if (matching.isEmpty()) Txt("No matching plugins.", Nz.type.bodySmall)
    matching.forEach { listing ->
        val plugin = listing.plugin
        val current = installed.firstOrNull { it.plugin.id == plugin.id }
        Txt("${plugin.name} · v${plugin.release}", Nz.type.label)
        Txt(listing.summary, Nz.type.bodySmall)
        if (current?.digest == listing.digest) Txt(if (current.enabled) "Installed" else "Installed and disabled", Nz.type.bodySmall)
        else if (current != null) Txt("This ID is installed with different content.", Nz.type.bodySmall)
        else NzButton("Review ${plugin.name}", { onReview(listing) }, kind = ButtonKind.SECONDARY)
    }
}
