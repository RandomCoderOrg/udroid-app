package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProotEnvironmentProfilesTest {
    @Test
    fun `default profile resolves the managed guest defaults in order`() {
        assertEquals(
            listOf(
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "COLORTERM=truecolor",
            ),
            ProotEnvironmentResolver.resolve(),
        )
    }

    @Test
    fun `codec preserves overrides ids and argv-safe values verbatim`() {
        val profile =
            ProotEnvironmentProfile(
                defaultOverrides = mapOf("LANG" to "en_IN.UTF-8"),
                managedOverrides = mapOf("DISPLAY" to ":7", "GALLIUM_DRIVER" to "custom"),
                customVariables =
                    listOf(
                        ProotCustomEnvironmentVariable("empty-id", "EMPTY", ""),
                        ProotCustomEnvironmentVariable("spaces-id", "GREETING", "hello world"),
                        ProotCustomEnvironmentVariable("equals-id", "TOKEN", "a=b=c"),
                        ProotCustomEnvironmentVariable("unicode-id", "CITY", "Hyderabad 🌆"),
                    ),
            )

        val decoded = ProotEnvironmentProfileCodec.decode(ProotEnvironmentProfileCodec.encode(profile))

        assertEquals(profile, decoded)
        assertEquals(
            listOf("DISPLAY=:7", "GALLIUM_DRIVER=custom"),
            ProotEnvironmentResolver.resolveManagedOverrides(decoded),
        )
        assertEquals("EMPTY=", ProotEnvironmentResolver.resolve(decoded).first { it.startsWith("EMPTY=") })
        assertEquals(
            "GREETING=hello world",
            ProotEnvironmentResolver.resolve(decoded).first { it.startsWith("GREETING=") },
        )
        assertEquals(
            "TOKEN=a=b=c",
            ProotEnvironmentResolver.resolve(decoded).first { it.startsWith("TOKEN=") },
        )
    }

    @Test
    fun `validator rejects invalid duplicate default and locked names`() {
        val profiles =
            listOf(
                ProotEnvironmentProfile(
                    customVariables = listOf(ProotCustomEnvironmentVariable("one", "9INVALID", "x")),
                ),
                ProotEnvironmentProfile(
                    customVariables =
                        listOf(
                            ProotCustomEnvironmentVariable("one", "CUSTOM", "x"),
                            ProotCustomEnvironmentVariable("two", "CUSTOM", "y"),
                        ),
                ),
                ProotEnvironmentProfile(
                    customVariables = listOf(ProotCustomEnvironmentVariable("one", "LANG", "x")),
                ),
                ProotEnvironmentProfile(
                    customVariables = listOf(ProotCustomEnvironmentVariable("one", "DISPLAY", ":9")),
                ),
                ProotEnvironmentProfile(managedOverrides = mapOf("NOT_MANAGED" to "x")),
                ProotEnvironmentProfile(
                    customVariables = listOf(ProotCustomEnvironmentVariable("one", "CUSTOM", "bad\u0000value")),
                ),
            )

        profiles.forEach { profile ->
            assertThrows(IllegalArgumentException::class.java) {
                ProotEnvironmentProfileValidator.requireValid(profile)
            }
        }
    }

    @Test
    fun `managed overrides wrap commands without shell parsing`() {
        assertEquals(
            listOf("/usr/bin/env", "DISPLAY=:7", "GALLIUM_DRIVER=", "/bin/bash", "--login"),
            ProotEnvironmentResolver.applyManagedOverrides(
                command = listOf("/bin/bash", "--login"),
                overrides = listOf("DISPLAY=:7", "GALLIUM_DRIVER="),
            ),
        )
    }

    @Test
    fun `profiles saved before managed overrides remain readable`() {
        val profile =
            ProotEnvironmentProfileCodec.decode(
                """{"format":"1","defaults_revision":1,"default_overrides":{},"custom_variables":[]}""",
            )

        assertEquals(emptyMap<String, String>(), profile.managedOverrides)
    }

    @Test
    fun `codec rejects profiles larger than 128 KiB`() {
        val profile =
            ProotEnvironmentProfile(
                customVariables =
                    List(17) { index ->
                        ProotCustomEnvironmentVariable(
                            id = "id-$index",
                            name = "VALUE_$index",
                            value = "x".repeat(8192),
                        )
                    },
            )

        assertThrows(IllegalArgumentException::class.java) {
            ProotEnvironmentProfileCodec.encode(profile)
        }
    }
}
