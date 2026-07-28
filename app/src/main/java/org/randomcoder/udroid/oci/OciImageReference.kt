package org.randomcoder.udroid.oci

/**
 * A normalized reference to an image in an OCI-compatible registry.
 *
 * uDroid intentionally accepts registry references rather than Docker Hub web
 * page URLs. Registry traffic is resolved from this value by the pull client.
 */
data class OciImageReference(
    val registry: String,
    val repository: String,
    val tag: String?,
    val digest: String?,
) {
    init {
        require(tag != null || digest != null) {
            "An OCI image reference needs a tag or digest"
        }
    }

    val manifestReference: String
        get() = digest ?: checkNotNull(tag)

    val canonicalName: String
        get() = "$registry/$repository"

    override fun toString(): String =
        buildString {
            append(canonicalName)
            tag?.let {
                append(':')
                append(it)
            }
            digest?.let {
                append('@')
                append(it)
            }
        }

    companion object {
        fun parse(input: String): OciImageReference {
            val value = input.trim()
            require(value.isNotEmpty()) { "Image reference is empty" }
            require("://" !in value) {
                "Use an image reference such as ubuntu:24.04, not a web URL"
            }
            require(value.none(Char::isWhitespace)) {
                "Image references cannot contain spaces"
            }

            val digestParts = value.split('@')
            require(digestParts.size <= 2) { "Image reference contains more than one digest" }
            val nameAndTag = digestParts[0]
            val digest =
                digestParts.getOrNull(1)?.also {
                    require(SHA256_DIGEST.matches(it)) {
                        "Only sha256 OCI image digests are currently supported"
                    }
                }

            val lastSlash = nameAndTag.lastIndexOf('/')
            val lastColon = nameAndTag.lastIndexOf(':')
            val hasTag = lastColon > lastSlash
            val tag =
                if (hasTag) {
                    nameAndTag.substring(lastColon + 1).also {
                        require(TAG.matches(it)) { "Invalid OCI image tag" }
                    }
                } else if (digest == null) {
                    DEFAULT_TAG
                } else {
                    null
                }
            val imageName =
                if (hasTag) {
                    nameAndTag.substring(0, lastColon)
                } else {
                    nameAndTag
                }
            require(imageName.isNotEmpty()) { "Image repository is empty" }

            val segments = imageName.split('/')
            require(segments.none(String::isEmpty)) { "Image repository contains an empty path" }
            val explicitRegistry = segments.size > 1 && isRegistryHost(segments.first())
            val rawRegistry = if (explicitRegistry) segments.first() else DEFAULT_REGISTRY_ALIAS
            val registry = normalizeRegistry(rawRegistry)
            val repositorySegments =
                if (explicitRegistry) {
                    segments.drop(1)
                } else {
                    segments
                }.let {
                    if (!explicitRegistry && it.size == 1) {
                        listOf(DEFAULT_NAMESPACE) + it
                    } else {
                        it
                    }
                }
            val repository = repositorySegments.joinToString("/")
            require(REPOSITORY.matches(repository)) {
                "Invalid OCI image repository"
            }

            return OciImageReference(
                registry = registry,
                repository = repository,
                tag = tag,
                digest = digest,
            )
        }

        private fun isRegistryHost(value: String): Boolean =
            value == "localhost" || '.' in value || ':' in value

        private fun normalizeRegistry(value: String): String {
            val normalized =
                when (value) {
                    "docker.io", "index.docker.io" -> DEFAULT_REGISTRY
                    else -> value
                }
            require(REGISTRY.matches(normalized)) { "Invalid OCI registry host" }
            return normalized
        }

        private const val DEFAULT_REGISTRY_ALIAS = "docker.io"
        private const val DEFAULT_REGISTRY = "registry-1.docker.io"
        private const val DEFAULT_NAMESPACE = "library"
        private const val DEFAULT_TAG = "latest"

        private val REGISTRY =
            Regex(
                "(?:localhost|[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)(?::[0-9]{1,5})?",
            )
        private val REPOSITORY_COMPONENT =
            "[a-z0-9]+(?:[._-][a-z0-9]+)*"
        private val REPOSITORY =
            Regex("$REPOSITORY_COMPONENT(?:/$REPOSITORY_COMPONENT)*")
        private val TAG = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
        private val SHA256_DIGEST = Regex("sha256:[a-f0-9]{64}")
    }
}
