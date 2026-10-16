package one.meshcore.android

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.MapFocusRequest
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.selectTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start through [MainActivity] into the process [AppContainer]. Each method is one process:
 * the host passes [PHASE_ARGUMENT], force-stops between phases, and clears data only before [PHASE_FIRST].
 * [ConnectionService.startAfterBoot] is not called. A force-stop is process death, not a low-memory kill.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartRestartInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val container: AppContainer
        get() = (context.applicationContext as MeshCoreApplication).container

    private var windowCapture = 0

    @Test
    fun firstLaunchShowsOnboardingAndKeepsTheProcessContainer() = runBlocking {
        assumePhase(PHASE_FIRST)
        assertTrue("a cleared install has not finished onboarding", !container.hasCompletedOnboarding())
        assertNull("a cleared install has no radio id", container.lastConnectionStore.radioId())
        val processContainer = container
        launchForegroundActivity(Intent(context, MainActivity::class.java)).use { scenario ->
            try {
                awaitWindow(PHASE_FIRST) { it.contains(ONBOARDING_LABEL) }
                assertTrue("onboarding stays incomplete while Welcome is up", !container.hasCompletedOnboarding())
                assertTrue("offline-only launch has no connection service", awaitNoConnectionService())
                assertLinkStayedDown()
                var firstActivity = 0
                scenario.onActivity { firstActivity = System.identityHashCode(it) }
                container.onboarding.complete()
                container.coordinator.selectTab(AppTab.TOOLS)
                container.lastConnectionStore.persistOfflineScopeIfAbsent(OFFLINE_RADIO, OFFLINE_RADIO_NAME)
                awaitWindow(PHASE_FIRST) { it.contains(SHELL_LABEL) && !it.contains(ONBOARDING_LABEL) }
                awaitTab(AppTab.TOOLS)
                awaitPersistedTab(AppTab.TOOLS)
                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertTrue(
                        "recreation builds a new activity",
                        System.identityHashCode(recreated) != firstActivity,
                    )
                }
                assertSame(processContainer, container)
                awaitWindow(PHASE_FIRST) { it.contains(SHELL_LABEL) && !it.contains(ONBOARDING_LABEL) }
                assertTrue(container.hasCompletedOnboarding())
                assertEquals(AppTab.TOOLS.index, container.coordinator.selectedTab.value)
                assertEquals(OFFLINE_RADIO, container.lastConnectionStore.radioId())
                assertLinkStayedDown()
                evidence(PHASE_FIRST)
            } finally {
                stopConnectionService()
            }
        }
    }

    @Test
    fun processDeathRestoresTheShellTabAndRadio() = runBlocking {
        assumePhase(PHASE_RESTART)
        assertEquals(OFFLINE_RADIO, container.lastConnectionStore.radioId())
        assertNull(container.lastConnectionStore.deviceId())
        launchForegroundActivity(Intent(context, MainActivity::class.java)).use {
            try {
                awaitWindow(PHASE_RESTART) { it.contains(SHELL_LABEL) && !it.contains(ONBOARDING_LABEL) }
                awaitTab(AppTab.TOOLS)
                assertTrue(container.hasCompletedOnboarding())
                assertTrue("offline-only launch has no connection service", awaitNoConnectionService())
                assertEquals(OFFLINE_RADIO, container.lastConnectionStore.radioId())
                assertNothingStaged()
                assertLinkStayedDown()
                evidence(PHASE_RESTART)
            } finally {
                stopConnectionService()
            }
        }
    }

    @Test
    fun rejectedViewIntentKeepsTheRestoredTab() = runBlocking {
        assumePhase(PHASE_REJECTED)
        launchForegroundActivity(viewIntent(REJECTED_MAP_URL)).use {
            try {
                awaitWindow(PHASE_REJECTED) { it.contains(SHELL_LABEL) && !it.contains(ONBOARDING_LABEL) }
                awaitTab(AppTab.TOOLS)
                assertNull(container.coordinator.pendingMapFocus.value)
                assertNothingStaged()
                assertEquals(OFFLINE_RADIO, container.lastConnectionStore.radioId())
                assertTrue("offline-only launch has no connection service", awaitNoConnectionService())
                assertLinkStayedDown()
                evidence(PHASE_REJECTED)
            } finally {
                stopConnectionService()
            }
        }
    }

    @Test
    fun coldMapViewIntentFocusesTheMap() = runBlocking {
        assumePhase(PHASE_MAP)
        launchForegroundActivity(viewIntent(MAP_URL)).use {
            try {
                awaitWindow(PHASE_MAP) {
                    !it.contains(ONBOARDING_LABEL) &&
                        (it.contains(SHELL_LABEL) || it.contains(LOCATION_PROMPT))
                }
                assertEquals(
                    "the map prompt is not a location grant",
                    PackageManager.PERMISSION_DENIED,
                    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION),
                )
                awaitTab(AppTab.MAP)
                val pin = awaitDroppedPin()
                assertEquals(MAP_LATITUDE, pin.latitude, COORDINATE_TOLERANCE)
                assertEquals(MAP_LONGITUDE, pin.longitude, COORDINATE_TOLERANCE)
                assertNull(
                    "the map screen consumes the one-shot focus",
                    container.coordinator.pendingMapFocus.value,
                )
                assertNothingStaged()
                assertEquals(OFFLINE_RADIO, container.lastConnectionStore.radioId())
                assertTrue("offline-only launch has no connection service", awaitNoConnectionService())
                assertLinkStayedDown()
                evidence(PHASE_MAP)
            } finally {
                stopConnectionService()
            }
        }
    }

    private fun assumePhase(phase: String) {
        assumeTrue(
            "host selects one cold-start phase per process",
            InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT) == phase,
        )
    }

    private fun viewIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url), context, MainActivity::class.java)

    private suspend fun assertLinkStayedDown() {
        delay(SETTLE_MS)
        val state = container.connectionManager.state.value
        assertTrue("activate must not open a radio; state=$state", state is ConnectionState.Disconnected)
        assertNull(container.lastConnectionStore.deviceId())
        assertNull(container.lastConnectionStore.handle())
    }

    private fun assertNothingStaged() {
        assertNull(container.coordinator.pendingSendMessage.value)
        assertNull(container.coordinator.pendingSendAdvert.value)
        assertNull(container.coordinator.pendingHashtagJoin.value)
    }

    private suspend fun awaitDroppedPin(): MapFocusRequest {
        var pin = container.coordinator.droppedMapPin.value
        val ok = withTimeoutOrNull(TIMEOUT_MS) {
            while (pin == null) {
                delay(POLL_MS)
                pin = container.coordinator.droppedMapPin.value
            }
            true
        }
        assertTrue("map view should drop a pin", ok == true)
        return checkNotNull(pin)
    }

    private suspend fun awaitTab(tab: AppTab) {
        val ok = withTimeoutOrNull(TIMEOUT_MS) {
            while (container.coordinator.selectedTab.value != tab.index) delay(POLL_MS)
            true
        }
        assertTrue("tab should be ${tab.name} but was ${container.coordinator.selectedTab.value}", ok == true)
    }

    private suspend fun awaitPersistedTab(tab: AppTab) {
        val ok = withTimeoutOrNull(TIMEOUT_MS) {
            while (container.prefs.getInt(NavigationCoordinator.SELECTED_TAB_KEY) != tab.index) delay(POLL_MS)
            true
        }
        assertTrue("persisted tab should be ${tab.name}", ok == true)
    }

    private suspend fun awaitWindow(phase: String, accept: (String) -> Boolean) {
        var last = ""
        val ok = withTimeoutOrNull(TIMEOUT_MS) {
            while (true) {
                last = dumpWindow()
                if (accept(last)) return@withTimeoutOrNull true
                delay(POLL_MS)
            }
        }
        assertTrue("window for $phase was ${last.take(DUMP_SNIPPET)}", ok == true)
        windowCapture += 1
        context.cacheDir.resolve("cold-start-$phase-$windowCapture.txt").writeText(last)
        Log.i(
            LOG_TAG,
            "window phase=$phase capture=$windowCapture " +
                "onboarding=${last.contains(ONBOARDING_LABEL)} shell=${last.contains(SHELL_LABEL)}",
        )
    }

    // A second uiautomator process cannot register while this instrumentation already owns UiAutomation.
    // Every window is included so a system permission dialog does not hide the activity behind it.
    @Suppress("DEPRECATION") // AccessibilityNodeInfo.recycle still releases the node before API 34.
    private fun dumpWindow(): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val windows = automation.windows
        val texts = ArrayList<String>()
        if (windows.isNullOrEmpty()) {
            val root = automation.rootInActiveWindow ?: return ""
            try {
                collectText(root, texts)
            } finally {
                root.recycle()
            }
            return texts.joinToString("\n")
        }
        for (window in windows) {
            val root = window.root
            if (root != null) {
                try {
                    collectText(root, texts)
                } finally {
                    root.recycle()
                }
            }
            window.recycle()
        }
        return texts.joinToString("\n")
    }

    @Suppress("DEPRECATION") // AccessibilityNodeInfo.recycle still releases the node before API 34.
    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectText(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private suspend fun launchForegroundActivity(intent: Intent): ActivityScenario<MainActivity> = coroutineScope {
        val finished = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT_MS) { container.appForeground.first() }
        }
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            finished.await()
            scenario
        } catch (error: Exception) {
            scenario.close()
            throw error
        }
    }

    private suspend fun awaitNoConnectionService(): Boolean {
        val settled = withTimeoutOrNull(TIMEOUT_MS) {
            while (isConnectionServiceRunning()) delay(POLL_MS)
            true
        }
        return settled == true
    }

    @Suppress("DEPRECATION")
    private fun isConnectionServiceRunning(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val target = ConnectionService::class.java.name
        return manager.getRunningServices(Int.MAX_VALUE).any { info ->
            info.service.className == target
        }
    }

    private suspend fun evidence(phase: String) {
        val focus = container.coordinator.pendingMapFocus.value
        val line = "phase=$phase onboarding=${container.hasCompletedOnboarding()} " +
            "tab=${container.coordinator.selectedTab.value} " +
            "radio=${container.lastConnectionStore.radioId()?.value} " +
            "device=${container.lastConnectionStore.deviceId()?.value} " +
            "handle=${container.lastConnectionStore.handle()} " +
            "state=${container.connectionManager.state.value} " +
            "focus=${focus?.latitude},${focus?.longitude} " +
            "container=${System.identityHashCode(container)}"
        Log.i(LOG_TAG, line)
        println(line)
    }

    private fun stopConnectionService() {
        runCatching { ConnectionService.stop(context) }
            .onFailure { Log.i(LOG_TAG, "stop failed: ${it::class.simpleName}") }
    }

    private companion object {
        const val PHASE_ARGUMENT = "coldPhase"
        const val PHASE_FIRST = "first"
        const val PHASE_RESTART = "restart"
        const val PHASE_REJECTED = "rejected"
        const val PHASE_MAP = "map"
        const val ONBOARDING_LABEL = "Get Started"
        const val SHELL_LABEL = "Chats"
        const val LOCATION_PROMPT = "access this device"
        const val MAP_URL = "meshcore://map?lat=1.5&lon=-2.25"
        const val REJECTED_MAP_URL = "meshcore://map?lat=999&lon=0"
        const val MAP_LATITUDE = 1.5
        const val MAP_LONGITUDE = -2.25
        const val COORDINATE_TOLERANCE = 0.000001
        const val OFFLINE_RADIO_NAME = "Offline scope"
        const val LOG_TAG = "ColdStartTest"
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 250L
        const val SETTLE_MS = 1_000L
        const val DUMP_SNIPPET = 400
        val OFFLINE_RADIO: Uuid = Uuid.normalized("C01D57A0-0000-4000-8000-0000000000C5")
    }
}
