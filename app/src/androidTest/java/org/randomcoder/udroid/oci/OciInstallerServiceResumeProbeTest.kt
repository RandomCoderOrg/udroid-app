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
import org.randomcoder.udroid.install.RootfsInstallationPipeline
import java.io.File

@RunWith(AndroidJUnit4::class)
class OciInstallerServiceResumeProbeTest {
    @Test
    fun interruptedLayerDownloadIsRetainedAndResumedByNextServiceRun() {
        val app = ApplicationProvider.getApplicationContext<UdroidApplication>()
        assertEquals("org.randomcoder.udroid.ociprobe", app.packageName)
        val installationName = "oci-service-resume-probe"
        val rootfsDirectory = File(app.filesDir, "rootfs")
        val active = File(rootfsDirectory, installationName)
        val staging = File(rootfsDirectory, ".$installationName.oci-staging")
        val blobCache = File(app.filesDir, "artifacts/oci-blobs")
        active.deleteRecursively()
        staging.deleteRecursively()
        blobCache.deleteRecursively()

        try {
            start(app, installationName)
            val partial =
                awaitValue("a partially downloaded OCI layer", DOWNLOAD_TIMEOUT_MS) {
                    blobCache
                        .listFiles()
                        .orEmpty()
                        .firstOrNull { it.name.endsWith(".part") && it.length() >= PAUSE_AFTER_BYTES }
                }
            val pausedAtBytes = partial.length()
            InstallerService.pause(app)
            await("the foreground worker to acknowledge cancellation", PAUSE_TIMEOUT_MS) {
                app.journal.tail(160).any { it.contains("\"event\":\"oci_paused\"") }
            }
            val pausedProgress = app.installState.current() ?: error("Missing paused progress")

            assertFalse(active.exists())
            assertFalse(staging.exists())
            assertTrue(partial.isFile)
            assertTrue(partial.length() >= pausedAtBytes)
            assertEquals(InstallStage.PAUSED, pausedProgress.stage)
            assertEquals(installationName, pausedProgress.installationName)
            assertFalse(pausedProgress.cancellable)

            val resumedAt = SystemClock.elapsedRealtime()
            start(app, installationName)
            await("the resumed OCI installation", INSTALL_TIMEOUT_MS) {
                File(active, RootfsInstallationPipeline.READY_MARKER).isFile &&
                    app.journal.tail(240).any { line ->
                        line.contains("\"event\":\"rootfs_ready\"") &&
                            line.contains(installationName)
                    }
            }
            val resumeMs = SystemClock.elapsedRealtime() - resumedAt
            val journal = app.journal.tail(240)
            val completedProgress =
                app.installState.current() ?: error("Missing completed progress")

            assertTrue(File(active, RootfsInstallationPipeline.READY_MARKER).isFile)
            assertTrue(blobCache.listFiles().orEmpty().none(File::isFile))
            assertTrue(journal.count { it.contains("\"event\":\"oci_requested\"") } >= 2)
            assertTrue(journal.any { it.contains("\"event\":\"oci_paused\"") })
            assertTrue(
                journal.any { line ->
                    line.contains("\"event\":\"oci_downloading\"") &&
                        line.contains("\"resumed\":true")
                },
            )
            assertEquals(InstallStage.COMPLETE, completedProgress.stage)
            assertEquals(installationName, completedProgress.installationName)

            println(
                "UDROID_OCI_SERVICE_RESUME_PROBE\n" +
                    "pausedAtBytes=$pausedAtBytes\n" +
                    "resumeMs=$resumeMs\n" +
                    "ready=true\n",
            )
        } finally {
            InstallerService.pause(app)
            SystemClock.sleep(500)
            active.deleteRecursively()
            staging.deleteRecursively()
            blobCache.deleteRecursively()
        }
    }

    private fun start(
        app: UdroidApplication,
        installationName: String,
    ) {
        InstallerService.startOci(
            context = app,
            reference = OciImageReference.parse("ubuntu:24.04"),
            platform = OciPlatform.fromAndroidAbis(Build.SUPPORTED_ABIS.toList()),
            installationName = installationName,
            displayName = "Ubuntu 24.04 resume probe",
            architecture = "aarch64",
        )
    }

    private fun await(
        description: String,
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        awaitValue(description, timeoutMs) {
            if (condition()) true else null
        }
    }

    private fun <T : Any> awaitValue(
        description: String,
        timeoutMs: Long,
        value: () -> T?,
    ): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            value()?.let { return it }
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
        const val PAUSE_AFTER_BYTES = 2L * 1024L * 1024L
        const val DOWNLOAD_TIMEOUT_MS = 60_000L
        const val PAUSE_TIMEOUT_MS = 30_000L
        const val INSTALL_TIMEOUT_MS = 180_000L
    }
}
