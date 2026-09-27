package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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
    fun `duplicate enabled guest destinations preserve user order`() {
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

        val mounts = ProotMountResolver.resolve(profile)
        assertEquals(2, mounts.count { it.guestTarget == "/sys" })
        assertEquals("/another/sys", mounts.last { it.guestTarget == "/sys" }.hostSource)
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

        val mounts =
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
        assertEquals(2, mounts.count { it.guestTarget == "/tmp/.X11-unix" })
        assertEquals("runtime:x11", mounts.last().origin)
    }

    @Test
    fun `runtime shared memory mount targets dev shm`() {
        val mount = ProotMountResolver.sharedMemoryMount("/data/user/0/udroid/no_backup/proot/shm")

        assertEquals("/data/user/0/udroid/no_backup/proot/shm:/dev/shm", mount.argument)
        assertEquals("runtime:shm", mount.origin)
    }

    @Test
    fun `compatibility files only replace unreadable Android data`() {
        val directory = Files.createTempDirectory("udroid-sysdata").toFile()
        val victim = Files.createTempFile("udroid-sysdata-victim", null).toFile().apply {
            writeText("preserve")
        }
        Files.createSymbolicLink(directory.resolve("pci_devices").toPath(), victim.toPath())

        val mounts =
            ProotMountResolver.prepareCompatibilityMounts(directory) { path ->
                path == "/proc/version"
            }

        assertFalse(mounts.any { it.guestTarget == "/proc/version" })
        assertEquals("", mounts.single { it.guestTarget == "/proc/bus/pci/devices" }.let {
            java.io.File(it.hostSource).readText()
        })
        assertEquals("preserve", victim.readText())
        assertFalse(Files.isSymbolicLink(directory.resolve("pci_devices").toPath()))
        assertEquals(
            "4096\n",
            mounts.single { it.guestTarget.endsWith("max_user_watches") }.let {
                java.io.File(it.hostSource).readText()
            },
        )
        assertTrue(mounts.all { it.origin == "runtime:compatibility" })
    }

    @Test
    fun `parent custom mount shadows automatic child mounts`() {
        assertTrue(
            ProotMountResolver.isShadowedByCustomTarget(
                "/proc/bus/pci/devices",
                setOf("/proc//"),
            ),
        )
        assertFalse(
            ProotMountResolver.isShadowedByCustomTarget(
                "/proc/bus/pci/devices",
                setOf("/proc/other"),
            ),
        )
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
