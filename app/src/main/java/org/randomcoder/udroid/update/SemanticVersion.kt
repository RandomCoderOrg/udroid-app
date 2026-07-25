package org.randomcoder.udroid.update

object SemanticVersion {
    fun compare(
        left: String,
        right: String,
    ): Int {
        val leftVersion = parse(left) ?: return 0
        val rightVersion = parse(right) ?: return 0
        for (index in 0..2) {
            val comparison = leftVersion.numbers[index].compareTo(rightVersion.numbers[index])
            if (comparison != 0) return comparison
        }
        return comparePrerelease(leftVersion.prerelease, rightVersion.prerelease)
    }

    fun normalize(value: String): String? =
        parse(value)?.let { parsed ->
            buildString {
                append(parsed.numbers.joinToString("."))
                parsed.prerelease?.let { append("-$it") }
            }
        }

    private fun comparePrerelease(
        left: String?,
        right: String?,
    ): Int {
        if (left == null && right == null) return 0
        if (left == null) return 1
        if (right == null) return -1
        val leftParts = left.split('.')
        val rightParts = right.split('.')
        repeat(maxOf(leftParts.size, rightParts.size)) { index ->
            val leftPart = leftParts.getOrNull(index) ?: return -1
            val rightPart = rightParts.getOrNull(index) ?: return 1
            val leftNumber = leftPart.toLongOrNull()
            val rightNumber = rightPart.toLongOrNull()
            val comparison =
                when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> leftPart.compareTo(rightPart)
                }
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun parse(value: String): ParsedVersion? {
        val match = VERSION.matchEntire(value.trim()) ?: return null
        return ParsedVersion(
            numbers =
                listOf(
                    match.groupValues[1].toLongOrNull() ?: return null,
                    match.groupValues[2].toLongOrNull() ?: return null,
                    match.groupValues[3].toLongOrNull() ?: return null,
                ),
            prerelease = match.groupValues[4].takeIf(String::isNotBlank),
        )
    }

    private data class ParsedVersion(
        val numbers: List<Long>,
        val prerelease: String?,
    )

    private val VERSION =
        Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")
}
