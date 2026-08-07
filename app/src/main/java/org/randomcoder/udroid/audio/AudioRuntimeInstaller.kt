package org.randomcoder.udroid.audio

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class AudioRuntime(
    val executable: File,
    val libraryDirectory: File,
    val moduleDirectory: File,
)

object AudioRuntimeInstaller {
    private const val RUNTIME_VERSION = "17.0-3-2"
    private const val VERSION_ENTRY = "VERSION"
    private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
    private val expectedEntries =
        setOf(
            "bin/pulseaudio",
            "lib/libFLAC.so",
            "lib/libandroid-execinfo.so",
            "lib/libdbus-1.so",
            "lib/libiconv.so",
            "lib/libltdl.so",
            "lib/libmp3lame.so",
            "lib/libogg.so",
            "lib/libopus.so",
            "lib/libprotocol-native.so",
            "lib/libpulse.so",
            "lib/libpulsecommon-17.0.so",
            "lib/libpulsecore-17.0.so",
            "lib/libsndfile.so",
            "lib/libsoxr.so",
            "lib/libspeexdsp.so",
            "lib/libvorbis.so",
            "lib/libvorbisenc.so",
            "modules/module-native-protocol-tcp.so",
            "modules/module-sles-sink.so",
            "modules/module-sles-source.so",
            VERSION_ENTRY,
        )

    fun install(context: Context): AudioRuntime {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis }
        checkNotNull(abi) {
            "No packaged PulseAudio runtime supports ${Build.SUPPORTED_ABIS.joinToString()}"
        }
        val runtimeParent = File(context.filesDir, "runtime").apply { mkdirs() }
        val destination = File(runtimeParent, "pulseaudio-$RUNTIME_VERSION-$abi")
        if (!isComplete(destination)) {
            val staging =
                File(runtimeParent, ".pulseaudio-${UUID.randomUUID()}.staging").apply {
                    check(mkdirs()) { "Could not prepare PulseAudio runtime staging" }
                }
            try {
                extract(context, "runtime/$abi/pulseaudio-runtime.zip", staging)
                check(isComplete(staging)) { "Packaged PulseAudio runtime is incomplete" }
                if (destination.exists()) {
                    check(destination.deleteRecursively()) {
                        "Could not replace the previous PulseAudio runtime"
                    }
                }
                check(staging.renameTo(destination)) {
                    "Could not atomically activate PulseAudio"
                }
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }
        return AudioRuntime(
            executable = File(destination, "bin/pulseaudio"),
            libraryDirectory = File(destination, "lib"),
            moduleDirectory = File(destination, "modules"),
        )
    }

    private fun extract(
        context: Context,
        assetPath: String,
        destination: File,
    ) {
        val extracted = linkedSetOf<String>()
        ZipInputStream(context.assets.open(assetPath).buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name.removePrefix("./").removeSuffix("/")
                if (name.isBlank()) continue
                val output = File(destination, name)
                val normalizedDestination = destination.canonicalFile.toPath()
                val normalizedOutput = output.canonicalFile.toPath()
                check(normalizedOutput.startsWith(normalizedDestination)) {
                    "Unsafe PulseAudio runtime entry: $name"
                }
                if (entry.isDirectory) {
                    check(output.mkdirs() || output.isDirectory) {
                        "Could not create PulseAudio runtime directory"
                    }
                } else {
                    check(name in expectedEntries) { "Unexpected PulseAudio runtime entry: $name" }
                    val parent = checkNotNull(output.parentFile)
                    check(parent.mkdirs() || parent.isDirectory) {
                        "Could not create the parent directory for $name"
                    }
                    FileOutputStream(output).use { stream ->
                        input.copyTo(stream)
                        stream.fd.sync()
                    }
                    check(output.setReadable(true, true)) { "Could not make $name readable" }
                    if (name != VERSION_ENTRY) {
                        check(output.setExecutable(true, true)) {
                            "Could not make $name executable"
                        }
                    }
                    extracted += name
                }
                input.closeEntry()
            }
        }
        check(extracted == expectedEntries) {
            "PulseAudio runtime archive is missing ${expectedEntries - extracted}"
        }
    }

    private fun isComplete(directory: File): Boolean =
        expectedEntries.all { File(directory, it).isFile } &&
            File(directory, VERSION_ENTRY).readText().trim() == "17.0-3"
}
