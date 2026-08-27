package org.randomcoder.udroid.gfxstream

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

data class GfxstreamHostRuntime(
    val executable: File,
    val libraryDirectory: File,
    val version: String,
)

/** Installs the optional Android-host Kumquat runtime from signed APK assets. */
object GfxstreamHostRuntimeInstaller {
    internal const val RUNTIME_VERSION = "2-0435a83-9bdcb4585"
    private const val MANIFEST_ENTRY = "MANIFEST.properties"
    private val runtimeEntries = listOf("bin/kumquat", "lib/libc++_shared.so")
    private val supportedAbis = setOf("arm64-v8a")

    fun install(context: Context): GfxstreamHostRuntime {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis }
        checkNotNull(abi) {
            "The experimental gfxstream host does not support ${Build.SUPPORTED_ABIS.joinToString()}"
        }
        val assetRoot = "runtime/$abi/gfxstream-host"
        val packagedManifest = loadManifest(context, "$assetRoot/$MANIFEST_ENTRY")
        validateManifest(packagedManifest, abi)

        val runtimeParent = File(context.filesDir, "runtime").apply { mkdirs() }
        val destination = File(runtimeParent, "gfxstream-host-$RUNTIME_VERSION-$abi")
        if (!isComplete(destination, abi)) {
            val staging =
                File(runtimeParent, ".gfxstream-host-${UUID.randomUUID()}.staging").apply {
                    check(mkdirs()) { "Could not prepare gfxstream host staging" }
                }
            try {
                runtimeEntries.forEach { entry ->
                    val output = safeOutput(staging, entry)
                    val parent = checkNotNull(output.parentFile)
                    check(parent.mkdirs() || parent.isDirectory) {
                        "Could not create the parent directory for $entry"
                    }
                    context.assets.open("$assetRoot/$entry").use { input ->
                        FileOutputStream(output).use { stream ->
                            input.copyTo(stream)
                            stream.fd.sync()
                        }
                    }
                    val expectedDigest = checkNotNull(packagedManifest.getProperty("$entry.sha256"))
                    check(output.sha256() == expectedDigest) {
                        "Packaged gfxstream host failed integrity verification: $entry"
                    }
                    check(output.setReadable(true, true)) { "Could not make $entry readable" }
                    if (entry == "bin/kumquat") {
                        check(output.setExecutable(true, true)) { "Could not make Kumquat executable" }
                    }
                }
                File(staging, MANIFEST_ENTRY).writeText(
                    context.assets.open("$assetRoot/$MANIFEST_ENTRY").bufferedReader().use { it.readText() },
                )
                check(isComplete(staging, abi)) { "Packaged gfxstream host is incomplete" }
                if (destination.exists()) {
                    check(destination.deleteRecursively()) {
                        "Could not replace the previous gfxstream host"
                    }
                }
                check(staging.renameTo(destination)) {
                    "Could not atomically activate the gfxstream host"
                }
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }

        return GfxstreamHostRuntime(
            executable = File(destination, "bin/kumquat"),
            libraryDirectory = File(destination, "lib"),
            version = RUNTIME_VERSION,
        )
    }

    private fun isComplete(
        directory: File,
        abi: String,
    ): Boolean {
        if (!directory.isDirectory) return false
        val manifestFile = File(directory, MANIFEST_ENTRY)
        if (!manifestFile.isFile) return false
        val manifest = runCatching { Properties().apply { manifestFile.inputStream().use(::load) } }
            .getOrNull() ?: return false
        return runCatching { validateManifest(manifest, abi) }.isSuccess &&
            runtimeEntries.all { File(directory, it).isFile } &&
            File(directory, "bin/kumquat").canExecute()
    }

    private fun loadManifest(
        context: Context,
        assetPath: String,
    ): Properties = Properties().apply { context.assets.open(assetPath).use(::load) }

    private fun validateManifest(
        manifest: Properties,
        abi: String,
    ) {
        check(manifest.getProperty("format") == "1") { "Unsupported gfxstream host manifest" }
        check(manifest.getProperty("runtime") == RUNTIME_VERSION) {
            "Mismatched gfxstream host runtime"
        }
        check(manifest.getProperty("abi") == abi) { "Mismatched gfxstream host ABI" }
        runtimeEntries.forEach { entry ->
            val digest = manifest.getProperty("$entry.sha256")
            check(digest?.matches(Regex("[0-9a-f]{64}")) == true) {
                "Missing gfxstream host digest for $entry"
            }
        }
    }

    private fun safeOutput(
        root: File,
        entry: String,
    ): File {
        val output = File(root, entry)
        check(output.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            "Unsafe gfxstream host entry: $entry"
        }
        return output
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
