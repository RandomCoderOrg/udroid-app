package org.randomcoder.udroid.runtime

import android.content.Context
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.storage.StorageManager
import android.system.Os
import android.system.OsConstants
import java.io.File

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
}
