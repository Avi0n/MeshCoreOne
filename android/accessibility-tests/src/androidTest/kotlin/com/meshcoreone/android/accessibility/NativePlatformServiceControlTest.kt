// AndroidOnly: WP-503 Isolate native accessibility timing without an Activity, Compose rule, or test dispatcher; never substitute for feature acceptance.
package com.meshcoreone.android.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePlatformServiceControlTest {
    @Test fun actualServiceStateActivatesAndRestoresWithin1000msWithoutActivityOrComposeRule() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val expectedApi = requireNotNull(InstrumentationRegistry.getArguments().getString("expectedApi")).toInt()
        assertTrue(expectedApi == 31 || expectedApi == 37)
        assertEquals(expectedApi, Build.VERSION.SDK_INT)
        assertEquals(2f, Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE), 0f)
        assertEquals(2f, context.resources.configuration.fontScale, 0f)
        assertEquals(1f, context.resources.displayMetrics.density, 0f)
        assertEquals(0f, Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE), 0f)
        println("WP503_DEVICE_PROFILE|sdk=${Build.VERSION.SDK_INT}|fontScale=2.0|density=1.0")

        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val activityEvents = ConcurrentLinkedQueue<String>()
        val callback = ActivityLifecycleCallback { activity, stage ->
            activityEvents.add("${activity.javaClass.name}:$stage")
        }
        instrumentation.runOnMainSync {
            val count = Stage.values().sumOf { monitor.getActivitiesInStage(it).size }
            assertEquals("A platform-only control must start without an Activity", 0, count)
            monitor.addLifecycleCallback(callback)
            println("WP503_NATIVE_PLATFORM_ACTIVITY|phase=start|actualCount=$count|composeRule=false|testDispatcher=false")
        }
        val observer = Closeable {
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
            assertTrue("No Activity may be launched during the platform-only control: $activityEvents",
                activityEvents.isEmpty())
            println("WP503_NATIVE_PLATFORM_ACTIVITY|phase=end|actualEvents=${activityEvents.size}" +
                "|composeRule=false|testDispatcher=false")
        }
        observer.use {
            val automation = instrumentation.uiAutomation
            val manager = requireNotNull(context.getSystemService(AccessibilityManager::class.java))
            val info = requireNotNull(automation.serviceInfo)
            val originalFlags = info.flags
            val originalEnabled = manager.isTouchExplorationEnabled
            val expectedRestored = originalFlags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
            assertTrue(info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION != 0)
            assertEquals("Observe the actual fresh service state before requesting a change", expectedRestored, originalEnabled)
            println("WP503_NATIVE_PLATFORM_SERVICE_CONTROL|sdk=${Build.VERSION.SDK_INT}" +
                "|originalFlags=$originalFlags|originalEnabled=$originalEnabled" +
                "|expectedRestored=$expectedRestored|composeRule=false|testDispatcher=false|physicalTalkBack=false")
            val restoration = Closeable {
                info.flags = originalFlags
                automation.serviceInfo = info
                waitForState(manager, "restoration", expectedRestored)
                assertEquals(originalFlags, requireNotNull(automation.serviceInfo).flags)
                snapshotNativeServiceState(manager, "platform-restored", afterOriginalDeadline = false)
            }
            restoration.use {
                info.flags = originalFlags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
                automation.serviceInfo = info
                assertEquals(info.flags, requireNotNull(automation.serviceInfo).flags)
                waitForState(manager, "activation", true)
                snapshotNativeServiceState(manager, "platform-activated", afterOriginalDeadline = false)
            }
        }
    }

    private fun waitForState(manager: AccessibilityManager, phase: String, expected: Boolean) {
        val start = SystemClock.elapsedRealtime()
        var checks = 0
        var lastEnabled: Boolean? = null
        while (true) {
            val enabled = manager.isTouchExplorationEnabled
            val elapsed = SystemClock.elapsedRealtime() - start
            checks++
            if (lastEnabled != enabled) {
                println("WP503_NATIVE_PLATFORM_SERVICE_OBSERVATION|phase=$phase|enabled=$enabled" +
                    "|expected=$expected|checks=$checks|elapsedMs=$elapsed|composePolling=false")
                lastEnabled = enabled
            }
            if (enabled == expected && elapsed <= 1000L) {
                println("WP503_NATIVE_PLATFORM_SERVICE_READY|phase=$phase|enabled=$enabled" +
                    "|expected=$expected|checks=$checks|elapsedMs=$elapsed|originalDeadlineMs=1000")
                return
            }
            if (elapsed >= 1000L) {
                println("WP503_NATIVE_PLATFORM_SERVICE_TIMEOUT|phase=$phase|enabled=$enabled" +
                    "|expected=$expected|checks=$checks|elapsedMs=$elapsed|originalDeadlineMs=1000")
                Closeable {
                    snapshotNativeServiceState(manager, "platform-$phase-timeout", afterOriginalDeadline = true)
                }.use {
                    throw AssertionError("Native platform $phase state $enabled did not reach $expected within1000ms")
                }
            }
            Thread.sleep(minOf(10L, 1000L - elapsed))
        }
    }
}
