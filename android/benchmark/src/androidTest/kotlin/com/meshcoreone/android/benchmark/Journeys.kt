// AndroidOnly: WP-504 Asserted external-package journeys shared by profiles and macrobenchmarks.
package com.meshcoreone.android.benchmark

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals

internal const val TARGET = "com.meshcoreone.android.benchmark"
internal const val FIXTURE_ACTIVITY = "com.meshcoreone.android.benchmarktarget.WorkloadActivity"
private const val TIMEOUT_MS = 30_000L

internal fun prepareWorkload(profileGeneration: Boolean = false, requireApplicationProfile: Boolean = false) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val metadata = requireNotNull(instrumentation.context.packageManager
        .getApplicationInfo(TARGET, PackageManager.GET_META_DATA).metaData)
    assertTrue("Missing target variant identity", metadata.containsKey("wp504.profileSource"))
    assertEquals("Install the profile APK for collection, benchmark APK for measurements",
        profileGeneration, metadata.getBoolean("wp504.profileSource"))
    if (requireApplicationProfile) {
        assertTrue("Partial(Require) needs an admitted generated application profile input, not dependency-only profiles",
            metadata.containsKey("wp504.applicationProfileInput") && metadata.getBoolean("wp504.applicationProfileInput"))
    }
    val device = UiDevice.getInstance(instrumentation)
    device.executeShellCommand("am start -W --activity-clear-task --activity-new-task -n $TARGET/$FIXTURE_ACTIVITY --es screen prepare")
    assertTrue("Room dataset preparation failed", device.wait(
        Until.hasObject(By.desc("WP504_READY:prepare:${Workload.VERSION}:${Workload.digest()}")), TIMEOUT_MS,
    ))
}

internal fun MacrobenchmarkScope.openWorkload(screen: String) {
    startActivityAndWait(Intent().apply {
        component = ComponentName(TARGET, FIXTURE_ACTIVITY)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        putExtra("screen", screen)
    })
    assertTrue("Workload did not become ready: $screen", device.wait(
        Until.hasObject(By.desc("WP504_READY:$screen:${Workload.VERSION}:${Workload.digest()}")), TIMEOUT_MS,
    ))
}

internal fun MacrobenchmarkScope.scrollTimeline() {
    repeat(12) {
        val timeline = checkNotNull(device.wait(Until.findObject(By.desc("WP504_TIMELINE")), TIMEOUT_MS)) {
            "Production timeline was not mounted"
        }
        timeline.setGestureMargin(device.displayWidth / 5)
        timeline.swipe(Direction.DOWN, 0.8f)
    }
    assertTrue("Timeline did not load older database pages", device.wait(
        Until.hasObject(By.descContains("WP504_PAGED:")), TIMEOUT_MS,
    ))
}

internal fun MacrobenchmarkScope.panMap() {
    val map = checkNotNull(device.wait(Until.findObject(By.desc("WP504_MAP")), TIMEOUT_MS)) {
        "Native MapLibre surface was not mounted"
    }
    map.setGestureMargin(device.displayWidth / 5)
    repeat(8) { index -> map.swipe(if (index % 2 == 0) Direction.LEFT else Direction.RIGHT, 0.6f) }
    assertTrue("Native map never reported a camera change", device.wait(
        Until.hasObject(By.desc("WP504_MAP_MOVED")), TIMEOUT_MS,
    ))
}
