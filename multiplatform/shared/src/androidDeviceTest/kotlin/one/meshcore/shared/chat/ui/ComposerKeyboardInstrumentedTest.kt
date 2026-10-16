package one.meshcore.shared.chat.ui

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.chat.ChatRenderState
import one.meshcore.shared.chat.ChatThreadRenderState
import one.meshcore.shared.chat.ComposerState
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.navigateToChat
import one.meshcore.shared.navigation.navigateToRoom
import one.meshcore.shared.navigation.pop
import one.meshcore.shared.room.ui.RoomDetailInputs
import one.meshcore.shared.room.ui.RoomDetailPane
import one.meshcore.shared.ui.CompactShell
import one.meshcore.shared.ui.tabLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ComposerKeyboardInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = NavigationCoordinator(scope)
    private val draft = mutableStateOf(DRAFT)
    private val interactivePop = mutableStateOf(false)
    private lateinit var composeView: android.view.View
    private lateinit var chatsLabel: String

    @After
    fun tearDown() {
        compose.runOnUiThread { keyboard().hide(WindowInsetsCompat.Type.ime()) }
        scope.cancel()
    }

    @Test
    fun pushedChatStaysOnTheKeyboardAfterHideReopenAndReturn() {
        navigation.navigateToChat(CHAT_ID)
        showShell(detail = { chat() })
        assertClosedComposer()
        openKeyboard()
        assertKeyboardAdjacency()
        capture("composer-chat-keyboard.png")
        hideKeyboard()
        assertClosedComposer()
        openKeyboard()
        assertKeyboardAdjacency()

        compose.runOnIdle { assertTrue(navigation.pop(AppTab.CHATS)) }
        compose.onNodeWithText(ROOT).assertIsDisplayed()
        hideKeyboard()
        compose.onNodeWithText(chatsLabel).assertIsDisplayed()
        compose.runOnIdle { navigation.navigateToChat(CHAT_ID) }
        assertClosedComposer()
        openKeyboard()
        assertKeyboardAdjacency()
        assertEquals(DRAFT, draft.value)
    }

    @Test
    fun realRoomDetailUsesOneKeyboardAndNavigationInset() {
        val store = KeyboardRoomStore()
        navigation.navigateToRoom(store.session.id.value)
        showShell(detail = {
            RoomDetailPane(
                RoomDetailInputs(
                    sessionId = store.session.id.value,
                    roomServerService = { null },
                    contactService = { null },
                    radioId = { store.session.radioID },
                    conversationListStore = { store },
                    roomAdminService = { null },
                    nodePasswordStore = { null },
                    servicesChanged = emptyFlow(),
                    appForeground = emptyFlow(),
                    onBack = { navigation.pop(AppTab.CHATS) },
                ),
            )
        })
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
        assertClosedComposer()
        openKeyboard()
        assertKeyboardAdjacency()
        capture("composer-room-keyboard.png")
        hideKeyboard()
        assertClosedComposer()
        openKeyboard()
        assertKeyboardAdjacency()
    }

    @Test
    fun visibleRootTabsReleaseTheirHeightWhenTheKeyboardOpens() {
        showShell(detail = { chat() }, root = { chat() })
        compose.onNodeWithText(chatsLabel).assertIsDisplayed()
        openKeyboard()
        assertKeyboardAdjacency()
        hideKeyboard()
        compose.onNodeWithText(chatsLabel).assertIsDisplayed()
        openKeyboard()
        assertKeyboardAdjacency()
    }

    @Test
    fun interactiveBackHoldKeepsPlacementUntilTheHoldReleases() {
        navigation.navigateToChat(CHAT_ID)
        showShell(detail = { chat() })
        openKeyboard()
        assertKeyboardAdjacency()
        val before = geometry(WindowInsetsCompat.Type.ime())
        compose.runOnIdle { interactivePop.value = true }
        hideKeyboard()
        val held = geometry(WindowInsetsCompat.Type.ime())
        assertEquals("held field position", before.fieldBottom, held.fieldBottom, ROUNDING_DP * density())
        assertEquals("held native origin", before.viewOrigin, held.viewOrigin)
        compose.runOnIdle { interactivePop.value = false }
        assertClosedComposer()
    }

    private fun showShell(detail: @Composable () -> Unit, root: @Composable () -> Unit = { Text(ROOT) }) {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            TimestampFormatting {
                composeView = LocalView.current
                chatsLabel = tabLabel(AppTab.CHATS)
                MaterialTheme {
                    CompositionLocalProvider(LocalInteractivePopActive provides interactivePop.value) {
                        CompactShell(
                            coordinator = navigation,
                            unreadCount = 0,
                            useNavigationRail = false,
                            chatsContent = { Box(Modifier.fillMaxSize()) { root() } },
                            chatDetailContent = { detail() },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun chat() {
        ChatThreadScreen(
            screenState = ChatThreadScreenState(
                thread = ChatThreadRenderState.EMPTY.copy(phase = ChatRenderState.LoadPhase.LOADED),
                composer = ComposerState(text = draft.value, maxBytes = BYTE_BUDGET),
                title = TITLE,
                isReady = false,
                isEncrypted = true,
            ),
            actions = idleChatThreadActions { navigation.pop(AppTab.CHATS) }.copy(onTextChange = { draft.value = it }),
        )
    }

    private fun openKeyboard() {
        compose.onNode(hasSetTextAction()).assertIsDisplayed().performClick()
        compose.runOnUiThread { keyboard().show(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(TIMEOUT_MS) { nativeInset(WindowInsetsCompat.Type.ime()) > 0 }
        compose.waitForIdle()
    }

    private fun hideKeyboard() {
        compose.runOnUiThread { keyboard().hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(TIMEOUT_MS) { nativeInset(WindowInsetsCompat.Type.ime()) == 0 }
        compose.waitForIdle()
    }

    private fun assertKeyboardAdjacency() {
        compose.onNodeWithText(chatsLabel).assertDoesNotExist()
        assertComposerGap(WindowInsetsCompat.Type.ime())
    }

    private fun assertClosedComposer() {
        assertEquals(0, nativeInset(WindowInsetsCompat.Type.ime()))
        assertComposerGap(WindowInsetsCompat.Type.navigationBars())
    }

    private fun assertComposerGap(insetType: Int) {
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        var last: KeyboardGeometry? = null
        var stableSamples = 0
        try {
            compose.waitUntil(TIMEOUT_MS) {
                val sample = geometry(insetType)
                val adjacent = kotlin.math.abs(sample.gap - ROW_PADDING_DP * density()) <= ROUNDING_DP * density()
                stableSamples = if (adjacent && sample == last) stableSamples + 1 else 0
                last = sample
                stableSamples >= STABLE_SAMPLES
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            capture("composer-keyboard-failure.png")
            throw AssertionError(
                "Keyboard geometry did not settle: $last",
                failure,
            )
        }
        assertEquals(
            "editable field gap above native inset",
            ROW_PADDING_DP * density(),
            geometry(insetType).gap,
            ROUNDING_DP * density(),
        )
    }

    private fun geometry(insetType: Int): KeyboardGeometry {
        val node = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
        var sample: KeyboardGeometry? = null
        compose.runOnUiThread {
            val origin = IntArray(2)
            composeView.getLocationOnScreen(origin)
            val focusRect = android.graphics.Rect()
            composeView.getFocusedRect(focusRect)
            sample = KeyboardGeometry(
                fieldBottom = origin[1] + node.boundsInRoot.bottom,
                windowBottom = compose.activity.windowManager.currentWindowMetrics.bounds.bottom,
                inset = nativeInset(insetType),
                viewOrigin = origin[1],
                viewHeight = composeView.height,
                rootHeight = composeView.rootView.height,
                rootScroll = composeView.rootView.scrollY,
                viewScroll = composeView.scrollY,
                softInputMode = compose.activity.window.attributes.softInputMode,
                focusBottom = focusRect.bottom,
            )
        }
        return requireNotNull(sample)
    }

    private fun keyboard() = WindowInsetsControllerCompat(compose.activity.window, composeView)

    private fun nativeInset(type: Int): Int =
        requireNotNull(ViewCompat.getRootWindowInsets(composeView)).getInsets(type).bottom

    private fun density(): Float = compose.activity.resources.displayMetrics.density

    private fun capture(name: String) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        FileOutputStream(file).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val STABLE_SAMPLES = 3
        const val ROW_PADDING_DP = 8f
        const val ROUNDING_DP = 2f
        const val BYTE_BUDGET = 150
        const val CHAT_ID = "C0000000-0000-0000-0000-000000000001"
        const val TITLE = "Keyboard fixture"
        const val ROOT = "Conversation list fixture"
        const val DRAFT = "Draft remains here"
    }

    private data class KeyboardGeometry(
        val fieldBottom: Float,
        val windowBottom: Int,
        val inset: Int,
        val viewOrigin: Int,
        val viewHeight: Int,
        val rootHeight: Int,
        val rootScroll: Int,
        val viewScroll: Int,
        val softInputMode: Int,
        val focusBottom: Int,
    ) {
        val gap: Float get() = windowBottom - inset - fieldBottom
    }
}
