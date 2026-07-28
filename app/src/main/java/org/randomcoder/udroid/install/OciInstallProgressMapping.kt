package org.randomcoder.udroid.install

import org.randomcoder.udroid.oci.OciInstallEvent
import org.randomcoder.udroid.oci.OciInstallStage
import kotlin.math.roundToInt

internal object OciInstallProgressMapping {
    data class MappedProgress(
        val stage: InstallStage,
        val stageProgress: Float,
        val percentage: Int,
    )

    fun map(event: OciInstallEvent): MappedProgress {
        val fraction =
            if (event.totalBytes > 0L) {
                event.completedBytes.toDouble() / event.totalBytes.toDouble()
            } else {
                0.0
            }.coerceIn(0.0, 1.0)
        val (stage, stageProgress) =
            when (event.stage) {
                OciInstallStage.RESOLVING -> InstallStage.CHECKING to 0.40f
                // OCI validates each blob immediately after downloading it. Both raw
                // events therefore belong to one monotonic acquisition segment.
                OciInstallStage.DOWNLOADING,
                OciInstallStage.VERIFYING
                -> InstallStage.DOWNLOADING to fraction.toFloat()
                OciInstallStage.ASSEMBLING -> InstallStage.EXTRACTING to fraction.toFloat()
                OciInstallStage.CONFIGURING -> InstallStage.CONFIGURING to 0.20f
                OciInstallStage.HEALTH_CHECKING -> InstallStage.CONFIGURING to 0.60f
                OciInstallStage.ACTIVATING -> InstallStage.CONFIGURING to 0.87f
                OciInstallStage.READY -> InstallStage.COMPLETE to 1f
            }
        val percentage =
            when (stage) {
                InstallStage.COMPLETE -> 100
                else ->
                    ((stage.startFraction + stage.weight * stageProgress) * 100f)
                        .roundToInt()
                        .coerceIn(0, 100)
            }
        return MappedProgress(stage, stageProgress, percentage)
    }

    fun percentage(event: OciInstallEvent): Int = map(event).percentage
}
