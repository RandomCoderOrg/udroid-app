package org.randomcoder.udroid.catalog

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Immutable
data class DistroVariant(
    val suite: String,
    val variant: String,
    val internalName: String,
    val friendlyName: String,
    val architecture: String,
    val downloadUrl: String,
    val sha256: String,
) {
    val id: String = "$suite:$variant"
    val recommended: Boolean = suite == "jammy" && variant == "raw"

    val releaseName: String
        get() =
            when (suite) {
                "focal" -> "Ubuntu 20.04 LTS"
                "jammy" -> "Ubuntu 22.04 LTS"
                "noble" -> "Ubuntu 24.04 LTS"
                "resolute" -> "Ubuntu 26.04 LTS"
                else -> "Ubuntu ${suite.replaceFirstChar { it.titlecase() }}"
            }

    val experienceName: String
        get() =
            when {
                variant.equals("raw", ignoreCase = true) -> "Terminal-first"
                variant.contains("xfce", ignoreCase = true) -> "Xfce desktop"
                variant.contains("gnome", ignoreCase = true) -> "GNOME desktop"
                variant.contains("kde", ignoreCase = true) -> "KDE desktop"
                variant.contains("lxqt", ignoreCase = true) -> "LXQt desktop"
                variant.contains("mate", ignoreCase = true) -> "MATE desktop"
                else -> variant
            }
}

data class DistroCatalog(
    val variants: List<DistroVariant>,
    val architecture: String,
    val source: CatalogSource,
)

enum class CatalogSource {
    NETWORK,
    CACHE,
    BUILT_IN,
}

sealed interface DistroCatalogState {
    data object Loading : DistroCatalogState

    data class Ready(val catalog: DistroCatalog) : DistroCatalogState

    data class Failed(val message: String) : DistroCatalogState
}

object DistroCatalogParser {
    fun parse(
        json: String,
        architecture: String,
        source: CatalogSource = CatalogSource.NETWORK,
    ): DistroCatalog {
        val root = Json.parseToJsonElement(json).jsonObject
        val suites = root.getValue("suites").jsonArray
        val variants = mutableListOf<DistroVariant>()
        val urlKey = "${architecture}url"
        val shaKey = "${architecture}sha"

        for (suiteElement in suites) {
            val suiteName = suiteElement.jsonPrimitive.content
            val suite = root[suiteName]?.jsonObject ?: continue
            val variantNames = suite["varients"]?.jsonArray ?: continue

            for (variantElement in variantNames) {
                val variantName = variantElement.jsonPrimitive.content
                val entry = suite[variantName]?.jsonObject ?: continue
                val downloadUrl = entry[urlKey]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (downloadUrl.isBlank()) continue

                variants +=
                    DistroVariant(
                        suite = suiteName,
                        variant = variantName,
                        internalName =
                            entry["Name"]?.jsonPrimitive?.contentOrNull
                                ?: "$suiteName-$variantName",
                        friendlyName =
                            entry["FirendlyName"]?.jsonPrimitive?.contentOrNull
                                ?: "$suiteName $variantName",
                        architecture = architecture,
                        downloadUrl = downloadUrl,
                        sha256 = entry[shaKey]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
            }
        }

        check(variants.isNotEmpty()) {
            "The uDroid catalogue has no images for $architecture"
        }
        return DistroCatalog(
            variants =
                variants.sortedWith(
                    compareByDescending<DistroVariant> { it.recommended }
                        .thenBy { it.suite }
                        .thenBy { it.variant },
                ),
            architecture = architecture,
            source = source,
        )
    }
}

class DistroCatalogRepository(private val context: Context) {
    private val cacheDirectory = File(context.filesDir, "catalog").apply { mkdirs() }
    private val cacheFile = File(cacheDirectory, "distro-data.json")

    fun load(): DistroCatalog {
        val architecture = manifestArchitecture()
        val networkResult =
            runCatching {
                val json = downloadCatalogue()
                persistCache(json)
                DistroCatalogParser.parse(json, architecture, CatalogSource.NETWORK)
            }
        if (networkResult.isSuccess) return networkResult.getOrThrow()

        val cachedResult =
            runCatching {
                DistroCatalogParser.parse(
                    cacheFile.readText(),
                    architecture,
                    CatalogSource.CACHE,
                )
            }
        if (cachedResult.isSuccess) return cachedResult.getOrThrow()

        return builtInFallback(architecture)
    }

    private fun downloadCatalogue(): String {
        val connection =
            (URL(CATALOGUE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "uDroid-Android/0.1")
            }
        return try {
            check(connection.responseCode in 200..299) {
                "Catalogue server returned HTTP ${connection.responseCode}"
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun persistCache(json: String) {
        val staging = File(cacheDirectory, "distro-data.json.staging")
        staging.writeText(json)
        if (cacheFile.exists()) check(cacheFile.delete()) {
            "Could not replace cached distro catalogue"
        }
        check(staging.renameTo(cacheFile)) {
            "Could not activate cached distro catalogue"
        }
    }

    private fun manifestArchitecture(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }
        return when (abi) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "armhf"
            "x86_64" -> "amd64"
            else -> error("uDroid does not yet package a runtime for ${Build.SUPPORTED_ABIS.joinToString()}")
        }
    }

    private fun builtInFallback(architecture: String): DistroCatalog {
        val variant =
            when (architecture) {
                "aarch64" ->
                    DistroVariant(
                        suite = "jammy",
                        variant = "raw",
                        internalName = "udroid-jammy-raw",
                        friendlyName = "Ubuntu 22.04 LTS - Jammy (raw)",
                        architecture = architecture,
                        downloadUrl =
                            "https://github.com/RandomCoderOrg/udroid-download/releases/download/V3R113/jammy-raw-arm64.tar.gz",
                        sha256 = "63f8dbb323570f1bd4c149c774dd05717f611111aa5da3105a32255139f69d26",
                    )
                "armhf" ->
                    DistroVariant(
                        suite = "jammy",
                        variant = "raw",
                        internalName = "udroid-jammy-raw",
                        friendlyName = "Ubuntu 22.04 LTS - Jammy (raw)",
                        architecture = architecture,
                        downloadUrl =
                            "https://github.com/RandomCoderOrg/udroid-download/releases/download/V3R113/jammy-raw-armhf.tar.gz",
                        sha256 = "7ef2a462747a06522db8d99e7a8c73cff4afd90275dad35b9971fea3b5ff783f",
                    )
                "amd64" ->
                    DistroVariant(
                        suite = "jammy",
                        variant = "raw",
                        internalName = "udroid-jammy-raw",
                        friendlyName = "Ubuntu 22.04 LTS - Jammy (raw)",
                        architecture = architecture,
                        downloadUrl =
                            "https://github.com/RandomCoderOrg/udroid-download/releases/download/V3R100/jammy-raw-amd64.tar.gz",
                        sha256 = "eadbdf2bd7d4e9caecb6e57df303d32807fdc2f21fd58565682e8399ab27942c",
                    )
                else -> error("No built-in fallback for $architecture")
            }
        return DistroCatalog(
            variants = listOf(variant),
            architecture = architecture,
            source = CatalogSource.BUILT_IN,
        )
    }

    private companion object {
        const val CATALOGUE_URL =
            "https://raw.githubusercontent.com/RandomCoderOrg/udroid-download/main/distro-data.json"
        val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
    }
}
