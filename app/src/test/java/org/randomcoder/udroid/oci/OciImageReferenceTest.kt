package org.randomcoder.udroid.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OciImageReferenceTest {
    @Test
    fun `docker hub shorthand receives the official namespace and default tag`() {
        val reference = OciImageReference.parse("ubuntu")

        assertEquals("registry-1.docker.io", reference.registry)
        assertEquals("library/ubuntu", reference.repository)
        assertEquals("latest", reference.tag)
        assertNull(reference.digest)
        assertEquals(
            "registry-1.docker.io/library/ubuntu:latest",
            reference.toString(),
        )
    }

    @Test
    fun `tagged docker hub reference is normalized`() {
        val reference = OciImageReference.parse("docker.io/library/ubuntu:24.04")

        assertEquals("registry-1.docker.io", reference.registry)
        assertEquals("library/ubuntu", reference.repository)
        assertEquals("24.04", reference.manifestReference)
    }

    @Test
    fun `custom registry digest remains pinned`() {
        val digest = "sha256:" + "a".repeat(64)
        val reference = OciImageReference.parse("ghcr.io/example/system@$digest")

        assertEquals("ghcr.io", reference.registry)
        assertEquals("example/system", reference.repository)
        assertNull(reference.tag)
        assertEquals(digest, reference.digest)
        assertEquals(digest, reference.manifestReference)
    }

    @Test
    fun `registry ports are distinguished from image tags`() {
        val reference = OciImageReference.parse("localhost:5000/example/system:test")

        assertEquals("localhost:5000", reference.registry)
        assertEquals("example/system", reference.repository)
        assertEquals("test", reference.tag)
    }

    @Test
    fun `web urls and unsafe repository paths are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OciImageReference.parse("https://hub.docker.com/_/ubuntu")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OciImageReference.parse("example/../ubuntu:latest")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OciImageReference.parse("Ubuntu:latest")
        }
    }
}
