package one.meshcore.shared.ui

import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import one.meshcore.shared.layout.SidebarVisibility
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.navigation.clearPerRadioSelection
import one.meshcore.shared.navigation.navigateToChannel
import one.meshcore.shared.navigation.navigateToChat
import one.meshcore.shared.navigation.navigateToContactDetail
import one.meshcore.shared.navigation.navigateToDiscovery
import one.meshcore.shared.navigation.navigateToRoom
import one.meshcore.shared.navigation.navigateToSettings
import one.meshcore.shared.navigation.selectTab
import one.meshcore.shared.navigation.selectTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveShellNavigationInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = NavigationCoordinator(scope)
    private lateinit var fixture: AdaptiveShellFixture
    private var room: NodeRemovalFixture? = null

    @After
    fun tearDown() {
        scope.cancel()
        room?.close()
    }

    @Test
    fun directSelectionSurvivesMeasuredWidthChangesAndTabReturn() {
        chatCycle(ChatRoute.Direct(CONTACT)) { navigation.navigateToChat(CONTACT) }
    }

    @Test
    fun channelSelectionSurvivesMeasuredWidthChangesAndTabReturn() {
        chatCycle(ChatRoute.Channel(CHANNEL)) { navigation.navigateToChannel(CHANNEL) }
    }

    @Test
    fun roomSelectionSurvivesMeasuredWidthChangesAndTabReturn() {
        chatCycle(ChatRoute.Room(ROOM)) { navigation.navigateToRoom(ROOM) }
    }

    @Test
    fun nodeSelectionSurvivesWidthChangesTabReturnAndNativeBack() {
        show()
        compose.runOnIdle { navigation.navigateToContactDetail(CONTACT) }
        listOf(AdaptiveShellFixture.MEDIUM_WIDTH, AdaptiveShellFixture.WIDE_WIDTH, AdaptiveShellFixture.COMPACT_WIDTH)
            .forEach { width ->
                resize(width)
                compose.onNodeWithTag(CONTACT).assertIsDisplayed()
                val list = compose.onNodeWithTag(AdaptiveShellFixture.NODES)
                if (width == AdaptiveShellFixture.WIDE_WIDTH) list.assertIsDisplayed() else list.assertDoesNotExist()
            }
        compose.runOnIdle { navigation.selectTab(AppTab.MAP) }
        resize(AdaptiveShellFixture.WIDE_WIDTH)
        tapTab(AppTab.NODES)
        compose.runOnIdle { assertEquals(CONTACT, navigation.selectedContactId.value) }
        compose.onNodeWithTag(CONTACT).assertIsDisplayed()
        pressBack()
        compose.runOnIdle { assertNull(navigation.selectedContactId.value) }
        compose.onNodeWithTag(AdaptiveShellFixture.NODES).assertIsDisplayed()
        compose.onNodeWithTag(CONTACT).assertDoesNotExist()
    }

    @Test
    fun discoverySurvivesWidthsAndTabReturnThenBackRestoresNodes() {
        show()
        compose.runOnIdle { navigation.navigateToDiscovery() }
        listOf(AdaptiveShellFixture.MEDIUM_WIDTH, AdaptiveShellFixture.WIDE_WIDTH, AdaptiveShellFixture.COMPACT_WIDTH)
            .forEach {
                resize(it)
                compose.onNodeWithTag(AdaptiveShellFixture.DISCOVERY).assertIsDisplayed()
            }
        tapTab(AppTab.SETTINGS)
        tapTab(AppTab.NODES)
        compose.onNodeWithTag(AdaptiveShellFixture.DISCOVERY).assertIsDisplayed()
        pressBack()
        compose.runOnIdle { assertFalse(navigation.nodesShowingDiscovery.value) }
        compose.onNodeWithTag(AdaptiveShellFixture.NODES).assertIsDisplayed()
    }

    @Test
    fun collapsingToolOnlyHidesTheActiveToolsList() {
        show()
        resize(AdaptiveShellFixture.WIDE_WIDTH)
        compose.runOnIdle {
            navigation.selectTab(AppTab.TOOLS)
            navigation.selectTool(ToolSelection.TRACE_PATH)
        }
        compose.onNodeWithTag("tools-pane:WideDetail").assertIsDisplayed()
        compose.onNodeWithTag("tools-pane:WideList").assertDoesNotExist()
        tapTab(AppTab.CHATS)
        compose.onNodeWithTag(AdaptiveShellFixture.CHATS).assertIsDisplayed()
        compose.runOnIdle { assertEquals(SidebarVisibility.Tiled, navigation.sidebarVisibility.value) }
        tapTab(AppTab.NODES)
        compose.onNodeWithTag(AdaptiveShellFixture.NODES).assertIsDisplayed()
        tapTab(AppTab.TOOLS)
        compose.onNodeWithTag("tools-pane:WideList").assertDoesNotExist()
        compose.runOnIdle { assertEquals(ToolSelection.TRACE_PATH, navigation.selectedTool.value) }
        pressBack()
        compose.onNodeWithTag("tools-pane:WideList").assertIsDisplayed()
    }

    @Test
    fun actualRxLogHubRetainsSelectionAcrossWidthsAndTabs() {
        show(liveHubs = true)
        tapTab(AppTab.TOOLS)
        compose.onNodeWithText(fixture.rxLogLabel).performClick()
        compose.runOnIdle { assertEquals(ToolSelection.RX_LOG, navigation.selectedTool.value) }
        resize(AdaptiveShellFixture.MEDIUM_WIDTH)
        resize(AdaptiveShellFixture.WIDE_WIDTH)
        compose.onNodeWithTag("tools-pane:WideList").assertIsDisplayed()
        compose.onNodeWithTag("tools-pane:WideDetail").assertIsDisplayed()
        assertPage(fixture.rxLogLabel, "tools-pane:WideDetail")
        capturePayloadFrame("adaptive-rx-wide.png")
        tapTab(AppTab.NODES)
        tapTab(AppTab.TOOLS)
        compose.runOnIdle { assertEquals(ToolSelection.RX_LOG, navigation.selectedTool.value) }
        assertPage(fixture.rxLogLabel, "tools-pane:WideDetail")
        resize(AdaptiveShellFixture.COMPACT_WIDTH)
        pressBack()
        compose.runOnIdle { assertNull(navigation.selectedTool.value) }
        compose.onNodeWithTag("tools-pane:CompactStacked").assertIsDisplayed()
    }

    @Test
    fun actualSettingsHubRetainsNestedSelectionAndBackVisitsItsParent() {
        show(liveHubs = true)
        compose.runOnIdle { navigation.navigateToSettings(SettingsDestination.DIAGNOSTICS) }
        resize(AdaptiveShellFixture.MEDIUM_WIDTH)
        resize(AdaptiveShellFixture.WIDE_WIDTH)
        compose.onNodeWithTag("settings-pane:WideList").assertIsDisplayed()
        compose.onNodeWithTag("settings-pane:WideDetail").assertIsDisplayed()
        assertPage(fixture.diagnosticsLabel, "settings-pane:WideDetail")
        tapTab(AppTab.NODES)
        tapTab(AppTab.SETTINGS)
        compose.runOnIdle { assertEquals(SettingsDestination.DIAGNOSTICS, navigation.selectedSetting.value) }
        assertPage(fixture.diagnosticsLabel, "settings-pane:WideDetail")
        resize(AdaptiveShellFixture.COMPACT_WIDTH)
        pressBack()
        compose.runOnIdle { assertEquals(SettingsDestination.FEEDBACK, navigation.selectedSetting.value) }
        assertPage(fixture.feedbackLabel, "settings-pane:CompactStacked")
        pressBack()
        compose.runOnIdle { assertNull(navigation.selectedSetting.value) }
        compose.onNodeWithTag("settings-pane:CompactStacked").assertIsDisplayed()
    }

    @Test
    fun radioResetCannotRestoreInactiveSelectionsAfterResizing() {
        show()
        compose.runOnIdle {
            navigation.navigateToChat(CONTACT)
            navigation.navigateToContactDetail(CONTACT)
            navigation.selectTool(ToolSelection.RX_LOG)
            navigation.navigateToSettings(SettingsDestination.DEVICE_INFO)
            navigation.selectTab(AppTab.MAP)
            navigation.clearPerRadioSelection()
        }
        resize(AdaptiveShellFixture.WIDE_WIDTH)
        tapTab(AppTab.CHATS)
        compose.onNodeWithTag(AdaptiveShellFixture.CHATS).assertIsDisplayed()
        compose.onNodeWithTag("direct:$CONTACT").assertDoesNotExist()
        tapTab(AppTab.NODES)
        compose.onNodeWithTag(AdaptiveShellFixture.NODES).assertIsDisplayed()
        compose.onNodeWithTag(CONTACT).assertDoesNotExist()
        tapTab(AppTab.TOOLS)
        compose.runOnIdle { assertNull(navigation.selectedTool.value) }
        tapTab(AppTab.SETTINGS)
        compose.runOnIdle { assertNull(navigation.selectedSetting.value) }
        resize(AdaptiveShellFixture.COMPACT_WIDTH)
        compose.runOnIdle {
            assertNull(navigation.selectedContactId.value)
            assertNull(navigation.chatsSelectedRoute.value)
        }
    }

    private fun assertPage(label: String, parent: String) {
        val pages = compose.onAllNodes(hasText(label) and hasAnyAncestor(hasTestTag(parent)))
        val count = pages.fetchSemanticsNodes().size
        assertTrue("Missing rendered page $label in $parent", count > 0)
        assertTrue((0 until count).any { runCatching { pages[it].assertIsDisplayed() }.isSuccess })
    }

    private fun chatCycle(route: ChatRoute, open: () -> Unit) {
        show()
        compose.runOnIdle(open)
        listOf(AdaptiveShellFixture.COMPACT_WIDTH, AdaptiveShellFixture.MEDIUM_WIDTH, AdaptiveShellFixture.WIDE_WIDTH)
            .forEach { width ->
                resize(width)
                compose.onNodeWithTag(route.conversationId).assertIsDisplayed()
                val list = compose.onNodeWithTag(AdaptiveShellFixture.CHATS)
                if (width == AdaptiveShellFixture.WIDE_WIDTH) list.assertIsDisplayed() else list.assertIsNotDisplayed()
            }
        tapTab(AppTab.NODES)
        tapTab(AppTab.CHATS)
        compose.runOnIdle { assertEquals(route, navigation.chatsSelectedRoute.value) }
        compose.onNodeWithTag(route.conversationId).assertIsDisplayed()
        resize(AdaptiveShellFixture.COMPACT_WIDTH)
        pressBack()
        compose.runOnIdle { assertNull(navigation.chatsSelectedRoute.value) }
        compose.onNodeWithTag(AdaptiveShellFixture.CHATS).assertIsDisplayed()
    }

    private fun show(liveHubs: Boolean = false) {
        if (liveHubs) room = NodeRemovalFixture(InstrumentationRegistry.getInstrumentation().targetContext, navigation)
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            navigation.selectTab(AppTab.NODES)
        }
        fixture =
            AdaptiveShellFixture(navigation, compose.activity.resources.displayMetrics.widthPixels, room?.dependencies)
        compose.setContent { fixture.Content() }
        resize(AdaptiveShellFixture.COMPACT_WIDTH)
    }

    private fun resize(width: Float) {
        compose.runOnIdle { fixture.widthDp = width }
        compose.waitUntil(TIMEOUT_MS) { navigation.isSidebarWide.value == (width == AdaptiveShellFixture.WIDE_WIDTH) }
        compose.waitForIdle()
        val root = compose.onNodeWithTag(AdaptiveShellFixture.ROOT).fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.widthPixels / AdaptiveShellFixture.WIDE_WIDTH
        assertEquals(width, root.width / density, DP_TOLERANCE)
        if (navigation.tabBarVisible.value || width != AdaptiveShellFixture.COMPACT_WIDTH) {
            val setting = compose.onNode(
                hasText(fixture.tabLabels.getValue(AppTab.SETTINGS)) and hasClickAction(),
            ).fetchSemanticsNode().boundsInRoot
            if (width == AdaptiveShellFixture.COMPACT_WIDTH) {
                assertTrue(setting.center.y > root.height * BOTTOM_FRACTION)
            } else {
                assertTrue(setting.center.x < root.width * RAIL_FRACTION)
            }
        }
    }

    private fun tapTab(tab: AppTab) {
        compose.onNode(hasText(fixture.tabLabels.getValue(tab)) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private companion object {
        const val CONTACT = "10000000-0000-0000-0000-000000000001"
        const val CHANNEL = "20000000-0000-0000-0000-000000000001"
        const val ROOM = "30000000-0000-0000-0000-000000000001"
        const val TIMEOUT_MS = 5_000L
        const val DP_TOLERANCE = 1f
        const val BOTTOM_FRACTION = 0.8f
        const val RAIL_FRACTION = 0.2f
    }
}
