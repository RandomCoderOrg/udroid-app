package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotMountProfilesTest {
    @Test
    fun `default profile preserves the existing six Android mappings`() {
        assertEquals(
            listOf(
                "/system",
                "/apex",
                "/dev",
                "/proc",
                "/sys",
                "/linkerconfig/ld.config.txt",
            ),
            ProotMountResolver.resolve(ProotMountProfile()).map { it.argument },
        )
    }

    @Test
    fun `required-looking default can be intentionally disabled`() {
        val profile = ProotMountProfile().withDefaultEnabled("android.sys", enabled = false)

        ProotMountProfileValidator.requireValid(profile)

        assertFalse(ProotMountResolver.resolve(profile).any { it.guestTarget == "/sys" })
    }

    @Test
    fun `custom mapping is emitted exactly as saved without checking host existence`() {
        val profile =
            ProotMountProfile(
                customMounts =
                    listOf(
                        ProotCustomMount(
                            id = "experiment.data",
                            hostSource = "/data/local/nonexistent-source",
                            guestTarget = "/experiment",
                        ),
                    ),
            )

        assertEquals(
            "/data/local/nonexistent-source:/experiment",
            ProotMountResolver.resolve(profile).last().argument,
        )
    }

    @Test
    fun `duplicate enabled guest destinations are rejected`() {
        val profile =
            ProotMountProfile(
                customMounts =
                    listOf(
                        ProotCustomMount(
                            id = "duplicate.sys",
                            hostSource = "/another/sys",
                            guestTarget = "/sys",
                        ),
                    ),
            )

        val failure = runCatching { ProotMountProfileValidator.requireValid(profile) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `session destination can be customized when its feature is inactive`() {
        val customX11 =
            ProotCustomMount(
                id = "custom.x11",
                hostSource = "/data/local/custom-x11",
                guestTarget = "/tmp/.X11-unix",
            )
        val profile = ProotMountProfile(customMounts = listOf(customX11))

        assertEquals(customX11.guestTarget, ProotMountResolver.resolve(profile).last().guestTarget)

        val failure =
            runCatching {
                ProotMountResolver.resolve(
                    profile,
                    sessionMounts =
                        listOf(
                            ResolvedProotMount(
                                hostSource = "/data/local/automatic-x11",
                                guestTarget = "/tmp/.X11-unix",
                                origin = "runtime:x11",
                            ),
                        ),
                )
            }.exceptionOrNull()
        assertTrue(failure?.message?.contains("active session feature") == true)
    }

    @Test
    fun `profile codec round trips overrides and custom mappings`() {
        val expected =
            ProotMountProfile(
                name = "Build environment",
                sourceSystemId = "udroid-jammy-raw",
                defaultOverrides = mapOf("android.proc" to false),
                customMounts =
                    listOf(
                        ProotCustomMount(
                            id = "custom.cache",
                            enabled = false,
                            hostSource = "/data/cache",
                            guestTarget = "/var/cache/host",
                        ),
                    ),
            )

        assertEquals(expected, ProotMountProfileCodec.decode(ProotMountProfileCodec.encode(expected)))
    }

    @Test
    fun `legacy profile without a name receives the default profile name`() {
        val legacy =
            """{"format":"1","defaults_revision":1,"default_overrides":{},"custom_mounts":[]}"""

        assertEquals("Default profile", ProotMountProfileCodec.decode(legacy).name)
    }

    @Test
    fun `blank profile name is rejected`() {
        val failure =
            runCatching {
                ProotMountProfileValidator.requireValid(ProotMountProfile(name = "  "))
            }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `copied profile gets independent custom mapping identities`() {
        val original =
            ProotMountProfile(
                sourceSystemId = "udroid-jammy-raw",
                customMounts =
                    listOf(
                        ProotCustomMount(
                            id = "custom.logs",
                            hostSource = "/data/logs",
                            guestTarget = "/mnt/logs",
                        ),
                    ),
            )

        val copied = original.independentCopy()

        assertNotEquals(original.customMounts.single().id, copied.customMounts.single().id)
        assertEquals(original.sourceSystemId, copied.sourceSystemId)
        assertEquals(original.customMounts.single().hostSource, copied.customMounts.single().hostSource)
        assertEquals(original.customMounts.single().guestTarget, copied.customMounts.single().guestTarget)
    }

    @Test
    fun `unsafe source system identity is rejected`() {
        val failure =
            runCatching {
                ProotMountProfileValidator.requireValid(
                    ProotMountProfile(sourceSystemId = "../another-system"),
                )
            }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
