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
    fun `custom title overrides shell updates until automatic titles are restored`() {
        val registry = TerminalTabRegistry<Any>()
        registry.add(tab("one"))

        registry.updateShellTitle("one", "root@localhost: ~")
        assertEquals("root@localhost: ~", registry.get("one")?.title)

        registry.rename("one", "Server")
        registry.updateShellTitle("one", "htop")
        assertEquals("Server", registry.get("one")?.title)

        registry.followShellTitle("one")
        assertEquals("htop", registry.get("one")?.title)
    }

    @Test
    fun `empty shell title falls back to default title`() {
        val registry = TerminalTabRegistry<Any>()
        registry.add(tab("one"))
        registry.updateShellTitle("one", "htop")

        registry.updateShellTitle("one", null)

        assertEquals("Terminal one", registry.get("one")?.title)
    }

    @Test
    fun `terminal titles drop controls and remain bounded`() {
        val title = "\u001B]0;\u202Eroot@localhost\n" + "x".repeat(50)

        assertEquals(
            " ]0;root@localhost".trim() + "x".repeat(23),
            normalizeTerminalTitle(title),
        )
    }

    @Test
    fun `removing last tab clears active id`() {
        val registry = TerminalTabRegistry<Any>()
        registry.add(tab("one"))

        registry.remove("one")

        assertNull(registry.activeId)
    }

    @Test
    fun `tabs preserve independent rootfs assignments`() {
        val registry = TerminalTabRegistry<Any>()
        registry.add(tab("ubuntu", rootfsName = "udroid-jammy-raw"))
        registry.add(tab("debian", rootfsName = "debian-bookworm"))

        registry.select("ubuntu")

        assertEquals("udroid-jammy-raw", registry.active()?.rootfsName)
        assertEquals("debian-bookworm", registry.get("debian")?.rootfsName)
    }

    @Test
    fun `closing state prevents duplicate close and supports retry after failure`() {
        val registry = TerminalTabRegistry<Any>()
        registry.add(tab("one"))

        assertEquals(TerminalTabCloseState.CLOSING, registry.beginClose("one")?.closeState)
        assertNull(registry.beginClose("one"))
        assertEquals(
            TerminalTabCloseState.FORCE_CLOSING,
            registry.markForceClosing("one")?.closeState,
        )
        assertEquals(
            TerminalTabCloseState.FAILED,
            registry.markCloseFailed("one", "Could not close")?.closeState,
        )
        assertEquals(TerminalTabCloseState.CLOSING, registry.beginClose("one")?.closeState)
        assertNull(registry.get("one")?.closeError)
    }

    @Test
    fun `rootfs names become readable distro titles`() {
        assertEquals("Ubuntu Jammy", terminalDistroTitle("udroid-jammy-raw"))
        assertEquals("Debian Bookworm", terminalDistroTitle("debian-bookworm"))
    }

    private fun tab(
        id: String,
        rootfsName: String = "ubuntu",
    ) =
        TerminalTab(
            id = id,
            defaultTitle = "Terminal $id",
            rootfsName = rootfsName,
            value = Any(),
        )
}
