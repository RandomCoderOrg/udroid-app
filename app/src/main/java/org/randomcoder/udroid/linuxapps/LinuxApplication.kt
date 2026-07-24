package org.randomcoder.udroid.linuxapps

data class LinuxApplication(
    val id: String,
    val name: String,
    val genericName: String?,
    val comment: String?,
    val executable: String,
    val arguments: List<String>,
    val iconName: String?,
    val iconPath: String?,
    val desktopFilePath: String,
    val desktopFileGuestPath: String,
    val workingDirectory: String,
    val categories: List<String>,
    val terminal: Boolean,
)

data class LinuxApplicationScanResult(
    val applications: List<LinuxApplication>,
    val scannedEntries: Int,
    val ignoredEntries: Int,
    val elapsedMillis: Long,
)

sealed interface LinuxApplicationsState {
    data object Loading : LinuxApplicationsState

    data class Ready(
        val rootfsName: String,
        val result: LinuxApplicationScanResult,
    ) : LinuxApplicationsState

    data class Failed(
        val message: String,
    ) : LinuxApplicationsState
}
