package org.randomcoder.udroid.oci

import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.randomcoder.udroid.install.AndroidRootfsConfigurator
import org.randomcoder.udroid.install.ProotRootfsHealthCheck
import org.randomcoder.udroid.install.ProotRuntimeInstaller
import java.io.File

@RunWith(AndroidJUnit4::class)
class OciUbuntuInstallProbeTest {
    @Test
    fun pullsAndBootChecksOfficialUbuntuArm64Image() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val probeDirectory = File(context.cacheDir, "oci-install-probe")
        val blobDirectory = File(probeDirectory, "blobs")
        val rootfs = File(probeDirectory, "rootfs")
        probeDirectory.deleteRecursively()
        check(probeDirectory.mkdirs()) { "Could not create OCI probe directory" }

        val timings = linkedMapOf<String, Long>()
        try {
            val platform = OciPlatform.fromAndroidAbis(Build.SUPPORTED_ABIS.toList())
            val pull =
                timed("pull", timings) {
                    OciImagePuller().pull(
                        reference = OciImageReference.parse("ubuntu:24.04"),
                        platform = platform,
                        cacheDirectory = blobDirectory,
                    )
                }
            assertEquals("linux", pull.image.platform.os)
            assertEquals("arm64", pull.image.platform.architecture)
            assertTrue(pull.layers.isNotEmpty())

            val runtime = ProotRuntimeInstaller.install(context)
            timed("assemble", timings) {
                OciLayerRootfsAssembler(context, runtime).assemble(
                    layers = pull.layers,
                    destination = rootfs,
                )
            }
            timed("configure", timings) {
                AndroidRootfsConfigurator().configure(rootfs)
            }
            timed("health", timings) {
                ProotRootfsHealthCheck(context, runtime).check(rootfs)
            }

            val osRelease = File(rootfs, "usr/lib/os-release").readText()
            assertTrue(osRelease.contains("Ubuntu 24.04"))
            assertTrue(File(rootfs, "usr/bin/dpkg").canExecute())
            assertTrue(File(rootfs, "usr/bin/perl").canExecute())

            val result =
                buildString {
                    appendLine("reference=${pull.image.reference}")
                    appendLine("manifest=${pull.image.manifestDigest}")
                    appendLine("platform=${platform.os}/${platform.architecture}/${platform.variant}")
                    appendLine("layers=${pull.layers.size}")
                    appendLine("compressedBytes=${pull.layers.sumOf { it.file.length() }}")
                    appendLine("rootfsBytes=${rootfs.walkBottomUp().filter(File::isFile).sumOf(File::length)}")
                    timings.forEach { (phase, milliseconds) ->
                        appendLine("${phase}Ms=$milliseconds")
                    }
                }
            File(context.filesDir, "logs").mkdirs()
            File(context.filesDir, "logs/oci-install-probe.txt").writeText(result)
            println("UDROID_OCI_PROBE\n$result")
        } finally {
            probeDirectory.deleteRecursively()
        }
    }

    private fun <T> timed(
        name: String,
        timings: MutableMap<String, Long>,
        operation: () -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtime()
        return operation().also {
            timings[name] = SystemClock.elapsedRealtime() - startedAt
        }
    }
}
