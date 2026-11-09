package com.nozzleitall.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import org.json.JSONObject
import java.io.File

internal class CommandShortcuts(private val file: File) {
    private val loaded = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        val saved = JSONObject(file.readText())
        saved.keys().asSequence().associateWith { id ->
            saved.getString(id).also { require(it.length == 1 && it.single() in 'A'..'Z') }
        }
    }
    val readProblem: String? = if (loaded.isFailure)
        "Shortcuts could not be loaded. Repair or move command-shortcuts.json in the config folder and restart."
    else null
    private var assignments by mutableStateOf(loaded.getOrDefault(emptyMap()))

    fun letter(id: String): String? = assignments[id]

    fun applyTo(defaults: List<AppMenu>): List<AppMenu> {
        val used = defaults.flatMap { it.items }.mapNotNull { it?.shortcut?.takeIf { key -> key.ctrl && key.shift }?.key }.toMutableSet()
        return defaults.map { menu ->
            AppMenu(menu.title, menu.mnemonic, menu.items.map { action ->
                val letter = assignments[action?.commandId]
                val key = letter?.let { letters[it.single() - 'A'] }
                if (action == null || key == null || !used.add(key)) action
                else MenuAction(action.label, Shortcut(key, ctrl = true, shift = true, text = "Ctrl+Shift+$letter",
                    afterFocus = true), action.enabled, action.commandId, action.run)
            })
        }
    }

    fun assign(id: String, letter: String?, defaults: List<AppMenu>): String? {
        readProblem?.let { return it }
        if (letter != null) {
            if (letter.length != 1 || letter.single() !in 'A'..'Z') return "Enter one letter."
            val key = letters[letter.single() - 'A']
            val reserved = defaults.flatMap { it.items }.filterNotNull().any {
                it.shortcut?.let { shortcut -> shortcut.key == key && shortcut.ctrl && shortcut.shift } == true
            }
            if (reserved || assignments.any { (other, assigned) -> other != id && assigned == letter })
                return "Ctrl+Shift+$letter is already assigned."
        }
        val next = if (letter == null) assignments - id else assignments + (id to letter)
        return runCatching {
            PrinterStore.atomicWrite(file, JSONObject(next).toString(2), private = true)
            assignments = next
            null
        }.getOrElse { "Could not save shortcuts." }
    }

    private companion object {
        val letters = listOf(Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J,
            Key.K, Key.L, Key.M, Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z)
    }
}
