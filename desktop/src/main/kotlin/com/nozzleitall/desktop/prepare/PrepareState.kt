package com.nozzleitall.desktop.prepare

import com.nozzleitall.printer.ext.MixedFilamentFormat
import net.jamesjennison.klippercompanion.FlushVolumes
import androidx.compose.runtime.*
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.desktop.SlicingSetup
import com.nozzleitall.printer.*
import com.nozzleitall.project.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.*

/** The engine key the user's own flushing-volume matrix is kept under in the project's settings. */
private const val FLUSH_KEY = "flush_volumes_matrix"

/** Values from one editor generation, before asynchronous preset resolution or engine dispatch. */
private data class SliceInputSnapshot(
    val project: Project3mf,
    val preset: QualityPreset,
    val supports: Boolean,
    val infill: Int,
    val materials: List<ProjectManifest.MaterialSlot>,
    val applyPreset: Boolean,
    val flush: List<Int>?,
    val overrides: Map<String, String>,
    val mixRows: List<MixedFilamentFormat.Row>,
    val physical: List<Pair<String, String?>>,
    val mixSublayers: Boolean,
    val presetSelection: PrepareState.PresetSelection,
)

/**
 * An object on the plate. [slot] is what it prints with where it isn't painted. A painted model keeps the file's own
 * filament numbers in its paint; [paintSlots] (entry N-1 for the file's filament N) says which slot each one prints with,
 * and [sources] describes those filaments as the file had them.
 */
class PrepItem(val id: Int, name: String, val mesh: Mesh, x: Float, y: Float, rotZ: Float = 0f, scale: Float = 1f, slot: Int = 1,
               paintSlots: List<Int> = emptyList(), val sources: List<SourceFilament> = emptyList(), settings: Map<String, String> = emptyMap(),
               plate: Int = 0) {
    /** Per-axis size on top of [scale] (Orca's unlocked scale fields); a negative one mirrors that axis. */
    var sx by mutableStateOf(1f); var sy by mutableStateOf(1f); var sz by mutableStateOf(1f)
    /** Extra volumes: negative volumes, modifiers, support blockers / enforcers, parts (coordinates from the mesh's bounding-box centre). */
    val parts = androidx.compose.runtime.mutableStateListOf<ProjectManifest.ObjectPart>()
    /** Height range modifiers: settings for layers between two heights. */
    val ranges = androidx.compose.runtime.mutableStateListOf<ProjectManifest.HeightRange>()
    /** A variable layer height profile, z / height pairs; empty = the process's layer height. */
    val layerProfile = androidx.compose.runtime.mutableStateListOf<Double>()
    val ex get() = scale * sx; val ey get() = scale * sy; val ez get() = scale * sz
    /** Takes [from]'s size, mirroring, parts, height ranges and layer profile (a copy or a re-meshed object keeps them). */
    fun copyShapeFrom(from: PrepItem) {
        sx = from.sx; sy = from.sy; sz = from.sz
        parts.clear(); parts.addAll(from.parts); ranges.clear(); ranges.addAll(from.ranges); layerProfile.clear(); layerProfile.addAll(from.layerProfile)
    }
    /** Whether the object has a variable layer height, by profile or by height ranges (the mixed sub-layer warning's question). */
    val hasVariableLayer get() = layerProfile.size > 4 || ranges.isNotEmpty()
    /** The plate it's on (0-based); x and y are on that plate. */
    var plate by mutableStateOf(plate)
    /** The object's own print settings (Orca's per-object settings), engine keys to serialized values. */
    val settings = androidx.compose.runtime.mutableStateMapOf<String, String>().apply { putAll(settings) }
    var name by mutableStateOf(name); var x by mutableStateOf(x); var y by mutableStateOf(y)
    var rotZ by mutableStateOf(rotZ); var scale by mutableStateOf(scale); var slot by mutableStateOf(slot)
    val paintSlots = androidx.compose.runtime.mutableStateListOf<Int>().apply { addAll(paintSlots) }
    /** The file's filament for unpainted areas, when the file said (a model from another slicer). */
    var ownFilament: Int? = null
    /** The object this is an instance of (Orca's instances), or null for an object in its own right. */
    var instanceOf by mutableStateOf<Int?>(null)
    /** The file's filament numbers this model's paint uses, in order. */
    val painted: List<Int> by lazy { MeshIO.paintedFilaments(mesh) }
    /** The slot the file's filament [n] prints with. */
    fun slotFor(n: Int): Int = if (n == 0) slot else paintSlots.getOrNull(n - 1) ?: slot
    val bounds = mesh.bounds()
    /** The outline seen from above (convex hull, centred on the mesh's footprint centre), so rotation is measured exactly. */
    private val outline: FloatArray by lazy { hull2d(mesh.vertices, (bounds[0] + bounds[3]) / 2, (bounds[1] + bounds[4]) / 2) }
    /** How far the object reaches from (x, y) at its current turn and scale: min x, max x, min y, max y. */
    val reach: FloatArray get() {
        val cr = cos(Math.toRadians(rotZ.toDouble())).toFloat(); val sr = sin(Math.toRadians(rotZ.toDouble())).toFloat()
        val r = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in outline.indices step 2) { val ox = outline[i] * ex; val oy = outline[i + 1] * ey; val px = ox * cr - oy * sr; val py = ox * sr + oy * cr
            r[0] = min(r[0], px); r[1] = max(r[1], px); r[2] = min(r[2], py); r[3] = max(r[3], py) }
        return r
    }
    val footprintW get() = reach.let { it[1] - it[0] }
    val footprintD get() = reach.let { it[3] - it[2] }
    val height get() = (bounds[5] - bounds[2]) * kotlin.math.abs(ez)

    /** The mesh's volume in mm³ before scaling (signed tetrahedra; a closed mesh's is exact). */
    val rawVolume: Double by lazy {
        val v = mesh.vertices; val t = mesh.triangles; var sum = 0.0
        for (i in t.indices step 3) {
            val a = t[i] * 3; val b = t[i + 1] * 3; val c = t[i + 2] * 3
            sum += (v[a] * (v[b + 1] * v[c + 2] - v[b + 2] * v[c + 1]) - v[a + 1] * (v[b] * v[c + 2] - v[b + 2] * v[c]) + v[a + 2] * (v[b] * v[c + 1] - v[b + 1] * v[c])).toDouble()
        }
        kotlin.math.abs(sum / 6.0)
    }
    val volume get() = rawVolume * kotlin.math.abs(ex * ey * ez)

    /** Edges used by only one triangle (vertices at the same place counted as one): 0 for a closed, printable surface. */
    val openEdges: Int by lazy {
        val canonical = HashMap<Triple<Float, Float, Float>, Int>()
        val same = IntArray(mesh.vertexCount) { i -> canonical.getOrPut(Triple(mesh.vertices[i * 3], mesh.vertices[i * 3 + 1], mesh.vertices[i * 3 + 2])) { i } }
        val count = HashMap<Long, Int>()
        val t = mesh.triangles
        for (f in 0 until mesh.triangleCount) for (e in 0..2) {
            val a = same[t[f * 3 + e]]; val b = same[t[f * 3 + (e + 1) % 3]]
            val k = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
            count[k] = (count[k] ?: 0) + 1
        }
        count.values.count { it == 1 }
    }

    /** Placement as a 3MF transform: centre the mesh on its own footprint, drop it onto the bed, rotate about Z, scale, move. */
    fun placement(): Transform {
        val mx = (bounds[0] + bounds[3]) / 2.0; val my = (bounds[1] + bounds[4]) / 2.0; val mz = bounds[2].toDouble()
        val cr = cos(Math.toRadians(rotZ.toDouble())); val sr = sin(Math.toRadians(rotZ.toDouble()))
        val kx = ex.toDouble(); val ky = ey.toDouble(); val kz = ez.toDouble()
        val tx = -(mx * kx * cr - my * ky * sr) + x; val ty = -(mx * kx * sr + my * ky * cr) + y
        val tz = -(if (kz >= 0) mz else bounds[5].toDouble()) * kz   // a mirrored height sits on its other end
        return Transform(doubleArrayOf(cr * kx, sr * kx, 0.0, -sr * ky, cr * ky, 0.0, 0.0, 0.0, kz, tx, ty, tz))
    }
}

data class SlicedPlateContext(
    val plateId: String,
    val draftQueueId: String,
    val plateName: String,
    val machine: String,
    val nozzles: String,
    val bed: String,
)

sealed class SliceState {
    object Idle : SliceState()
    data class Running(val progress: Float, val stage: String, val batch: Boolean) : SliceState()
    data class Done(val result: SliceOutcome.Done, val preview: GcodePreview?,
                    val savedProjectId: String? = null, val savedProjectRevision: Long? = null,
                    val savedPlateId: String? = null, val targetProfileId: String? = null,
                    val materials: List<ProjectManifest.MaterialSlot> = emptyList(),
                    val plateContext: SlicedPlateContext? = null) : SliceState() {
        val materialPlan by lazy { materialPlanFor(this) }
    }
    data class Failed(val message: String, val cause: GuidanceFailureKind = GuidanceFailureKind.SLICE) : SliceState() {
        internal val diagnosisEvent = Any()
    }
    object Cancelled : SliceState()
}

/** Everything the Prepare workflow edits. Saved as a canonical 3MF + manifest, so Android and the Web App open it. */
class PrepareState(private val app: AppState) {
    val items = mutableStateListOf<PrepItem>()
    /** Objects hidden from the editor viewport. They remain in the project and are still sliced. */
    private val hiddenObjectIds = mutableStateListOf<Int>()
    var selectedPart by mutableIntStateOf(-1); private set
    var selectedIds by mutableStateOf<Set<Int>>(emptySet()); private set
    private var primarySelection by mutableStateOf<Int?>(null)
    /** Primary object for single-object tools; assigning it replaces the selection. */
    var selected: Int?
        get() = primarySelection
        set(value) { setSelection(value?.let { setOf(it) }.orEmpty(), value) }
    private var selectionAnchor: Int? = null

    private fun setSelection(ids: Set<Int>, primary: Int? = ids.lastOrNull()) {
        val previousPrimary = primarySelection
        val previousIds = selectedIds
        selectedIds = ids.intersect(plateItems().map { it.id }.toSet())
        primarySelection = primary?.takeIf { it in selectedIds } ?: selectedIds.lastOrNull()
        if (selectedIds != previousIds || primarySelection != previousPrimary) discardMeshEditPreview()
        if (primarySelection != previousPrimary) { lastMeasurement = null; selectedPart = -1 }
        selectionAnchor = primarySelection
        if (selectedIds.size != 1) stopPainting()
        if (aiItemId != null && (selectedIds.size != 1 || selected != aiItemId)) aiDiscard()
        history.selectionChanged()
    }
    fun selectedItems() = plateItems().filter { it.id in selectedIds }
    fun objectVisible(id: Int) = id !in hiddenObjectIds
    fun toggleObjectVisibility(id: Int) {
        if (id in hiddenObjectIds) hiddenObjectIds.remove(id) else {
            hiddenObjectIds.add(id)
            if (selected == id) selectedPart = -1
        }
    }
    fun selectPart(objectId: Int, partIndex: Int) {
        selectObject(objectId)
        selectedPart = partIndex
    }
    fun selectAll() = setSelection(plateItems().map { it.id }.toSet(), selected)
    fun deselect() = setSelection(emptySet())
    /** Ctrl toggles; Shift selects a range in list order, or adds an object on the plate. */
    fun selectObject(id: Int?, toggle: Boolean = false, range: Boolean = false, listRange: Boolean = false) {
        selectedPart = -1
        if (id == null) { if (!toggle && !range) deselect(); return }
        val order = plateItems().map { it.id }
        if (id !in order) return
        val anchor = selectionAnchor
        val ids = when {
            range && listRange && anchor in order -> {
                val a = order.indexOf(anchor); val b = order.indexOf(id)
                val span = order.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
                if (toggle) selectedIds + span else span
            }
            range -> selectedIds + id
            toggle -> if (id in selectedIds) selectedIds - id else selectedIds + id
            else -> setOf(id)
        }
        setSelection(ids, id)
        selectionAnchor = if (range) anchor ?: id else id
    }
    var file by mutableStateOf<File?>(null)
    /** The plate's camera, here (not in the screen) so the View menu can set it. */
    val camera = ViewCamera()
    var manifest by mutableStateOf<ProjectManifest?>(null)
    var name by mutableStateOf("Untitled project")
    private var dirtyFlag by mutableStateOf(false)
    /** Changes whenever the editor content is replaced or edited, including another project opening. */
    var editorGeneration by mutableStateOf(0L)
        private set
    /** Setting it true is what every edit does: that also records the change for Undo ([history]). */
    var dirty: Boolean
        get() = dirtyFlag
        set(v) { dirtyFlag = v; editorGeneration++; if (v) history.recordChange() }
    val history = PlateHistory(this)
    var passthrough: Map<String, ByteArray> = emptyMap()
    var metadata: Map<String, String> = emptyMap()
    var printerId by mutableStateOf<String?>(null)
    /** The slicing profile. Independent of any printer connection: every bundled profile can be sliced and exported. */
    var profileId by mutableStateOf(ProfileCatalog.DEFAULT_ID)
    // This participates directly in Compose readiness and advice. Keep it observable even when the
    // installed profile/nozzle/process equal the defaults already on screen; otherwise Finish setup
    // can leave Slice disabled until an unrelated navigation forces recomposition.
    private var installedSetup by mutableStateOf<SlicingSetup?>(null)
    var preset by mutableStateOf(QualityPreset.STANDARD)
    internal var presetSnapshot by mutableStateOf<PresetSnapshot?>(null)
        private set
    var presetSnapshotProblem by mutableStateOf<String?>(null)
        private set
    var supports by mutableStateOf(false)
    var infill by mutableStateOf(15)
    /** Settings changed in All settings, in the engine's serialized form; applied after the preset and quick controls. */
    val overrides = androidx.compose.runtime.mutableStateMapOf<String, String>()
    /** Set while the plate holds a calibration print (see [startCalibration]): the G-code gets its height-dependent step after slicing. */
    var calibration by mutableStateOf<net.jamesjennison.klippercompanion.CalibrationSpec?>(null)
    /** Which settings the panel shows: the print (process), a material, or the printer. */
    var settingsScope by mutableStateOf(com.nozzleitall.desktop.settings.Scope.PROCESS)
    /** A user preset is an extra NIA-owned base layer. System, vendor, and imported presets remain immutable. */
    private val selectedUserPresetIds = androidx.compose.runtime.mutableStateMapOf<com.nozzleitall.desktop.settings.Scope, String>()
    /** Everything (on) or only the essential settings (off). */
    var advancedSettings by mutableStateOf(false)
    var settingsLayoutSaveProblem by mutableStateOf(false)
        private set
    private fun settingGroupKey(group: com.nozzleitall.desktop.settings.SettingGroup) = "${group.scope.name}:${group.id}"
    fun isSettingGroupCollapsed(group: com.nozzleitall.desktop.settings.SettingGroup) =
        settingGroupKey(group) in app.fleet.settings.value.collapsedSettingGroups
    fun toggleSettingGroup(group: com.nozzleitall.desktop.settings.SettingGroup) {
        settingsLayoutSaveProblem = !app.fleet.updateSettingGroupCollapsed(settingGroupKey(group), !isSettingGroupCollapsed(group))
    }

    /**
     * What a setting is before any project override.
     *
     * A bundled printer-library Process already contains its complete values, so the
     * generic quick quality preset must not mask them in the settings sheet. Profiles
     * without a process library still use the quick preset as their layer-height base.
     */
    fun baseSetting(key: String, profile: com.nozzleitall.desktop.settings.ProfileValues): String? {
        val scope = com.nozzleitall.desktop.settings.SettingsCatalog.bundled.byKey[key]?.scope
        val userValue = scope?.let(::selectedUserPreset)?.values?.get(key)
        return userValue ?: if (library() == null) preset.overrides[key] ?: profile[key] else profile[key]
    }

    /** What a setting is for this project. Infill density and supports are the quick controls' own values. */
    fun setting(key: String, profile: com.nozzleitall.desktop.settings.ProfileValues): String? = when (key) {
        "sparse_infill_density" -> "$infill%"
        "enable_support" -> if (supports) "1" else "0"
        else -> overrides[key] ?: baseSetting(key, profile)
    }

    fun isChanged(key: String, profile: com.nozzleitall.desktop.settings.ProfileValues) = when (key) {
        "sparse_infill_density" -> infill != 15
        "enable_support" -> supports
        else -> key in overrides
    }

    /** Sets (or with null, resets) a setting; a value equal to the base clears the change rather than storing a copy. */
    fun setSetting(key: String, value: String?, profile: com.nozzleitall.desktop.settings.ProfileValues) {
        when (key) {
            "sparse_infill_density" -> infill = value?.removeSuffix("%")?.trim()?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100) ?: 15
            "enable_support" -> supports = value == "1"
            else -> if (value == null || value == baseSetting(key, profile)) overrides.remove(key) else overrides[key] = value
        }
        changed()
    }

    data class PresetSnapshotChoice(
        val id: String,
        val name: String,
        val user: Boolean,
        val values: Map<String, String>,
    )

    private fun presetContextId(scope: com.nozzleitall.desktop.settings.Scope): String = when (scope) {
        com.nozzleitall.desktop.settings.Scope.PROCESS,
        com.nozzleitall.desktop.settings.Scope.PRINTER -> machineVariant()?.id ?: profileId
        com.nozzleitall.desktop.settings.Scope.FILAMENT -> filamentProfileKey()
    }

    private fun systemPresetId(scope: com.nozzleitall.desktop.settings.Scope): String = when (scope) {
        com.nozzleitall.desktop.settings.Scope.PROCESS -> currentProcess()?.id ?: processId ?: preset.name
        com.nozzleitall.desktop.settings.Scope.FILAMENT -> materials().firstOrNull()?.filamentProfile
            ?: machineVariant()?.defaultFilament ?: "profile-default"
        com.nozzleitall.desktop.settings.Scope.PRINTER -> machineVariant()?.id ?: profileId
    }

    private fun systemPresetLabel(scope: com.nozzleitall.desktop.settings.Scope): String = when (scope) {
        com.nozzleitall.desktop.settings.Scope.PROCESS -> currentProcess()?.displayLabel ?: preset.label
        com.nozzleitall.desktop.settings.Scope.FILAMENT -> {
            val id = systemPresetId(scope)
            filamentLibrary().firstOrNull { it.id == id }?.displayName ?: "Profile default"
        }
        com.nozzleitall.desktop.settings.Scope.PRINTER -> machineVariant()?.name ?: profile?.model ?: profileId
    }

    fun selectedUserPreset(scope: com.nozzleitall.desktop.settings.Scope) =
        app.userPresets.find(selectedUserPresetIds[scope])?.takeIf {
            it.scope == scope && it.profileId == profileId && it.contextId == presetContextId(scope)
        }

    fun presetChoices(scope: com.nozzleitall.desktop.settings.Scope): List<com.nozzleitall.desktop.settings.Choice> {
        val system = when (scope) {
            com.nozzleitall.desktop.settings.Scope.PROCESS -> processes().map {
                com.nozzleitall.desktop.settings.Choice(it.id, it.displayLabel)
            }.ifEmpty { QualityPreset.entries.map { com.nozzleitall.desktop.settings.Choice(it.name, it.label) } }
            com.nozzleitall.desktop.settings.Scope.FILAMENT -> filamentLibrary().map {
                com.nozzleitall.desktop.settings.Choice(it.id, it.displayName)
            }.ifEmpty { listOf(com.nozzleitall.desktop.settings.Choice(systemPresetId(scope), systemPresetLabel(scope))) }
            com.nozzleitall.desktop.settings.Scope.PRINTER -> library()?.machines.orEmpty().map {
                com.nozzleitall.desktop.settings.Choice(it.id, "${it.name} · ${it.nozzle} mm")
            }.ifEmpty { listOf(com.nozzleitall.desktop.settings.Choice(systemPresetId(scope), systemPresetLabel(scope))) }
        }
        return system + app.userPresets.entries(scope, profileId, presetContextId(scope)).map {
            com.nozzleitall.desktop.settings.Choice(it.id, it.name)
        }
    }

    fun selectedPresetId(scope: com.nozzleitall.desktop.settings.Scope): String =
        selectedUserPreset(scope)?.id ?: systemPresetId(scope)

    /** Selects an immutable base or a NIA user preset and resets only edits in that settings scope. */
    fun selectSettingsPreset(scope: com.nozzleitall.desktop.settings.Scope, id: String) {
        val user = app.userPresets.find(id)?.takeIf {
            it.scope == scope && it.profileId == profileId && it.contextId == presetContextId(scope)
        }
        val baseId = user?.baseId ?: id
        when (scope) {
            com.nozzleitall.desktop.settings.Scope.PROCESS -> if (library() == null)
                QualityPreset.entries.firstOrNull { it.name == baseId }?.let { preset = it }
            else processId = baseId
            com.nozzleitall.desktop.settings.Scope.FILAMENT ->
                filamentLibrary().firstOrNull { it.id == baseId }?.let { setSlotFilament(1, it) }
            com.nozzleitall.desktop.settings.Scope.PRINTER ->
                library()?.machines?.firstOrNull { it.id == baseId }?.let { machine ->
                    nozzle = machine.nozzle
                    processId = null
                }
        }
        if (user == null) selectedUserPresetIds.remove(scope) else selectedUserPresetIds[scope] = user.id
        if (scope == com.nozzleitall.desktop.settings.Scope.PROCESS) {
            val legacy = app.legacyProcessPresets.find(profileId, baseId)
                ?.takeIf { it.machineId == machineVariant()?.id }?.processOverrides
            val density = user?.values?.get("sparse_infill_density") ?: legacy?.optString("sparse_infill_density")
            infill = density?.removeSuffix("%")?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100) ?: 15
            supports = (user?.values?.get("enable_support") ?: legacy?.optString("enable_support")) == "1"
        }
        resetSettingsScope(scope, markChanged = false)
        changed()
    }

    fun changedSettings(scope: com.nozzleitall.desktop.settings.Scope): Map<String, String> {
        val keys = com.nozzleitall.desktop.settings.SettingsCatalog.bundled.all
            .asSequence().filter { it.scope == scope }.map { it.key }.toSet()
        return overrides.filterKeys(keys::contains)
    }

    fun resetSettingsScope(scope: com.nozzleitall.desktop.settings.Scope, markChanged: Boolean = true) {
        changedSettings(scope).keys.toList().forEach(overrides::remove)
        if (markChanged) changed()
    }

    fun saveUserPreset(
        scope: com.nozzleitall.desktop.settings.Scope,
        name: String,
        profileValues: com.nozzleitall.desktop.settings.ProfileValues,
        overwrite: Boolean,
    ): com.nozzleitall.desktop.settings.UserPresetStore.Preset {
        val current = selectedUserPreset(scope)
        val values = com.nozzleitall.desktop.settings.SettingsCatalog.bundled.all
            .asSequence().filter { it.scope == scope }.mapNotNull { definition ->
                setting(definition.key, profileValues)?.let { definition.key to it }
            }.toMap()
        val saved = app.userPresets.save(scope, profileId, presetContextId(scope), current?.baseId ?: systemPresetId(scope),
            name, values, overwriteId = current?.id.takeIf { overwrite })
        selectedUserPresetIds[scope] = saved.id
        resetSettingsScope(scope, markChanged = false)
        changed()
        return saved
    }

    fun deleteSelectedUserPreset(scope: com.nozzleitall.desktop.settings.Scope): Boolean {
        val selected = selectedUserPreset(scope) ?: return false
        if (!app.userPresets.delete(selected.id)) return false
        selectedUserPresetIds.remove(scope)
        if (scope == com.nozzleitall.desktop.settings.Scope.PROCESS) { infill = 15; supports = false }
        resetSettingsScope(scope, markChanged = false)
        changed()
        return true
    }

    fun presetSnapshots(
        scope: com.nozzleitall.desktop.settings.Scope,
        profileValues: com.nozzleitall.desktop.settings.ProfileValues,
    ): List<PresetSnapshotChoice> {
        val keys = com.nozzleitall.desktop.settings.SettingsCatalog.bundled.all.filter { it.scope == scope }.map { it.key }
        val base = keys.mapNotNull { key -> profileValues[key]?.let { key to it } }.toMap()
        fun jsonValues(json: JSONObject): Map<String, String> = json.keySet().associateWith { key ->
            when (val value = json.get(key)) {
                is JSONArray -> (0 until value.length()).map { value.get(it).toString() }.let { items ->
                    if (items.any { it.any { ch -> ch in ",;\"\n" } || it.isEmpty() })
                        items.joinToString(";") { com.nozzleitall.desktop.settings.SettingDef.quote(it) }
                    else items.joinToString(",")
                }
                else -> value.toString()
            }
        }.filterKeys(keys.toSet()::contains)
        val system = when (scope) {
            com.nozzleitall.desktop.settings.Scope.PROCESS -> library()?.let { library ->
                processes().mapNotNull { process ->
                    val values = library.json("process", process.id)?.let(::jsonValues)
                        ?: base.takeIf { process.id == systemPresetId(scope) }
                    values?.let { PresetSnapshotChoice(process.id, process.displayLabel, false, base + it) }
                }
            } ?: QualityPreset.entries.map { quality ->
                PresetSnapshotChoice(quality.name, quality.label, false, base + quality.overrides.filterKeys(keys.toSet()::contains))
            }
            com.nozzleitall.desktop.settings.Scope.FILAMENT -> filamentLibrary().mapNotNull { filament ->
                FilamentLibrary.profileJson(filamentKey(), filament.id)?.let(::jsonValues)?.let {
                    PresetSnapshotChoice(filament.id, filament.displayName, false, base + it)
                }
            }.ifEmpty { listOf(PresetSnapshotChoice(systemPresetId(scope), systemPresetLabel(scope), false, base)) }
            com.nozzleitall.desktop.settings.Scope.PRINTER -> library()?.let { library ->
                library.machines.mapNotNull { machine -> library.json("machine", machine.id)?.let(::jsonValues)?.let {
                    PresetSnapshotChoice(machine.id, "${machine.name} · ${machine.nozzle} mm", false, base + it)
                } }
            }.orEmpty().ifEmpty { listOf(PresetSnapshotChoice(systemPresetId(scope), systemPresetLabel(scope), false, base)) }
        }
        return system.distinctBy { it.id } + app.userPresets.entries(scope, profileId, presetContextId(scope)).map {
                PresetSnapshotChoice(it.id, it.name, true, it.values)
            }
    }

    private fun userPresetOverrides(): Map<String, String> = com.nozzleitall.desktop.settings.Scope.entries
        .mapNotNull(::selectedUserPreset).flatMap { it.values.entries }
        .filterNot { it.key == "sparse_infill_density" || it.key == "enable_support" }
        .associate { it.toPair() }

    private fun effectiveOverrides(): Map<String, String> = userPresetOverrides() + overrides.toMap()
    var slice by mutableStateOf<SliceState>(SliceState.Idle)
    var previewFirstLayer by mutableStateOf(0)
    var previewLayer by mutableStateOf(0)
    var previewMove by mutableStateOf(Int.MAX_VALUE)
    var showPreview by mutableStateOf(false)
    /** How the preview is coloured; null picks from the print (filaments if it uses several, else line types). */
    var previewView by mutableStateOf<PreviewView?>(null)
    val previewHiddenKinds = mutableStateListOf<Int>()
    val previewHiddenTools = mutableStateListOf<Int>()
    var notice by mutableStateOf<String?>(null)
    var uploadProgress by mutableStateOf<Float?>(null)
    /** Material slots when no printer reports its toolheads (1-based). */
    // Enough slots for any bundled profile (the Prusa XL has five toolheads); a profile shows as many as it has tools.
    private val defaultMaterialSlots = listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#A78BFA"), ProjectManifest.MaterialSlot(2, "PLA", colorHex = "#F2754E"),
        ProjectManifest.MaterialSlot(3, "PLA", colorHex = "#EEF2F4"), ProjectManifest.MaterialSlot(4, "PLA", colorHex = "#1E2429"),
        ProjectManifest.MaterialSlot(5, "PLA", colorHex = "#4CAF50"), ProjectManifest.MaterialSlot(6, "PLA", colorHex = "#29B6F6"),
        ProjectManifest.MaterialSlot(7, "PLA", colorHex = "#FFB300"), ProjectManifest.MaterialSlot(8, "PLA", colorHex = "#8D6E63"))
    val manualSlots = defaultMaterialSlots.toMutableStateList()
    private var engine: SliceEngine? = null
    private var nextId = 1
    internal val nextItemId get() = nextId
    /** Puts the plates back as [history] kept them. */
    internal fun restorePlate(restored: List<PrepItem>, restoredOverrides: Map<String, String>, names: List<String>, ids: List<String>, current: Int, select: Int?, next: Int, selection: Set<Int> = select?.let { setOf(it) }.orEmpty()) {
        items.clear(); items.addAll(restored)
        hiddenObjectIds.clear(); selectedPart = -1
        overrides.clear(); overrides.putAll(restoredOverrides)
        plateNames.clear(); plateNames.addAll(names); currentPlate = current.coerceIn(0, plateNames.lastIndex)
        plateIds.clear(); plateIds.addAll(ids)
        setSelection(selection, select)
        nextId = next
        paintHistory.clear()
        syncInstances(); dirtyFlag = true; editorGeneration++; invalidateSlice(); refreshRules()
    }

    val profile: PrinterProfileInfo? get() = ProfileCatalog.byId(profileId)
    /** True only after setup was deliberately installed or a project supplied a known profile. The internal
     * default keeps legacy data readable, but must never be presented as an Owner-selected slicing target. */
    val hasSlicingSetup: Boolean get() = installedSetup != null ||
        manifest?.printer?.profileId?.let(ProfileCatalog::byId) != null
    /** One canonical slicing identity for every destination. A physical printer connection is deliberately separate. */
    val slicingMachineLabel: String get() = if (hasSlicingSetup)
        installedSetup?.takeIf { it.profileId == profileId }?.name?.trim()?.takeIf(String::isNotEmpty)
            ?: profile?.model?.trim()?.takeIf(String::isNotEmpty)
            ?: profileId
    else "Choose machine"
    val nozzleToolheadLabel: String get() = if (hasSlicingSetup) {
        val diameter = machineVariant()?.nozzle ?: nozzle ?: "0.4"
        val count = installedSetup?.takeIf { it.profileId == profileId }?.toolheads
            ?: profile?.tools?.coerceAtLeast(1) ?: 1
        "$diameter mm · $count toolhead${if (count == 1) "" else "s"}"
    } else "Set up machine first"
    /** The chosen profile's printable area. */
    private val activeCustomSetup: SlicingSetup? get() = installedSetup?.takeIf { it.customOffline && it.profileId == profileId }
    val bed: Pair<Float, Float> get() = activeCustomSetup?.let { (it.bedWidthMm ?: 250f) to (it.bedDepthMm ?: 250f) }
        ?: profile?.let { it.bedW to it.bedD } ?: (270f to 270f)

    // --- Plates (Orca's plate list): names in order, the one shown, and the objects on it.
    val plateNames = androidx.compose.runtime.mutableStateListOf("Plate 1")
    val plateIds = androidx.compose.runtime.mutableStateListOf(UUID.randomUUID().toString())
    private var draftSessionId = UUID.randomUUID()

    internal fun draftQueuePlateId(plateId: String): String =
        UUID.nameUUIDFromBytes("$draftSessionId:$plateId".toByteArray(Charsets.UTF_8)).toString()
    var currentPlate by mutableStateOf(0)
    var showPlateOverview by mutableStateOf(false)
    fun plateItems(plate: Int = currentPlate): List<PrepItem> = items.filter { it.plate == plate }

    /** Shows plate [i]; a slice result belongs to the plate it was made for. */
    fun showPlate(i: Int) { if (i in plateNames.indices) {
        showPlateOverview = false
        if (i != currentPlate) {
            currentPlate = i
            selected = null
            val prepared = batchSlices[plateIds[i]]
            when {
                (slice as? SliceState.Running)?.batch == true -> discardMeshEditPreview()
                prepared != null -> showCompletedSlice(prepared, openPreview = showPreview)
                batchSlices.isNotEmpty() -> {
                    slice = SliceState.Idle
                    showPreview = false
                }
                else -> invalidateSlice()
            }
        }
    } }

    fun addPlate() { plateNames += "Plate ${plateNames.size + 1}"; plateIds += UUID.randomUUID().toString(); currentPlate = plateNames.lastIndex; selected = null; changed() }
    fun renamePlate(i: Int, name: String) { if (i in plateNames.indices) { plateNames[i] = name; changed() } }
    fun duplicateCurrentPlate() {
        if (toolBusy != null) return
        val source = currentPlate
        val objects = plateItems(source)
        history.beginEdit()
        try {
            val target = plateNames.size
            plateNames += "${plateNames[source]} copy"
            plateIds += UUID.randomUUID().toString()
            nextId = maxOf(nextId, (items.maxOfOrNull { it.id } ?: 0) + 1)
            val copyIds = objects.associate { it.id to nextId++ }
            val meshes = mutableMapOf<Mesh, Mesh>()
            items.addAll(objects.map { item ->
                val copiedMesh = meshes.getOrPut(item.mesh) {
                    val mesh = item.mesh
                    Mesh(mesh.vertices.copyOf(), mesh.triangles.copyOf(), mesh.paint?.copyOf(),
                        mesh.supportPaint?.copyOf(), mesh.seamPaint?.copyOf(), mesh.fuzzyPaint?.copyOf())
                }
                withMesh(item, copiedMesh, id = copyIds.getValue(item.id)).also {
                    it.plate = target
                    // Cross-plate instances become independent copies of their current geometry.
                    it.instanceOf = item.instanceOf?.let(copyIds::get)
                }
            })
            showPlate(target)
            changed()
        } finally { history.endEdit() }
    }

    fun moveCurrentPlate(offset: Int) {
        if (toolBusy != null || offset !in listOf(-1, 1)) return
        val source = currentPlate
        val target = source + offset
        if (target !in plateNames.indices) return
        history.beginEdit()
        try {
            plateNames.add(target, plateNames.removeAt(source))
            plateIds.add(target, plateIds.removeAt(source))
            for (item in items) {
                when (item.plate) {
                    source -> item.plate = target
                    target -> item.plate = source
                }
            }
            currentPlate = target
            changed()
        } finally { history.endEdit() }
    }

    internal fun clearPlateObjects(plate: Int?) {
        if (plate != null && plate !in plateIds.indices) return
        if (items.none { plate == null || it.plate == plate }) return
        editSelection {
            items.removeAll { plate == null || it.plate == plate }
            if (plate == null || plate == currentPlate) deselect()
            changed()
        }
    }

    /** Inline removal is for empty plates. The scope-confirmed action also allows a populated plate. */
    fun removePlate(i: Int, allowPopulated: Boolean = false) {
        if (i !in plateIds.indices || plateNames.size <= 1 || (!allowPopulated && plateItems(i).isNotEmpty())) return
        editSelection {
            if (i == currentPlate) deselect()
            items.removeAll { it.plate == i }
            plateNames.removeAt(i)
            plateIds.removeAt(i)
            items.forEach { if (it.plate > i) it.plate -= 1 }
            currentPlate = (if (currentPlate > i) currentPlate - 1 else currentPlate).coerceAtMost(plateNames.lastIndex)
            changed()
        }
    }
    fun moveToPlate(item: PrepItem, plate: Int) {
        if (plate !in plateNames.indices || plate == item.plate) return
        item.plate = plate; selected = null; changed()
    }

    fun printer(): PrinterEntry? = printerId?.let { app.fleet.printers[it] }

    /** Choosing a physical destination never changes the independently installed slicing configuration. */
    fun choosePrinter(id: String?) {
        if (printerId == id) return
        printerId = id
        changed()
    }

    internal fun switchWorkspaceMachine(setup: SlicingSetup, destinationId: String?) {
        bedType = null
        nozzleFlows.clear()
        presetSnapshot = null
        presetSnapshotProblem = null
        applySlicingSetup(setup, clearToolheadPresets = false)
        if (destinationId != null) printerId = destinationId
        changed()
    }

    /** Filament profiles chosen on slots of a printer that reports what's loaded (slot → library id). */
    private val destinationHeadProfiles = mutableMapOf<Pair<String?, String>, androidx.compose.runtime.snapshots.SnapshotStateMap<Int, String>>()
    val headProfiles get() = destinationHeadProfiles.getOrPut(printerId to profileId) {
        androidx.compose.runtime.mutableStateMapOf()
    }

    /** The filament profiles this printer profile can slice with, one per slot. */
    fun filamentLibrary(): List<FilamentLibrary.Entry> {
        val all = FilamentLibrary.forProfile(filamentKey())
        val installed = installedSetup?.installedFilamentProfiles.orEmpty()
        if (installed.isEmpty()) return all
        val inUse = manualSlots.mapNotNull { it.filamentProfile }.toSet() + headProfiles.values
        return all.filter { it.id in installed || it.id in inUse }
    }

    // --- The printer's profile family (PrinterLibrary), where it has one: nozzle size and process preset.
    /** The nozzle diameter chosen on the printer card ("0.4"), or null for the family's 0.4 mm default. */
    var nozzle by mutableStateOf<String?>(null)
    /** The process preset chosen in the Print header (a library id), or null for the machine's default. */
    var processId by mutableStateOf<String?>(null)
    /** Applies the user's installed offline slicing setup without creating or requiring a physical printer. */
    fun applySlicingSetup(setup: SlicingSetup) = applySlicingSetup(setup, clearToolheadPresets = true)

    private fun applySlicingSetup(setup: SlicingSetup, clearToolheadPresets: Boolean) {
        installedSetup = setup
        machineCache = null
        profileId = setup.profileId
        nozzle = setup.nozzle
        processId = setup.processId
        if (clearToolheadPresets) headProfiles.clear()
        manualSlots.indices.forEach { i ->
            val slot = i + 1
            manualSlots[i] = manualSlots[i].copy(filamentProfile = setup.filamentProfiles[slot])
        }
        invalidateSlice()
        refreshRules()
    }
    fun library(): PrinterLibrary? = PrinterLibrary.of(profileId)
    fun machineVariant(): PrinterLibrary.Machine? = library()?.machineFor(nozzle)
    fun processes(): List<PrinterLibrary.Process> = library()?.let { lib -> machineVariant()?.let { lib.processesFor(it) } }.orEmpty()
    fun currentProcess(): PrinterLibrary.Process? = machineVariant()?.let { m ->
        library()?.processFor(m, processId) }
    /** Switches every nozzle to [diameter]: the family's machine for it, its default process, and each slot's filament
     *  kept by family where that size has it (else the printer default). */
    fun chooseNozzle(diameter: String) {
        val lib = library() ?: return
        val next = lib.machineFor(diameter)
        val nextFilaments = lib.filamentsFor(next)
        fun carry(id: String?): String? = id?.let { old -> lib.filaments[old]?.family?.let { fam -> nextFilaments.firstOrNull { it.family.equals(fam, true) }?.id } }
        for (i in manualSlots.indices) manualSlots[i] = manualSlots[i].copy(filamentProfile = carry(manualSlots[i].filamentProfile))
        headProfiles.keys.toList().forEach { k -> carry(headProfiles[k])?.let { headProfiles[k] = it } ?: headProfiles.remove(k) }
        nozzle = next.nozzle; processId = null; changed()
    }

    private fun filamentKey(): String = machineVariant()?.let { "$profileId@${it.id}" } ?: profileId
    fun filamentProfileKey(): String = filamentKey()

    /** Sets slot [slot]'s filament profile (and, for a slot Nozzle keeps itself, its material type and colour). */
    fun setSlotFilament(slot: Int, entry: FilamentLibrary.Entry?, colourHex: String? = null) {
        val reportedSpoolId = printer()?.status?.value?.toolheads?.firstOrNull { it.index + 1 == slot }?.material?.spoolId
            ?: manualSlots.firstOrNull { it.slot == slot }?.takeUnless { !it.fromTag && SpoolReader.owns(it.spoolId) }?.spoolId
        if (printer()?.status?.value?.toolheads.isNullOrEmpty()) {
            val i = manualSlots.indexOfFirst { it.slot == slot }
            if (i >= 0) manualSlots[i] = manualSlots[i].copy(filamentProfile = entry?.id, type = entry?.type ?: manualSlots[i].type,
                vendor = entry?.vendor?.takeIf { it.isNotBlank() }, colorHex = colourHex ?: manualSlots[i].colorHex)
        } else if (entry != null) headProfiles[slot] = entry.id else headProfiles.remove(slot)
        if (reportedSpoolId != null && !app.fleet.saveRfidFilamentProfile(reportedSpoolId, entry?.id))
            notice = "The filament choice changed for this project, but its RFID mapping could not be saved."
        changed()
    }

    internal val cadPlugins = CadPluginStore(app.paths.config.resolve("plugins"))

    internal fun readerProfile(tag: String): String? = app.fleet.externalSpoolProfile(tag)

    internal fun applyReaderSpool(expected: ProjectManifest.MaterialSlot, tag: String, profileId: String): String {
        if (!printer()?.status?.value?.toolheads.isNullOrEmpty() || materials().firstOrNull { it.slot == expected.slot } != expected)
            return "The slot changed. Review the mapping again."
        val profile = filamentLibrary().firstOrNull { it.id == profileId }
            ?: return "The selected filament profile is unavailable."
        if (!SpoolReader.owns(tag) || materials().any { it.slot != expected.slot && !it.fromTag && it.spoolId == tag })
            return "This tag is invalid or already assigned to another slot."
        val index = manualSlots.indexOfFirst { it.slot == expected.slot }
        if (index < 0 || !app.fleet.saveExternalSpoolProfile(tag, profile.id))
            return "The reader mapping could not be saved."
        manualSlots[index] = expected.copy(type = profile.type, vendor = profile.vendor.takeIf { it.isNotBlank() },
            subType = null, filamentProfile = profile.id, spoolId = tag, sku = null, fromTag = false)
        changed()
        return "Mapped reader spool to slot ${expected.slot}. Save the project to keep this slot assignment."
    }

    /**
     * Sync filament from the printer: what each toolhead reports loaded (material, vendor, colour) is copied into the project's own
     * slots, so it stays when the printer is later off. Returns a sentence saying what happened.
     */
    fun syncFromPrinter(): String {
        val entry = printer() ?: return "Choose a printer first (Setup > Printer)."
        val name = entry.config.identity.displayName
        val heads = entry.status.value.toolheads
        if (heads.isEmpty()) return "$name doesn't report loaded filament, so there is nothing to sync. Set the slots by hand."
        val profilesBySlot = materials().associate { it.slot to it.filamentProfile }
        var synced = 0
        heads.forEach { t ->
            val m = t.material ?: return@forEach
            val i = manualSlots.indexOfFirst { it.slot == t.index + 1 }
            if (i >= 0) {
                manualSlots[i] = manualSlots[i].copy(type = m.type ?: manualSlots[i].type, vendor = m.vendor ?: manualSlots[i].vendor,
                    subType = m.subType ?: manualSlots[i].subType, colorHex = m.colorHex ?: manualSlots[i].colorHex,
                    filamentProfile = profilesBySlot[t.index + 1], spoolId = m.spoolId, sku = m.sku, fromTag = m.fromTag)
                synced++
            }
        }
        if (synced > 0) changed()
        return if (synced == 0) "$name reports every slot empty. Load filament, or set the slots by hand." else "Synced $synced of ${heads.size} slots from $name."
    }

    /** Materials in slot order: the printer's loaded toolheads when it reports them, otherwise the manual slots. */
    fun materials(): List<ProjectManifest.MaterialSlot> {
        val heads = printer()?.status?.value?.toolheads.orEmpty()
        // A loaded filament slices with the printer's own filament profile for it, matched by vendor and type (the choice
        // made on the slot overrides that).
        if (heads.isNotEmpty()) return heads.map { t ->
            val chosen = headProfiles[t.index + 1] ?: app.fleet.rfidFilamentProfile(t.material?.spoolId)
            ProjectManifest.MaterialSlot(t.index + 1, t.material?.type ?: "PLA", t.material?.vendor, t.material?.subType,
                t.material?.colorHex ?: manualSlots.getOrNull(t.index)?.colorHex, t.index,
                filamentProfile = chosen ?: FilamentLibrary.bestFor(filamentKey(), t.material?.vendor, t.material?.type ?: "PLA", t.material?.subType)?.id,
                spoolId = t.material?.spoolId, sku = t.material?.sku, fromTag = t.material?.fromTag == true)
        }
        // No printer report: one slot per tool the profile has (a U1 has four, most printers one).
        val tools = (activeCustomSetup?.toolheads ?: profile?.tools ?: 1).coerceIn(1, manualSlots.size)
        return manualSlots.take(tools)
    }

    internal data class PresetSelection(val profileId: String, val nozzle: String?, val processId: String?,
        val saved: PresetSnapshot?, val problem: String?)

    internal fun presetSelection() = PresetSelection(profileId, nozzle, processId, presetSnapshot, presetSnapshotProblem)

    private fun bundledProfileDir(selection: PresetSelection): File {
        val bundled = PrinterLibrary.of(selection.profileId)?.let { lib ->
            lib.materialize(app.paths.cache, lib.machineFor(selection.nozzle), selection.processId)
        } ?: ProfileCatalog.materialize(app.paths.cache, selection.profileId)
        val custom = activeCustomSetup ?: return bundled
        val dir = File(app.paths.cache, "profiles/custom-${custom.profileId}-${custom.hashCode().toUInt().toString(16)}").apply { mkdirs() }
        listOf("process.json", "filament.json").forEach { name ->
            val source = File(bundled, name); val target = File(dir, name)
            if (!target.isFile || !target.readBytes().contentEquals(source.readBytes())) source.copyTo(target, overwrite = true)
        }
        val machine = JSONObject(File(bundled, "machine.json").readText())
        val width = custom.bedWidthMm ?: profile?.bedW ?: 250f
        val depth = custom.bedDepthMm ?: profile?.bedD ?: 250f
        val height = custom.heightMm ?: profile?.height ?: 250f
        val diameter = custom.nozzle?.toDoubleOrNull()?.takeIf { it in 0.1..2.0 } ?: 0.4
        val tools = custom.toolheads.coerceIn(1, manualSlots.size)
        machine.put("name", custom.name).put("printer_model", custom.name).put("printer_settings_id", custom.name)
            .put("is_custom_defined", "1").put("gcode_flavor", custom.gcodeFlavor)
            .put("printable_height", height.toString())
            .put("printable_area", org.json.JSONArray(listOf("0x0", "${width}x0", "${width}x${depth}", "0x${depth}")))
            .put("bed_exclude_area", org.json.JSONArray(listOf("0x0")))
            .put("bed_model", "").put("bed_texture", "")
            .put("nozzle_diameter", org.json.JSONArray(List(tools) { diameter.toString() }))
            .put("extruder_offset", org.json.JSONArray(List(tools) { "0x0" }))
            .put("extruder_colour", org.json.JSONArray(List(tools) { "#FCE94F" }))
        val target = File(dir, "machine.json")
        val text = machine.toString(2)
        if (!target.isFile || target.readText() != text) target.writeText(text)
        return dir
    }

    private fun bundledProfileDir(): File = bundledProfileDir(presetSelection())

    private fun selectedPresetSnapshot(bundled: File, selection: PresetSelection = presetSelection()): PresetSnapshot? =
        selection.saved?.takeIf { it.appliesTo(selection.profileId, bundled) }

    private fun resolvedPreset(selection: PresetSelection = presetSelection()): Pair<File, PresetSnapshot?> {
        val bundled = bundledProfileDir(selection)
        if (selection.problem != null) return bundled to null
        val saved = selectedPresetSnapshot(bundled, selection)
        return (saved?.materialize(app.paths.cache) ?: bundled) to saved
    }

    /** The project keeps using its saved preset files until the available update is explicitly applied. */
    fun profileDir(): File {
        // The UI can still display current choices for repair; Slice and Save reject the unreadable saved snapshot.
        return resolvedPreset().first
    }

    internal fun profileDir(selection: PresetSelection): File = resolvedPreset(selection).first

    internal data class MaterialSelection(val slot: Int, val type: String?, val vendor: String?, val subType: String?,
        val colorHex: String?, val toolhead: Int?, val filamentProfile: String?, val spoolId: String?, val sku: String?, val fromTag: Boolean)

    internal fun materialSelection() = materials().map { MaterialSelection(it.slot, it.type, it.vendor, it.subType,
        it.colorHex, it.toolhead, it.filamentProfile, it.spoolId, it.sku, it.fromTag) }

    internal data class PresetUpdateReview(val saved: PresetSnapshot, val candidate: PresetSnapshot,
        val profileId: String, val nozzle: String?, val processId: String?, val materials: List<MaterialSelection>,
        val qualityPreset: QualityPreset, val overrides: Map<String, String>,
        val changes: List<String>)

    /** Capture the reviewed candidate so Apply can reject a stale comparison. */
    internal fun presetUpdateReview(): PresetUpdateReview? {
        val bundled = bundledProfileDir()
        val saved = selectedPresetSnapshot(bundled) ?: return null
        val selectedMaterials = materials()
        val candidate = PresetSnapshot.capture(profileId, bundled, selectedMaterials)
        val selection = selectedMaterials.map { MaterialSelection(it.slot, it.type, it.vendor, it.subType,
            it.colorHex, it.toolhead, it.filamentProfile, it.spoolId, it.sku, it.fromTag) }
        return PresetUpdateReview(saved, candidate, profileId, nozzle, processId, selection, preset, overrides.toMap(),
            saved.changes(candidate))
    }

    /** Changed fields in a newer bundled preset; empty means the saved version still matches. */
    fun presetUpdate(): List<String> = presetUpdateReview()?.changes.orEmpty()

    internal fun reviewSelectionMatches(review: PresetUpdateReview): Boolean =
        presetSnapshot == review.saved && profileId == review.profileId && nozzle == review.nozzle &&
            processId == review.processId && materialSelection() == review.materials &&
            preset == review.qualityPreset && overrides.toMap() == review.overrides

    internal fun applyPresetUpdate(review: PresetUpdateReview? = null) {
        val bundled = bundledProfileDir()
        check(review != null || presetSnapshot == null || presetSnapshotProblem != null ||
            presetSnapshot?.appliesTo(profileId, bundled) == false) {
            "Review the bundled preset changes before applying them."
        }
        val candidate = PresetSnapshot.capture(profileId, bundled, materials())
        check(candidate.appliesTo(profileId, bundled)) { "The selected printer preset changed before it could be recorded." }
        if (review != null) check(reviewSelectionMatches(review) && candidate == review.candidate &&
            candidate.appliesTo(profileId, bundled)) { "The preset or material selection changed. Review the comparison again before applying." }
        presetSnapshot = candidate
        presetSnapshotProblem = null
        machineCache = null
        changed()
    }

    // --- Flushing volumes (Snapmaker Orca's Flushing volumes dialog, FlushVolumes). Worked out from the slot colours unless the
    // user edited the matrix; an edited matrix is kept with the project's settings, and a slot whose colour changes later is
    // worked out again (its row and column), as upstream does.
    private var flushColours: List<String>? = null

    private fun slotColours() = materials().map { it.colorHex?.takeIf(String::isNotBlank) ?: "#FFFFFF" }
    fun flushSetup() = SliceEngine.flushSetup(profileDir(), materials())

    fun autoFlush(): List<Int> = flushSetup().matrix(slotColours())

    /** The user's matrix (row = from slot, column = to), or null when it is worked out from the colours. */
    fun editedFlush(): List<Int>? {
        val colours = slotColours(); val n = colours.size
        val saved = overrides[FLUSH_KEY]?.split(',')?.mapNotNull { it.trim().toDoubleOrNull()?.toInt() }?.takeIf { it.size == n * n } ?: return null
        val before = flushColours ?: return saved
        val changedSlots = colours.indices.filter { before.getOrNull(it) != colours[it] }
        if (changedSlots.isEmpty()) return saved
        val setup = flushSetup()
        return changedSlots.fold(saved) { m, i -> setup.recalcSlot(m, i, colours) }
    }

    fun flushMatrix(): List<Int> = editedFlush() ?: autoFlush()

    fun setFlush(matrix: List<Int>?) {
        if (matrix == null) overrides.remove(FLUSH_KEY) else overrides[FLUSH_KEY] = matrix.joinToString(",")
        flushColours = if (matrix == null) null else slotColours()
        changed()
    }

    /** The engine multiplies every flushing volume by this (Snapmaker Orca: 0-3). */
    fun flushMultiplier(): Float = (overrides["flush_multiplier"] ?: runCatching { JSONObject(File(profileDir(), "process.json").readText()).optString("flush_multiplier") }.getOrNull())
        ?.toFloatOrNull() ?: FlushVolumes.ENGINE_DEFAULT_MULTIPLIER

    fun primeTowerEnabled(): Boolean {
        val serialized = overrides["enable_prime_tower"] ?: runCatching {
            JSONObject(File(profileDir(), "process.json").readText()).optString("enable_prime_tower")
        }.getOrNull()
        return serialized?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
    }

    fun setFlushMultiplier(v: Float) {
        overrides["flush_multiplier"] = "%.2f".format(java.util.Locale.ROOT, v.coerceIn(FlushVolumes.MIN_MULTIPLIER, FlushVolumes.MAX_MULTIPLIER)); changed()
    }

    // --- Colour mixing: OrcaSlicer 2.5.0's mixed filaments. Each row is a filament slot after the loaded ones, made of loaded
    // filaments in set proportions (optionally a gradient up the object); the engine (Orca's own code) blends its colour and
    // resolves it when slicing. They are saved with the project as the same filament_mixed_* settings Orca writes.
    val mixed = mutableStateListOf<MixedFilamentFormat.Row>()
    var mixedEditorRequest by mutableStateOf(0)
        private set
    var filamentEditorRequest by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var mixProblem by mutableStateOf<String?>(null)
    /** Whether mixed filaments split each layer into sub-layers (Orca's default) rather than alternating whole layers. */
    var mixSublayers by mutableStateOf(true)
    /** The slice settings for [mixed] as the engine last worked them out, kept so saving the project needs no engine call. */
    internal var mixedSettings: Map<String, String> = emptyMap()
        private set

    internal fun restoreMixes(rows: List<MixedFilamentFormat.Row>, settings: Map<String, String>, sublayers: Boolean) {
        mixed.clear()
        mixed.addAll(rows)
        mixedSettings = settings
        mixSublayers = sublayers
    }

    /** The slot number of mixed row [index]: after the loaded filaments. */
    fun mixedSlot(index: Int): Int = materials().size + index + 1

    /** The mixed rows this printer prints: none where it offers no mixing (CANVAS, a single-slot printer). */
    fun offeredMixed(): List<MixedFilamentFormat.Row> = if (MixedFilamentFormat.FEATURE in features()) mixed.toList() else emptyList()

    fun requestMixedFilamentEditor() { mixedEditorRequest++ }

    fun requestFilamentEditor(slot: Int) {
        filamentEditorRequest = slot to ((filamentEditorRequest?.second ?: 0) + 1)
    }

    private fun typedPhysical() = materials().map { (it.colorHex ?: "#FFFFFF") to it.type }

    /** Re-reads the rows through the engine for the current loaded filaments (colours, problems) and refreshes the saved settings. */
    fun refreshMixes(scope: kotlinx.coroutines.CoroutineScope) {
        if (mixed.isEmpty()) { mixedSettings = emptyMap(); return }
        val physical = typedPhysical(); val rows = mixed.toList(); val sublayer = mixSublayers
        scope.launch { runCatching { withContext(Dispatchers.IO) { MixedFilament.display(physical, rows) to runCatching { MixedFilament.sliceOverrides(physical, rows, sublayer) }.getOrDefault(emptyMap()) } }
            .onSuccess { (display, settings) ->
                if (mixed.toList() == rows && typedPhysical() == physical && mixSublayers == sublayer) {
                    restoreMixes(display, settings, sublayer)
                }
            }
            .onFailure { mixProblem = it.message } }
    }

    private fun editMix(op: (List<Pair<String, String?>>, List<MixedFilamentFormat.Row>) -> List<MixedFilamentFormat.Row>?) {
        val physical = typedPhysical()
        val rows = mixed.toList()
        val sublayer = mixSublayers
        val generation = editorGeneration
        app.scope.launch(Dispatchers.Main) {
            try {
                val (result, settings) = withContext(Dispatchers.IO) {
                    val updated = op(physical, rows) ?: error("That mix is already in the list.")
                    updated to MixedFilament.sliceOverrides(physical, updated, sublayer)
                }
                if (editorGeneration != generation || typedPhysical() != physical ||
                    mixed.toList() != rows || mixSublayers != sublayer) {
                    mixProblem = "The filament assignments changed. Review the mix again."
                    return@launch
                }
                restoreMixes(result, settings, sublayer)
                mixProblem = null
                changed()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mixProblem = error.message ?: "The mix could not be saved."
            }
        }
    }

    fun addMixed(row: MixedFilamentFormat.Row) = editMix { phys, rows -> MixedFilament.add(phys, rows, row) }
    fun updateMixed(index: Int, row: MixedFilamentFormat.Row) = editMix { phys, rows -> MixedFilament.update(phys, rows, index, row) }

    /** A reviewed removal changes the recipe table and all plate assignments in the same Undo step. */
    internal fun removeMixed(index: Int, replacement: Int, reviewedGeneration: Long): Job? {
        val physical = typedPhysical()
        val rows = mixed.toList()
        val sublayers = mixSublayers
        val gone = physical.size + index + 1
        if (index !in rows.indices || replacement !in 1..physical.size || editorGeneration != reviewedGeneration) {
            mixProblem = "The filament assignments changed. Review the removal again."
            return null
        }
        return app.scope.launch(Dispatchers.Main) {
            try {
                val (remaining, settings) = withContext(Dispatchers.IO) {
                    val updated = MixedFilament.remove(physical, rows, index)
                    check(updated.size == rows.size - 1) { "The engine did not remove the reviewed mix." }
                    updated to if (updated.isEmpty()) emptyMap() else MixedFilament.sliceOverrides(physical, updated, sublayers)
                }
                if (editorGeneration != reviewedGeneration || typedPhysical() != physical ||
                    mixed.toList() != rows || mixSublayers != sublayers) {
                    mixProblem = "The filament assignments changed. Review the removal again."
                    return@launch
                }
                fun remap(slot: Int): Int = when {
                    slot == gone -> replacement
                    slot > gone -> slot - 1
                    else -> slot
                }
                history.beginEdit()
                try {
                    restoreMixes(remaining, settings, sublayers)
                    items.forEach { item ->
                        item.slot = remap(item.slot)
                        item.paintSlots.indices.forEach { i -> item.paintSlots[i] = remap(item.paintSlots[i]) }
                    }
                    mixProblem = null
                    changed()
                } finally {
                    history.endEdit()
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                mixProblem = error.message ?: "The mix could not be removed."
            }
        }
    }

    // --- Printer card: bed type and each nozzle's flow (Snapmaker Orca's rules, PrinterSetup).
    /** The chosen bed type (curr_bed_type), or null for the printer's own default. */
    var bedType by mutableStateOf<String?>(null)
    /** Each nozzle's flow type ("standard" / "high_flow"); missing entries are standard. */
    val nozzleFlows = mutableStateListOf<String>()

    private var machineCache: Pair<String, JSONObject>? = null
    fun machineJson(): JSONObject { val key = "$profileId@${nozzle.orEmpty()}@${activeCustomSetup?.hashCode() ?: 0}"
        return machineCache?.takeIf { it.first == key }?.second
            ?: runCatching { JSONObject(File(profileDir(), "machine.json").readText()) }.getOrDefault(JSONObject()).also { machineCache = key to it } }
    fun beds(): PrinterSetup.Beds = PrinterSetup.beds(machineJson())
    /** The bed type slicing uses: the choice when it's valid for this printer, else the printer's default. */
    fun effectiveBedType(): String { val b = beds(); return bedType?.takeIf { b.enabled && b.choices.any { c -> c.value == it } } ?: b.default }

    /** Printer settings the card controls, as engine keys: the bed type, and nozzle flows where high flow exists. */
    fun printerOverrides(): Map<String, String> = buildMap {
        put("curr_bed_type", effectiveBedType())
        val m = machineJson()
        if (PrinterSetup.supportsHighFlow(m)) {
            val n = PrinterSetup.nozzles(m).size
            // The engine names the flow types "Standard" and "High Flow"; the card keeps "standard" / "high_flow".
            put("nozzle_volume_type", List(n) { if (nozzleFlows.getOrElse(it) { "standard" } == "high_flow") "High Flow" else "Standard" }.joinToString(","))
        }
    }

    /** Colour-mixing features on offer: the connected printer's own report, else what the chosen profile offers. */
    fun features(): Set<String> = printer()?.capabilities?.value?.vendorExtensions?.let { com.nozzleitall.printer.ext.ProfileFeatures.ofPrinter(it, materials().size, profile?.id) }
        ?: profile?.let { com.nozzleitall.printer.ext.ProfileFeatures.of(it.familyHint, it.tools, it.id) } ?: emptySet()

    fun physicalColours(): List<String> = materials().map { it.colorHex ?: "#FFFFFF" }

    /** The colour of slot [id]: a loaded filament, or a mixed filament's blended colour. */
    fun slotHex(id: Int): String? = materials().firstOrNull { it.slot == id }?.colorHex ?: mixed.getOrNull(id - materials().size - 1)?.hex

    /** Every slot a model can print with: the loaded filaments, then the mixed filaments. */
    fun allSlots(): List<Pair<Int, String>> = materials().map { it.slot to listOfNotNull(it.vendor, it.type).joinToString(" ").ifBlank { "Filament" } } +
        offeredMixed().mapIndexed { i, r -> mixedSlot(i) to "Mix ${r.label}" }

    /** The slots the project uses: every object's own slot and every slot its colours print in. */
    fun usedSlots(): Set<Int> = items.flatMapTo(HashSet()) { item -> listOf(item.slot) + item.painted.map { item.slotFor(it) } }

    /**
     * Matches each of [item]'s file colours to the closest loaded filament or a new mix of loaded filaments (the engine's
     * recipe search), adds the mixes and sets the slots. Returns the number of mixes added.
     */
    fun matchWithMixing(item: PrepItem, targets: List<Pair<String, List<Int>>>, scope: kotlinx.coroutines.CoroutineScope) {
        val physical = typedPhysical()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                var rows = mixed.toList()
                val slotFor = HashMap<String, Int>()
                for ((hex, _) in targets) {
                    val m = MixedFilament.match(physical, hex)
                    val single = m.parts.singleOrNull()
                    if (single != null || m.row == null) { slotFor[hex] = m.parts.firstOrNull()?.filament ?: 1; continue }
                    val row = m.row!!
                    // The engine says an identical fixed blend is already there by returning nothing; reuse that row.
                    val existing = rows.indexOfFirst { !it.gradient.enabled && it.components == row.components && it.weights == row.weights }
                    if (existing < 0) rows = MixedFilament.add(physical, rows, row) ?: rows
                    slotFor[hex] = physical.size + (if (existing >= 0) existing else rows.size - 1) + 1
                }
                rows to slotFor
            } }.onSuccess { (rows, slotFor) ->
                mixed.clear(); mixed.addAll(rows)
                targets.forEach { (hex, ids) -> val slot = slotFor[hex] ?: return@forEach
                    ids.forEach { n -> if (n == item.ownFilament) item.slot = slot
                        if (n >= 1) { while (item.paintSlots.size < n) item.paintSlots.add(item.slot); item.paintSlots[n - 1] = slot } } }
                mixProblem = null; changed(); refreshMixes(scope)
            }.onFailure { mixProblem = it.message }
        }
    }

    fun newProject() {
        invalidateSlice()
        draftSessionId = UUID.randomUUID()
        manualSlots.clear()
        manualSlots.addAll(defaultMaterialSlots)
        bedType = null; nozzleFlows.clear(); nozzle = null; processId = null
        presetSnapshot = null; presetSnapshotProblem = null
        machineCache = null
        overrides.clear(); selectedUserPresetIds.clear(); supports = false; infill = 15
        mixed.clear(); mixedSettings = emptyMap(); mixSublayers = true; mixProblem = null; destinationHeadProfiles.clear()
        printerId = null
        items.clear(); hiddenObjectIds.clear(); selectedPart = -1; selected = null; file = null; manifest = null; name = "Untitled project"; dirty = false; passthrough = emptyMap(); metadata = emptyMap()
        plateNames.clear(); plateNames += "Plate 1"; plateIds.clear(); plateIds += UUID.randomUUID().toString(); currentPlate = 0
        showPlateOverview = false
        nextId = 1; calibration = null
        installedSetup = null
        profileId = ProfileCatalog.DEFAULT_ID
        app.fleet.settings.value.slicingSetup?.let(::applySlicingSetup)
        history.reset()
    }

    fun open(f: File) = openProject(f, ThreeMf.read(f))

    internal fun openProject(f: File, p: Project3mf) {
        // Plates: Nozzle's own from the manifest, another slicer's from its plate list. Objects are stored where that
        // slicer puts its plates side by side (Plates.origin), so each is brought back onto its own plate.
        val ownPlates = p.manifest?.plates?.sortedBy { it.index }?.takeIf { it.isNotEmpty() }
        val plateOf: (ModelObject) -> Int = if (ownPlates != null) { o -> ownPlates.indexOfFirst { pl -> pl.objects.any { it.objectId == o.id } }.coerceAtLeast(0) } else { o -> o.plate ?: 0 }
        val names = ownPlates?.map { it.name } ?: p.plateNames
        val count = maxOf(names.size, (p.objects.maxOfOrNull(plateOf) ?: 0) + 1, 1)
        val foreignDigest = if (p.manifest == null) {
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered().use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(bytes)
                    if (read < 0) break
                    digest.update(bytes, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } else null
        val openedPlateIds = (0 until count).map { i ->
            ownPlates?.getOrNull(i)?.id
                ?: p.manifest?.let { ProjectManifest.legacyPlateId(it.projectId, ownPlates?.getOrNull(i)?.index ?: i + 1) }
                ?: UUID.nameUUIDFromBytes("nozzle-foreign-plate:$foreignDigest:$i".toByteArray(Charsets.UTF_8)).toString()
        }
        check(openedPlateIds.all { it.isNotBlank() } && openedPlateIds.size == openedPlateIds.toSet().size) {
            "This project has blank or duplicate plate identities. Its plates cannot be opened safely."
        }
        val openedPreset = runCatching { p.manifest?.extensions?.let(PresetSnapshot::fromExtensions) }
        newProject()
        file = f; manifest = p.manifest; name = p.manifest?.name?.trim()?.takeIf(String::isNotEmpty)
            ?: p.metadata["Title"]?.trim()?.takeIf(String::isNotEmpty) ?: f.nameWithoutExtension
        presetSnapshot = openedPreset.getOrNull()
        presetSnapshotProblem = openedPreset.exceptionOrNull()?.let { "The saved preset snapshot cannot be read: ${it.message}. Review this project before slicing." }
        passthrough = p.passthrough; metadata = p.metadata
        // Resolve the machine before interpreting a saved Process identity. Older
        // projects used "process:standard", which is a guided-quality alias rather
        // than an id in a printer library; library-backed machines must fall back to
        // their declared default instead of showing the first dropdown item while
        // loading another Process's values.
        p.manifest?.printer?.profileId?.takeIf { ProfileCatalog.byId(it) != null }?.let { profileId = it }
        p.manifest?.printer?.nozzleDiameters?.firstOrNull()?.let { d ->
            nozzle = if (d == Math.floor(d)) d.toInt().toString() else d.toString()
        }
        val entries = p.manifest?.plates?.flatMap { it.objects }?.associateBy { it.objectId } ?: emptyMap()
        plateNames.clear(); plateNames.addAll((0 until count).map { i -> names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Plate ${i + 1}" })
        plateIds.clear(); plateIds.addAll(openedPlateIds)
        val layoutBed = (if (ownPlates != null) p.manifest?.printer?.profileId?.let { ProfileCatalog.byId(it) }?.let { it.bedW.toDouble() to it.bedD.toDouble() } else p.sourceBed)
            ?: bed.let { it.first.toDouble() to it.second.toDouble() }
        p.objects.forEach { o ->
            val plate = plateOf(o)
            val (ox, oy) = Plates.origin(plate, count, layoutBed.first, layoutBed.second)
            val local = if (ox == 0.0 && oy == 0.0) o else o.copy(placement = Transform(o.placement.m.copyOf().also { it[9] -= ox; it[10] -= oy }))
            addFromPlacement(local, entries[o.id]?.materialSlot ?: 1, entries[o.id]?.paintSlots.orEmpty(), p.filaments,
                ObjectSettings.usable(entries[o.id]?.settings?.takeIf { it.isNotEmpty() } ?: o.settings), plate)
            entries[o.id]?.let { e -> items.lastOrNull()?.let { it.parts.addAll(e.parts); it.ranges.addAll(e.heightRanges); it.layerProfile.addAll(e.layerProfile); it.instanceOf = e.instanceOf } }
        }
        p.manifest?.settings?.preset?.let { key ->
            if (key.startsWith("process:")) {
                val requested = key.removePrefix("process:")
                processId = requested.takeIf { id -> library() == null || processes().any { it.id == id } }
            }
            else QualityPreset.entries.firstOrNull { it.name.equals(key, true) }?.let { preset = it } }
        val importedProcess = ForeignProcessSettings.read(p, (machineVariant()?.nozzle ?: nozzle)?.toDoubleOrNull())
        importedProcess?.values?.forEach { (key, value) ->
            when (key) {
                "sparse_infill_density" -> value.removeSuffix("%").toIntOrNull()?.let { infill = it }
                "enable_support" -> supports = value == "1"
                else -> overrides[key] = value
            }
        }
        p.manifest?.settings?.overrides?.let { ov ->
            ov["sparse_infill_density"]?.removeSuffix("%")?.toIntOrNull()?.let { infill = it }; ov["enable_support"]?.let { supports = it == "1" }
            // Everything else the project changed (from any platform) shows up in All settings.
            mixed.addAll(MixedFilamentFormat.rowsFromSettings(ov, p.manifest?.materials?.size ?: materials().size))
            ov["enable_mixed_color_sublayer"]?.let { mixSublayers = it == "1" }
            ov["curr_bed_type"]?.let { bedType = it }
            ov["nozzle_volume_type"]?.let { v -> nozzleFlows.clear(); nozzleFlows.addAll(v.split(',').map { it.trim().lowercase().replace(' ', '_') }) }
            ov.filter { (k, v) -> k != "sparse_infill_density" && k != "enable_support" && k != "filament_colour" && k !in MixedFilamentFormat.SETTING_KEYS && k != "curr_bed_type" &&
                k != "nozzle_volume_type" && preset.overrides[k] != v }.forEach { (k, v) -> overrides[k] = v }
        }
        // The project's slots: what a printer doesn't report comes back as saved (colour, material, filament profile).
        printerId = p.manifest?.printer?.printerId?.takeIf { it in app.fleet.printers }
        val savedMaterials = p.manifest?.materials.orEmpty()
        val conflictingReaderTags = savedMaterials.filter { !it.fromTag && SpoolReader.owns(it.spoolId) }
            .groupingBy { it.spoolId }.eachCount().filterValues { it > 1 }.keys
        savedMaterials.forEach { m ->
            val i = manualSlots.indexOfFirst { it.slot == m.slot }
            val readerTag = m.spoolId?.takeIf { !m.fromTag && SpoolReader.owns(it) && it !in conflictingReaderTags }
            if (i >= 0) {
                val previous = manualSlots[i]
                manualSlots[i] = previous.copy(type = m.type ?: previous.type, vendor = m.vendor, subType = m.subType,
                    colorHex = m.colorHex ?: previous.colorHex, filamentProfile = m.filamentProfile,
                    spoolId = readerTag ?: previous.spoolId, fromTag = readerTag == null && previous.fromTag)
            }
            m.filamentProfile?.let { headProfiles[m.slot] = it }
        }
        presetSnapshot?.let { saved ->
            if (!saved.appliesTo(profileId, bundledProfileDir())) {
                presetSnapshotProblem = "The saved printer or process preset is unavailable in this app version. Review the current choices before slicing."
            }
        }
        val readerProblem = "A reader tag was assigned to more than one slot. Review those spool mappings."
            .takeIf { conflictingReaderTags.isNotEmpty() }
        notice = listOfNotNull(p.manifestProblem, presetSnapshotProblem, readerProblem, importedProcess?.notice).joinToString(" ").ifBlank { null }
        dirty = false
        history.reset()
        showPlateOverview = count > 1
    }

    /** Reverses [PrepItem.placement] for a mesh loaded from a 3MF: recovers bed position, Z rotation and uniform scale. */
    private fun addFromPlacement(o: ModelObject, slot: Int, paintSlots: List<Int>, sources: List<SourceFilament>, settings: Map<String, String> = emptyMap(), plate: Int = 0) {
        val m = o.placement.m
        val scale = sqrt(m[0] * m[0] + m[1] * m[1]).toFloat().takeIf { it > 0 } ?: 1f
        val rot = Math.toDegrees(atan2(m[1], m[0])).toFloat()
        // The other axes relative to the first: Y's length and handedness (a left-handed turn mirrors Y), Z's length and sign.
        val yLen = sqrt(m[3] * m[3] + m[4] * m[4]).toFloat().takeIf { it > 0 } ?: scale
        val mirrorY = m[0] * m[4] - m[1] * m[3] < 0
        val relY = (if (mirrorY) -yLen else yLen) / scale; val relZ = (m[8].toFloat().takeIf { it != 0f } ?: scale) / scale
        val b = o.mesh.bounds(); val cx = (b[0] + b[3]) / 2.0; val cy = (b[1] + b[4]) / 2.0
        val p = o.placement.apply(cx, cy, 0.0)
        val item = PrepItem(o.id, o.name, o.mesh, p[0].toFloat(), p[1].toFloat(), rot, scale, slot, paintSlots, sources, settings, plate)
        item.sy = relY; item.sz = relZ
        // A painted model from another slicer (or saved before colours were mapped) gets its colours matched to the slots.
        if (item.painted.isNotEmpty() && item.paintSlots.size < item.painted.max()) matchColours(item, o.filament)
        items += item
        nextId = maxOf(nextId, o.id + 1)
    }

    /** A new project holding the calibration model for [spec] on the plate, with the settings the test needs. */
    fun startCalibration(spec: net.jamesjennison.klippercompanion.CalibrationSpec) {
        newProject()
        val tri = net.jamesjennison.klippercompanion.Calibration.mesh(spec)
        // The calibration models list every triangle's corners; weld equal corners so the mesh is closed, as a loaded file is.
        val index = HashMap<Triple<Float, Float, Float>, Int>(); val verts = ArrayList<Float>(); val tris = IntArray(tri.v.size / 3)
        for (i in tris.indices) {
            val key = Triple(tri.v[i * 3], tri.v[i * 3 + 1], tri.v[i * 3 + 2])
            tris[i] = index.getOrPut(key) { verts += key.first; verts += key.second; verts += key.third; index.size }
        }
        val mesh = Mesh(verts.toFloatArray(), tris)
        val (bw, bd) = bed
        name = spec.kind.label
        items += PrepItem(nextId++, spec.kind.label, mesh, bw / 2, bd / 2, plate = currentPlate)
        overrides.putAll(net.jamesjennison.klippercompanion.Calibration.overrides(spec))
        calibration = spec
        dirty = true; invalidateSlice()
    }

    fun importModel(f: File) = importLoadedModel(f, MeshIO.load(f))

    /** Prepare every object before replacing an open draft; a malformed later mesh cannot discard it. */
    fun newProjectWithLoadedModels(loaded: List<Pair<File, MeshIO.Loaded>>) {
        require(loaded.isNotEmpty())
        val (bw, bd) = bed
        val prepared = loaded.mapIndexed { index, (file, model) ->
            PrepItem(index + 1, file.nameWithoutExtension, model.mesh, bw / 2, bd / 2,
                sources = model.filaments, plate = 0).also { item ->
                if (item.painted.isNotEmpty() || model.filament != null) matchColours(item, model.filament)
            }
        }
        newProject()
        items.addAll(prepared)
        nextId = prepared.size + 1
        name = loaded.first().first.nameWithoutExtension
        dirty = true
        invalidateSlice()
        arrange()
        setSelection(prepared.map { it.id }.toSet(), prepared.last().id)
    }

    /** Accept an already parsed model into the current project. */
    fun importLoadedModel(f: File, loaded: MeshIO.Loaded) {
        val (bw, bd) = bed
        val item = PrepItem(nextId++, f.nameWithoutExtension, loaded.mesh, bw / 2, bd / 2, sources = loaded.filaments, plate = currentPlate)
        if (item.painted.isNotEmpty() || loaded.filament != null) matchColours(item, loaded.filament)
        items += item
        dirty = true; invalidateSlice(); arrange()
        selectObject(item.id)
        if (name == "Untitled project") name = f.nameWithoutExtension
    }

    /**
     * Gives the file's filaments slots the way Snapmaker Orca opens a file: filament N in slot N (wrapping when the file
     * has more filaments than the printer has slots). The object's own filament sets its slot.
     */
    fun matchColours(item: PrepItem, own: Int? = item.ownFilament) {
        item.ownFilament = own
        assign(item, FilamentSync.byNumber(filamentCount(item), materials()))
    }

    /** "Match": each of the file's filaments to the nearest loaded colour, same material first (Snapmaker Orca's filament sync). */
    fun matchByColour(item: PrepItem) = assign(item, FilamentSync.byColour(filamentCount(item), item.sources, materials())).also { changed() }

    private fun filamentCount(item: PrepItem) = maxOf(item.painted.maxOrNull() ?: 0, item.ownFilament ?: 0, item.sources.size)
    private fun assign(item: PrepItem, slots: List<Int>) {
        item.paintSlots.clear()
        item.paintSlots.addAll(slots)
        item.ownFilament?.let { f -> slots.getOrNull(f - 1)?.let { item.slot = it } }
    }

    /** Sets which slot the file's filament [n] of [item] prints with. */
    fun setPaintSlot(item: PrepItem, n: Int, slot: Int) {
        while (item.paintSlots.size < n) item.paintSlots.add(item.slot)
        item.paintSlots[n - 1] = slot; changed()
    }

    /** Assigns an object's unpainted/base regions to an available material slot. */
    fun setObjectSlot(item: PrepItem, slot: Int) {
        if (allSlots().none { it.first == slot } || item.slot == slot) return
        item.slot = slot
        changed()
    }

    fun duplicateSelected() {
        val targets = selectedItems().ifEmpty { return }
        history.beginEdit()
        try {
            nextId = maxOf(nextId, (items.maxOfOrNull { it.id } ?: 0) + 1)
            val copies = targets.map { s -> PrepItem(nextId++, s.name + " copy", s.mesh, s.x + 10, s.y + 10, s.rotZ, s.scale, s.slot, s.paintSlots.toList(), s.sources, s.settings.toMap(), s.plate).also { it.copyShapeFrom(s); it.ownFilament = s.ownFilament } }
            items.addAll(copies); setSelection(copies.map { it.id }.toSet()); changed()
        } finally { history.endEdit() }
    }
    fun removeSelected() {
        if (selectedIds.isEmpty()) return
        history.beginEdit()
        try { items.removeAll { it.id in selectedIds }; deselect(); changed() } finally { history.endEdit() }
    }
    fun changed() { syncInstances(); dirty = true; invalidateSlice(); refreshRules() }

    private var selectionTransform: SelectionTransforms? = null
    fun editSelection(action: () -> Unit) {
        history.beginEdit()
        try { action() } finally { history.endEdit() }
    }
    fun beginSelectionTransform(id: Int) {
        if (id !in selectedIds) selectObject(id)
        if (id !in selectedIds) return
        history.beginEdit()
        selectionTransform = SelectionTransforms(selectedItems(), id)
    }
    fun endSelectionTransform() { selectionTransform = null; history.endEdit() }
    fun moveSelection(id: Int, x: Float, y: Float) {
        if (id !in selectedIds) return
        val transform = selectionTransform ?: SelectionTransforms(selectedItems(), id)
        transform.move(x, y); changed()
    }
    fun turnSelection(id: Int, angle: Float) {
        if (id !in selectedIds) return
        val transform = selectionTransform ?: SelectionTransforms(selectedItems(), id)
        transform.turn(angle); changed()
    }
    fun resizeSelection(id: Int, scale: Float) {
        if (id !in selectedIds) return
        val transform = selectionTransform ?: SelectionTransforms(selectedItems(), id)
        transform.resize(scale); changed()
    }

    /**
     * Orca's instances: another copy of the same object. It shares the object's mesh, filament, settings, parts, height ranges, layer
     * profile and paint, so a change to the object reaches every instance; its place, turn and size are its own. An instance whose
     * object is gone becomes an object in its own right.
     */
    fun addInstance() {
        if (selectedIds.size != 1) return
        val s = items.firstOrNull { it.id == selected } ?: return
        val root = s.instanceOf ?: s.id
        nextId = maxOf(nextId, (items.maxOfOrNull { it.id } ?: 0) + 1)
        items += PrepItem(nextId++, s.name.removeSuffix(" (instance)") + " (instance)", s.mesh, s.x + 10, s.y + 10, s.rotZ, s.scale, s.slot, s.paintSlots.toList(), s.sources, s.settings.toMap(), s.plate)
            .also { it.copyShapeFrom(s); it.instanceOf = root; it.ownFilament = s.ownFilament }
        changed(); arrange()
    }
    fun syncInstances() {
        val byId = items.associateBy { it.id }
        items.forEach { i ->
            val root = i.instanceOf ?: return@forEach
            val src = byId[root]?.takeIf { it !== i } ?: run { i.instanceOf = null; return@forEach }
            if (i.slot != src.slot) i.slot = src.slot
            if (i.paintSlots.toList() != src.paintSlots.toList()) { i.paintSlots.clear(); i.paintSlots.addAll(src.paintSlots) }
            if (i.settings.toMap() != src.settings.toMap()) { i.settings.clear(); i.settings.putAll(src.settings) }
            if (i.parts.toList() != src.parts.toList()) { i.parts.clear(); i.parts.addAll(src.parts) }
            if (i.ranges.toList() != src.ranges.toList()) { i.ranges.clear(); i.ranges.addAll(src.ranges) }
            if (i.layerProfile.toList() != src.layerProfile.toList()) { i.layerProfile.clear(); i.layerProfile.addAll(src.layerProfile) }
        }
    }
    @Volatile private var sliceGeneration = 0L
    @Volatile private var sliceCancelRequested = false
    private var batchSlices: Map<String, SliceState.Done> = emptyMap()

    private fun invalidateSlice() {
        discardMeshEditPreview()
        sliceGeneration++
        batchSlices = emptyMap()
        if (slice is SliceState.Running) engine?.cancel()
        slice = SliceState.Idle
        showPreview = false
    }

    private fun showCompletedSlice(completed: SliceState.Done, openPreview: Boolean = true) {
        slice = completed
        previewFirstLayer = 0
        previewLayer = ((completed.preview?.layers?.size ?: 1) - 1).coerceAtLeast(0)
        previewMove = Int.MAX_VALUE
        showPreview = openPreview
    }

    /** Shelf packing by footprint, largest first, with a 6 mm gap, centred on the bed. Objects that can't fit are reported. */
    // --- Plate tools (PlateOps): Orca's Arrange, Auto orient, Split and Cut, run on the engine off the UI thread.
    /** What the plate tools are doing now ("Arranging"...), or null. */
    var toolBusy by mutableStateOf<String?>(null)
    private var toolJob: Job? = null

    private fun plateWork() = File(app.paths.cache, "plate").apply { mkdirs() }

    private fun runTool(label: String, block: suspend () -> Unit) {
        if (toolBusy != null) return
        toolBusy = label
        // On the UI thread: the plate's state changes there; the engine work inside runs on IO.
        lateinit var job: Job
        job = app.scope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
            try { block() }
            catch (_: CancellationException) { notice = "$label was cancelled. Nothing was changed." }
            catch (e: Exception) { notice = "$label didn't work: ${e.message ?: e.javaClass.simpleName}" }
            finally {
                if (toolJob === job) { toolJob = null; toolBusy = null }
                val discarded = aiPendingDiscards.toList(); aiPendingDiscards.clear()
                discarded.forEach { it.discard() }
            }
        }
        toolJob = job
        job.start()
    }

    /** Stops a long-running plate/modeling operation. State is only committed after its engine result returns. */
    fun cancelTool() { toolJob?.cancel(CancellationException("Cancelled by the user")) }

    private suspend fun <T> modelingIo(block: () -> T): T = runInterruptible(Dispatchers.IO, block)

    /** The same object with a new mesh (its turn, size, slot, colours and settings kept). */
    private fun withMesh(old: PrepItem, mesh: Mesh, id: Int = old.id, name: String = old.name, x: Float = old.x, y: Float = old.y) =
        PrepItem(id, name, mesh, x, y, old.rotZ, old.scale, old.slot, old.paintSlots.toList(), old.sources, old.settings.toMap(), old.plate).also { it.ownFilament = old.ownFilament; it.copyShapeFrom(old) }

    private fun replace(old: PrepItem, vararg new: PrepItem) {
        val i = items.indexOfFirst { it.id == old.id }.takeIf { it >= 0 } ?: return
        items.removeAt(i); items.addAll(i, new.toList())
    }

    /**
     * Orca's Arrange (engine: ModelArrange/Arrange with the printer's clearances and the print's brim/skirt spacing).
     * Objects that don't fit stay where they are and are named.
     */
    fun arrange() { if (plateItems().isNotEmpty()) runTool("Arrange") { arrangeNow() } }

    private suspend fun arrangeNow() {
            val snapshot = plateItems()
            val (bw, bd) = bed
            val dir = profileDir()
            val placed = withContext(Dispatchers.IO) {
                PlateOps.arrange(listOf("machine.json", "process.json", "filament.json").map { File(dir, it) },
                    (printerOverrides() + effectiveOverrides() - FLUSH_KEY), snapshot, bw, bd, work = plateWork())
            }
            // What doesn't fit goes onto new plates after the last, as Orca's arrange fills further plates.
            val firstNew = plateNames.size
            var moved = 0
            snapshot.zip(placed).forEach { (item, p) ->
                item.x += p.dx.toFloat(); item.y += p.dy.toFloat(); item.rotZ = (item.rotZ + p.rotationDeg.toFloat()) % 360f
                if (p.plate > 0) {
                    val target = firstNew + p.plate - 1
                    while (plateNames.size <= target) { plateNames += "Plate ${plateNames.size + 1}"; plateIds += UUID.randomUUID().toString() }
                    item.plate = target; moved++
                }
            }
            if (moved > 0) notice = "$moved object${if (moved == 1) "" else "s"} didn't fit, so Arrange put ${if (moved == 1) "it" else "them"} on another plate."
            changed()
    }

    // --- Object tools beyond the plate ops: parts, height ranges, variable layer height, size and mirror (the engine takes them as slice request lines).
    /** Adds an extra volume to [item], centred on its bounding box (coordinates are from that centre) at a quarter of its largest side. */
    fun addPart(item: PrepItem, kind: String, shape: String) {
        val b = item.bounds; val side = maxOf(b[3] - b[0], b[4] - b[1], b[5] - b[2]) / 4.0
        item.parts += ProjectManifest.ObjectPart(kind, shape, listOf(0.0, 0.0, 0.0), listOf(side, side, side)); changed()
    }
    fun updatePart(item: PrepItem, index: Int, part: ProjectManifest.ObjectPart) { if (index in item.parts.indices) { item.parts[index] = part; changed() } }
    fun removePart(item: PrepItem, index: Int) { if (index in item.parts.indices) { item.parts.removeAt(index); changed() } }
    /** Adds a height range from 20% to 40% of the object's height that prints with a finer layer, the setting Orca's range starts with. */
    fun addRange(item: PrepItem) {
        val h = item.height.toDouble()
        item.ranges += ProjectManifest.HeightRange(Math.round(h * 20) / 100.0, Math.round(h * 40) / 100.0, mapOf("layer_height" to (overrides["layer_height"] ?: preset.overrides["layer_height"] ?: "0.2")))
        changed()
    }
    fun updateRange(item: PrepItem, index: Int, range: ProjectManifest.HeightRange) { if (index in item.ranges.indices) { item.ranges[index] = range; changed() } }
    fun removeRange(item: PrepItem, index: Int) { if (index in item.ranges.indices) { item.ranges.removeAt(index); changed() } }
    fun clearLayerProfile(item: PrepItem) { item.layerProfile.clear(); changed() }
    /** Orca's adaptive layer height for [item] (finer where the surface is shallow), at [quality] 0..1, from the engine. */
    fun adaptiveLayers(item: PrepItem, quality: Double) = runTool("Adaptive layers") {
        val dir = profileDir()
        val profile = withContext(Dispatchers.IO) {
            PlateOps.adaptiveLayers(listOf("machine.json", "process.json", "filament.json").map { File(dir, it) }, printerOverrides() + effectiveOverrides() - FLUSH_KEY, item.mesh,
                Triple(kotlin.math.abs(item.ex.toDouble()), kotlin.math.abs(item.ey.toDouble()), kotlin.math.abs(item.ez.toDouble())), quality, plateWork())
        }
        item.layerProfile.clear(); item.layerProfile.addAll(profile); changed()
    }

    // --- Orca's settings rules (OptionRules): greyed-out and hidden settings, the Setting Overrides page, the IDEX layout choices and the checks.
    var rules by mutableStateOf<OptionRules.Result?>(null)
    var ruleChecks by mutableStateOf<OptionRules.Result?>(null)
    private var rulesJob: kotlinx.coroutines.Job? = null
    /** Asks the engine for the rules of the current configuration (off the UI thread; a newer change supersedes an older answer). */
    fun refreshRules() {
        rulesJob?.cancel()
        val dir = runCatching { profileDir() }.getOrNull() ?: return
        val files = listOf("machine.json", "filament.json", "process.json").map { File(dir, it) }
        val settings = printerOverrides() + effectiveOverrides() - FLUSH_KEY
        val variable = items.any { it.hasVariableLayer }
        val filaments = maxOf(1, allSlots().size)
        rulesJob = app.scope.launch(Dispatchers.IO) {
            val states = runCatching { OptionRules.states(files, settings, filamentCount = filaments, printerName = profileId) }.getOrNull()
            val checks = runCatching { OptionRules.checks(files, settings, variable, filaments, profileId) }.getOrNull()
            withContext(Dispatchers.Main) { rules = states; ruleChecks = checks }
        }
    }

    // --- AI auto-paint (AutoPaint): the engine segments the model; the user's own provider says which slot goes where.
    var autoPaintSettings by mutableStateOf(AutoPaint.load(app.paths.config.resolve("auto-paint.json")))
    fun saveAutoPaintSettings(s: AutoPaint.Settings) { autoPaintSettings = s; AutoPaint.save(app.paths.config.resolve("auto-paint.json"), s) }

    /** Paints the selected object with the plate's slots as [hint] asks; the colours print in the same slot numbers they are offered under. */
    /** The reviewed flow: show what will be sent ([aiPrepare]), send it ([aiSend]), then keep ([aiApply]) or drop ([aiDiscard]) the answer. */
    var aiPrepared by mutableStateOf<AutoPaint.Prepared?>(null)
    var aiAnswer by mutableStateOf<AutoPaintReview?>(null); private set
    var aiItemId by mutableStateOf<Int?>(null)
    private var aiSource: PrepItem? = null
    private var aiSourceSlots = emptyList<Int>()
    private var aiSourceSlot = 1
    var aiPreviewMesh by mutableStateOf<Mesh?>(null); private set
    var aiShowOriginal by mutableStateOf(false)
    var aiCleanBoundaries by mutableStateOf(false); private set
    var aiCleanedFaces by mutableStateOf(0); private set
    private var aiGeneration = 0
    private val aiPendingDiscards = mutableListOf<AutoPaint.Prepared>()

    fun aiCorrect(region: Int, slot: Int) {
        if (toolBusy != null || slot !in (aiPrepared?.filaments?.map { it.index }.orEmpty() + 0)) return
        val answer = aiAnswer ?: return
        if (region !in answer.slots.indices) return
        aiAnswer = answer.corrected(region, slot); refreshAiPreview()
    }
    fun aiResetCorrections() { if (toolBusy == null) { aiAnswer = aiAnswer?.reset(); refreshAiPreview() } }
    fun aiKeepOriginal() {
        if (toolBusy != null) return
        aiAnswer = aiAnswer?.let { AutoPaintReview(it.faceRegions, List(it.slots.size) { 0 }, it.originalSlots) }
        refreshAiPreview()
    }
    fun aiSetCleanup(enabled: Boolean) { if (toolBusy == null) { aiCleanBoundaries = enabled; refreshAiPreview() } }
    fun aiRetryPreview() { if (toolBusy == null) refreshAiPreview() }
    private fun refreshAiPreview() {
        aiPreviewMesh = null; aiCleanedFaces = 0; aiShowOriginal = false
        runTool("Updating paint draft") { rebuildAiPreview() }
    }
    private suspend fun rebuildAiPreview() {
        val source = aiSource ?: return
        val answer = aiAnswer ?: return
        val generation = aiGeneration; val mapping = aiSourceSlots; val baseSlot = aiSourceSlot; val cleanup = aiCleanBoundaries
        val (mesh, count) = withContext(Dispatchers.IO) {
            val raw = answer.faces()
            val slots = if (cleanup) cleanPaintBoundaries(source.mesh, raw).also { cleaned ->
                // Explicit owner corrections take precedence over automatic cleanup.
                for (f in cleaned.indices) if (answer.slots[answer.faceRegions[f]] != answer.originalSlots[answer.faceRegions[f]]) cleaned[f] = raw[f]
            } else raw
            AutoPaint.applied(AutoPaintReview.base(source.mesh, mapping, baseSlot), slots) to slots.indices.count { slots[it] != raw[it] }
        }
        if (generation != aiGeneration || aiAnswer !== answer) return
        aiCleanedFaces = count; aiPreviewMesh = mesh
        aiShowOriginal = false
    }

    private fun aiFilaments() = allSlots().map { (id, label) ->
        com.nozzleitall.printer.ext.AutoPaintFormat.Filament(id, label, slotHex(id) ?: "#808080")
    }

    private fun currentAiTarget(prepared: AutoPaint.Prepared): PrepItem? {
        val target = selectedItems().singleOrNull()?.takeIf {
            it.id == aiItemId && it.plate == currentPlate && it.mesh === aiSource?.mesh &&
                it.paintSlots.toList() == aiSourceSlots && it.slot == aiSourceSlot
        }
        if (target != null && prepared.filaments == aiFilaments()) return target
        notice = "The object or its filament palette changed. Prepare auto-paint again."
        aiDiscard()
        return null
    }

    fun aiPrepare(hint: String) {
        if (toolBusy != null || selectedIds.size != 1) return
        val item = items.firstOrNull { it.id == selected } ?: return
        val filaments = aiFilaments()
        if (filaments.size < 2) { notice = "AI auto-paint needs at least two filaments to choose between."; return }
        aiDiscard()
        val generation = aiGeneration
        val sourceSlots = item.paintSlots.toList()
        val sourceSlot = item.slot
        aiItemId = item.id
        runTool("Preparing the views") {
            val prepared = withContext(Dispatchers.IO) { AutoPaint.prepare(item.mesh, item.name, filaments, hint, plateWork()) }
            if (generation != aiGeneration || selectedIds != setOf(item.id)) { prepared.discard(); return@runTool }
            aiPrepared = prepared
            aiSource = item
            aiSourceSlots = sourceSlots
            aiSourceSlot = sourceSlot
            currentAiTarget(prepared)
        }
    }

    fun aiSend(reviewed: AutoPaint.Prepared, reviewedProvider: com.nozzleitall.printer.ext.AutoPaintFormat.Provider) {
        if (toolBusy != null) return
        if (selectedIds != setOfNotNull(aiItemId)) { aiDiscard(); return }
        val prepared = aiPrepared ?: return
        if (prepared !== reviewed) {
            notice = "The prepared request changed. Review the new request before sending."
            return
        }
        currentAiTarget(prepared) ?: return
        val provider = autoPaintSettings.provider ?: run { notice = "Choose how to ask first: a command, or an OpenAI-compatible server."; return }
        if (provider != reviewedProvider) {
            notice = "The AI recipient changed. Go back and review the request again."
            return
        }
        if (!AutoPaint.requestDisclosure(provider).valid) {
            notice = "Enter a valid server address before sending."
            return
        }
        val generation = aiGeneration
        aiPreviewMesh = null
        runTool("Waiting for the answer") {
            if (generation != aiGeneration || aiPrepared !== prepared || currentAiTarget(prepared) == null) return@runTool
            val answer = withContext(Dispatchers.IO) { AutoPaint.askReview(prepared, provider) }
            if (generation != aiGeneration || aiPrepared !== prepared || currentAiTarget(prepared) == null) return@runTool
            aiAnswer = answer
            rebuildAiPreview()
        }
    }

    fun aiApply() {
        if (toolBusy != null) return
        if (selectedIds != setOfNotNull(aiItemId)) { aiDiscard(); return }
        val prepared = aiPrepared ?: return
        val mesh = aiPreviewMesh ?: return
        if (aiAnswer?.slots?.all { it == 0 } == true) { aiDiscard(); return }
        val item = currentAiTarget(prepared) ?: return
        val painted = withMesh(item, mesh)
        painted.paintSlots.clear(); painted.paintSlots.addAll(1..prepared.filaments.maxOf { it.index })
        history.beginEdit()
        replace(item, painted); selected = painted.id
        changed()
        history.endEdit()
        aiDiscard()
    }

    fun aiDiscard() {
        aiGeneration++
        // Do not remove views while a provider is still reading them; runTool completion owns that cleanup.
        val old = aiPrepared
        if (toolBusy == null) old?.discard()
        else if (old != null) aiPendingDiscards.add(old)
        aiPrepared = null; aiAnswer = null; aiItemId = null; aiSource = null; aiPreviewMesh = null
        aiSourceSlots = emptyList(); aiCleanedFaces = 0; aiShowOriginal = false; aiCleanBoundaries = false
    }

    // --- Paint tools (PaintOps): Orca's support, seam, colour and fuzzy-skin painting, run on the engine's TriangleSelector.
    /** The kind being painted on the selected object, or null when painting is off. */
    var paintKind by mutableStateOf<PaintOps.Kind?>(null)
    /** What a stroke paints: support/seam 1 = enforcer and 2 = blocker, fuzzy skin 1; colour: the slot. 0 erases. */
    var paintState by mutableStateOf(1)
    var paintMode by mutableStateOf(PaintOps.Mode.BRUSH)
    /** Brush radius in millimetres on the plate (the engine gets it in the model's own units). */
    var paintRadius by mutableStateOf(3f)
    var paintBusy by mutableStateOf(false)
    private val paintQueue = ArrayDeque<Pair<Int, List<PaintRay>>>()
    private class PaintStep(val id: Int, val kind: PaintOps.Kind, val before: Mesh, val slots: List<Int>)
    private val paintHistory = ArrayDeque<PaintStep>()
    val canUndoPaint: Boolean get() = paintHistory.isNotEmpty()

    /** The file's filament number that prints in [slot] for [item]: an existing one, or a new one added to its paint slots. */
    private fun colourStateForSlot(item: PrepItem, slot: Int): Int {
        val at = item.paintSlots.indexOf(slot)
        if (at >= 0) return at + 1
        item.paintSlots.add(slot)
        return item.paintSlots.size
    }

    /** Applies the strokes the viewer collected to the object [id], one batch at a time so each builds on the last. */
    fun paintStroke(id: Int, rays: List<PaintRay>) {
        if (paintKind == null || rays.isEmpty()) return
        paintQueue.addLast(id to rays)
        if (paintBusy) return
        paintBusy = true
        app.scope.launch(Dispatchers.Main) {
            try {
                while (paintQueue.isNotEmpty()) {
                    val (target, batch) = paintQueue.removeFirst()
                    val kind = paintKind ?: break
                    val item = items.firstOrNull { it.id == target } ?: continue
                    val slotsBefore = item.paintSlots.toList()
                    val state = if (kind == PaintOps.Kind.COLOUR && paintState > 0) colourStateForSlot(item, paintState) else paintState
                    val strokes = batch.map { PaintOps.Stroke(it.origin, it.dir, it.radius, state) }
                    val mode = paintMode
                    val mesh = withContext(Dispatchers.IO) { PaintOps.stroke(item.mesh, kind, strokes, mode) }
                    val fresh = withMesh(item, mesh)
                    paintHistory.addLast(PaintStep(target, kind, item.mesh, slotsBefore)); while (paintHistory.size > 30) paintHistory.removeFirst()
                    replace(item, fresh); changed()
                }
            } catch (e: Exception) { notice = "Painting didn't work: ${e.message ?: e.javaClass.simpleName}" }
            finally { paintBusy = false }
        }
    }

    /** Takes back the last paint stroke (or clear). */
    fun undoPaint() {
        val step = paintHistory.removeLastOrNull() ?: return
        val item = items.firstOrNull { it.id == step.id } ?: return
        val fresh = withMesh(item, step.before)
        fresh.paintSlots.clear(); fresh.paintSlots.addAll(step.slots)
        replace(item, fresh); changed()
    }

    /** Removes all paint of [kind] from the selected object. */
    fun clearPaint(kind: PaintOps.Kind) {
        val item = items.firstOrNull { it.id == selected } ?: return
        if (kind.of(item.mesh) == null) return
        paintHistory.addLast(PaintStep(item.id, kind, item.mesh, item.paintSlots.toList())); while (paintHistory.size > 30) paintHistory.removeFirst()
        replace(item, withMesh(item, kind.with(item.mesh, null))); changed()
    }

    fun stopPainting() { paintKind = null; paintQueue.clear() }

    /** Orca's Auto orient for the selected object, or every object when none is selected. */
    fun orient() {
        val targets = plateItems().filter { selectedIds.isEmpty() || it.id in selectedIds }.ifEmpty { return }
        runTool("Auto orient") {
            targets.forEach { item ->
                val mesh = withContext(Dispatchers.IO) { PlateOps.orient(item.mesh, plateWork()) }
                replace(item, withMesh(item, mesh))
            }
            changed()
        }
    }

    /** Orca's Split to objects: each separate part of the selected object becomes its own object, where it was. */
    fun splitSelected() {
        if (selectedIds.size != 1) return
        val item = items.firstOrNull { it.id == selected } ?: return
        runTool("Split") {
            val parts = withContext(Dispatchers.Default) { PlateOps.split(item.mesh) }
            if (parts.size < 2) { notice = "${item.name} is one piece, so there's nothing to split."; return@runTool }
            val place = item.placement()
            val made = parts.mapIndexed { i, m ->
                val b = m.bounds(); val c = place.apply((b[0] + b[3]) / 2.0, (b[1] + b[4]) / 2.0, 0.0)
                withMesh(item, m, if (i == 0) item.id else nextId++, "${item.name} ${i + 1}", c[0].toFloat(), c[1].toFloat())
            }
            replace(item, *made.toTypedArray())
            selected = made.first().id
            changed()
        }
    }

    // --- Modeling tools: the Compose interaction layer over the pinned engine's Orca/libslic3r mesh operations.
    var lastMeasurement by mutableStateOf<ModelingOps.Measurement?>(null)
        private set

    data class MeshEditPreview(
        val title: String,
        val primary: PrepItem,
        val other: PrepItem?,
        val result: PrepItem,
        internal val generation: Long,
        val targetTriangles: Int? = null,
        val repairStats: ModelingOps.RepairPreview? = null,
    )

    var meshEditPreview by mutableStateOf<MeshEditPreview?>(null)
        private set

    fun discardMeshEditPreview() { meshEditPreview = null }

    private fun matchesMeshEdit(primary: PrepItem, other: PrepItem?, generation: Long): Boolean =
        generation == sliceGeneration && currentPlate == primary.plate && selected == primary.id &&
            selectedIds == setOfNotNull(primary.id, other?.id) &&
            items.any { it === primary } && (other == null || items.any { it === other })

    fun previewBooleanSelected(mode: ModelingOps.BooleanMode) {
        if (toolBusy != null || selectedIds.size != 2) return
        val primary = items.firstOrNull { it.id == selected } ?: return
        val other = items.firstOrNull { it.id in selectedIds && it.id != primary.id } ?: return
        val generation = sliceGeneration
        val primaryPlacement = primary.placement()
        val otherPlacement = other.placement()
        discardMeshEditPreview()
        runTool("Boolean ${mode.wire}") {
            val mesh = modelingIo {
                ModelingOps.boolean(ModelingOps.transform(primary.mesh, primaryPlacement),
                    ModelingOps.transform(other.mesh, otherPlacement), mode, plateWork())
            }
            if (!matchesMeshEdit(primary, other, generation)) return@runTool
            val bounds = mesh.bounds()
            val result = PrepItem(primary.id, "${primary.name} ${mode.wire}", mesh,
                (bounds[0] + bounds[3]) / 2f, (bounds[1] + bounds[4]) / 2f, slot = primary.slot,
                sources = primary.sources, settings = primary.settings.toMap(), plate = primary.plate).also {
                it.ownFilament = primary.ownFilament
            }
            meshEditPreview = MeshEditPreview("Boolean ${mode.wire}", primary, other, result, generation)
        }
    }

    fun previewSimplifySelected(targetTriangles: Int) {
        if (toolBusy != null || selectedIds.size != 1) return
        val item = items.firstOrNull { it.id == selected } ?: return
        if (targetTriangles !in 4 until item.mesh.triangleCount) return
        val generation = sliceGeneration
        discardMeshEditPreview()
        runTool("Simplify") {
            val mesh = modelingIo { ModelingOps.simplify(item.mesh, targetTriangles, plateWork()) }
            if (!matchesMeshEdit(item, null, generation)) return@runTool
            val result = withMesh(item, mesh)
            meshEditPreview = MeshEditPreview("Simplify mesh", item, null, result, generation, targetTriangles)
        }
    }

    fun applyMeshEditPreview() {
        if (toolBusy != null) return
        val preview = meshEditPreview ?: return
        discardMeshEditPreview()
        val (title, primary, other, result, generation) = preview
        if (!matchesMeshEdit(primary, other, generation)) {
            notice = "The plate or selection changed. Generate a new preview."
            return
        }
        history.beginEdit()
        try {
            replace(primary, result)
            if (other != null) {
                items.removeAll { it.id == other.id }
                setSelection(setOf(result.id))
            }
            if (!result.mesh.hasAnyPaint && (primary.mesh.hasAnyPaint || other?.mesh?.hasAnyPaint == true))
                notice = "$title changed the mesh topology, so painted facets were cleared."
            changed()
        } finally { history.endEdit() }
    }

    fun previewRepairSelected() {
        if (toolBusy != null || selectedIds.size != 1) return
        val item = items.firstOrNull { it.id == selected } ?: return
        val generation = sliceGeneration
        discardMeshEditPreview()
        runTool("Check mesh") {
            val (mesh, stats) = modelingIo { ModelingOps.repair(item.mesh, plateWork()) }
            if (!matchesMeshEdit(item, null, generation)) return@runTool
            val result = withMesh(item, mesh)
            result.paintSlots.clear()
            meshEditPreview = MeshEditPreview("Repair mesh", item, null, result, generation, repairStats = stats)
        }
    }

    fun laySelectedOnFace(normal: DoubleArray) {
        if (selectedIds.size != 1) return
        val item = items.firstOrNull { it.id == selected } ?: return
        runTool("Lay on face") {
            val mesh = modelingIo { ModelingOps.layOnFace(item.mesh, normal) }
            history.beginEdit()
            try { replace(item, withMesh(item, mesh)); changed() } finally { history.endEdit() }
        }
    }

    fun measurePoints(vararg points: DoubleArray) = runTool("Measure") {
        lastMeasurement = modelingIo { ModelingOps.measure(*points) }
    }

    /** Creates Orca-engine text as a new object, or applies it to the selected object's horizontal top surface. */
    internal fun addCad(recipe: CadRecipe) = addCadMesh("CAD model") { ModelingOps.cadMesh(recipe, plateWork()) }

    internal fun importStep(source: File) = addCadMesh(source.nameWithoutExtension) { ModelingOps.stepMesh(source, plateWork()) }

    private fun addCadMesh(objectName: String, create: () -> Mesh) {
        val plate = currentPlate
        val plateId = plateIds.getOrNull(plate)
        runTool("CAD") {
            val mesh = modelingIo { create() }
            check(currentPlate == plate && plateIds.getOrNull(plate) == plateId) { "The active plate changed." }
            addLocalMesh(objectName, mesh, plate)
        }
    }

    internal val generatedGeometry by lazy { GeneratedGeometryStore(File(app.paths.data, "meshy-assets")) }

    internal fun inspectMeshy(asset: GeneratedGeometryStore.Asset, sizeMm: Double): ReviewedGeometry {
        val mesh = GeneratedGeometryStore.scaledModel(asset.bytes, sizeMm)
        generatedGeometry.cache(asset)
        val (repaired, report) = ModelingOps.repair(mesh, plateWork())
        GeneratedGeometryStore.validateLocalMesh(repaired, sizeMm)
        return ReviewedGeometry(asset.taskId, mesh, repaired, report, asset.credits, sizeMm)
    }

    internal fun addMeshyModel(reviewed: ReviewedGeometry, useRepair: Boolean) {
        val mesh = if (useRepair) reviewed.repaired else reviewed.original
        GeneratedGeometryStore.validateLocalMesh(mesh, reviewed.sizeMm)
        addLocalMesh("Meshy " + reviewed.taskId.take(8), mesh, currentPlate)
    }

    private fun addLocalMesh(objectName: String, mesh: Mesh, plate: Int) {
        history.beginEdit()
        try {
            val (width, depth) = bed
            val item = PrepItem(nextId++, objectName, mesh, width / 2, depth / 2,
                slot = allSlots().firstOrNull()?.first ?: 1, plate = plate)
            items += item
            selectObject(item.id)
            changed()
        } finally { history.endEdit() }
    }

    internal fun openCadRecipe(file: File, opened: (CadRecipe) -> Unit) = runTool("Open CAD recipe") {
        opened(modelingIo { CadRecipe.read(file) })
    }

    internal fun saveCadRecipe(recipe: CadRecipe, file: File) = runTool("Save CAD recipe") {
        modelingIo { ModelingOps.saveCadRecipe(recipe, file) }
        notice = "CAD recipe saved."
    }

    internal fun exportCadStep(recipe: CadRecipe, file: File) = runTool("Export STEP") {
        modelingIo { ModelingOps.exportStep(recipe, file) }
        notice = "STEP exported."
    }

    fun addText(value: String, sizeMm: Double, depthMm: Double, mode: ModelingOps.ReliefMode) {
        addRelief("Text", value.trim().take(32).ifBlank { "Text" }, mode) {
            ModelingOps.text(value, sizeMm, depthMm, plateWork())
        }
    }

    /** Creates an Orca-engine SVG as a new object, or applies it to the selected object's horizontal top surface. */
    fun addSvg(source: File, depthMm: Double, toleranceMm: Double, mode: ModelingOps.ReliefMode) {
        addRelief("SVG", source.nameWithoutExtension.ifBlank { "SVG" }, mode) {
            ModelingOps.svg(source, depthMm, toleranceMm, plateWork())
        }
    }

    private fun addRelief(label: String, objectName: String, mode: ModelingOps.ReliefMode, create: () -> Mesh) {
        val target = selectedItems().singleOrNull()
        if (mode != ModelingOps.ReliefMode.STANDALONE && target == null) {
            notice = "Select one object before adding raised or engraved ${label.lowercase()}."
            return
        }
        runTool(label) {
            val generated = modelingIo { create() }
            val result = if (mode == ModelingOps.ReliefMode.STANDALONE) generated else modelingIo {
                val base = ModelingOps.transform(target!!.mesh, target.placement())
                val baseBounds = base.bounds()
                val reliefBounds = generated.bounds()
                val x = ((baseBounds[0] + baseBounds[3]) - (reliefBounds[0] + reliefBounds[3])).toDouble() / 2.0
                val y = ((baseBounds[1] + baseBounds[4]) - (reliefBounds[1] + reliefBounds[4])).toDouble() / 2.0
                val depth = (reliefBounds[5] - reliefBounds[2]).toDouble()
                val overlap = minOf(0.05, depth * 0.1)
                val z = if (mode == ModelingOps.ReliefMode.RAISED)
                    baseBounds[5].toDouble() - reliefBounds[2].toDouble() - overlap
                else
                    baseBounds[5].toDouble() - reliefBounds[5].toDouble() + overlap
                val positioned = ModelingOps.translate(generated, x, y, z)
                ModelingOps.boolean(base, positioned,
                    if (mode == ModelingOps.ReliefMode.RAISED) ModelingOps.BooleanMode.UNION else ModelingOps.BooleanMode.SUBTRACT,
                    plateWork())
            }
            history.beginEdit()
            try {
                if (mode == ModelingOps.ReliefMode.STANDALONE) {
                    val (bw, bd) = bed
                    val made = PrepItem(nextId++, objectName, result, bw / 2, bd / 2,
                        slot = allSlots().firstOrNull()?.first ?: 1, plate = currentPlate)
                    items += made
                    setSelection(setOf(made.id))
                } else {
                    val current = items.firstOrNull { it.id == target!!.id }
                        ?: throw IllegalStateException("The selected object is no longer on this plate.")
                    val bounds = result.bounds()
                    val made = PrepItem(current.id, current.name, result,
                        (bounds[0] + bounds[3]) / 2f, (bounds[1] + bounds[4]) / 2f,
                        slot = current.slot, sources = current.sources, settings = current.settings.toMap(), plate = current.plate)
                        .also { it.ownFilament = current.ownFilament }
                    replace(current, made)
                    setSelection(setOf(made.id))
                    if (current.mesh.isPainted)
                        notice = "$label changed the mesh topology, so painted facets were cleared."
                }
                changed()
            } finally { history.endEdit() }
        }
    }

    /** Scales the selected object so the most recently measured span becomes [targetMm]. */
    fun scaleSelectedFromMeasurement(targetMm: Double) {
        val item = selectedItems().singleOrNull() ?: run {
            notice = "Select one object before scaling from a measurement."
            return
        }
        val measured = lastMeasurement?.distance?.takeIf { it.isFinite() && it > 0.0 } ?: run {
            notice = "Measure two different points before scaling from a measurement."
            return
        }
        val ratio = targetMm / measured
        if (!ratio.isFinite() || ratio <= 0.0 || ratio > 10_000.0) {
            notice = "Enter a positive target measurement within the supported scale range."
            return
        }
        editSelection { resizeSelection(item.id, ratio.toFloat()) }
    }

    /**
     * Orca's Cut with a horizontal plane [heightMm] above the selected object's base: both halves are kept, each on the
     * bed, then the plate is arranged. Painted colours don't carry over to the halves.
     */
    fun cutSelected(heightMm: Float) {
        if (selectedIds.size != 1) return
        val item = items.firstOrNull { it.id == selected } ?: return
        if (heightMm <= 0f || heightMm >= item.height) { notice = "Cut between the object's base and its top (0-${"%.1f".format(item.height)} mm)."; return }
        runTool("Cut") {
            val z = item.bounds[2] + heightMm / kotlin.math.abs(item.ez)
            val (upper, lower) = withContext(Dispatchers.IO) { PlateOps.cut(item.mesh, z, plateWork()) }
            val halves = listOfNotNull(lower?.let { withMesh(item, it, item.id, "${item.name} (lower)") },
                upper?.let { withMesh(item, it, nextId++, "${item.name} (upper)", item.x + item.footprintW + 5f) })
            if (halves.isEmpty()) return@runTool
            // A half has no paint of its own; its colours follow the object's material slot.
            halves.forEach { it.paintSlots.clear() }
            if (item.mesh.isPainted) notice = "The cut halves print in ${item.name}'s material; its painted colours aren't kept."
            replace(item, *halves.toTypedArray())
            changed()
            arrangeNow()
        }
    }

    fun outOfBounds(plate: Int = currentPlate): List<PrepItem> { val (bw, bd) = bed
        return plateItems(plate).filter { val r = it.reach; it.x + r[0] < 0 || it.y + r[2] < 0 || it.x + r[1] > bw || it.y + r[3] > bd || it.height > (activeCustomSetup?.heightMm ?: profile?.height ?: 250f) } }

    /**
     * The project as saved (every plate, objects placed where Orca lays plates side by side, plates recorded for other
     * slicers too), or [forSlice]: the current plate alone, on its own bed.
     */
    fun toProject(forSlice: Boolean = false, slicePlate: Int = currentPlate): Project3mf {
        check(presetSnapshotProblem == null) { presetSnapshotProblem ?: "The saved preset needs review." }
        val (bw, bd) = bed
        val plates = if (forSlice) listOf(slicePlate) else plateNames.indices.toList()
        // Sliced for a printer that offers no mixing, an object or painted area on a mixed filament prints with the
        // default slot.
        val unoffered = if (forSlice && offeredMixed().isEmpty()) mixed.indices.map { mixedSlot(it) }.toSet() else emptySet()
        fun sliceSlot(slot: Int) = if (slot in unoffered) 1 else slot
        val m = (manifest ?: app.library.newManifest(name, app.version)).let { base ->
            val p = printer()
            val bundled = bundledProfileDir()
            val applicablePreset = selectedPresetSnapshot(bundled)
            check(presetSnapshot == null || applicablePreset != null) {
                "The selected printer or variant differs from the saved preset. Review it and use selected printer presets before saving or slicing."
            }
            val savedPreset = applicablePreset ?: PresetSnapshot.capture(profileId, bundled, materials())
            base.copy(name = name, revision = base.revision + if (manifest != null && dirty) 1 else 0,
                modifiedBy = ProjectManifest.Producer("Nozzle It All", "desktop", app.version), modifiedAtMillis = System.currentTimeMillis(),
                printer = ProjectManifest.PrinterTarget(profile?.model ?: p?.config?.identity?.model ?: "Unknown printer", base.printer?.firmware, p?.config?.identity?.id,
                    unknown = base.printer?.unknown ?: org.json.JSONObject(), profileId = profileId,
                    family = p?.config?.identity?.family?.id ?: profile?.familyHint,
                    nozzleDiameters = machineVariant()?.let { m -> PrinterSetup.nozzles(machineJson()).map { it.toDoubleOrNull() ?: 0.4 } } ?: base.printer?.nozzleDiameters.orEmpty()),
                plates = plates.mapIndexed { n, i -> ProjectManifest.PlateEntry(n + 1, plateNames[i], plateItems(i).map {
                    ProjectManifest.ObjectEntry(it.id, it.name, sliceSlot(it.slot), paintSlots = it.paintSlots.map { s -> if (s in unoffered) sliceSlot(it.slot) else s }, settings = it.settings.toMap(), parts = it.parts.toList(), heightRanges = it.ranges.toList(), layerProfile = it.layerProfile.toList(), instanceOf = it.instanceOf) },
                    base.plates.firstOrNull { old -> (old.id ?: ProjectManifest.legacyPlateId(base.projectId, old.index)) == plateIds[i] }?.unknown ?: org.json.JSONObject(),
                    plateIds[i]) },
                materials = materials(),
                extensions = PresetSnapshot.withSnapshot(base.extensions, savedPreset),
                settings = ProjectManifest.SettingsChoice(currentProcess()?.let { "process:${it.id}" } ?: preset.name.lowercase(),
                    mapOf("sparse_infill_density" to "$infill%", "enable_support" to if (supports) "1" else "0") + (if (library() == null) preset.overrides else emptyMap()) + effectiveOverrides() +
                    (if (mixed.isEmpty()) emptyMap() else mixedSettings) + printerOverrides(),
                    base.settings.unknown))
        }
        val files = passthrough
        val objects = plates.flatMap { i ->
            val (ox, oy) = if (forSlice) 0.0 to 0.0 else Plates.origin(i, plateNames.size, bw.toDouble(), bd.toDouble())
            plateItems(i).map { ModelObject(it.id, it.name, it.mesh, Transform(it.placement().m.copyOf().also { t -> t[9] += ox; t[10] += oy }),
                sliceSlot(it.slot), it.settings.toMap(), if (forSlice) 0 else i) }
        }
        return Project3mf(objects, metadata + ("Title" to name) + ("Application" to "Nozzle It All ${app.version}"),
            m, files, plateNames = if (forSlice || plateNames.size < 2) emptyList() else plateNames.toList())
    }

    fun save(): File {
        if (!dirty) file?.let { return it }
        val source = file
        val saveCopy = source != null && (source.canonicalFile.parentFile != app.library.dir.canonicalFile || manifest == null)
        val current = toProject()
        val project = if (saveCopy) forkProject(current, name) else current
        val target = if (source == null || saveCopy) app.library.saveNew(project, name)
            else source.also { app.library.save(project, it) }
        file = target; manifest = project.manifest; dirty = false
        return target
    }

    /** A library Save As becomes a new project identity; an outside destination is an export copy. */
    fun saveAs(target: File): File {
        if (file?.canonicalFile == target.canonicalFile) return save()
        check(!target.exists()) { "Save as will not replace an existing file. Choose a new name." }
        check(target.name.endsWith(".3mf", ignoreCase = true)) { "Choose a .3mf filename." }
        val libraryRoot = app.library.dir.canonicalFile
        val parent = target.canonicalFile.parentFile
        check(parent == libraryRoot || !parent.toPath().startsWith(libraryRoot.toPath())) {
            "Projects must be saved directly in the project library, not one of its subfolders."
        }
        val fork = forkProject(toProject(), target.nameWithoutExtension)
        if (parent == libraryRoot) {
            app.library.save(fork, target)
            open(target)
        } else {
            ThreeMf.writeAtomicallyNew(fork, target)
            notice = "A copy was saved outside the project library. The current project remains open here."
        }
        return target
    }

    private fun forkProject(project: Project3mf, copyName: String): Project3mf {
        val original = requireNotNull(project.manifest) { "The project has no manifest to copy." }
        val extensions = JSONObject(original.extensions.toString())
        val desktop = JSONObject(extensions.optJSONObject("desktop")?.toString() ?: "{}")
        manifest?.let { source -> desktop.put("saveAsSource", JSONObject().put("projectId", source.projectId)
            .put("revision", source.revision).put("name", source.name)) }
        extensions.put("desktop", desktop)
        return project.copy(manifest = original.copy(projectId = UUID.randomUUID().toString(), revision = 1,
            name = copyName, modifiedAtMillis = System.currentTimeMillis(), extensions = extensions),
            metadata = project.metadata + ("Title" to copyName))
    }

    fun slice(scope: kotlinx.coroutines.CoroutineScope) = slicePlates(scope, listOf(currentPlate), batch = false)

    fun sliceAll(scope: kotlinx.coroutines.CoroutineScope) =
        slicePlates(scope, plateNames.indices.filter { plateItems(it).isNotEmpty() }, batch = true)

    private fun slicePlates(scope: kotlinx.coroutines.CoroutineScope, plates: List<Int>, batch: Boolean) {
        if (slice is SliceState.Running) return
        batchSlices = emptyMap()
        presetSnapshotProblem?.let { slice = SliceState.Failed(it, GuidanceFailureKind.PRESET); return }
        val bin = SliceEngine.locateEngine() ?: run {
            slice = SliceState.Failed("The slicing engine is missing, invalid, or cannot run. Check the Desktop installation and engine permissions.", GuidanceFailureKind.ENGINE_UNAVAILABLE)
            return
        }
        if (plates.isEmpty() || plates.any { plateItems(it).isEmpty() }) {
            slice = SliceState.Failed("Add a model to the plate before slicing.", GuidanceFailureKind.EMPTY_PLATE)
            return
        }
        for (plate in plates) {
            val outside = outOfBounds(plate)
            if (outside.isNotEmpty()) {
                slice = SliceState.Failed("${plateNames[plate]}: ${outside.joinToString { it.name }} is off the plate. Move it or use Arrange.", GuidanceFailureKind.OUT_OF_BOUNDS)
                return
            }
        }
        val generation = sliceGeneration
        val saved = manifest?.takeIf { !dirty }
        val selectedProfileId = profileId
        val calibrationAtStart = calibration
        val draftIds = plates.associate { plateIds[it] to draftQueuePlateId(plateIds[it]) }
        val machineLabel = slicingMachineLabel
        val nozzleLabel = nozzleToolheadLabel
        val surfaceLabel = effectiveBedType()
        val targets = runCatching {
            plates.associate { plate ->
                val project = toProject(forSlice = true, slicePlate = plate)
                plateIds[plate] to (plateNames[plate] to SliceInputSnapshot(project, preset, supports, infill,
                    project.manifest?.materials.orEmpty(), library() == null, editedFlush()?.toList(),
                    printerOverrides() + (effectiveOverrides() - FLUSH_KEY), offeredMixed(), typedPhysical(), mixSublayers, presetSelection()))
            }
        }.getOrElse { error ->
            slice = SliceState.Failed("The slice inputs could not be prepared: ${error.message}", GuidanceFailureKind.INPUTS)
            return
        }
        sliceCancelRequested = false
        slice = SliceState.Running(0f, "Starting", batch)
        val results = linkedMapOf<String, SliceState.Done>()
        scope.launch(Dispatchers.Main) {
            for ((id, target) in targets) {
                val (plateName, input) = target
                val worker = SliceEngine(bin, app.paths.slices).also { engine = it }
                val completed = try {
                    withContext(Dispatchers.IO) {
                        val settings = if (input.mixRows.isEmpty()) emptyMap()
                            else MixedFilament.sliceOverrides(input.physical, input.mixRows, input.mixSublayers)
                        val (profileDirectory, selectedPreset) = resolvedPreset(input.presetSelection)
                        val bambuBundle = runCatching {
                            JSONObject(File(profileDirectory, "machine.json").readText())
                                .optString("printer_model").startsWith("Bambu Lab ")
                        }.getOrDefault(false)
                        val request = SliceRequest(input.project, profileDirectory, input.preset, input.supports, input.infill,
                            input.materials, applyPreset = input.applyPreset, flushMatrix = input.flush,
                            savedFilamentProfiles = selectedPreset?.filamentProfiles.orEmpty(),
                            extraOverrides = input.overrides + settings, bambuBundle = bambuBundle && calibrationAtStart == null)
                        if (sliceCancelRequested || generation != sliceGeneration) return@withContext SliceState.Cancelled
                        val index = results.size
                        when (val outcome = worker.slice(request) { progress, stage ->
                            if (generation == sliceGeneration) slice = SliceState.Running((index + progress) / targets.size,
                                if (batch) "$plateName · $stage" else stage, batch)
                        }) {
                            is SliceOutcome.Done -> {
                                try {
                                    calibrationAtStart?.let { net.jamesjennison.klippercompanion.Calibration.applyToFile(outcome.gcode, it) }
                                } catch (error: Exception) {
                                    return@withContext SliceState.Failed("Calibration instructions could not be applied: ${error.message}", GuidanceFailureKind.CALIBRATION)
                                }
                                val completeInput = if (calibrationAtStart == null || outcome.inputSha256 == null) outcome.inputSha256
                                    else MessageDigest.getInstance("SHA-256").digest(
                                        "slice-input-and-calibration-v1\n${outcome.inputSha256}\n${calibrationAtStart.encode()}".toByteArray(Charsets.UTF_8)
                                    ).joinToString("") { "%02x".format(it) }
                                val preview = runCatching { (PlannerPreview.read(outcome.gcode) ?: GcodePreview.parse(outcome.gcode))
                                    .takeIf { it.layers.isNotEmpty() } }.getOrNull()
                                SliceState.Done(outcome.copy(inputSha256 = completeInput), preview,
                                    saved?.projectId, saved?.revision, id.takeIf { saved != null }, selectedProfileId,
                                    input.materials.sortedBy { it.slot },
                                    SlicedPlateContext(id, draftIds.getValue(id), plateName, machineLabel, nozzleLabel, surfaceLabel))
                            }
                            is SliceOutcome.Failed -> SliceState.Failed(outcome.message)
                            SliceOutcome.Cancelled -> SliceState.Cancelled
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    SliceState.Failed(error.message ?: "The plate could not be sliced.")
                }
                if (generation != sliceGeneration) return@launch
                if (sliceCancelRequested) {
                    slice = SliceState.Cancelled
                    showPreview = false
                    return@launch
                }
                if (completed !is SliceState.Done) {
                    slice = if (batch && completed is SliceState.Failed) SliceState.Failed("$plateName: ${completed.message}", completed.cause)
                        else completed
                    return@launch
                }
                results[id] = completed
            }
            if (batch) batchSlices = results.toMap()
            val selectedId = plateIds[currentPlate].takeIf { it in results } ?: results.keys.first()
            if (plateIds[currentPlate] != selectedId) {
                currentPlate = plateIds.indexOf(selectedId)
                selected = null
            }
            showCompletedSlice(results.getValue(selectedId))
        }
    }

    fun cancelSlice() {
        if (slice !is SliceState.Running) return
        sliceCancelRequested = true
        engine?.cancel()
    }

    /** Uploads the sliced plate to the printer. Starting the print is a separate, confirmed action. */
    suspend fun upload(entry: PrinterEntry, session: PrinterSession, expectedDigest: String): UploadResult {
        val done = slice as? SliceState.Done ?: return UploadResult.Failed("Slice the plate first.")
        if (done.targetProfileId != null && entry.config.identity.profileId != null &&
            done.targetProfileId != entry.config.identity.profileId)
            return UploadResult.Failed("This file was sliced for another printer profile. Choose a matching printer or slice again.")
        if (entry.guard?.needsReconcile == true)
            return UploadResult.Failed("The previous printer command has an unknown outcome. Check the printer before sending another file.")
        if (entry.status.value.state !in PrinterAction.StartJob("x").allowedStates)
            return UploadResult.Failed("${entry.config.identity.displayName} is not ready to receive this job.")
        if (entry.session !== session || entry.retired) return UploadResult.Failed("The printer connection changed. Review the job again.")
        if (done.result.output !in (entry.capabilities.value?.acceptedOutputs ?: emptySet()))
            return UploadResult.Failed("This connection cannot receive the prepared file format.")
        val remote = "nozzle/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40) +
            "-" + expectedDigest.take(24) + "." + done.result.output
        uploadProgress = 0f
        return try {
            withContext(Dispatchers.IO) {
                if (digestPreparedFile(done.result.printFile) != expectedDigest)
                    return@withContext UploadResult.Failed("The prepared file changed after review. Slice it again before sending.")
                if (entry.session !== session || entry.retired)
                    return@withContext UploadResult.Failed("The printer connection changed. Review the job again.")
                session.upload(done.result.printFile, remote) { s, t -> uploadProgress = if (t > 0) s.toFloat() / t else null }
            }
        } finally { uploadProgress = null }
    }

    init { history.reset() }

}

/** The 2D convex hull (monotone chain) of a mesh's vertices seen from above, relative to ([cx], [cy]), as x,y pairs. */
fun hull2d(v: FloatArray, cx: Float, cy: Float): FloatArray {
    val n = v.size / 3
    if (n == 0) return FloatArray(0)
    val idx = (0 until n).sortedWith(compareBy<Int>({ v[it * 3] }, { v[it * 3 + 1] }))
    fun cross(o: Int, a: Int, b: Int) = (v[a * 3] - v[o * 3]) * (v[b * 3 + 1] - v[o * 3 + 1]) - (v[a * 3 + 1] - v[o * 3 + 1]) * (v[b * 3] - v[o * 3])
    val h = IntArray(2 * n); var k = 0
    for (i in idx) { while (k >= 2 && cross(h[k - 2], h[k - 1], i) <= 0) k--; h[k++] = i }
    val lower = k + 1
    for (i in idx.asReversed()) { while (k >= lower && cross(h[k - 2], h[k - 1], i) <= 0) k--; h[k++] = i }
    val out = FloatArray(2 * maxOf(k - 1, 1))
    for (j in 0 until maxOf(k - 1, 1)) { out[j * 2] = v[h[j] * 3] - cx; out[j * 2 + 1] = v[h[j] * 3 + 1] - cy }
    return out
}
