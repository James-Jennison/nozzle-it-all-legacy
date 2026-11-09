package com.nozzleitall.desktop.prepare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nozzleitall.desktop.settings.Choice
import com.nozzleitall.desktop.settings.DenseInput
import com.nozzleitall.desktop.settings.DenseSelect
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.project.ProjectManifest.MaterialSlot

/** Keyboard-output readers emit opaque IDs. Keep their technologies and printer-reported IDs separate. */
internal enum class SpoolReader(val label: String, private val prefix: String) {
    RFID("RFID reader", "reader:rfid:"),
    NFC("NFC reader", "reader:nfc:");

    fun tag(input: String): String? =
        input.trim().takeIf(pattern::matches)?.let { prefix + it }

    companion object {
        const val MAX_TAG_LENGTH = 96
        private val pattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,${MAX_TAG_LENGTH - 1}}")

        fun owns(tag: String?): Boolean = tag != null && entries.any { reader ->
            tag.startsWith(reader.prefix) && pattern.matches(tag.removePrefix(reader.prefix))
        }
    }
}

@Composable
internal fun SpoolReaderControls(p: PrepareState, slot: MaterialSlot, library: List<FilamentLibrary.Entry>) {
    var open by remember(slot.slot) { mutableStateOf(false) }
    if (!open) {
        NzButton("Map spool from reader", { open = true }, kind = ButtonKind.QUIET)
        return
    }
    var reader by remember(slot.slot) { mutableStateOf(SpoolReader.RFID) }
    var input by remember(slot.slot, reader) { mutableStateOf("") }
    var tooLong by remember(slot.slot, reader) { mutableStateOf(false) }
    val tag = if (tooLong) null else reader.tag(input)
    val remembered = tag?.let(p::readerProfile)?.let { id -> library.firstOrNull { it.id == id } }
    var chosen by remember(slot, tag, remembered) { mutableStateOf(remembered?.id) }
    val entry = library.firstOrNull { it.id == chosen }
    Column(Modifier.fillMaxWidth()) {
        DenseSelect("Spool reader", SpoolReader.entries.map { Choice(it.name, it.label) }, reader.name,
            Modifier.fillMaxWidth()) { reader = SpoolReader.valueOf(it) }
        Txt("Focus the tag field and scan with a reader that types the tag ID.", Nz.type.bodySmall)
        Txt("Reader mappings apply to local slots. Live printer reports take precedence when available.", Nz.type.bodySmall)
        DenseInput(input, { value ->
            tooLong = value.length > SpoolReader.MAX_TAG_LENGTH
            input = value.take(SpoolReader.MAX_TAG_LENGTH + 1)
        }, "Reader tag ID", Modifier.fillMaxWidth(), error = tooLong)
        if (input.isNotBlank() && tag == null) {
            Txt(if (tooLong) "Tag ID is too long." else "Unsupported tag ID.", Nz.type.bodySmall, Nz.colors.danger)
        }
        if (tag != null) {
            Txt(if (remembered == null) "Unknown tag. Choose the spool’s slicing profile."
                else "Remembered profile: ${remembered.displayName}", Nz.type.bodySmall)
            GroupedFilamentPicker(slot.slot, entry?.displayName ?: "Choose filament profile", library,
                chosen.orEmpty(), Modifier.fillMaxWidth()) { chosen = it }
            entry?.let { profile ->
                val previous = library.firstOrNull { it.id == slot.filamentProfile }?.displayName ?: "Printer profile default"
                Txt("Slice profile: $previous → ${profile.displayName}", Nz.type.bodySmall)
                Txt("The profile choice is remembered on this computer. Save the project to keep the slot assignment.", Nz.type.bodySmall)
            }
            NzButton("Apply reader mapping to slot ${slot.slot}", {
                if (entry != null) p.notice = p.applyReaderSpool(slot, tag, entry.id)
            }, enabled = entry != null, kind = ButtonKind.SECONDARY)
        }
        NzButton("Close reader", { open = false }, kind = ButtonKind.QUIET)
    }
}
