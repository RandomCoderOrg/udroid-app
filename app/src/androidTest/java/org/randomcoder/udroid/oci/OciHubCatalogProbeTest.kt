package org.randomcoder.udroid.oci

import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OciHubCatalogProbeTest {
    @Test
    fun discoversOfficialOperatingSystemsAndValidatesSelectedTagForPhone() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = OciHubCatalogClient()
        val repository = OciHubCatalogRepository(context)
        val tagRepository = OciHubTagRepository(context)
        repository.clearCache()
        val target = OciPlatform.fromAndroidAbis(Build.SUPPORTED_ABIS.toList())
        tagRepository.clearCache("ubuntu", target)
        val startedAt = SystemClock.elapsedRealtime()
        val cold = repository.load()
        val coldCatalogueMs = SystemClock.elapsedRealtime() - startedAt
        val warmStartedAt = SystemClock.elapsedRealtime()
        val warm = repository.load()
        val warmCatalogueMs = SystemClock.elapsedRealtime() - warmStartedAt
        val diskCatalogueStartedAt = SystemClock.elapsedRealtime()
        val diskCatalogue = OciHubCatalogRepository(context).load()
        val diskCatalogueMs = SystemClock.elapsedRealtime() - diskCatalogueStartedAt
        val coldTagsStartedAt = SystemClock.elapsedRealtime()
        val coldTags = tagRepository.load("ubuntu", target)
        val coldTagsMs = SystemClock.elapsedRealtime() - coldTagsStartedAt
        val warmTagsStartedAt = SystemClock.elapsedRealtime()
        val warmTags = tagRepository.load("ubuntu", target)
        val warmTagsMs = SystemClock.elapsedRealtime() - warmTagsStartedAt
        val diskTagsStartedAt = SystemClock.elapsedRealtime()
        val diskTags = OciHubTagRepository(context).load("ubuntu", target)
        val diskTagsMs = SystemClock.elapsedRealtime() - diskTagsStartedAt
        val tagStartedAt = SystemClock.elapsedRealtime()
        val ubuntu = client.tagPlatform("ubuntu", "24.04", target)
        val tagMs = SystemClock.elapsedRealtime() - tagStartedAt
        val systems = cold.repositories
        val names = systems.map(OciHubRepository::name)

        assertEquals(OciHubCatalogSource.NETWORK, cold.source)
        assertEquals(OciHubCatalogSource.CACHE, warm.source)
        assertEquals(OciHubCatalogSource.CACHE, diskCatalogue.source)
        assertEquals(cold.repositories, warm.repositories)
        assertEquals(cold.repositories, diskCatalogue.repositories)
        assertEquals(OciHubCatalogSource.NETWORK, coldTags.source)
        assertEquals(OciHubCatalogSource.CACHE, warmTags.source)
        assertEquals(OciHubCatalogSource.CACHE, diskTags.source)
        assertEquals(coldTags.tags, warmTags.tags)
        assertEquals(coldTags.tags, diskTags.tags)
        assertTrue(coldTags.tags.any { it.tag == "24.04" })
        assertTrue(systems.isNotEmpty())
        assertTrue("ubuntu" in names)
        assertTrue("debian" in names)
        assertTrue("alpine" in names)
        assertTrue("archlinux" in names)
        assertEquals(target, ubuntu.platform)
        assertTrue(ubuntu.digest.startsWith("sha256:"))
        assertTrue(ubuntu.compressedBytes > 10L * 1024L * 1024L)

        println(
            "UDROID_OCI_HUB_CATALOG_PROBE\n" +
                "coldCatalogueMs=$coldCatalogueMs\n" +
                "memoryCatalogueMs=$warmCatalogueMs\n" +
                "diskCatalogueMs=$diskCatalogueMs\n" +
                "coldTagsMs=$coldTagsMs\n" +
                "memoryTagsMs=$warmTagsMs\n" +
                "diskTagsMs=$diskTagsMs\n" +
                "tagMs=$tagMs\n" +
                "operatingSystems=${systems.size}\n" +
                "compatibleUbuntuTags=${coldTags.tags.size}\n" +
                "first=${names.take(12).joinToString()}\n" +
                "ubuntuDigest=${ubuntu.digest}\n" +
                "ubuntuBytes=${ubuntu.compressedBytes}\n",
        )
    }
}
