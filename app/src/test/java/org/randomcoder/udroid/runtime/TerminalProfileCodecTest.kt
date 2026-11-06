package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalProfileCodecTest {
    @Test
    fun termuxExportCanBeImportedWithoutLosingColors() {
        val source = TerminalProfiles.builtIns[1].copy(id = "source")

        val imported = importTerminalProfile("Breeze.properties", exportTermuxProfile(source)).getOrThrow()

        assertEquals("Breeze", imported.name)
        assertEquals(source.background, imported.background)
        assertEquals(source.foreground, imported.foreground)
        assertEquals(source.cursor, imported.cursor)
        assertEquals(source.ansiColors, imported.ansiColors)
    }

    @Test
    fun importsGhosttyPalette() {
        val source = TerminalProfiles.udroid
        val theme =
            buildString {
                appendLine("background = ${formatTerminalColor(source.background)}")
                appendLine("foreground = ${formatTerminalColor(source.foreground)}")
                appendLine("cursor-color = ${formatTerminalColor(source.cursor)}")
                source.ansiColors.forEachIndexed { index, color ->
                    appendLine("palette = $index=${formatTerminalColor(color)}")
                }
            }

        val imported = importTerminalProfile("Ghostty theme", theme).getOrThrow()

        assertEquals(source.cursor, imported.cursor)
        assertEquals(source.ansiColors, imported.ansiColors)
    }

    @Test
    fun importsItermColors() {
        val source = TerminalProfiles.udroid
        val colors =
            buildMap {
                put("Background Color", source.background)
                put("Foreground Color", source.foreground)
                put("Cursor Color", source.cursor)
                source.ansiColors.forEachIndexed { index, color -> put("Ansi $index Color", color) }
            }
        val xml =
            buildString {
                append("<?xml version=\"1.0\"?><plist><dict>")
                colors.forEach { (key, color) ->
                    append("<key>$key</key><dict>")
                    appendComponent("Red Component", (color shr 16) and 0xFF)
                    appendComponent("Green Component", (color shr 8) and 0xFF)
                    appendComponent("Blue Component", color and 0xFF)
                    append("</dict>")
                }
                append("</dict></plist>")
            }

        val imported = importTerminalProfile("uDroid.itermcolors", xml).getOrThrow()

        assertEquals(source.background, imported.background)
        assertEquals(source.foreground, imported.foreground)
        assertEquals(source.cursor, imported.cursor)
        assertEquals(source.ansiColors, imported.ansiColors)
    }

    @Test
    fun rejectsIncompletePalettes() {
        val result = importTerminalProfile("broken.properties", "background=#000000\nforeground=#FFFFFF")

        assertTrue(result.isFailure)
    }

    private fun StringBuilder.appendComponent(
        name: String,
        value: Int,
    ) {
        append("<key>$name</key><real>${value / 255.0}</real>")
    }
}
