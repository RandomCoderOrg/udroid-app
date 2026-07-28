package org.randomcoder.udroid.install

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.randomcoder.udroid.oci.OciImageReference
import org.randomcoder.udroid.oci.OciPlatform

@RunWith(AndroidJUnit4::class)
class InstallProgressPersistenceProbeTest {
    @Test
    fun ociProgressSurvivesAndroidPreferencesPersistence() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("org.randomcoder.udroid.ociprobe", context.packageName)
        val store = InstallStateStore(context)
        store.clear()
        val expected =
            InstallProgress(
                work =
                    InstallerWorkRequest.Oci(
                        reference = OciImageReference.parse("ubuntu:24.04"),
                        platform = OciPlatform("linux", "arm64", "v8"),
                        installationName = "oci-progress-probe",
                        displayName = "Ubuntu progress probe",
                        architecture = "arm64",
                        operationId = "progress-probe-operation",
                    ),
                stage = InstallStage.DOWNLOADING,
                stageProgress = 0.37f,
                currentDetail = "Downloading verified image data",
                terminalLines = listOf("[resolve] manifest", "[download] 37 MiB / 100 MiB"),
                previewOnly = false,
                completedBytes = 37,
                totalBytes = 100,
                bytesPerSecond = 5,
                cancellable = true,
            )

        try {
            store.save(expected)
            val actual = InstallStateStore(context).current()

            assertEquals(expected, actual)
            assertTrue(actual?.work is InstallerWorkRequest.Oci)
            assertEquals(19, actual?.percentage)

            val writeMicros =
                (1..40)
                    .map { sample ->
                        val startedAt = SystemClock.elapsedRealtimeNanos()
                        store.save(expected.copy(stageProgress = sample / 100f))
                        (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000L
                    }.sorted()
            println(
                "UDROID_INSTALL_PROGRESS_PERSISTENCE\n" +
                    "writes=${writeMicros.size}\n" +
                    "medianUs=${writeMicros[writeMicros.size / 2]}\n" +
                    "p95Us=${writeMicros[(writeMicros.size * 95 / 100).coerceAtMost(writeMicros.lastIndex)]}\n",
            )
        } finally {
            store.clear()
        }
    }
}
