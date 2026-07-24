package org.randomcoder.udroid.linuxapps

object DesktopExecParser {
    fun parse(
        commandLine: String,
        applicationName: String,
        iconName: String?,
        desktopFileGuestPath: String,
    ): Result<List<String>> =
        runCatching {
            val tokens = tokenize(commandLine)
            require(tokens.isNotEmpty()) { "Exec is empty" }
            buildList {
                tokens.forEach { token ->
                    when (token) {
                        "%f", "%F", "%u", "%U",
                        "%d", "%D", "%n", "%N", "%v", "%m",
                        -> Unit
                        "%i" -> {
                            if (!iconName.isNullOrBlank()) {
                                add("--icon")
                                add(iconName)
                            }
                        }
                        else -> add(expandToken(token, applicationName, desktopFileGuestPath))
                    }
                }
            }.also {
                require(it.isNotEmpty() && it.first().isNotBlank()) {
                    "Exec did not contain an executable"
                }
            }
        }

    private fun tokenize(value: String): List<String> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var tokenStarted = false

        value.forEach { character ->
            when {
                escaped -> {
                    token.append(character)
                    escaped = false
                    tokenStarted = true
                }
                character == '\\' -> {
                    escaped = true
                    tokenStarted = true
                }
                quote != null && character == quote -> {
                    quote = null
                    tokenStarted = true
                }
                quote == null && (character == '"' || character == '\'') -> {
                    quote = character
                    tokenStarted = true
                }
                quote == null && character.isWhitespace() -> {
                    if (tokenStarted) {
                        tokens += token.toString()
                        token.clear()
                        tokenStarted = false
                    }
                }
                else -> {
                    token.append(character)
                    tokenStarted = true
                }
            }
        }
        require(!escaped) { "Exec ends with an incomplete escape" }
        require(quote == null) { "Exec contains an unterminated quote" }
        if (tokenStarted) tokens += token.toString()
        return tokens
    }

    private fun expandToken(
        token: String,
        applicationName: String,
        desktopFileGuestPath: String,
    ): String {
        val expanded = StringBuilder()
        var index = 0
        while (index < token.length) {
            if (token[index] != '%') {
                expanded.append(token[index++])
                continue
            }
            require(index + 1 < token.length) { "Exec ends with an incomplete field code" }
            when (val code = token[index + 1]) {
                '%' -> expanded.append('%')
                'c' -> expanded.append(applicationName)
                'k' -> expanded.append(desktopFileGuestPath)
                'f', 'F', 'u', 'U', 'i',
                'd', 'D', 'n', 'N', 'v', 'm',
                -> error("Field code %$code must be a complete argument")
                else -> error("Unsupported Exec field code %$code")
            }
            index += 2
        }
        return expanded.toString()
    }
}
