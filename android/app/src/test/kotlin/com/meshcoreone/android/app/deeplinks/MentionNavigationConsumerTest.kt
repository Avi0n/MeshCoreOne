// PortedFrom: MC1Tests/Views/Chats/MentionDeeplinkSupportTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.app.navigation.MapFocusRequest
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.feature.chats.composer.MessageTextFormatter
import com.meshcoreone.android.feature.chats.composer.MentionDeeplink
import com.meshcoreone.android.feature.chats.composer.MessageLinkDispatcher
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import kotlin.test.*

@Config(sdk = [31, 37], qualifiers = "en-rUS-w390dp-h1000dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class MentionNavigationConsumerTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val names = listOf("a%2Fb", "100% Coverage", "Node 1/2 Repeater", "Alice Smith", "Caf\u00e9 \u5317\u4eac")

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun mountedOriginalMentionBodiesReachTheActualContactDestinationWithoutDoubleDecoding() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            names.forEachIndexed { index, name -> radio.knownContacts += contactFrame(key(index + 20), name) }
        }
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            h.eventually("original mention contacts finish synchronizing") {
                h.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced
            }
            val radio = assertNotNull(h.appState.currentRadioId)
            val contacts = store.fetchContacts(radio)
            val peer = contacts.single { it.name == names.first() }
            val body = names.joinToString(" ") { "@[$it]" }
            val originalLinks = listOf(
                "meshcoreone://mention/a%252Fb",
                "meshcoreone://mention/100%25%20Coverage",
                "meshcoreone://mention/Node%201%2F2%20Repeater",
                "meshcoreone://mention/Alice%20Smith",
                "meshcoreone://mention/Caf%C3%A9%20%E5%8C%97%E4%BA%AC",
            )
            assertEquals(originalLinks, MessageTextFormatter.format(body).links)
            store.saveMessage(MessageDTO(
                radioId = radio, contactID = peer.id, text = body, timestamp = 100u,
                createdAt = EPOCH, direction = MessageDirection.INCOMING,
            ))
            compose.runOnUiThread {
                h.container.navigation.navigateToChat(peer)
                host.get().setContent { MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(h.container.navigation, chats = h.container.chatsFeature)
                } }
            }
            for (name in names) {
                compose.runOnUiThread {
                    h.container.navigation.clearPendingContactDetailNavigation()
                    h.container.navigation.navigateToChat(peer)
                }
                compose.waitUntil(10_000) {
                    h.settle()
                    compose.onAllNodesWithText("@$name", substring = true, useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("@$name", substring = true, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.CustomActions) { actions ->
                        assertTrue(actions.single { it.label.contains(name) }.action())
                    }
                compose.waitForIdle()
                val selected = assertNotNull(h.container.navigation.state.value.pendingContactDetail)
                assertEquals(contacts.single { it.name == name }.id, selected.id)
                assertEquals(name, selected.name)
            }
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }

    @Test fun originalNonMentionUrlsReachTheirActualRoutesInsteadOfMentionResolution() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val navigation = h.container.navigation
            val feature = h.container.chatsFeature
            val chat = ChatLinkRouter(feature.list.links, backgroundScope)
            val external = mutableListOf<String>()
            val dispatcher = MessageLinkDispatcher(
                contacts = { error("Non-mention URLs must not resolve contacts") },
                radioId = { error("Non-mention URLs must not resolve a mention radio") },
                connectedDeviceName = { error("Non-mention URLs must not resolve a mention device") },
                onMention = { error("Non-mention URLs must not select a mention") },
                route = chat::route,
                openExternal = external::add,
            )
            for (url in listOf(
                "https://apple.com",
                "meshcore://map?lat=1&lon=2",
                "meshcoreone://hashtag/general",
            )) {
                assertNull(MentionDeeplink.name(url), url)
                assertTrue(dispatcher.dispatch(url), url)
            }
            testScheduler.runCurrent()
            assertEquals(listOf("https://apple.com"), external)
            assertEquals(MapFocusRequest(1.0, 2.0), navigation.state.value.pendingMapFocus)
            assertEquals("#general", navigation.state.value.pendingHashtag?.id)
            assertNull(navigation.state.value.pendingContactDetail)
        } finally { h.container.close() }
    }
}
