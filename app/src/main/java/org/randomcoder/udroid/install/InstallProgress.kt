package org.randomcoder.udroid.install

import org.randomcoder.udroid.catalog.DistroVariant

enum class InstallStage(
    val normalTitle: String,
    val normalSubtitle: String,
    val startFraction: Float,
    val weight: Float,
) {
    CHECKING(
        normalTitle = "Checking your device",
        normalSubtitle = "Making sure this Linux image fits and can run here",
        startFraction = 0.00f,
        weight = 0.05f,
    ),
    DOWNLOADING(
        normalTitle = "Bringing Linux onto your phone",
        normalSubtitle = "Downloading the selected system image",
        startFraction = 0.05f,
        weight = 0.40f,
    ),
    VERIFYING(
        normalTitle = "Checking the download",
        normalSubtitle = "Making sure every byte arrived unchanged",
        startFraction = 0.45f,
        weight = 0.10f,
    ),
    EXTRACTING(
        normalTitle = "Building your Linux system",
        normalSubtitle = "Unpacking files into an isolated uDroid environment",
        startFraction = 0.55f,
        weight = 0.30f,
    ),
    CONFIGURING(
        normalTitle = "Finishing the setup",
        normalSubtitle = "Preparing users, networking, and the first boot",
        startFraction = 0.85f,
        weight = 0.15f,
    ),
    COMPLETE(
        normalTitle = "Your Linux system is ready",
        normalSubtitle = "You can boot it now",
        startFraction = 1.00f,
        weight = 0.00f,
    ),
    FAILED(
        normalTitle = "Installation needs attention",
        normalSubtitle = "Open the terminal details to see what stopped",
        startFraction = 0.00f,
        weight = 0.00f,
    ),
}

data class InstallProgress(
    val distro: DistroVariant,
    val stage: InstallStage,
    val stageProgress: Float,
    val currentDetail: String,
    val terminalLines: List<String>,
    val previewOnly: Boolean,
) {
    val overallProgress: Float =
        when (stage) {
            InstallStage.COMPLETE -> 1f
            InstallStage.FAILED -> 0f
            else ->
                (stage.startFraction + (stage.weight * stageProgress.coerceIn(0f, 1f)))
                    .coerceIn(0f, 1f)
        }

    val percentage: Int = (overallProgress * 100).toInt().coerceIn(0, 100)
}

object InstallationUxPreview {
    data class Step(
        val stage: InstallStage,
        val stageProgress: Float,
        val detail: String,
        val terminalLine: String,
        val delayMs: Long = 420,
    )

    fun steps(distro: DistroVariant): List<Step> =
        listOf(
            Step(
                InstallStage.CHECKING,
                0.25f,
                "Checking architecture ${distro.architecture}",
                "\$ udroid install --plan ${distro.id}",
            ),
            Step(
                InstallStage.CHECKING,
                1.00f,
                "Storage and image metadata look good",
                "[ok] sha256 metadata present for ${distro.architecture}",
            ),
            Step(
                InstallStage.DOWNLOADING,
                0.12f,
                "Downloading the base system",
                "[download] 126 MiB / 1.02 GiB · 8.4 MiB/s",
            ),
            Step(
                InstallStage.DOWNLOADING,
                0.48f,
                "Downloading the base system",
                "[download] 492 MiB / 1.02 GiB · range resume enabled",
            ),
            Step(
                InstallStage.DOWNLOADING,
                0.82f,
                "Almost finished downloading",
                "[download] 839 MiB / 1.02 GiB · 7.9 MiB/s",
            ),
            Step(
                InstallStage.DOWNLOADING,
                1.00f,
                "Download complete",
                "[ok] cached ${distro.internalName}.tar.gz",
            ),
            Step(
                InstallStage.VERIFYING,
                1.00f,
                "The downloaded image passed its integrity check",
                "[ok] sha256 ${distro.sha256.take(16)}…",
            ),
            Step(
                InstallStage.EXTRACTING,
                0.18f,
                "Unpacking the base filesystem",
                "[extract] usr/lib/aarch64-linux-gnu/",
            ),
            Step(
                InstallStage.EXTRACTING,
                0.61f,
                "Adding system files and links",
                "[extract] translated hard links with proot --link2symlink",
            ),
            Step(
                InstallStage.EXTRACTING,
                1.00f,
                "Linux files are in place",
                "[ok] activated rootfs/${distro.internalName}",
            ),
            Step(
                InstallStage.CONFIGURING,
                0.45f,
                "Preparing networking and the default user",
                "[configure] wrote resolv.conf and passwd mappings",
            ),
            Step(
                InstallStage.CONFIGURING,
                1.00f,
                "First boot checks passed",
                "[ok] proot /usr/bin/env true",
            ),
            Step(
                InstallStage.COMPLETE,
                1.00f,
                "Installed as ${distro.internalName}",
                "[complete] ${distro.id} is ready to boot",
                delayMs = 0,
            ),
        )

    fun initial(distro: DistroVariant): InstallProgress =
        InstallProgress(
            distro = distro,
            stage = InstallStage.CHECKING,
            stageProgress = 0f,
            currentDetail = "Preparing an installation UX preview",
            terminalLines =
                listOf(
                    "# Preview mode: no distro archive will be downloaded",
                    "# The real installer will emit the same event stream",
                ),
            previewOnly = true,
        )
}
