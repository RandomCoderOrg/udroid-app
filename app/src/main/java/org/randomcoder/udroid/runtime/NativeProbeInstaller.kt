package org.randomcoder.udroid.runtime

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream

object NativeProbeInstaller {
    private const val PROBE_VERSION = "4"
    private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

    fun install(context: Context): File {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis }
        checkNotNull(abi) {
            "No packaged uDroid probe supports ${Build.SUPPORTED_ABIS.joinToString()}"
        }
        val assetPath = "runtime/$abi/runtime_probe"

        val runtimeDirectory =
            File(context.filesDir, "runtime/probe-$PROBE_VERSION-$abi").apply {
                mkdirs()
            }
        val destination = File(runtimeDirectory, "runtime_probe")
        if (destination.exists() && destination.canExecute()) return destination

        val staging = File(runtimeDirectory, "runtime_probe.staging")
        if (staging.exists()) staging.delete()

        context.assets.open(assetPath).use { input ->
            FileOutputStream(staging).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }

        check(staging.setReadable(true, true)) { "Could not make probe readable" }
        check(staging.setWritable(true, true)) { "Could not make probe writable" }
        check(staging.setExecutable(true, true)) { "Could not make probe executable" }

        if (destination.exists()) check(destination.delete()) {
            "Could not replace the previous probe"
        }
        check(staging.renameTo(destination)) { "Could not atomically activate the probe" }
        return destination
    }
}
