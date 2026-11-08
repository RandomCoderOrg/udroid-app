package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class TerminalAppearanceTest {
    @Test
    fun parsesRgbHexWithOptionalHash() {
        assertEquals(0xFF1122AA.toInt(), parseTerminalColor("#1122aa"))
        assertEquals(0xFF43D292.toInt(), parseTerminalColor(" 43D292 "))
    }

    @Test
    fun rejectsIncompleteOrNonRgbColors() {
        assertNull(parseTerminalColor("#123"))
        assertNull(parseTerminalColor("#12345678"))
        assertNull(parseTerminalColor("#12ZZ56"))
    }

    @Test
    fun formatsColorsWithoutAlpha() {
        assertEquals("#43D292", formatTerminalColor(0x7F43D292))
    }

    @Test
    fun profileSerializationRoundTripsEveryTerminalColor() {
        val profile =
            TerminalProfiles.udroid.copy(
                id = "custom-profile",
                name = "My terminal | profile",
            )

        assertEquals(profile, decodeTerminalProfile(encodeTerminalProfile(profile)))
    }

    @Test
    fun malformedProfilesAreIgnored() {
        assertNull(decodeTerminalProfile("not-a-profile"))
        assertNull(decodeTerminalProfile(encodeTerminalProfile(TerminalProfiles.udroid).replace("#000000", "bad")))
    }

    @Test
    fun profilesRequireAllSixteenAnsiColors() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalProfiles.udroid.copy(ansiColors = listOf(0xFF000000.toInt()))
        }
    }

    @Test
    fun missingSelectionFallsBackToUdroid() {
        val state = TerminalProfileState(selectedProfileId = "missing", userProfiles = emptyList())

        assertSame(TerminalProfiles.udroid, state.selectedProfile)
    }
}
