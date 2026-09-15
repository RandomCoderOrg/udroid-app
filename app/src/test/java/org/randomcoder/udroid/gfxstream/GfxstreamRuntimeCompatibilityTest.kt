package org.randomcoder.udroid.gfxstream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GfxstreamRuntimeCompatibilityTest {
    @Test
    fun `exact host and guest pair passes`() {
        val diagnostic = GfxstreamRuntimeCompatibility.requireCompatible(host(), guest())

        assertEquals(
            "host gfxstream=1111111111 · guest protocol=1111111111 · " +
                "host Mesa protocol=2222222222 · guest Mesa=2222222222",
            diagnostic,
        )
    }

    @Test
    fun `gfxstream protocol mismatch fails`() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                GfxstreamRuntimeCompatibility.requireCompatible(
                    host(),
                    guest(protocolHostCommit = commit('3')),
                )
            }

        assertTrue(error.message.orEmpty().contains("Incompatible gfxstream runtime pair"))
        assertAllHashesPresent(error.message.orEmpty())
    }

    @Test
    fun `Mesa protocol mismatch fails`() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                GfxstreamRuntimeCompatibility.requireCompatible(
                    host(),
                    guest(mesaCommit = commit('4')),
                )
            }

        assertTrue(error.message.orEmpty().contains("Incompatible gfxstream runtime pair"))
        assertAllHashesPresent(error.message.orEmpty())
    }

    @Test
    fun `missing metadata fails closed`() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                GfxstreamRuntimeCompatibility.requireCompatible(
                    host(gfxstreamCommit = ""),
                    guest(),
                )
            }

        assertTrue(error.message.orEmpty().contains("Missing or malformed"))
        assertTrue(error.message.orEmpty().contains("host gfxstream=missing"))
        assertAllHashesPresent(error.message.orEmpty())
    }

    @Test
    fun `malformed metadata fails closed`() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                GfxstreamRuntimeCompatibility.requireCompatible(
                    host(mesaProtocolCommit = "not-a-commit"),
                    guest(),
                )
            }

        assertTrue(error.message.orEmpty().contains("Missing or malformed"))
        assertTrue(error.message.orEmpty().contains("host Mesa protocol=invalid-not-a-comm"))
        assertAllHashesPresent(error.message.orEmpty())
    }

    private fun assertAllHashesPresent(message: String) {
        assertTrue(message.contains("host gfxstream="))
        assertTrue(message.contains("guest protocol="))
        assertTrue(message.contains("host Mesa protocol="))
        assertTrue(message.contains("guest Mesa="))
    }

    private fun host(
        gfxstreamCommit: String = commit('1'),
        mesaProtocolCommit: String = commit('2'),
    ) = GfxstreamHostRuntime(
        executable = File("/host/kumquat"),
        libraryDirectory = File("/host/lib"),
        version = "host-test",
        gfxstreamCommit = gfxstreamCommit,
        mesaProtocolCommit = mesaProtocolCommit,
    )

    private fun guest(
        protocolHostCommit: String = commit('1'),
        mesaCommit: String = commit('2'),
    ) = GfxstreamGuestRuntime(
        directory = File("/guest"),
        launcher = File("/guest/launcher"),
        version = "guest-test",
        protocolHostCommit = protocolHostCommit,
        mesaCommit = mesaCommit,
    )

    private fun commit(character: Char): String = character.toString().repeat(40)
}
