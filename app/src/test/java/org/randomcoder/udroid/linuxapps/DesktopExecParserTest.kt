package org.randomcoder.udroid.linuxapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopExecParserTest {
    @Test
    fun parsesQuotedArgumentsWithoutInvokingAShell() {
        val result =
            DesktopExecParser.parse(
                commandLine = """demo --title "Hello Linux" 'one argument'""",
                applicationName = "Demo",
                iconName = null,
                desktopFileGuestPath = "/usr/share/applications/demo.desktop",
            )

        assertEquals(
            listOf("demo", "--title", "Hello Linux", "one argument"),
            result.getOrThrow(),
        )
    }

    @Test
    fun expandsSupportedFieldCodesAndDropsMissingFiles() {
        val result =
            DesktopExecParser.parse(
                commandLine = "demo %i --name=%c --desktop=%k %F %%",
                applicationName = "Demo App",
                iconName = "demo",
                desktopFileGuestPath = "/usr/share/applications/demo.desktop",
            )

        assertEquals(
            listOf(
                "demo",
                "--icon",
                "demo",
                "--name=Demo App",
                "--desktop=/usr/share/applications/demo.desktop",
                "%",
            ),
            result.getOrThrow(),
        )
    }

    @Test
    fun rejectsUnknownOrEmbeddedMultiArgumentFieldCodes() {
        assertTrue(
            DesktopExecParser
                .parse("demo --files=%F", "Demo", null, "/demo.desktop")
                .isFailure,
        )
        assertTrue(
            DesktopExecParser
                .parse("demo %Z", "Demo", null, "/demo.desktop")
                .isFailure,
        )
    }
}
