package com.nozzleitall.desktop.prepare

/** One thing worth knowing before you slice, with the one-click fix when there is one. */
data class Advice(val id: String, val text: String, val action: String? = null, val actionLabel: String? = null)

/**
 * The problems the app can see on the plate before a slice, said plainly. Pure rules so they are easy to test; the screen only draws them.
 * Nothing here blocks slicing: these are notices, and each can be dismissed.
 */
object PrepareAdvice {
    const val ARRANGE = "arrange"
    const val CHOOSE_PRINTER = "choose-printer"
    const val FIND_PRINTER = "find-printer"
    const val SET_UP_MACHINE = "set-up-machine"

    class Input(
        /** Names of objects that hang off the plate. */
        val offPlate: List<String>,
        /** True when a printer (or an export-only target) has been chosen. */
        val hasPrinter: Boolean,
        /** Object name to its count of open edges (a mesh that isn't closed may print with holes). */
        val openEdges: Map<String, Int>,
        val objectCount: Int,
        /** True when at least one printer has been added to the app (false on a first run). */
        val anyPrinterSaved: Boolean = true,
        /** True after an offline slicing machine/profile has deliberately been installed or imported. */
        val hasMachine: Boolean = true,
    )

    fun compute(i: Input): List<Advice> {
        if (i.objectCount == 0) return emptyList()
        val out = ArrayList<Advice>()
        if (!i.hasMachine)
            out += Advice("no-machine", "Choose a machine, nozzle and toolhead setup before slicing.", SET_UP_MACHINE, "Set up machine")
        if (i.offPlate.isNotEmpty())
            out += Advice("off-plate", (if (i.offPlate.size == 1) "${i.offPlate[0]} hangs off the plate." else "${i.offPlate.size} objects hang off the plate.") + " Anything outside the bed can't print.", ARRANGE, "Arrange")
        val open = i.openEdges.filterValues { it > 0 }
        if (open.isNotEmpty()) {
            val worst = open.maxByOrNull { it.value }!!
            out += Advice("open-edges", (if (open.size == 1) "${worst.key} isn't a closed shape (${worst.value} open edges)" else "${open.size} objects aren't closed shapes (worst: ${worst.key}, ${worst.value} open edges)") +
                ". It may print with holes: repair the mesh before printing.")
        }
        // A physical connection is optional while preparing and slicing. Send and Monitor own that prerequisite;
        // repeating it here conflates the machine preset with the destination and duplicates the connection control.
        return out
    }
}
