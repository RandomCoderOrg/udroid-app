package org.randomcoder.udroid.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PulseAudioConfigurationTest {
    @Test
    fun `output only never loads microphone capture`() {
        val configuration =
            PulseAudioConfiguration.render(
                AudioConfiguration(outputEnabled = true, microphoneEnabled = false),
                File("/data/user/0/org.randomcoder.udroid/files/audio/transport/cookie"),
            )

        assertTrue("module-sles-sink" in configuration)
        assertFalse("module-sles-source" in configuration)
        assertFalse("module-native-protocol-unix" in configuration)
        assertTrue("module-native-protocol-tcp" in configuration)
        assertTrue("listen=127.0.0.1" in configuration)
        assertTrue("auth-cookie-enabled=1" in configuration)
        assertFalse("auth-anonymous=1" in configuration)
    }

    @Test
    fun `microphone capture is loaded only after it is enabled`() {
        val configuration =
            PulseAudioConfiguration.render(
                AudioConfiguration(outputEnabled = false, microphoneEnabled = true),
                File("/data/user/0/org.randomcoder.udroid/files/audio/transport/cookie"),
            )

        assertFalse("module-sles-sink" in configuration)
        assertTrue("module-sles-source" in configuration)
    }
}
