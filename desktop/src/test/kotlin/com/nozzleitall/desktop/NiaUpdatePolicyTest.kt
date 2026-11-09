package com.nozzleitall.desktop

import com.nozzleitall.desktop.updates.NiaUpdatePolicy
import org.junit.Assert.*
import org.junit.Test

class NiaUpdatePolicyTest {
    private val valid = NiaUpdatePolicy.Release("nozzle-it-all-desktop", "0.2.0", "stable", "655acbd",
        "2026-11-05T00:00:00Z", "linux", "x86_64", "https://updates.nozzleitall.com/releases/nia.deb", "a".repeat(64),
        "Release notes", "nia-settings-v1", "signed-metadata-placeholder")

    @Test fun `only complete NIA metadata is structurally accepted`() {
        assertTrue(NiaUpdatePolicy.isNiaRelease(valid, NiaUpdatePolicy.Channel.STABLE))
        assertFalse(NiaUpdatePolicy.isNiaRelease(valid.copy(product = "OrcaSlicer"), NiaUpdatePolicy.Channel.STABLE))
        assertFalse(NiaUpdatePolicy.isNiaRelease(valid.copy(channel = "beta"), NiaUpdatePolicy.Channel.STABLE))
        assertFalse(NiaUpdatePolicy.isNiaRelease(valid.copy(artifactUrl = "http://example.invalid/nia.deb"), NiaUpdatePolicy.Channel.STABLE))
        assertFalse(NiaUpdatePolicy.isNiaRelease(valid.copy(artifactUrl = "https://evil.example/nia.deb"), NiaUpdatePolicy.Channel.STABLE))
        assertFalse(NiaUpdatePolicy.isNiaRelease(valid.copy(signature = null), NiaUpdatePolicy.Channel.STABLE))
        assertFalse(NiaUpdatePolicy.isNiaRelease(valid.copy(settingsRequirements = "anything"), NiaUpdatePolicy.Channel.STABLE))
        assertFalse(NiaUpdatePolicy.isNiaRelease(valid.copy(artifactSha256 = "not-a-digest"), NiaUpdatePolicy.Channel.STABLE))
    }

    @Test fun `closed channel never selects upstream or otherwise valid metadata`() {
        assertFalse(NiaUpdatePolicy.available)
        assertNull(NiaUpdatePolicy.select(listOf(valid), NiaUpdatePolicy.Channel.STABLE))
        assertEquals("Update checks are not configured for this build.", NiaUpdatePolicy.status)
    }
}
