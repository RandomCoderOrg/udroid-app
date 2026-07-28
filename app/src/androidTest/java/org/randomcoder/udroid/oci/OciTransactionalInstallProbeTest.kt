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
import org.randomcoder.udroid.install.RootfsInstallationPipeline
import org.randomcoder.udroid.runtime.InstalledRootfsRegistry
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OciTransactionalInstallProbeTest {
    @Test
    fun installsActivatesDiscoversAndReusesOfficialUbuntuImage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("org.randomcoder.udroid.ociprobe", context.packageName)
        val installationName = "oci-ubuntu-24.04-probe"
        val rootfsDirectory = File(context.filesDir, "rootfs")
        val blobCache = File(context.cacheDir, "oci-transaction-probe/blobs")
        val active = File(rootfsDirectory, installationName)
        val staging = File(rootfsDirectory, ".$installationName.oci-staging")
        active.deleteRecursively()
        staging.deleteRecursively()
        blobCache.parentFile?.deleteRecursively()
        val events = mutableListOf<OciInstallEvent>()

        try {
            val request =
                OciRootfsInstallRequest(
                    reference = OciImageReference.parse("ubuntu:24.04"),
                    platform = OciPlatform.fromAndroidAbis(Build.SUPPORTED_ABIS.toList()),
                    rootfsDirectory = rootfsDirectory,
                    blobCacheDirectory = blobCache,
                    installationName = installationName,
                    operationId = UUID.randomUUID().toString(),
                )
            val startedAt = SystemClock.elapsedRealtime()
            val result = OciRootfsInstaller(context).install(request, events::add)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt

            assertFalse(result.reusedInstallation)
            assertEquals(active.canonicalPath, result.rootfs.canonicalPath)
            assertTrue(File(active, RootfsInstallationPipeline.READY_MARKER).isFile)
            assertFalse(staging.exists())
            assertTrue(
                InstalledRootfsRegistry(context).all()
                    .any { it.name == installationName },
            )
            assertEquals(
                listOf(
                    OciInstallStage.RESOLVING,
                    OciInstallStage.DOWNLOADING,
                    OciInstallStage.VERIFYING,
                    OciInstallStage.ASSEMBLING,
                    OciInstallStage.CONFIGURING,
                    OciInstallStage.HEALTH_CHECKING,
                    OciInstallStage.ACTIVATING,
                    OciInstallStage.READY,
                ),
                events.map(OciInstallEvent::stage).distinct(),
            )
            assertTrue("Progress event count was ${events.size}", events.size < 200)
            assertTrue(blobCache.listFiles().orEmpty().none(File::isFile))

            val reuseStartedAt = SystemClock.elapsedRealtime()
            val reused = OciRootfsInstaller(context).install(request)
            val reuseMs = SystemClock.elapsedRealtime() - reuseStartedAt
            assertTrue(reused.reusedInstallation)
            assertEquals(result.manifestDigest, reused.manifestDigest)

            val marker =
                File(active, RootfsInstallationPipeline.READY_MARKER)
                    .readText()
            assertTrue(marker.contains("source=oci"))
            assertTrue(marker.contains("reference=registry-1.docker.io/library/ubuntu:24.04"))
            assertTrue(marker.contains("manifest=${result.manifestDigest}"))

            val output =
                buildString {
                    appendLine("manifest=${result.manifestDigest}")
                    appendLine("compressedBytes=${result.compressedBytes}")
                    appendLine("installMs=$elapsedMs")
                    appendLine("reuseMs=$reuseMs")
                    appendLine("events=${events.size}")
                    appendLine("stages=${events.map(OciInstallEvent::stage).distinct().joinToString()}")
                }
            File(context.filesDir, "oci-transaction-probe.txt").writeText(output)
            println("UDROID_OCI_TRANSACTION_PROBE\n$output")
        } finally {
            active.deleteRecursively()
            staging.deleteRecursively()
            blobCache.parentFile?.deleteRecursively()
        }
    }
}
