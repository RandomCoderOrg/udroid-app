package org.randomcoder.udroid.linuxapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class DesktopApplicationScannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun scansVisibleLocalizedApplicationsAndResolvesIcons() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        executable(rootfs, "usr/bin/demo")
        file(rootfs, "usr/share/pixmaps/demo.png").writeBytes(byteArrayOf(1, 2, 3))
        desktop(
            rootfs,
            "usr/share/applications/demo.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Demo
            Name[fr]=Démo
            Comment=Open a demo
            Exec=demo --label=%c %F
            TryExec=demo
            Icon=demo
            Categories=Utility;Development;
            """,
        )

        val result = DesktopApplicationScanner().scan(rootfs, Locale.FRENCH)

        assertEquals(1, result.applications.size)
        val application = result.applications.single()
        assertEquals("Démo", application.name)
        assertEquals("demo", application.executable)
        assertEquals(listOf("--label=Démo"), application.arguments)
        assertEquals(listOf("Utility", "Development"), application.categories)
        assertTrue(application.iconPath!!.endsWith("/usr/share/pixmaps/demo.png"))
    }

    @Test
    fun userHiddenEntryMasksSystemEntryWithTheSameDesktopId() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        executable(rootfs, "usr/bin/demo")
        desktop(
            rootfs,
            "root/.local/share/applications/demo.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Hidden override
            Hidden=true
            Exec=demo
            """,
        )
        desktop(
            rootfs,
            "usr/share/applications/demo.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=System Demo
            Exec=demo
            """,
        )

        val result = DesktopApplicationScanner().scan(rootfs)

        assertTrue(result.applications.isEmpty())
        assertEquals(1, result.scannedEntries)
    }

    @Test
    fun ignoresNoDisplayMissingTryExecAndDesktopSpecificEntries() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        desktop(
            rootfs,
            "usr/share/applications/hidden.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Hidden
            NoDisplay=true
            Exec=hidden
            """,
        )
        desktop(
            rootfs,
            "usr/share/applications/missing.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Missing
            TryExec=does-not-exist
            Exec=does-not-exist
            """,
        )
        desktop(
            rootfs,
            "usr/share/applications/gnome.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=GNOME only
            OnlyShowIn=GNOME;
            Exec=gnome-only
            """,
        )

        val result = DesktopApplicationScanner().scan(rootfs)

        assertTrue(result.applications.isEmpty())
        assertEquals(3, result.ignoredEntries)
    }

    @Test
    fun resolvesAbsoluteGuestSymlinksForTryExec() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        executable(rootfs, "usr/bin/demo.real")
        File(rootfs, "etc/alternatives").mkdirs()
        File(rootfs, "usr/bin").mkdirs()
        Files.createSymbolicLink(
            File(rootfs, "usr/bin/demo").toPath(),
            Path.of("/etc/alternatives/demo"),
        )
        Files.createSymbolicLink(
            File(rootfs, "etc/alternatives/demo").toPath(),
            Path.of("/usr/bin/demo.real"),
        )
        desktop(
            rootfs,
            "usr/share/applications/demo.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Demo
            TryExec=demo
            Exec=demo
            """,
        )

        val result = DesktopApplicationScanner().scan(rootfs)

        assertEquals(listOf("Demo"), result.applications.map(LinuxApplication::name))
    }

    private fun executable(
        rootfs: File,
        path: String,
    ) {
        file(rootfs, path).apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
    }

    private fun desktop(
        rootfs: File,
        path: String,
        content: String,
    ) {
        file(rootfs, path).writeText(content.trimIndent() + "\n")
    }

    private fun file(
        rootfs: File,
        path: String,
    ): File =
        File(rootfs, path).apply {
            parentFile?.mkdirs()
            if (!exists()) createNewFile()
        }
}
