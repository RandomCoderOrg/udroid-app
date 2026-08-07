package org.randomcoder.udroid.audio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.randomcoder.udroid.runtime.EventJournal
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class AudioRuntimeProbeTest {
    @Test
    fun packagedRuntimeStartsOutputOnAuthenticatedLoopback() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executor = Executors.newCachedThreadPool()
        val controller = AudioServerController(context, EventJournal(context), executor)
        try {
            val started =
                controller.apply(
                    AudioConfiguration(outputEnabled = true, microphoneEnabled = false),
                    bootId = "audio-device-probe",
                )
            assertTrue(started.running)
            val cookie =
                controller.endpoint().hostAuthDirectory.resolve(AudioEndpoint.COOKIE_NAME)
            val deadline = System.nanoTime() + 5_000_000_000L
            var acceptingConnections = false
            while (!acceptingConnections && controller.current().running && System.nanoTime() < deadline) {
                acceptingConnections =
                    runCatching {
                        Socket(InetAddress.getLoopbackAddress(), AudioEndpoint.PORT).use { }
                        true
                    }.getOrDefault(false)
                Thread.sleep(20)
            }
            assertTrue("PulseAudio did not create its authentication cookie", cookie.isFile)
            assertTrue("PulseAudio did not listen on device-local loopback", acceptingConnections)
            assertTrue(controller.current().outputEnabled)
            assertTrue(!controller.current().microphoneEnabled)
        } finally {
            controller.stop("audio-device-probe")
            executor.shutdownNow()
        }
    }
}
