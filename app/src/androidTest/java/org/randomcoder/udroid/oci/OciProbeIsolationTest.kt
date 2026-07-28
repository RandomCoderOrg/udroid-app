package org.randomcoder.udroid.oci

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OciProbeIsolationTest {
    @Test
    fun probeUsesAnIsolatedApplicationId() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertEquals("org.randomcoder.udroid.ociprobe", context.packageName)
    }
}
