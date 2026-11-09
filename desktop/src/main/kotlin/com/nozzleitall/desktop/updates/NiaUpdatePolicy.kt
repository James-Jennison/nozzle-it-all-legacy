package com.nozzleitall.desktop.updates

import java.net.URI

/** Product update policy is intentionally closed until signed NIA release infrastructure is configured. */
object NiaUpdatePolicy {
    enum class Channel(val id: String) { STABLE("stable"), BETA("beta") }
    data class Release(val product: String, val version: String, val channel: String, val sourceCommit: String,
                       val buildTime: String, val platform: String, val architecture: String, val artifactUrl: String,
                       val artifactSha256: String, val releaseNotes: String, val settingsRequirements: String,
                       val signature: String? = null)

    const val available = false
    const val status = "Update checks are not configured for this build."

    fun isNiaRelease(release: Release, requested: Channel): Boolean =
        release.product == "nozzle-it-all-desktop" && release.channel == requested.id && release.version.isNotBlank() &&
            release.sourceCommit.length >= 7 && release.sourceCommit.all { it.digitToIntOrNull(16) != null } && release.buildTime.isNotBlank() &&
            release.platform.isNotBlank() && release.architecture.isNotBlank() && trustedArtifactUrl(release.artifactUrl) &&
            release.artifactSha256.length == 64 && release.artifactSha256.all { it.digitToIntOrNull(16) != null } &&
            release.releaseNotes.isNotBlank() && release.settingsRequirements in SETTINGS_SCHEMAS && !release.signature.isNullOrBlank()

    fun select(releases: List<Release>, requested: Channel): Release? =
        if (!available) null else releases.firstOrNull { isNiaRelease(it, requested) }

    private fun trustedArtifactUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" && uri.host in ARTIFACT_HOSTS && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)

    private val ARTIFACT_HOSTS = setOf("updates.nozzleitall.com")
    private val SETTINGS_SCHEMAS = setOf("nia-settings-v1")
}
