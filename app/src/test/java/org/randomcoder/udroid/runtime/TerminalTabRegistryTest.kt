package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalTabRegistryTest {
    @Test
    fun `new tab becomes active and preserves insertion order`() {
        val registry = TerminalTabRegistry<Any>()
        registry.add(tab("one"))
        registry.add(tab("two"))

        assertEquals(listOf("one", "two"), registry.all().map { it.id })
        assertEquals("two", registry.activeId)
    }

    @Test
    fun `removing active tab selects its next neighbour`() {
        val registry = TerminalTabRegistry<Any>()
        registry.add(tab("one"))
        registry.add(tab("two"))
        registry.add(tab("three"))
        registry.select("two")

        registry.remove("two")

        assertEquals("three", registry.activeId)
    }

    @Test
    fun `removing final active tab selects previous neighbour`() {
        val registry = TerminalTabRegistry<Any>()
        registry.add(tab("one"))
        registry.add(tab("two"))

        registry.remove("two")

        assertEquals("one", registry.activeId)
    }

    @Test
    fun `rename affects only requested tab`() {
        val registry = TerminalTabRegistry<Any>()
        registry.add(tab("one"))
        registry.add(tab("two"))

        registry.rename("one", "Logs")

        assertEquals("Logs", registry.get("one")?.title)
        assertEquals("Terminal two", registry.get("two")?.title)
    }

    @Test
    fun `removing last tab clears active id`() {
        val registry = TerminalTabRegistry<Any>()
        registry.add(tab("one"))

        registry.remove("one")

        assertNull(registry.activeId)
    }

    private fun tab(id: String) =
        TerminalTab(
            id = id,
            title = "Terminal $id",
            rootfsName = "ubuntu",
            value = Any(),
        )
}
