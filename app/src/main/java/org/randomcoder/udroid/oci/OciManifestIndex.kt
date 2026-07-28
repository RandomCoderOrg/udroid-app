package org.randomcoder.udroid.oci

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class OciPlatform(
    val os: String,
    val architecture: String,
    val variant: String? = null,
) {
    companion object {
        fun fromAndroidAbis(abis: List<String>): OciPlatform {
            for (abi in abis) {
                when (abi) {
                    "arm64-v8a" -> return OciPlatform("linux", "arm64", "v8")
                    "armeabi-v7a" -> return OciPlatform("linux", "arm", "v7")
                    "x86_64" -> return OciPlatform("linux", "amd64")
                }
            }
            error("No packaged uDroid runtime supports ${abis.joinToString()}")
        }
    }
}

data class OciManifestDescriptor(
    val digest: String,
    val mediaType: String,
    val size: Long,
    val platform: OciPlatform,
)

object OciManifestIndex {
    fun select(
        json: String,
        target: OciPlatform,
    ): OciManifestDescriptor {
        val root = Json.parseToJsonElement(json).jsonObject
        val manifests =
            root["manifests"]?.jsonArray
                ?: error("Registry response is not an OCI image index")

        val candidates =
            manifests.mapNotNull { element ->
                val value = element.jsonObject
                val platform = value["platform"]?.jsonObject ?: return@mapNotNull null
                val os = platform["os"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val architecture =
                    platform["architecture"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                if (os != target.os || architecture != target.architecture) {
                    return@mapNotNull null
                }
                val variant = platform["variant"]?.jsonPrimitive?.contentOrNull
                OciManifestDescriptor(
                    digest =
                        value["digest"]?.jsonPrimitive?.contentOrNull
                            ?: error("OCI manifest descriptor omitted its digest"),
                    mediaType =
                        value["mediaType"]?.jsonPrimitive?.contentOrNull
                            ?: error("OCI manifest descriptor omitted its media type"),
                    size =
                        value["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                            ?: error("OCI manifest descriptor omitted its size"),
                    platform = OciPlatform(os, architecture, variant),
                )
            }

        val selected =
            candidates.firstOrNull { it.platform.variant == target.variant }
                ?: candidates.firstOrNull { it.platform.variant == null }
                ?: error(
                    "Image has no ${target.os}/${target.architecture}" +
                        target.variant?.let { "/$it" }.orEmpty() +
                        " manifest",
                )
        require(SHA256_DIGEST.matches(selected.digest)) {
            "Registry returned an unsupported manifest digest"
        }
        require(selected.size >= 0L) {
            "Registry returned a negative manifest size"
        }
        return selected
    }

    private val SHA256_DIGEST = Regex("sha256:[a-f0-9]{64}")
}
