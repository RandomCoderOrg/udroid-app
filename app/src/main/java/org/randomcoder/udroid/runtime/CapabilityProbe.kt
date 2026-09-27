package org.randomcoder.udroid.runtime

import android.content.Context
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.storage.StorageManager
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import java.io.File

internal data class ProbeRead(
    val succeeded: Boolean,
    val value: String? = null,
)

object CapabilityProbe {
    fun run(context: Context): List<CapabilityResult> {
        val results = mutableListOf<CapabilityResult>()
        val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        val selectedAbi = Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis }
        results +=
            result(
                name = "Supported Linux architecture",
                passed = selectedAbi != null,
                detail =
                    selectedAbi?.let { "$it selected; device offers ${Build.SUPPORTED_ABIS.joinToString()}" }
                        ?: "Device offers ${Build.SUPPORTED_ABIS.joinToString()}",
                required = true,
            )

        val socModel =
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        results +=
            CapabilityResult(
                name = "Device profile",
                status = CapabilityStatus.INFO,
                detail = "${Build.MANUFACTURER} ${Build.MODEL}; SoC $socModel",
                required = false,
            )

        results += phantomProcessMonitorResult(context)

        val dmaHeapDirectory = File("/dev/dma_heap")
        val heaps =
            dmaHeapDirectory.list()
                ?.map { File(dmaHeapDirectory, it) }
                ?.sortedBy { it.name }
                .orEmpty()
        val openableHeap = heaps.firstOrNull { canOpen(it.path) }
        results +=
            result(
                name = "DMA heap (optional acceleration)",
                passed = openableHeap != null,
                detail =
                    if (openableHeap != null) {
                        "${openableHeap.path} is openable; ${heaps.size} node(s) visible"
                    } else {
                        "${heaps.size} node(s) visible; none openable"
                    },
                required = false,
            )

        val ahbResult =
            runCatching {
                HardwareBuffer.create(
                    4,
                    4,
                    HardwareBuffer.RGBA_8888,
                    1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
                ).close()
            }
        results +=
            result(
                name = "AHardwareBuffer (optional display)",
                passed = ahbResult.isSuccess,
                detail =
                    ahbResult.exceptionOrNull()?.message
                        ?: "RGBA GPU allocation succeeded",
                required = false,
            )

        val usableBytes =
            runCatching {
                context.getSystemService(StorageManager::class.java)
                    .getAllocatableBytes(StorageManager.UUID_DEFAULT)
            }.getOrElse { context.filesDir.usableSpace }
        results +=
            CapabilityResult(
                name = "App storage",
                status = CapabilityStatus.INFO,
                detail =
                    "${formatBytes(usableBytes)} currently allocatable; " +
                        "the selected distro sets the real requirement",
                required = false,
            )

        results +=
            CapabilityResult(
                name = "Android / kernel",
                status = CapabilityStatus.INFO,
                detail = "API ${Build.VERSION.SDK_INT}; ${System.getProperty("os.version")}",
                required = false,
            )

        return results
    }

    private fun canOpen(path: String): Boolean =
        runCatching {
            val descriptor =
                Os.open(path, OsConstants.O_RDWR, 0)
            Os.close(descriptor)
        }.isSuccess

    private fun phantomProcessMonitorResult(context: Context): CapabilityResult {
        if (Build.VERSION.SDK_INT < 31) {
            return classifyPhantomProcessMonitor(Build.VERSION.SDK_INT, ProbeRead(false), ProbeRead(false))
        }

        val global =
            runCatching {
                Settings.Global.getString(
                    context.contentResolver,
                    PHANTOM_PROCESS_MONITOR_SETTING,
                )
            }.fold(
                onSuccess = { ProbeRead(succeeded = true, value = it?.trim()) },
                onFailure = { ProbeRead(succeeded = false) },
            )
        val property =
            if (global.succeeded && global.value.isNullOrBlank()) {
                readSystemProperty(PHANTOM_PROCESS_MONITOR_PROPERTY)
            } else {
                ProbeRead(succeeded = false)
            }
        return classifyPhantomProcessMonitor(Build.VERSION.SDK_INT, global, property)
    }

    private fun readSystemProperty(name: String): ProbeRead =
        runCatching {
            val process = ProcessBuilder("/system/bin/getprop", name).start()
            val value = process.inputStream.bufferedReader().use { it.readText().trim() }
            if (process.waitFor() == 0) ProbeRead(succeeded = true, value = value) else ProbeRead(false)
        }.getOrElse { ProbeRead(succeeded = false) }

    private fun result(
        name: String,
        passed: Boolean,
        detail: String,
        required: Boolean,
    ) = CapabilityResult(
        name = name,
        status = if (passed) CapabilityStatus.PASS else CapabilityStatus.FAIL,
        detail = detail,
        required = required,
    )

    private fun formatBytes(bytes: Long): String {
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return "%.1f GiB".format(gib)
    }

    private const val PHANTOM_PROCESS_MONITOR_SETTING =
        "settings_enable_monitor_phantom_procs"
    private const val PHANTOM_PROCESS_MONITOR_PROPERTY =
        "persist.sys.fflag.override.settings_enable_monitor_phantom_procs"
}

internal fun classifyPhantomProcessMonitor(
    sdkInt: Int,
    global: ProbeRead,
    property: ProbeRead,
): CapabilityResult {
    if (sdkInt < 31) {
        return CapabilityResult(
            name = "Child process restrictions",
            status = CapabilityStatus.PASS,
            detail = "Not used by this Android version",
            required = false,
        )
    }

    if (!global.succeeded) return unknownPhantomProcessMonitorResult(sdkInt)
    val selected = if (global.value.isNullOrBlank()) property else global
    if (!selected.succeeded) return unknownPhantomProcessMonitorResult(sdkInt)

    val value = selected.value.orEmpty()
    val guidance = phantomProcessMonitorGuidance(sdkInt)
    if (value.isBlank()) {
        return CapabilityResult(
            name = "Child process restrictions",
            status = CapabilityStatus.WARNING,
            detail =
                "No override is set; Android's default is enabled on standard builds and may " +
                    "stop Linux child processes. $guidance",
            required = false,
            showDeveloperOptionsAction = sdkInt >= 34,
        )
    }

    return when (value.lowercase()) {
        "false" ->
            CapabilityResult(
                name = "Child process restrictions",
                status = CapabilityStatus.PASS,
                detail = "Disabled; Linux child processes are not restricted by this monitor",
                required = false,
            )
        "true" ->
            CapabilityResult(
                name = "Child process restrictions",
                status = CapabilityStatus.WARNING,
                detail = activePhantomProcessMonitorDetail(sdkInt),
                required = false,
                showDeveloperOptionsAction = sdkInt >= 34,
                linuxProcessRestrictionActive = true,
            )
        else -> unknownPhantomProcessMonitorResult(sdkInt)
    }
}

private fun unknownPhantomProcessMonitorResult(sdkInt: Int) =
    CapabilityResult(
        name = "Child process restrictions",
        status = CapabilityStatus.WARNING,
        detail = "State unknown. ${phantomProcessMonitorGuidance(sdkInt)}",
        required = false,
        showDeveloperOptionsAction = sdkInt >= 34,
    )

private fun phantomProcessMonitorGuidance(sdkInt: Int): String =
    if (sdkInt >= 34) {
        "Enable Developer options > Disable child process restrictions. uDroid cannot change it."
    } else {
        "This Android version has no built-in toggle and uDroid cannot change it. Run: " +
            "adb shell settings put global settings_enable_monitor_phantom_procs false"
    }

private fun activePhantomProcessMonitorDetail(sdkInt: Int): String =
    if (sdkInt >= 34) {
        "Android can stop desktops and larger Linux apps when they start many background " +
            "processes. In Developer options, turn on “Disable child process restrictions.”"
    } else {
        "Android can stop desktops and larger Linux apps when they start many background " +
            "processes. This Android version has no settings toggle. Use ADB: adb shell " +
            "settings put global settings_enable_monitor_phantom_procs false"
    }
