package org.randomcoder.udroid.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestX11TransportProbeTest {
    @Test
    fun `ready output preserves negotiated protocol`() {
        val result =
            GuestX11ProbeOutput.parse(
                """{"event":"x11_guest_probe","status":"ready","protocol_major":11,"protocol_minor":0}""",
            )

        assertEquals(GuestX11TransportResult.Ready(11, 0), result)
    }

    @Test
    fun `permission denial becomes a transport-specific error`() {
        val result =
            GuestX11ProbeOutput.parse(
                """{"event":"x11_guest_probe","status":"connect_failed","errno":13,"detail":"Permission denied"}""",
            ) as GuestX11TransportResult.Failed

        assertEquals("connect_failed", result.stage)
        assertEquals(13, result.errno)
        assertEquals("X11 guest transport denied: Permission denied", result.userMessage)
    }

    @Test
    fun `probe uses the exact guest socket bind alias`() {
        val arguments =
            GuestX11ProbeCommand.buildArguments(
                prootPath = "/data/proot",
                rootfsPath = "/data/rootfs",
                socketDirectory = "/data/x11/.X11-unix",
                nativeProbe = "/data/runtime_probe",
                forceDenied = false,
                androidBindMounts = listOf("/system", "/proc"),
                systemLinkerPath = "/system/bin/linker64",
            )

        assertTrue("/data/x11/.X11-unix:/tmp/.X11-unix" in arguments)
        assertTrue("/data/runtime_probe:/tmp/.udroid-x11-probe" in arguments)
        assertEquals(
            listOf(
                "/system/bin/linker64",
                "/tmp/.udroid-x11-probe",
                "--x11",
                "/tmp/.X11-unix/X0",
            ),
            arguments.takeLast(4),
        )
    }

    @Test
    fun `fault build selects deterministic permission denial`() {
        val arguments =
            GuestX11ProbeCommand.buildArguments(
                prootPath = "/data/proot",
                rootfsPath = "/data/rootfs",
                socketDirectory = "/data/x11/.X11-unix",
                nativeProbe = "/data/runtime_probe",
                forceDenied = true,
                androidBindMounts = emptyList(),
                systemLinkerPath = "/system/bin/linker64",
            )

        assertEquals("--x11-deny", arguments[arguments.lastIndex - 1])
    }
}
