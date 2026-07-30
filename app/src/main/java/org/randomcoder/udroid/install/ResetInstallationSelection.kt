package org.randomcoder.udroid.install

import java.util.UUID

object ResetInstallationSelection {
    fun initial(
        previous: InstallerWorkRequest,
        operationId: String = UUID.randomUUID().toString(),
    ): InstallProgress {
        val work =
            when (previous) {
                is InstallerWorkRequest.Archive ->
                    previous.copy(operationId = operationId)
                is InstallerWorkRequest.Oci ->
                    previous.copy(operationId = operationId)
            }
        return InstallProgress(
            work = work,
            stage = InstallStage.READY,
            stageProgress = 0f,
            currentDetail = "Ready to rebuild ${work.displayName} from its original image",
            terminalLines =
                listOf(
                    "\$ udroid reset --plan ${work.installationName}",
                    "[ready] source ${sourceLabel(work)}",
                    "[ready] existing filesystem removed",
                ),
            previewOnly = false,
        )
    }

    private fun sourceLabel(work: InstallerWorkRequest): String =
        when (work) {
            is InstallerWorkRequest.Archive -> work.distro.id
            is InstallerWorkRequest.Oci -> work.reference.toString()
        }
}
