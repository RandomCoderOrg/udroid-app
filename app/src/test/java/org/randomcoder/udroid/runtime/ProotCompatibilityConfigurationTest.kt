package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ProotCompatibilityConfigurationTest {
    @Test
    fun `disabled compatibility adds no PRoot options`() {
        val arguments = mutableListOf("proot")

        arguments.addProotCompatibilityOptions(ProotCompatibilityConfiguration())

        assertEquals(listOf("proot"), arguments)
    }

    @Test
    fun `enabled compatibility adds supported PRoot options`() {
        val arguments = mutableListOf("proot")

        arguments.addProotCompatibilityOptions(
            ProotCompatibilityConfiguration(
                sysvipcEnabled = true,
                memfdFallbackEnabled = true,
            ),
        )

        assertEquals(listOf("proot", "--sysvipc", "--ashmem-memfd"), arguments)
    }
}
