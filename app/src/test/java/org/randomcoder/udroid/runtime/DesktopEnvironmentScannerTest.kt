package org.randomcoder.udroid.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEnvironmentScannerTest {
    @Test
    fun `discovers and classifies xsession desktop files`() {
        val rootfs = Files.createTempDirectory("udroid-desktops").toFile()
        val sessions = rootfs.resolve("usr/share/xsessions").apply { mkdirs() }
        sessions.resolve("xfce.desktop").writeText(
            """
            [Desktop Entry]
            Name=Xfce Session
            Exec=startxfce4
            Type=Application
            DesktopNames=XFCE
            """.trimIndent(),
        )
        sessions.resolve("hidden.desktop").writeText(
            """
            [Desktop Entry]
            Name=Hidden
            Exec=hidden-session
            Type=Application
            Hidden=true
            """.trimIndent(),
        )

        val result = DesktopEnvironmentScanner().scan(rootfs)

        assertEquals(1, result.size)
        assertEquals("xfce", result.single().id)
        assertEquals("Xfce Session", result.single().name)
        assertEquals(listOf("startxfce4"), result.single().command)
        assertEquals(DesktopEnvironmentKind.XFCE, result.single().kind)
    }

    @Test
    fun `ignores malformed session commands`() {
        val rootfs = Files.createTempDirectory("udroid-desktops").toFile()
        val sessions = rootfs.resolve("usr/share/xsessions").apply { mkdirs() }
        sessions.resolve("broken.desktop").writeText(
            """
            [Desktop Entry]
            Name=Broken
            Exec="unterminated
            Type=Application
            """.trimIndent(),
        )

        assertTrue(DesktopEnvironmentScanner().scan(rootfs).isEmpty())
    }

    @Test
    fun `desktop launch passes session arguments without interpolating them into shell`() {
        val desktop =
            DesktopEnvironment(
                id = "xfce",
                name = "Xfce",
                command = listOf("startxfce4", "--demo=one;touch /tmp/no"),
                desktopFilePath = "/usr/share/xsessions/xfce.desktop",
                kind = DesktopEnvironmentKind.XFCE,
            )
        val arguments =
            ProotDesktopLaunchBuilder.buildArguments(
                prootPath = "/data/proot",
                rootfsPath = "/data/rootfs",
                x11SocketDirectory = "/data/x11",
                guestHome = "/root",
                environment = desktop,
                configuration =
                    DesktopConfiguration(
                        environmentId = desktop.id,
                        compositingEnabled = false,
                        touchScaleEnabled = true,
                    ),
                hasDbusRunSession = true,
                audioAuthDirectory = "/data/audio/transport",
            )

        assertTrue(arguments.contains("/usr/bin/dbus-run-session"))
        assertTrue(arguments.contains("/data/audio/transport:/tmp/.udroid-pulse"))
        assertTrue(arguments.contains("PULSE_SERVER=tcp:127.0.0.1:4713"))
        assertTrue(arguments.contains("PULSE_COOKIE=/tmp/.udroid-pulse/cookie"))
        assertTrue(arguments.contains("GDK_SCALE=2"))
        val script = arguments[arguments.indexOf("-lc") + 1]
        assertTrue(script.contains("-s false"))
        assertTrue(script.contains("sleep 1"))
        assertTrue(script.contains("wait \"\$desktop_pid\""))
        assertEquals(desktop.command, arguments.takeLast(desktop.command.size))
        assertFalse(script.contains(desktop.command.last()))
    }
}
