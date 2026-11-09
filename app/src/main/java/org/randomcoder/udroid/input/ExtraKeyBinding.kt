package org.randomcoder.udroid.input

data class ExtraKeyBinding(
    val action: String,
    val macro: Boolean = false,
    val display: String? = null,
) {
    fun normalized(): ExtraKeyBinding =
        copy(
            action = action.trim(),
            display = display?.trim()?.takeIf(String::isNotEmpty),
        )

    fun tokens(): List<String> =
        if (macro) action.trim().split(Regex("\\s+")).filter(String::isNotEmpty) else listOf(action.trim())
}
