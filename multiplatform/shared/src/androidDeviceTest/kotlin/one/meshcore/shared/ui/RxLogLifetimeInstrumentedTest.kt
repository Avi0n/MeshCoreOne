package one.meshcore.shared.ui

import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.navigation.selectTab
import one.meshcore.shared.navigation.selectTool
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_rx_log_direct
import one.meshcore.shared.resources.tools_rx_log_packets_count
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RxLogLifetimeInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = NavigationCoordinator(scope)
    private val rooms = mutableListOf<NodeRemovalFixture>()
    private lateinit var shell: AdaptiveShellFixture
    private lateinit var directLabel: String
    private lateinit var packetsTemplate: String

    @After
    fun tearDown() {
        runBlocking { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
        rooms.forEach { it.close() }
    }

    @Test
    fun hidingDuringRoomHydrationDoesNotInstallASubscriptionAfterLoading() {
        val log = showPaused()
        tapTab(AppTab.NODES)
        assertObservers(log, 0)
        releaseHidden(log)
        tapTab(AppTab.TOOLS)
        assertObservers(log, 1)
        assertRow(OLD_HASH)
    }

    @Test
    fun nativeBackDuringRoomHydrationKeepsLoadingButEndsTheUiSubscription() {
        val log = showPaused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertObservers(log, 0)
        releaseHidden(log)
        compose.onNode(hasText(shell.rxLogLabel) and hasClickAction()).performClick()
        assertObservers(log, 1)
        assertRow(OLD_HASH)
    }

    @Test
    fun replacingTheMountedStoreRejectsCompletionFromThePreviousStore() {
        val old = showPaused()
        val room = newRoom()
        runBlocking { RxLogLifetimeFixture.seed(room, old.radioID, NEW_HASH) }
        val replacement = RxLogLifetimeFixture(room, scope, old.radioID)
        compose.runOnIdle {
            replacement.release.complete(Unit)
            shell.dependencies = replacement.dependencies(room.dependencies)
        }
        compose.waitUntil(TIMEOUT_MS) { replacement.durable.snapshot.value.size == 1 }
        assertObservers(old, 0)
        assertObservers(replacement, 1)
        assertRow(NEW_HASH)
        compose.runOnIdle { old.release.complete(Unit) }
        compose.waitUntil(TIMEOUT_MS) { old.durable.snapshot.value.size == 1 }
        assertObservers(old, 0)
        compose.onNodeWithText(OLD_HASH).assertDoesNotExist()
        compose.onNodeWithText(NEW_HASH).assertIsDisplayed()
    }

    @Test
    fun resizingAndAppendingWhileHiddenReturnsEachRowOnce() {
        val log = showPaused()
        compose.runOnIdle { log.release.complete(Unit) }
        compose.waitUntil(TIMEOUT_MS) { log.durable.snapshot.value.size == 1 }
        compose.runOnIdle { shell.widthDp = AdaptiveShellFixture.WIDE_WIDTH }
        assertObservers(log, 1)
        assertRow(OLD_HASH)
        compose.runOnIdle { shell.widthDp = AdaptiveShellFixture.COMPACT_WIDTH }
        assertObservers(log, 1)
        tapTab(AppTab.NODES)
        assertObservers(log, 0)
        compose.runOnIdle { scope.launch { log.durable.append(RxLogLifetimeFixture.entry(log.radioID, NEW_HASH)) } }
        compose.waitUntil(TIMEOUT_MS) { log.durable.snapshot.value.size == 2 }
        assertObservers(log, 0)
        tapTab(AppTab.TOOLS)
        assertObservers(log, 1)
        compose.onNodeWithText(formatTemplate(packetsTemplate, listOf(2))).assertIsDisplayed()
        compose.onAllNodesWithText(directLabel).assertCountEquals(2)
        compose.onAllNodesWithText(directLabel)[0].performClick()
        compose.onNodeWithText(NEW_HASH).performScrollTo().assertIsDisplayed()
        capturePayloadFrame("rx-log-hidden-append.png")
        compose.runOnIdle { assertEquals(2, log.durable.snapshot.value.map { it.id }.distinct().size) }
    }

    private fun showPaused(): RxLogLifetimeFixture {
        val room = newRoom()
        runBlocking { RxLogLifetimeFixture.seed(room, room.contact.radioID, OLD_HASH) }
        val log = RxLogLifetimeFixture(room, scope, room.contact.radioID)
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            navigation.selectTab(AppTab.TOOLS)
            navigation.selectTool(ToolSelection.RX_LOG)
        }
        shell = AdaptiveShellFixture(
            navigation,
            compose.activity.resources.displayMetrics.widthPixels,
            log.dependencies(room.dependencies),
        )
        compose.setContent {
            directLabel = stringResource(Res.string.tools_rx_log_direct)
            packetsTemplate = stringResource(Res.string.tools_rx_log_packets_count)
            shell.Content()
        }
        compose.waitUntil(TIMEOUT_MS) { log.readStarted }
        assertObservers(log, 1)
        return log
    }

    private fun newRoom() = NodeRemovalFixture(
        InstrumentationRegistry.getInstrumentation().targetContext,
        navigation,
    ).also { rooms += it }

    private fun releaseHidden(log: RxLogLifetimeFixture) {
        compose.runOnIdle { log.release.complete(Unit) }
        compose.waitUntil(TIMEOUT_MS) { log.durable.snapshot.value.size == 1 }
        assertObservers(log, 0)
        compose.runOnIdle { assertFalse(log.readCancelled) }
    }

    private fun assertObservers(log: RxLogLifetimeFixture, expected: Int) {
        compose.waitUntil(TIMEOUT_MS) { log.observerCount == expected }
        compose.runOnIdle { assertEquals(expected, log.observerCount) }
    }

    private fun assertRow(hash: String) {
        compose.onNodeWithText(formatTemplate(packetsTemplate, listOf(1))).assertIsDisplayed()
        compose.onNodeWithText(directLabel).performClick()
        compose.onNodeWithText(hash).performScrollTo().assertIsDisplayed()
    }

    private fun tapTab(tab: AppTab) {
        compose.onNode(hasText(requireNotNull(shell.tabLabels[tab])) and hasClickAction()).performClick()
    }

    companion object {
        const val TIMEOUT_MS = 5_000L
        const val OLD_HASH = "old-room-row"
        const val NEW_HASH = "replacement-row"
    }
}
