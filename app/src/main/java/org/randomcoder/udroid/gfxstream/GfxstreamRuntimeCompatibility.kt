package org.randomcoder.udroid.gfxstream

internal object GfxstreamRuntimeCompatibility {
    private val commitPattern = Regex("[0-9a-f]{40}")

    fun requireCompatible(
        host: GfxstreamHostRuntime,
        guest: GfxstreamGuestRuntime,
    ): String {
        val diagnostic =
            "host gfxstream=${shortHash(host.gfxstreamCommit)} · " +
                "guest protocol=${shortHash(guest.protocolHostCommit)} · " +
                "host Mesa protocol=${shortHash(host.mesaProtocolCommit)} · " +
                "guest Mesa=${shortHash(guest.mesaCommit)}"
        check(
            listOf(
                host.gfxstreamCommit,
                guest.protocolHostCommit,
                host.mesaProtocolCommit,
                guest.mesaCommit,
            ).all { it.matches(commitPattern) },
        ) {
            "Missing or malformed gfxstream runtime metadata · $diagnostic"
        }
        check(
            host.gfxstreamCommit == guest.protocolHostCommit &&
                host.mesaProtocolCommit == guest.mesaCommit,
        ) {
            "Incompatible gfxstream runtime pair · $diagnostic"
        }
        return diagnostic
    }

    private fun shortHash(commit: String): String =
        when {
            commit.isBlank() -> "missing"
            commit.matches(commitPattern) -> commit.take(SHORT_HASH_LENGTH)
            else -> "invalid-${commit.take(SHORT_HASH_LENGTH)}"
        }

    private const val SHORT_HASH_LENGTH = 10
}
