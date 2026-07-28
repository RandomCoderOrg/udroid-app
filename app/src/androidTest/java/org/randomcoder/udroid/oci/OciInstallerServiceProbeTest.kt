package org.randomcoder.udroid.oci

import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.randomcoder.udroid.UdroidApplication
import org.randomcoder.udroid.install.InstallStage
import org.randomcoder.udroid.install.InstallerService
import org.randomcoder.udroid.install.InstallerWorkRequest
import org.randomcoder.udroid.install.RootfsInstallationPipeline
import java.io.File

@RunWith(AndroidJUnit4::class)
class OciInstallerServiceProbeTest {
    @Test
    fun foregroundServiceInstallsAndRegistersOfficialUbuntuImage() {
        val app = ApplicationProvider.getApplicationContext<UdroidApplication>()
        assertEquals("org.randomcoder.udroid.ociprobe", app.packageName)
        val installationName = "oci-service-ubuntu-24.04-probe"
        val rootfsDirectory = File(app.filesDir, "rootfs")
        val active = File(rootfsDirectory, installationName)
        val staging = File(rootfsDirectory, ".$installationName.oci-staging")
        val blobCache = File(app.filesDir, "artifacts/oci-blobs")
        active.deleteRecursively()
        staging.deleteRecursively()
        blobCache.deleteRecursively()

        try {
            val startedAt = SystemClock.elapsedRealtime()
            InstallerService.startOci(
                context = app,
                reference = OciImageReference.parse("ubuntu:24.04"),
                platform = OciPlatform.fromAndroidAbis(Build.SUPPORTED_ABIS.toList()),
                installationName = installationName,
                displayName = "Ubuntu 24.04 service probe",
                architecture = "aarch64",
            )

            await("foreground OCI installation", TIMEOUT_MS) {
                File(active, RootfsInstallationPipeline.READY_MARKER).isFile &&
                    app.rootfsRegistry.all().any { it.name == installationName } &&
                    app.journal.tail(240).any { line ->
                        line.contains("\"event\":\"rootfs_ready\"") &&
                            line.contains("\"source\":\"oci\"") &&
                            line.contains(installationName)
                    }
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val marker = File(active, RootfsInstallationPipeline.READY_MARKER).readText()
            val journal = app.journal.tail(240)
            val progress = app.installState.current() ?: error("Missing installer progress")

            assertTrue(marker.contains("source=oci"))
            assertTrue(marker.contains("reference=registry-1.docker.io/library/ubuntu:24.04"))
            assertFalse(staging.exists())
            assertTrue(
                listOf(
                    "oci_requested",
                    "oci_resolving",
                    "oci_downloading",
                    "oci_verifying",
                    "oci_assembling",
                    "oci_configuring",
                    "oci_health_checking",
                    "oci_activating",
                    "oci_ready",
                    "rootfs_ready",
                ).all { expected -> journal.any { it.contains("\"event\":\"$expected\"") } },
            )
            assertTrue(blobCache.listFiles().orEmpty().none(File::isFile))
            assertEquals(InstallStage.COMPLETE, progress.stage)
            assertTrue(progress.work is InstallerWorkRequest.Oci)
            assertEquals(installationName, progress.installationName)
            assertEquals(100, progress.percentage)
            assertTrue(progress.terminalLines.any { it.startsWith("[download]") })
            assertTrue(progress.terminalLines.any { it.startsWith("[complete]") })

            println(
                "UDROID_OCI_SERVICE_PROBE\n" +
                    "installMs=$elapsedMs\n" +
                    "rootfs=${active.absolutePath}\n" +
                    "journalStages=10\n",
            )
        } finally {
            InstallerService.pause(app)
            SystemClock.sleep(500)
            active.deleteRecursively()
            staging.deleteRecursively()
            blobCache.deleteRecursively()
        }
    }

    private fun await(
        description: String,
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error(
            "Timed out waiting for $description\n" +
                ApplicationProvider.getApplicationContext<UdroidApplication>()
                    .journal
                    .tail(80)
                    .joinToString("\n"),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
    }
}
