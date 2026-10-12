// AndroidOnly: WP-504 Runtime-only 16 KiB assertion over a fully rendered native MapLibre workload.
package com.meshcoreone.android.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePageRuntimeTest {
    @Test fun actual16KiBMapRenderer() {
        prepareWorkload()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertEquals("This test requires an actual 16 KiB-page runtime; do not substitute static ELF checks",
            "16384", device.executeShellCommand("getconf PAGE_SIZE").trim())
        device.executeShellCommand("am start -W -n $TARGET/$FIXTURE_ACTIVITY --es screen map")
        assertTrue("Native MapLibre did not fully render the 1000-marker workload", device.wait(
            Until.hasObject(By.desc("WP504_READY:map:${Workload.VERSION}:${Workload.digest()}")), 30_000,
        ))
    }
}
