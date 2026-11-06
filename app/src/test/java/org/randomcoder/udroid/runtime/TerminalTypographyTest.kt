package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class TerminalTypographyTest {
    @Test
    fun exposesBundledFontsAsBuiltIns() {
        assertEquals(
            listOf("System monospace", "JetBrains Mono", "Fira Code", "Source Code Pro"),
            TerminalFonts.builtIns.map(TerminalFontProfile::name),
        )
    }

    @Test
    fun normalizesBoundsAndMissingFontSelection() {
        val state =
            TerminalTypographyState(
                selectedFontId = "missing",
                textSizeSp = 100f,
                lineSpacing = 0.1f,
                cursorBlinkRateMs = 50,
            ).normalized()

        assertEquals(TerminalFonts.system.id, state.selectedFontId)
        assertEquals(TERMINAL_TEXT_SIZE_MAX_SP, state.textSizeSp)
        assertEquals(TERMINAL_LINE_SPACING_MIN, state.lineSpacing)
        assertEquals(TERMINAL_CURSOR_BLINK_RATE_MIN_MS, state.cursorBlinkRateMs)
    }

    @Test
    fun fontMetadataRoundTrips() {
        val font = TerminalFontProfile("font-id", "JetBrains Mono", "123e4567-e89b-12d3-a456-426614174000.ttf")

        assertEquals(font, decodeTerminalFont(encodeTerminalFont(font)))
    }

    @Test
    fun malformedFontMetadataIsIgnored() {
        assertNull(decodeTerminalFont("not-a-font"))
        assertNull(decodeTerminalFont("font|Rm9udA|../font.ttf"))
    }

    @Test
    fun readsFullNameFromOpenTypeNameTable() {
        val family = "Example Mono".toByteArray(StandardCharsets.UTF_16BE)
        val fullName = "Example Mono Regular".toByteArray(StandardCharsets.UTF_16BE)
        val nameTableLength = 6 + 24 + family.size + fullName.size
        val buffer = ByteBuffer.allocate(28 + nameTableLength).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(0x00010000)
        buffer.putShort(1)
        repeat(3) { buffer.putShort(0) }
        buffer.put("name".toByteArray(StandardCharsets.US_ASCII))
        buffer.putInt(0)
        buffer.putInt(28)
        buffer.putInt(nameTableLength)
        buffer.putShort(0)
        buffer.putShort(2)
        buffer.putShort(30)
        buffer.putNameRecord(nameId = 1, length = family.size, offset = 0)
        buffer.putNameRecord(nameId = 4, length = fullName.size, offset = family.size)
        buffer.put(family)
        buffer.put(fullName)
        val file = Files.createTempFile("font-name", ".ttf").toFile()

        try {
            file.writeBytes(buffer.array())
            assertEquals("Example Mono Regular", readOpenTypeFontName(file))
        } finally {
            file.delete()
        }
    }

    private fun ByteBuffer.putNameRecord(
        nameId: Int,
        length: Int,
        offset: Int,
    ) {
        putShort(3)
        putShort(1)
        putShort(0x0409.toShort())
        putShort(nameId.toShort())
        putShort(length.toShort())
        putShort(offset.toShort())
    }
}
