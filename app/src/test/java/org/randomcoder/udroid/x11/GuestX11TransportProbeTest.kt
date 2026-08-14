package org.randomcoder.udroid.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestX11TransportProbeTest {
    @Test
    fun `ready output preserves negotiated protocol`() {
        val result =
            GuestX11ProbeOutput.parse(
                """
                {"event":"x11_guest_probe","status":"ready","socket_namespace":"filesystem","protocol_major":11,"protocol_minor":0,"address_bytes":110,"elapsed_ms":7}
                """.trimIndent(),
                GuestX11SocketNamespace.FILESYSTEM,
            )

        assertEquals(GuestX11TransportResult.Ready(11, 0, 110, 7), result)
    }

    @Test
    fun `permission denial becomes a transport-specific error`() {
        val result =
            GuestX11ProbeOutput.parse(
                """
                {"event":"x11_guest_probe","status":"connect_failed","socket_namespace":"abstract","errno":13,"detail":"Permission denied"}
                """.trimIndent(),
                GuestX11SocketNamespace.ABSTRACT,
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
                bindSocket = true,
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
    fun `abstract probe selects the Linux abstract namespace`() {
        val arguments =
            GuestX11ProbeCommand.buildArguments(
                prootPath = "/data/proot",
                rootfsPath = "/data/rootfs",
                socketDirectory = "/data/x11/.X11-unix",
                bindSocket = false,
                nativeProbe = "/data/runtime_probe",
                forceDenied = false,
                socketNamespace = GuestX11SocketNamespace.ABSTRACT,
                androidBindMounts = emptyList(),
                systemLinkerPath = "/system/bin/linker64",
            )

        assertEquals("--x11-abstract", arguments[arguments.lastIndex - 1])
    }

    @Test
    fun `fault build selects deterministic permission denial`() {
        val arguments =
            GuestX11ProbeCommand.buildArguments(
                prootPath = "/data/proot",
                rootfsPath = "/data/rootfs",
                socketDirectory = "/data/x11/.X11-unix",
                bindSocket = false,
                nativeProbe = "/data/runtime_probe",
                forceDenied = true,
                androidBindMounts = emptyList(),
                systemLinkerPath = "/system/bin/linker64",
            )

        assertEquals("--x11-deny", arguments[arguments.lastIndex - 1])
        assertTrue("/data/x11/.X11-unix:/tmp/.X11-unix" !in arguments)
    }

    @Test
    fun `guest client probe uses the desktop display and bound socket`() {
        val arguments =
            GuestX11ClientProbeCommand.buildArguments(
                prootPath = "/data/proot",
                rootfsPath = "/data/rootfs",
                socketDirectory = "/data/x11/.X11-unix",
                bindSocket = true,
                guestHome = "/root",
                androidBindMounts = listOf("/system"),
            )

        assertTrue("/data/x11/.X11-unix:/tmp/.X11-unix" in arguments)
        assertTrue("DISPLAY=:0" in arguments)
        assertTrue(arguments.last().contains("xrdb -display \"${'$'}target\" -query"))
        assertTrue(arguments.last().contains("run_xrdb xrdb_unix 'unix/:0'"))
        assertTrue(arguments.last().contains("run_xrdb xrdb_path '/tmp/.X11-unix/X0'"))
        assertTrue(arguments.last().contains("/proc/self/attr/current"))
    }

    @Test
    fun `guest client output keeps only bounded diagnostic records`() {
        val fields =
            GuestX11ClientProbeOutput.parseFields(
                """
                unrelated output
                UDROID_X11|xrdb_status=failed
                UDROID_X11|xrdb_exit=1
                UDROID_X11|xrdb_output=xrdb: Permission denied
                """.trimIndent(),
            )

        assertEquals("failed", fields["xrdb_status"])
        assertEquals("1", fields["xrdb_exit"])
        assertEquals("xrdb: Permission denied", fields["xrdb_output"])
        assertEquals(3, fields.size)
    }
}
