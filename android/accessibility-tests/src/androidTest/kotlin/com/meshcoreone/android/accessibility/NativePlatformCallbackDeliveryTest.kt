// AndroidOnly: WP-503 Measure public accessibility callbacks and main-looper progress without changing original readiness predicates or supplying feature acceptance.
package com.meshcoreone.android.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Printer
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePlatformCallbackDeliveryTest {
    @Test fun actualServiceStateRetains1000msWithPublicCallbackAndMainLooperObservations() {
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
        val activities = ConcurrentLinkedQueue<String>()
        val activityCallback = ActivityLifecycleCallback { activity, stage ->
            activities.add("${activity.javaClass.name}:$stage")
        }
        instrumentation.runOnMainSync {
            val count = Stage.values().sumOf { monitor.getActivitiesInStage(it).size }
            assertEquals("Start without an Activity", 0, count)
            monitor.addLifecycleCallback(activityCallback)
            println("WP503_NATIVE_CALLBACK_ACTIVITY|phase=start|actualCount=$count")
        }
        Closeable {
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(activityCallback) }
            assertTrue("No Activity may launch during callback observation: $activities", activities.isEmpty())
            println("WP503_NATIVE_CALLBACK_ACTIVITY|phase=end|actualEvents=${activities.size}")
        }.use {
            val automation = instrumentation.uiAutomation
            val manager = requireNotNull(context.getSystemService(AccessibilityManager::class.java))
            val main = Handler(Looper.getMainLooper())
            val phase = AtomicReference("setup")
            val callbacks = ConcurrentLinkedQueue<String>()
            val callback = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
                callbacks.add("WP503_NATIVE_CALLBACK_EVENT|phase=${phase.get()}|enabled=$enabled" +
                    "|actualManager=${manager.isTouchExplorationEnabled}" +
                    "|onMain=${Looper.myLooper() == Looper.getMainLooper()}" +
                    "|elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
            }
            manager.addTouchExplorationStateChangeListener(callback, main)
            println("WP503_NATIVE_CALLBACK_REGISTERED|registrationCallReturned=true|handler=main|physicalTalkBack=false")
            Closeable {
                instrumentation.runOnMainSync {
                    assertTrue("Remove the actual registered listener",
                        manager.removeTouchExplorationStateChangeListener(callback))
                }
                callbacks.forEach(::println)
                println("WP503_NATIVE_CALLBACK_COUNT|actual=${callbacks.size}")
            }.use {
                val info = requireNotNull(automation.serviceInfo)
                val originalFlags = info.flags
                val expectedRestored =
                    originalFlags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
                assertTrue(info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION != 0)
                assertEquals("Observe the fresh real state before changing flags",
                    expectedRestored, manager.isTouchExplorationEnabled)
                println("WP503_NATIVE_CALLBACK_CONTROL|originalFlags=$originalFlags" +
                    "|originalEnabled=${manager.isTouchExplorationEnabled}|expectedRestored=$expectedRestored" +
                    "|composeRule=false|testDispatcher=false")
                Closeable {
                    phase.set("restoration")
                    info.flags = originalFlags
                    automation.serviceInfo = info
                    waitForState(manager, main, "restoration", expectedRestored)
                    assertEquals(originalFlags, requireNotNull(automation.serviceInfo).flags)
                    snapshotNativeServiceState(manager, "callback-restored", afterOriginalDeadline = false)
                }.use {
                    phase.set("activation")
                    info.flags = originalFlags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
                    automation.serviceInfo = info
                    assertEquals(info.flags, requireNotNull(automation.serviceInfo).flags)
                    waitForState(manager, main, "activation", true)
                    snapshotNativeServiceState(manager, "callback-activated", afterOriginalDeadline = false)
                }
            }
        }
    }

    private data class Heartbeat(val postedMs: Long, val ranMs: Long)

    private fun waitForState(manager: AccessibilityManager, main: Handler, phase: String, expected: Boolean) {
        val start = SystemClock.elapsedRealtime()
        val token = Any()
        val pending = AtomicBoolean(false)
        val heartbeats = ConcurrentLinkedQueue<Heartbeat>()
        var nextHeartbeatMs = 0L
        var pendingPostedMs: Long? = null
        var sampledPendingMain = false
        var checks = 0
        println("WP503_NATIVE_CALLBACK_WAIT_START|phase=$phase|elapsedRealtimeMs=$start|originalDeadlineMs=1000")
        Closeable {
            main.removeCallbacksAndMessages(token)
            val observed = heartbeats.toList()
            observed.forEach {
                println("WP503_NATIVE_MAIN_HEARTBEAT|phase=$phase|postedMs=${it.postedMs}" +
                    "|ranMs=${it.ranMs}|latencyMs=${it.ranMs - it.postedMs}" +
                    "|withinOriginalDeadline=${it.ranMs <= 1000L}")
            }
            println("WP503_NATIVE_MAIN_HEARTBEAT_COUNT|phase=$phase|observed=${observed.size}" +
                "|withinOriginalDeadline=${observed.count { it.ranMs <= 1000L }}" +
                "|maxObservedLatencyMs=${observed.maxOfOrNull { it.ranMs - it.postedMs }}" +
                "|callbackObservationOnly=true")
        }.use {
            while (true) {
                val before = SystemClock.elapsedRealtime() - start
                if (before < 1000L && before >= nextHeartbeatMs && pending.compareAndSet(false, true)) {
                    val posted = SystemClock.elapsedRealtime() - start
                    pendingPostedMs = posted
                    assertTrue("Post an actual main-looper heartbeat",
                        main.postAtTime({
                            heartbeats.add(Heartbeat(posted, SystemClock.elapsedRealtime() - start))
                            pending.set(false)
                        }, token, SystemClock.uptimeMillis()))
                    nextHeartbeatMs = before + 100L
                }
                val posted = pendingPostedMs
                if (!sampledPendingMain && pending.get() && posted != null && before - posted >= 100L) {
                    sampledPendingMain = true
                    val sampleStart = SystemClock.elapsedRealtime() - start
                    val thread = main.looper.thread
                    val state = thread.state
                    val stack = thread.stackTrace.joinToString(" <- ")
                    val queue = mutableListOf<String>()
                    main.looper.dump(Printer { queue.add(it) }, "")
                    val sampleEnd = SystemClock.elapsedRealtime() - start
                    println("WP503_NATIVE_MAIN_PENDING_SAMPLE|phase=$phase|startedMs=$sampleStart" +
                        "|finishedMs=$sampleEnd|durationMs=${sampleEnd - sampleStart}" +
                        "|pendingAfter=${pending.get()}|afterOriginalDeadline=${sampleEnd > 1000L}" +
                        "|threadState=$state|stack=$stack")
                    queue.forEach { println("WP503_NATIVE_MAIN_PENDING_QUEUE|phase=$phase|$it") }
                }
                val enabled = manager.isTouchExplorationEnabled
                val elapsed = SystemClock.elapsedRealtime() - start
                checks++
                if (enabled == expected && elapsed <= 1000L) {
                    println("WP503_NATIVE_CALLBACK_READY|phase=$phase|enabled=$enabled|expected=$expected" +
                        "|checks=$checks|elapsedMs=$elapsed|originalDeadlineMs=1000")
                    return
                }
                if (elapsed >= 1000L) {
                    println("WP503_NATIVE_CALLBACK_TIMEOUT|phase=$phase|enabled=$enabled|expected=$expected" +
                        "|accessibilityEnabled=${manager.isEnabled}|checks=$checks|elapsedMs=$elapsed" +
                        "|originalDeadlineMs=1000")
                    Closeable {
                        val thread = Looper.getMainLooper().thread
                        println("WP503_NATIVE_CALLBACK_MAIN_STACK|afterOriginalDeadline=true|state=${thread.state}" +
                            "|stack=${thread.stackTrace.joinToString(" <- ")}")
                        snapshotNativeServiceState(manager, "callback-$phase-timeout", afterOriginalDeadline = true)
                    }.use {
                        throw AssertionError("Native callback control $phase state $enabled did not reach $expected within1000ms")
                    }
                }
                Thread.sleep(minOf(10L, 1000L - elapsed))
            }
        }
    }
}
