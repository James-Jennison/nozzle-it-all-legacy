package com.nozzleitall.desktop.prepare

import com.nozzleitall.desktop.PrinterStore
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

/** Versioned data contribution. A recipe only fills fields in the existing CAD editor. */
@ConsistentCopyVisibility
internal data class CadPlugin private constructor(
    val id: String,
    val name: String,
    val release: Int,
    val recipe: CadRecipe,
) {
    fun encode(): String = JSONObject()
        .put("api", 1).put("kind", "cad-recipe").put("id", id).put("name", name)
        .put("release", release).put("recipe", recipe.parameters()).toString(2)

    companion object {
        private const val MAX_BYTES = 8192
        internal val idPattern = Regex("[a-z][a-z0-9-]{1,63}")

        fun sha256(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun readBytes(file: File): ByteArray {
            val path = file.toPath()
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Select a regular plugin file." }
            return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES + 1) }
        }

        fun decode(bytes: ByteArray): CadPlugin {
            require(bytes.size in 1..MAX_BYTES) { "The plugin file is empty or too large." }
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            require('\u0000' !in text) { "The plugin manifest contains a null character." }
            val reader = JSONTokener(text)
            val document = reader.nextValue() as? JSONObject ?: error("The plugin manifest must be an object.")
            require(reader.nextClean() == '\u0000' && document.keySet() == setOf("api", "kind", "id", "name", "release", "recipe")) {
                "The plugin manifest contains unsupported fields."
            }
            require(document.opt("api") == 1 && document.opt("kind") == "cad-recipe") { "Unsupported plugin API or contribution." }
            val id = document.getString("id")
            val name = document.getString("name")
            val release = document.opt("release")
            require(idPattern.matches(id) && name.length in 1..80 && name == name.trim() &&
                name.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } &&
                release is Int && release > 0) { "Invalid plugin identity." }
            val recipe = document.getJSONObject("recipe")
            val circular = when (recipe.opt("shape")) {
                "circle" -> true
                "rectangle" -> false
                else -> error("Unsupported CAD shape.")
            }
            val dimensions = if (circular) setOf("diameterMm") else setOf("widthMm", "heightMm")
            val required = dimensions + setOf("shape", "depthMm")
            require(recipe.keySet().containsAll(required) && recipe.keySet().all { it in required || it == "holeDiameterMm" }) {
                "Unsupported CAD recipe fields."
            }
            fun dimension(key: String): Double {
                val value = (recipe.opt(key) as? Number)?.toDouble() ?: error("Invalid CAD dimension.")
                require(value.isFinite() && value in 0.0..1000.0) { "Plugin dimensions must be between 0 and 1000 mm." }
                return value
            }
            return CadPlugin(id, name, release, CadRecipe(circular,
                dimension(if (circular) "diameterMm" else "widthMm"),
                if (circular) 20.0 else dimension("heightMm"), dimension("depthMm"),
                if (recipe.has("holeDiameterMm")) dimension("holeDiameterMm") else 0.0))
        }
    }
}

internal class PluginStorageProblem(message: String) : IllegalStateException(message)

internal class CadPluginStore(private val root: File) {
    data class Installed(val plugin: CadPlugin, val digest: String, val enabled: Boolean)
    data class Inventory(val installed: List<Installed>, val unreadable: Int)

    fun inventory(): Inventory {
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return Inventory(emptyList(), 0)
        return locked(::readInventory)
    }

    private fun readInventory(): Inventory {
        val files = Files.newDirectoryStream(root.toPath(), "*.niaplugin").use { entries ->
            entries.asSequence().take(129).toList()
        }
        if (files.size > 128) throw PluginStorageProblem("Too many installed plugins.")
        val installed = files.mapNotNull { path ->
            runCatching { readInstalled(path.toFile()).takeIf { "${it.plugin.id}.niaplugin" == path.fileName.toString() } }.getOrNull()
        }.sortedBy { it.plugin.name }
        return Inventory(installed, files.size - installed.size)
    }

    fun install(
        plugin: CadPlugin,
        packageBytes: ByteArray = plugin.encode().toByteArray(Charsets.UTF_8),
        reviewedDigest: String = CadPlugin.sha256(packageBytes),
    ) = locked {
        val bytes = packageBytes.copyOf()
        require(CadPlugin.sha256(bytes) == reviewedDigest && CadPlugin.decode(bytes) == plugin) {
            "The reviewed plugin data changed."
        }
        val dir = root.toPath()
        val inventory = readInventory()
        if (inventory.installed.size + inventory.unreadable >= 128) throw PluginStorageProblem("Too many installed plugins.")
        val target = dir.resolve("${plugin.id}.niaplugin")
        val staged = Files.createTempFile(dir, ".plugin-", ".tmp")
        try {
            Files.write(staged, bytes)
            // Publish a complete file without ever replacing an installed contribution.
            Files.createLink(target, staged)
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    fun load(selected: Installed): CadRecipe = locked {
        val current = checkCurrent(selected)
        if (!current.enabled) throw PluginStorageProblem("This plugin is disabled.")
        current.plugin.recipe
    }

    fun setEnabled(selected: Installed, enabled: Boolean) = locked {
        val current = checkCurrent(selected)
        val marker = disabledMarker(current.plugin.id)
        if (enabled) Files.deleteIfExists(marker.toPath())
        else PrinterStore.atomicWrite(marker, current.digest, private = true)
    }

    private fun disabledMarker(id: String): File = root.resolve(".disabled-$id")

    private fun readInstalled(file: File): Installed {
        val bytes = CadPlugin.readBytes(file)
        val plugin = CadPlugin.decode(bytes)
        val digest = CadPlugin.sha256(bytes)
        // An on-disk replacement never re-enables a disabled ID. A fresh exact-byte selection is
        // required to enable it explicitly. An unreadable marker also leaves the plugin disabled.
        val enabled = Files.notExists(disabledMarker(plugin.id).toPath(), LinkOption.NOFOLLOW_LINKS)
        return Installed(plugin, digest, enabled)
    }

    private fun checkCurrent(selected: Installed): Installed {
        require(CadPlugin.idPattern.matches(selected.plugin.id)) { "Invalid plugin ID." }
        val target = root.resolve("${selected.plugin.id}.niaplugin")
        val current = runCatching { readInstalled(target) }.getOrNull()
        if (current == null || current.digest != selected.digest || current.plugin != selected.plugin)
            throw PluginStorageProblem("The installed plugin changed. Review the current list.")
        return current
    }

    private fun <T> locked(action: () -> T) = synchronized(writeLock) {
        val dir = root.toPath()
        Files.createDirectories(dir)
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw PluginStorageProblem("Plugin storage is unavailable.")
        // Keep the sentinel so every app instance continues to lock the same inode.
        FileChannel.open(dir.resolve(".install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS).use { channel ->
            val lock = channel.tryLock() ?: throw PluginStorageProblem("Plugin storage is busy. Try again.")
            lock.use { action() }
        }
    }

    private companion object {
        val writeLock = Any()
    }
}
