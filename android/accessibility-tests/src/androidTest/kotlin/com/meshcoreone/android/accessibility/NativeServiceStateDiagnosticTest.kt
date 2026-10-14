// AndroidOnly: WP-503 Independent native accessibility-state timing and public system snapshots, without Compose polling or feature-acceptance substitution.
package com.meshcoreone.android.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeServiceStateDiagnosticTest : DeviceAccessibilityHarness() {
    @Test fun actualServiceStateActivatesAndRestoresWithin1000msWithoutComposePolling() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val manager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val info = requireNotNull(automation.serviceInfo)
        val originalFlags = info.flags
        val originalEnabled = manager.isTouchExplorationEnabled
        val expectedRestored = originalFlags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
        assertTrue(info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION != 0)
        println("WP503_NATIVE_SERVICE_CONTROL|sdk=${android.os.Build.VERSION.SDK_INT}" +
            "|originalFlags=$originalFlags|originalEnabled=$originalEnabled" +
            "|expectedRestored=$expectedRestored|composePolling=false|physicalTalkBack=false")
        assertEquals("Start from the actual fresh service state, not an assumed enabled manager",
            expectedRestored, originalEnabled)
        val restoration = Closeable {
            info.flags = originalFlags
            automation.serviceInfo = info
            waitForNativeState(manager, "restoration", expectedRestored)
            assertEquals(originalFlags, requireNotNull(automation.serviceInfo).flags)
            snapshot(manager, "restored", afterOriginalDeadline = false)
        }
        restoration.use {
            info.flags = originalFlags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            automation.serviceInfo = info
            assertEquals(info.flags, requireNotNull(automation.serviceInfo).flags)
            waitForNativeState(manager, "activation", true)
            snapshot(manager, "activated", afterOriginalDeadline = false)
        }
    }

    private fun waitForNativeState(manager: AccessibilityManager, phase: String, expected: Boolean) {
        val start = SystemClock.elapsedRealtime()
        var checks = 0
        var lastEnabled: Boolean? = null
        while (true) {
            val enabled = manager.isTouchExplorationEnabled
            val elapsed = SystemClock.elapsedRealtime() - start
            checks++
            if (lastEnabled != enabled) {
                println("WP503_NATIVE_SERVICE_OBSERVATION|phase=$phase|enabled=$enabled" +
                    "|expected=$expected|checks=$checks|elapsedMs=$elapsed|composePolling=false")
                lastEnabled = enabled
            }
            if (enabled == expected && elapsed <= 1000L) {
                println("WP503_NATIVE_SERVICE_READY|phase=$phase|enabled=$enabled" +
                    "|expected=$expected|checks=$checks|elapsedMs=$elapsed|originalDeadlineMs=1000")
                return
            }
            if (elapsed >= 1000L) {
                println("WP503_NATIVE_SERVICE_TIMEOUT|phase=$phase|enabled=$enabled" +
                    "|expected=$expected|checks=$checks|elapsedMs=$elapsed|originalDeadlineMs=1000")
                val diagnostic = Closeable { snapshot(manager, "$phase-timeout", afterOriginalDeadline = true) }
                diagnostic.use {
                    throw AssertionError("Native $phase state $enabled did not reach $expected within1000ms")
                }
            }
            Thread.sleep(minOf(10L, 1000L - elapsed))
        }
    }

    private fun snapshot(manager: AccessibilityManager, phase: String, afterOriginalDeadline: Boolean) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val start = SystemClock.elapsedRealtime()
        println("WP503_NATIVE_SERVICE_SNAPSHOT_START|phase=$phase|afterOriginalDeadline=$afterOriginalDeadline" +
            "|managerEnabled=${manager.isTouchExplorationEnabled}|flags=${requireNotNull(automation.serviceInfo).flags}")
        val descriptors = automation.executeShellCommandRw("sh")
        check(descriptors.size == 2) { "Public shell stream API must return stdout and stdin" }
        val script = "dumpsys -t 5 accessibility 2>&1\n" +
            "result=${'$'}?\n" +
            "printf '\\nWP503_NATIVE_SERVICE_DUMP_EXIT=%s\\n' \"${'$'}result\"\n"
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { stdout ->
            ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { stdin ->
                stdin.write(script.toByteArray(Charsets.UTF_8))
            }
            stdout.readBytes().toString(Charsets.UTF_8)
        }
        val lines = output.lineSequence().toList()
        for (line in lines.filter { it.isNotBlank() && !it.startsWith("WP503_NATIVE_SERVICE_DUMP_EXIT=") }) {
            println("WP503_NATIVE_SERVICE_SYSTEM|phase=$phase|$line")
        }
        val exits = lines.filter { it.startsWith("WP503_NATIVE_SERVICE_DUMP_EXIT=") }
        println("WP503_NATIVE_SERVICE_DUMP_MARKERS|phase=$phase|actual=${exits.size}|expected=1")
        check(exits.size == 1) { "Public shell did not return exactly one actual dump exit marker" }
        val exit = exits.single()
        println("WP503_NATIVE_SERVICE_DUMP_RESULT|phase=$phase|$exit")
        check(exit == "WP503_NATIVE_SERVICE_DUMP_EXIT=0") { "Public accessibility system snapshot failed: $exit" }
        println("WP503_NATIVE_SERVICE_SNAPSHOT_END|phase=$phase" +
            "|managerEnabled=${manager.isTouchExplorationEnabled}|flags=${requireNotNull(automation.serviceInfo).flags}" +
            "|durationMs=${SystemClock.elapsedRealtime() - start}|physicalTalkBack=false")
    }
}
