package one.meshcore.shared.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.chat.ui.ConversationListInputs
import one.meshcore.shared.chat.ui.ConversationListRoot
import one.meshcore.shared.chat.ui.KeyboardRoomStore
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.backStackOf
import one.meshcore.shared.navigation.navigateToChat
import one.meshcore.shared.navigation.navigateToRoom
import one.meshcore.shared.navigation.reconcileSelectedChatRoute
import one.meshcore.shared.navigation.setSidebarWide
import one.meshcore.shared.room.ui.RoomDetailInputs
import one.meshcore.shared.room.ui.RoomDetailPane
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Mounted real detail panes receive fresh payloads without navigation transitions or draft loss. */
@RunWith(AndroidJUnit4::class)
class ChatPayloadInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = NavigationCoordinator(scope)
    private val connection = MutableStateFlow(DeviceConnectionState.READY)
    private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var chatFixture: PayloadChatFixture? = null
    private var mounts = 0

    @After
    fun tearDown() {
        compose.runOnUiThread {
            WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        scope.cancel()
        chatFixture?.close()
    }

    @Test
    fun compactDirectRenameKeepsItsMountedHeaderAndDraft() = verifyDirect(wide = false)

    @Test
    fun wideDirectRenameKeepsItsMountedHeaderAndDraft() = verifyDirect(wide = true)

    @Test
    fun compactRoomRefreshKeepsDraftThenStagesAuthOnDisconnect() = verifyRoom(wide = false)

    @Test
    fun wideRoomRefreshKeepsDraftThenStagesAuthOnDisconnect() = verifyRoom(wide = true)

    @Test
    fun sameOwnerMissKeepsTheMountedDirectUntilAFreshPayloadResolves() =
        verifyDirect(wide = false, transientMiss = true)

    @Test
    fun replacementBinderDropsCachedDirectWhileItsLookupIsPending() = verifyDirect(wide = false, replaceOwner = true)

    @Test
    fun foreignRadioRoomLookupCannotMountTheRoom() {
        val base = KeyboardRoomStore()
        val expectedRadio = Uuid.random()
        var reads = 0
        val foreignStore = object : ConversationListStore by base {
            override suspend fun fetchRooms(radioID: Uuid): List<RemoteNodeSessionDto> {
                reads += 1
                return listOf(base.session)
            }
        }
        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    RoomDetailPane(
                        RoomDetailInputs(
                            sessionId = base.session.id.value, sessionSnapshot = base.session,
                            roomServerService = { null }, contactService = { null }, radioId = { expectedRadio },
                            conversationListStore = {
                                foreignStore
                            }, roomAdminService = { null }, nodePasswordStore = { null },
                            servicesChanged = emptyFlow(), appForeground = emptyFlow(), onBack = {},
                        ),
                    )
                }
            }
        }
        compose.waitUntil(LOAD_TIMEOUT_MS) { reads > 0 }
        compose.waitForIdle()
        compose.onAllNodesWithText(base.session.name).assertCountEquals(0)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    private fun verifyDirect(wide: Boolean, transientMiss: Boolean = false, replaceOwner: Boolean = false) {
        val fixture = PayloadChatFixture(InstrumentationRegistry.getInstrumentation().targetContext, scope)
        chatFixture = fixture
        compose.runOnUiThread {
            navigation.navigateToChat(fixture.contact.id.value, displayTitle = fixture.contact.name)
        }
        val pending = coordinatorChatScroll(navigation)
        val binderSource = mutableStateOf(fixture.binder)
        val inputs = ConversationListInputs(
            store = { fixture.listStore },
            contactService = { null },
            radioId = { fixture.radioID },
            connectionState = connection,
            onConversationTap = {},
            listInvalidations = invalidations,
            onSnapshotRefreshed = { rows, ids ->
                navigation.reconcileSelectedChatRoute(ids) { it.refreshedPayload(rows) }
            },
        )
        showShell(wide, list = { ConversationListRoot(inputs) }) { route ->
            ChatDetailPane(route, binderSource.value, connection, pending, onBack = {})
        }
        compose.waitUntil(LOAD_TIMEOUT_MS) {
            mounts == 1 &&
                fixture.successfulLookups > 0 &&
                invalidations.subscriptionCount.value == 1
        }
        header(PayloadChatFixture.ORIGINAL)
        compose.onNode(hasSetTextAction() and detailDescendant()).performTextReplacement(DRAFT)
        if (replaceOwner) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            compose.runOnIdle {
                binderSource.value = ChatThreadBinder(
                    resolveDestination = {
                        started.complete(Unit)
                        release.await()
                        null
                    },
                    environment = { one.meshcore.shared.chat.ui.ChatThreadEnvironment.Disconnected },
                )
            }
            compose.waitUntil(LOAD_TIMEOUT_MS) { started.isCompleted }
            compose.onAllNodesWithText(DRAFT).assertCountEquals(0)
            release.complete(Unit)
            compose.waitForIdle()
            return
        }
        fixture.missNextLookup = transientMiss
        fixture.rename(UPDATED)
        invalidate()
        compose.waitUntil(LOAD_TIMEOUT_MS) { navigation.chatsSelectedRoute.value?.displayTitle == UPDATED }
        if (transientMiss) {
            header(PayloadChatFixture.ORIGINAL)
            compose.onAllNodesWithText(DRAFT).filterToOne(detailDescendant()).assertIsDisplayed()
            compose.runOnIdle { assertEquals(1, mounts) }
            fixture.rename(RECOVERED)
            invalidate()
            header(RECOVERED)
        } else {
            header(UPDATED)
        }
        compose.onAllNodesWithText(DRAFT).filterToOne(detailDescendant()).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, mounts)
            assertEquals(1, navigation.backStackOf(AppTab.CHATS).size)
            assertNull(navigation.pendingRoomAuth.value)
        }
    }

    private fun verifyRoom(wide: Boolean) {
        val store = KeyboardRoomStore()
        compose.runOnUiThread { navigation.navigateToRoom(store.session.id.value, displayTitle = store.session.name) }
        val inputs = ConversationListInputs(
            store = { store },
            contactService = { null },
            radioId = { store.session.radioID },
            connectionState = connection,
            onConversationTap = {},
            listInvalidations = invalidations,
            onSnapshotRefreshed = { rows, ids ->
                navigation.reconcileSelectedChatRoute(ids) { it.refreshedPayload(rows) }
            },
        )
        showShell(wide, list = { ConversationListRoot(inputs) }) { route ->
            val room = route as ChatRoute.Room
            RoomDetailPane(
                RoomDetailInputs(
                    sessionId = room.sessionId, sessionSnapshot = room.sessionSnapshot,
                    roomServerService = { null }, contactService = { null }, radioId = { store.session.radioID },
                    conversationListStore = { store }, roomAdminService = { null }, nodePasswordStore = { null },
                    servicesChanged = emptyFlow(), appForeground = emptyFlow(), onBack = {
                    }, connectionState = connection,
                ),
            )
        }
        compose.waitUntil(LOAD_TIMEOUT_MS) { mounts == 1 && invalidations.subscriptionCount.value == 1 }
        header(store.session.name)
        compose.onNode(hasSetTextAction() and detailDescendant()).performTextReplacement(DRAFT)
        compose.runOnIdle { store.session = store.session.copy(name = UPDATED) }
        invalidate()
        header(UPDATED)
        compose.onAllNodesWithText(DRAFT).filterToOne(detailDescendant()).assertIsDisplayed()
        if (!wide) capturePayloadFrame("payload-room-refreshed.png")
        compose.runOnIdle { assertEquals(1, mounts) }
        compose.runOnIdle { store.session = store.session.copy(isConnected = false) }
        invalidate()
        compose.waitUntil(LOAD_TIMEOUT_MS) { navigation.pendingRoomAuth.value == store.session.id.value }
        compose.runOnIdle {
            assertNull(navigation.chatsSelectedRoute.value)
            assertTrue(navigation.backStackOf(AppTab.CHATS).isEmpty())
        }
    }

    private fun invalidate() {
        compose.runOnIdle { assertTrue(invalidations.tryEmit(Unit)) }
        compose.waitForIdle()
    }

    private fun header(text: String) {
        try {
            compose.waitUntil(LOAD_TIMEOUT_MS) {
                val title = compose.onAllNodesWithText(text).filter(detailDescendant())
                    .fetchSemanticsNodes().minByOrNull { it.boundsInRoot.top }
                title != null &&
                    try {
                        compose.onNode(SemanticsMatcher("top-bar title") { it.id == title.id }).assertIsDisplayed()
                        true
                    } catch (_: AssertionError) {
                        false
                    }
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            capturePayloadFrame("payload-header-failure.png")
            throw failure
        }
        val headerId = compose.onAllNodesWithText(text).filter(detailDescendant())
            .fetchSemanticsNodes().minBy { it.boundsInRoot.top }.id
        compose.onNode(SemanticsMatcher("top-bar title") { it.id == headerId }).assertIsDisplayed()
    }

    private fun detailDescendant() = hasAnyAncestor(hasTestTag(DETAIL))

    private fun showShell(wide: Boolean, list: @Composable () -> Unit, detail: @Composable (ChatRoute) -> Unit) {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            navigation.setSidebarWide(wide)
        }
        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    val content: @Composable (ChatRoute) -> Unit = { route ->
                        Box(Modifier.fillMaxSize().testTag(DETAIL)) {
                            DisposableEffect(Unit) {
                                mounts += 1
                                onDispose {}
                            }
                            detail(route)
                        }
                    }
                    if (wide) {
                        val density = LocalDensity.current
                        // A lower fixture density fits tiled-width composition on the phone; native tablet geometry is separate.
                        CompositionLocalProvider(
                            LocalDensity provides Density(density.density * WIDE_DENSITY_SCALE, density.fontScale),
                        ) {
                            WideShell(navigation, 0, chatsContent = list, chatDetailContent = content)
                        }
                    } else {
                        CompactShell(navigation, 0, false, chatsContent = list, chatDetailContent = content)
                    }
                }
            }
        }
    }

    private companion object {
        const val WIDE_DENSITY_SCALE = 0.5f
        const val LOAD_TIMEOUT_MS = 5_000L
        const val DETAIL = "payload-detail"
        const val DRAFT = "kept payload draft"
        const val UPDATED = "Updated payload title"
        const val RECOVERED = "Recovered payload title"
    }
}
