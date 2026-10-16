package one.meshcore.shared.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.layout.SidebarVisibility
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `NavigationStateTests` and `NavigationCoordinatorTests`: the navigate intents, the
 * consume-once clears, the per-radio/per-device teardown selectors, the width-driven sidebar-visibility
 * derivation (the `SidebarNavigationLayoutTests` cases seen through the coordinator), the per-tab back
 * stack, and tab persistence across process death.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationCoordinatorTest {
    // MARK: - Defaults & tab selection

    @Test
    fun `Default navigation state is tab 0 with no pending navigation`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.chatsSelectedRoute.value)
        assertNull(nav.pendingMapFocus.value)
        assertNull(nav.droppedMapPin.value)
        assertTrue(nav.tabBarVisible.value)
        assertTrue(nav.backStacks.value.isEmpty())
    }

    @Test
    fun `selectTab switches tabs and shows the tab bar`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToChat("aa") // hides the bar
        assertFalse(nav.tabBarVisible.value)
        nav.selectTab(AppTab.MAP)
        assertEquals(AppTab.MAP.index, nav.selectedTab.value)
        assertTrue(nav.tabBarVisible.value)
    }

    @Test
    fun `re-entering a tab whose top is a chat keeps the bar hidden, popping it restores`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = coordinator(backgroundScope)
            nav.navigateToChat("chat1") // Chats tab, open ChatDetail, bar hidden
            nav.selectTab(AppTab.MAP)
            assertTrue(nav.tabBarVisible.value)
            // Returning to Chats must not reveal the bar over the still-open thread.
            nav.selectTab(AppTab.CHATS)
            assertFalse(nav.tabBarVisible.value)
            // Popping the ChatDetail restores it.
            assertTrue(nav.pop(AppTab.CHATS))
            assertTrue(nav.tabBarVisible.value)
        }

    // MARK: - Navigate intents

    @Test
    fun `navigateToChat sets route, tab, and hides the tab bar`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.selectTab(AppTab.NODES)
        nav.navigateToChat("contactKeyHex", scrollToMessageId = "m1")
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertEquals(ChatRoute.Direct("contactKeyHex"), nav.chatsSelectedRoute.value)
        assertEquals(one.meshcore.shared.model.Uuid.normalized("m1"), nav.pendingScrollTarget.value?.messageID)
        assertFalse(nav.tabBarVisible.value)
        assertEquals(listOf(NavKey.ChatDetail(ChatRoute.Direct("contactKeyHex"))), nav.backStackOf(AppTab.CHATS))
    }

    @Test
    fun `navigateToChannel sets channel route and Chats tab`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToChannel("ABCD", scrollToMessageId = "m2")
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertEquals(ChatRoute.Channel("ABCD"), nav.chatsSelectedRoute.value)
        assertEquals(one.meshcore.shared.model.Uuid.normalized("m2"), nav.pendingScrollTarget.value?.messageID)
    }

    @Test
    fun `navigateToRoom sets room route and Chats tab`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToRoom("session-1")
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertEquals(ChatRoute.Room("session-1"), nav.chatsSelectedRoute.value)
        assertFalse(nav.tabBarVisible.value)
        assertEquals(listOf(NavKey.ChatDetail(ChatRoute.Room("session-1"))), nav.backStackOf(AppTab.CHATS))
        assertTrue(nav.canHandleSystemBack())
    }

    @Test
    fun `navigateToRoom with isConnected false stages pendingRoomAuth instead of opening`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = coordinator(backgroundScope)
            nav.navigateToRoom("session-1", isConnected = false)
            assertEquals("session-1", nav.pendingRoomAuth.value)
            assertNull(nav.chatsSelectedRoute.value)
            assertTrue(nav.backStackOf(AppTab.CHATS).isEmpty())
        }

    @Test
    fun `clearPendingRoomAuth consumes the staged session id`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToRoom("session-1", isConnected = false)
        nav.clearPendingRoomAuth()
        assertNull(nav.pendingRoomAuth.value)
    }

    @Test
    fun `handleSystemBack from room chat returns to chats list`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToRoom("session-1")
        assertTrue(nav.handleSystemBack())
        assertTrue(nav.backStackOf(AppTab.CHATS).isEmpty())
        assertEquals(null, nav.chatsSelectedRoute.value)
        assertTrue(nav.tabBarVisible.value)
    }

    @Test
    fun `clearChatRouteIfMatching pops only the open matching conversation`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToChat("alice")
        nav.navigateToChat("bob")
        nav.clearChatRouteIfMatching { it is ChatRoute.Direct && it.contactId == "alice" }
        assertEquals(ChatRoute.Direct("bob"), nav.chatsSelectedRoute.value)
        nav.clearChatRouteIfMatching { it is ChatRoute.Direct && it.contactId == "bob" }
        assertEquals(ChatRoute.Direct("alice"), nav.chatsSelectedRoute.value)
        assertEquals(listOf(NavKey.ChatDetail(ChatRoute.Direct("alice"))), nav.backStackOf(AppTab.CHATS))
    }

    @Test
    fun `existing direct route survives an empty conversation snapshot`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToChat("alice")
        nav.reconcileSelectedChatRoute(null) { null }
        assertEquals(ChatRoute.Direct("alice"), nav.chatsSelectedRoute.value)
    }

    @Test
    fun `new direct route closes when its contact is deleted`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToNewChat("alice")

        nav.reconcileSelectedChatRoute(null) { null }
        assertEquals(ChatRoute.Direct("alice"), nav.chatsSelectedRoute.value)

        nav.reconcileSelectedChatRoute(null) { it }
        nav.reconcileSelectedChatRoute(emptySet()) { null }
        assertNull(nav.chatsSelectedRoute.value)
    }

    @Test
    fun `direct retention distinguishes present missing and unknown contact ids`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = coordinator(backgroundScope)
            nav.navigateToChat("alice")
            nav.reconcileSelectedChatRoute(setOf(Uuid.normalized("alice"))) { null }
            assertEquals(ChatRoute.Direct("alice"), nav.chatsSelectedRoute.value)
            nav.reconcileSelectedChatRoute(null) { null }
            assertEquals(ChatRoute.Direct("alice"), nav.chatsSelectedRoute.value)
            nav.reconcileSelectedChatRoute(emptySet()) { null }
            assertNull(nav.chatsSelectedRoute.value)
        }

    @Test
    fun `matched direct row wins over an empty contact set`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToChat("alice")
        nav.reconcileSelectedChatRoute(emptySet()) { it }
        assertEquals(ChatRoute.Direct("alice"), nav.chatsSelectedRoute.value)
    }

    @Test
    fun `unseen new direct closes when the contact is already missing`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToNewChat("alice")
        nav.reconcileSelectedChatRoute(emptySet()) { null }
        assertNull(nav.chatsSelectedRoute.value)
    }

    @Test
    fun `missing room route still pops immediately`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToRoom("room")
        nav.reconcileSelectedChatRoute(null) { null }
        assertNull(nav.chatsSelectedRoute.value)
    }

    @Test
    fun `missing channel route still pops immediately`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToChannel("channel-1")
        nav.reconcileSelectedChatRoute(null) { null }
        assertNull(nav.chatsSelectedRoute.value)
    }

    @Test
    fun `chat to chat push keeps prior chat for Back`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToChat("alice")
        nav.navigateToChat("bob")
        assertEquals(
            listOf(
                NavKey.ChatDetail(ChatRoute.Direct("alice")),
                NavKey.ChatDetail(ChatRoute.Direct("bob")),
            ),
            nav.backStackOf(AppTab.CHATS),
        )
        assertEquals(ChatRoute.Direct("bob"), nav.chatsSelectedRoute.value)
        assertTrue(nav.handleSystemBack())
        assertEquals(listOf(NavKey.ChatDetail(ChatRoute.Direct("alice"))), nav.backStackOf(AppTab.CHATS))
        assertEquals(ChatRoute.Direct("alice"), nav.chatsSelectedRoute.value)
        assertFalse(nav.tabBarVisible.value)
        assertTrue(nav.handleSystemBack())
        assertTrue(nav.backStackOf(AppTab.CHATS).isEmpty())
        assertNull(nav.chatsSelectedRoute.value)
        assertTrue(nav.tabBarVisible.value)
    }

    @Test
    fun `navigating to same chat does not deepen stack`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToChat("alice")
        nav.navigateToChat("alice")
        assertEquals(listOf(NavKey.ChatDetail(ChatRoute.Direct("alice"))), nav.backStackOf(AppTab.CHATS))
    }

    @Test
    fun `chat stack respects max depth cap`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        val depth = NavigationCoordinator.MAX_CHAT_BACK_STACK_DEPTH
        repeat(depth + 3) { i ->
            nav.navigateToChat("c$i")
        }
        val stack = nav.backStackOf(AppTab.CHATS)
        assertEquals(depth, stack.size)
        assertEquals(NavKey.ChatDetail(ChatRoute.Direct("c${depth + 2}")), stack.last())
    }

    @Test
    fun `navigateToDiscovery sets the flag, Nodes tab, stack entry, and does not hide the tab bar`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = coordinator(backgroundScope)
            nav.navigateToDiscovery()
            assertEquals(AppTab.NODES.index, nav.selectedTab.value)
            assertTrue(nav.nodesShowingDiscovery.value)
            assertFalse(nav.nodesShowingBlocked.value)
            assertTrue(nav.tabBarVisible.value)
            assertEquals(listOf(NavKey.Discovery), nav.backStackOf(AppTab.NODES))
            assertTrue(nav.canHandleSystemBack())
        }

    @Test
    fun `navigateToBlockedContacts sets the flag, Nodes stack, and keeps the tab bar`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = coordinator(backgroundScope)
            nav.navigateToBlockedContacts()
            assertEquals(AppTab.NODES.index, nav.selectedTab.value)
            assertTrue(nav.nodesShowingBlocked.value)
            assertFalse(nav.nodesShowingDiscovery.value)
            assertTrue(nav.tabBarVisible.value)
            assertEquals(listOf(NavKey.BlockedContacts), nav.backStackOf(AppTab.NODES))
            assertTrue(nav.canHandleSystemBack())
        }

    @Test
    fun `popping discovery returns to Nodes root and clears the flag`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToDiscovery()
        assertTrue(nav.handleSystemBack())
        assertFalse(nav.nodesShowingDiscovery.value)
        assertFalse(nav.canPop(AppTab.NODES))
        assertEquals(AppTab.NODES.index, nav.selectedTab.value)
        assertFalse(nav.canHandleSystemBack())
    }

    @Test
    fun `popping blocked contacts returns to Nodes root`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToBlockedContacts()
        assertTrue(nav.handleSystemBack())
        assertFalse(nav.nodesShowingBlocked.value)
        assertFalse(nav.canHandleSystemBack())
    }

    @Test
    fun `navigateToContacts switches to the Nodes tab`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToContacts()
        assertEquals(AppTab.NODES.index, nav.selectedTab.value)
    }

    @Test
    fun `contact detail from blocked contacts keeps the list under the detail`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToBlockedContacts()
        nav.navigateToContactDetail("blocked-1")
        assertEquals(
            listOf(NavKey.BlockedContacts, NavKey.ContactDetail("blocked-1")),
            nav.backStackOf(AppTab.NODES),
        )
        assertTrue(nav.nodesShowingBlocked.value)
        assertTrue(nav.handleSystemBack())
        assertTrue(nav.nodesShowingBlocked.value)
        assertEquals(listOf(NavKey.BlockedContacts), nav.backStackOf(AppTab.NODES))
    }

    @Test
    fun `navigateToContactDetail sets the contact and Nodes tab and restores the tab bar`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = coordinator(backgroundScope)
            nav.navigateToChat("from-chat") // hides bar
            assertFalse(nav.tabBarVisible.value)
            nav.navigateToContactDetail("contact-1")
            assertEquals(AppTab.NODES.index, nav.selectedTab.value)
            assertEquals("contact-1", nav.selectedContactId.value)
            assertFalse(nav.nodesShowingDiscovery.value)
            assertFalse(nav.nodesShowingBlocked.value)
            assertTrue(nav.tabBarVisible.value)
            assertTrue(nav.canHandleSystemBack())
        }

    @Test
    fun `navigateToContactDetailFromChat stays on Chats and Back returns to the thread`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = coordinator(backgroundScope)
            nav.navigateToChat("contact-1")
            assertFalse(nav.tabBarVisible.value)
            assertEquals(AppTab.CHATS.index, nav.selectedTab.value)

            nav.navigateToContactDetailFromChat("contact-1")
            assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
            assertEquals(ChatRoute.Direct("contact-1"), nav.chatsSelectedRoute.value)
            assertNull(nav.selectedContactId.value)
            assertFalse(nav.tabBarVisible.value)
            assertEquals(
                listOf(
                    NavKey.ChatDetail(ChatRoute.Direct("contact-1")),
                    NavKey.ContactDetail("contact-1", fromDirectChat = true),
                ),
                nav.backStackOf(AppTab.CHATS),
            )

            assertTrue(nav.handleSystemBack())
            assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
            assertEquals(listOf(NavKey.ChatDetail(ChatRoute.Direct("contact-1"))), nav.backStackOf(AppTab.CHATS))
            assertEquals(ChatRoute.Direct("contact-1"), nav.chatsSelectedRoute.value)
            assertFalse(nav.tabBarVisible.value)
        }

    @Test
    fun `opening a chat while contact info is showing drops the overlay`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToChat("contact-1")
        nav.navigateToContactDetailFromChat("contact-1")
        nav.navigateToChat("contact-2")
        assertEquals(
            listOf(
                NavKey.ChatDetail(ChatRoute.Direct("contact-1")),
                NavKey.ChatDetail(ChatRoute.Direct("contact-2")),
            ),
            nav.backStackOf(AppTab.CHATS),
        )
        assertEquals(ChatRoute.Direct("contact-2"), nav.chatsSelectedRoute.value)
    }

    @Test
    fun `navigateToMap restores tab bar, pushes MapFocus, and Back returns to chat`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = coordinator(backgroundScope)
            nav.navigateToChat("chat1")
            assertFalse(nav.tabBarVisible.value)
            assertEquals(AppTab.CHATS.index, nav.selectedTab.value)

            nav.navigateToMap(1.5, -2.5)
            assertEquals(AppTab.MAP.index, nav.selectedTab.value)
            assertEquals(MapFocusRequest(1.5, -2.5), nav.pendingMapFocus.value)
            assertTrue(nav.tabBarVisible.value)
            assertEquals(listOf(NavKey.MapFocus), nav.backStackOf(AppTab.MAP))
            assertTrue(nav.canHandleSystemBack())
            // Chat stack preserved so return restores the open conversation.
            assertEquals(listOf(NavKey.ChatDetail(ChatRoute.Direct("chat1"))), nav.backStackOf(AppTab.CHATS))

            assertTrue(nav.handleSystemBack())
            assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
            assertEquals(ChatRoute.Direct("chat1"), nav.chatsSelectedRoute.value)
            assertFalse(nav.tabBarVisible.value)
            assertNull(nav.pendingMapFocus.value)
            assertEquals(MapFocusRequest(1.5, -2.5), nav.droppedMapPin.value)
            assertFalse(nav.canPop(AppTab.MAP))
        }

    @Test
    fun `navigateToMap from root selects map with focus and consumable Back`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToMap(1.0, 2.0)
        assertEquals(AppTab.MAP.index, nav.selectedTab.value)
        assertEquals(MapFocusRequest(1.0, 2.0), nav.pendingMapFocus.value)
        assertTrue(nav.tabBarVisible.value)
        assertTrue(nav.canHandleSystemBack())
        // From CHATS root, Back returns to chats list (not exit).
        assertTrue(nav.handleSystemBack())
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
    }

    // MARK: - Consume-once clears

    @Test
    fun `clearPendingMapFocus resets the pending focus`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToMap(1.0, 2.0)
        nav.clearPendingMapFocus()
        assertNull(nav.pendingMapFocus.value)
        assertEquals(MapFocusRequest(1.0, 2.0), nav.droppedMapPin.value)
    }

    @Test
    fun `clearDroppedMapPin removes the live pin`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToMap(1.0, 2.0)
        nav.clearDroppedMapPin()
        assertNull(nav.droppedMapPin.value)
    }

    @Test
    fun `pendingContactLink and pendingChannelLink stage and clear`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        val contact = MeshCoreDeepLink.Contact("N", ByteArray(32), one.meshcore.shared.model.ContactType.CHAT)
        val channel = MeshCoreDeepLink.Channel("C", ByteArray(16))
        nav.stageContactLink(contact)
        nav.stageChannelLink(channel)
        assertEquals(contact, nav.pendingContactLink.value)
        assertEquals(channel, nav.pendingChannelLink.value)
        nav.clearPendingContactLink()
        nav.clearPendingChannelLink()
        assertNull(nav.pendingContactLink.value)
        assertNull(nav.pendingChannelLink.value)
    }

    @Test
    fun `dismissing a staged send does not radio`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        val contact = MeshCoreDeepLink.Contact("N", ByteArray(32), one.meshcore.shared.model.ContactType.CHAT)
        val request = MeshCoreDeepLink.SendMessage(
            kind = MeshCoreDeepLink.SendMessage.Kind.CONTACT,
            targetId = "contact-1",
            text = "hello",
        )
        nav.stageContactLink(contact)
        nav.stageSendMessage(request)
        assertEquals(contact, nav.pendingContactLink.value)
        assertEquals(request, nav.pendingSendMessage.value)
        assertNull(nav.chatsSelectedRoute.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)

        nav.consumeSendMessage()

        assertNull(nav.pendingSendMessage.value)
        assertEquals(contact, nav.pendingContactLink.value)
        assertNull(nav.pendingSendAdvert.value)
        assertNull(nav.pendingRoomAuth.value)
        assertNull(nav.chatsSelectedRoute.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertEquals(0, nav.radioSheetEpoch.value)
    }

    @Test
    fun `dismissing a staged hashtag join does not radio`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        val request = MeshCoreDeepLink.SendMessage(
            kind = MeshCoreDeepLink.SendMessage.Kind.CONTACT,
            targetId = "contact-1",
            text = "hello",
        )
        nav.stageHashtagLink("#pond")
        nav.stageSendMessage(request)
        assertEquals("#pond", nav.pendingHashtagJoin.value)
        assertEquals(request, nav.pendingSendMessage.value)

        nav.clearPendingHashtagJoin()

        assertNull(nav.pendingHashtagJoin.value)
        assertEquals(request, nav.pendingSendMessage.value)
        assertNull(nav.pendingChannelLink.value)
        assertNull(nav.chatsSelectedRoute.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertEquals(0, nav.radioSheetEpoch.value)
    }

    @Test
    fun `dismissing a staged contact link does not radio`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        val contact = MeshCoreDeepLink.Contact("N", ByteArray(32), one.meshcore.shared.model.ContactType.CHAT)
        val channel = MeshCoreDeepLink.Channel("C", ByteArray(16))
        val request = MeshCoreDeepLink.SendMessage(
            kind = MeshCoreDeepLink.SendMessage.Kind.CONTACT,
            targetId = "contact-1",
            text = "hello",
        )
        nav.stageContactLink(contact)
        nav.stageChannelLink(channel)
        nav.stageSendMessage(request)
        assertEquals(contact, nav.pendingContactLink.value)
        assertEquals(channel, nav.pendingChannelLink.value)
        assertEquals(request, nav.pendingSendMessage.value)

        nav.clearPendingContactLink()

        assertNull(nav.pendingContactLink.value)
        assertEquals(channel, nav.pendingChannelLink.value)
        assertEquals(request, nav.pendingSendMessage.value)
        assertNull(nav.pendingHashtagJoin.value)
        assertNull(nav.chatsSelectedRoute.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertEquals(0, nav.radioSheetEpoch.value)
    }

    @Test
    fun `dismissing a staged advert does not radio`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        val advert = MeshCoreDeepLink.SendAdvert(flood = false)
        val request = MeshCoreDeepLink.SendMessage(
            kind = MeshCoreDeepLink.SendMessage.Kind.CONTACT,
            targetId = "contact-1",
            text = "hello",
        )
        nav.stageSendAdvert(advert)
        nav.stageSendMessage(request)
        assertEquals(advert, nav.pendingSendAdvert.value)
        assertEquals(request, nav.pendingSendMessage.value)

        nav.consumeSendAdvert()

        assertNull(nav.pendingSendAdvert.value)
        assertEquals(request, nav.pendingSendMessage.value)
        assertNull(nav.pendingContactLink.value)
        assertNull(nav.pendingHashtagJoin.value)
        assertNull(nav.chatsSelectedRoute.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertEquals(0, nav.radioSheetEpoch.value)
    }

    @Test
    fun `dismissing a staged channel link does not radio`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        val contact = MeshCoreDeepLink.Contact("N", ByteArray(32), one.meshcore.shared.model.ContactType.CHAT)
        val channel = MeshCoreDeepLink.Channel("C", ByteArray(16))
        nav.stageContactLink(contact)
        nav.stageChannelLink(channel)
        assertEquals(contact, nav.pendingContactLink.value)
        assertEquals(channel, nav.pendingChannelLink.value)

        nav.clearPendingChannelLink()

        assertNull(nav.pendingChannelLink.value)
        assertEquals(contact, nav.pendingContactLink.value)
        assertNull(nav.pendingSendMessage.value)
        assertNull(nav.pendingHashtagJoin.value)
        assertNull(nav.chatsSelectedRoute.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertEquals(0, nav.radioSheetEpoch.value)
    }
}

/**
 * Per-radio teardown, sidebar width, the back stack, and tab persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationCoordinatorShellTest {
    // MARK: - Teardown selectors

    @Test
    fun `clearPendingLinks clears deep-link confirmations and every per-radio field`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = coordinator(backgroundScope)
            nav.stageContactLink(
                MeshCoreDeepLink.Contact("N", ByteArray(32), one.meshcore.shared.model.ContactType.CHAT),
            )
            nav.navigateToContactDetail("c1")
            nav.navigateToDiscovery()
            nav.navigateToChat("chat1")

            nav.clearPendingLinks()

            assertNull(nav.pendingContactLink.value)
            assertNull(nav.selectedContactId.value)
            assertFalse(nav.nodesShowingDiscovery.value)
            assertNull(nav.chatsSelectedRoute.value)
            assertEquals(1, nav.radioSheetEpoch.value)
        }

    @Test
    fun `clearPerRadioSelection bumps the radio sheet epoch`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        assertEquals(0, nav.radioSheetEpoch.value)
        nav.clearPerRadioSelection()
        assertEquals(1, nav.radioSheetEpoch.value)
        nav.clearPerDeviceSelection()
        assertEquals(1, nav.radioSheetEpoch.value)
    }

    @Test
    fun `clearPerRadioSelection clears the Chats route and a radio-requiring tool`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = coordinator(backgroundScope)
            nav.navigateToChat("chat1")
            nav.selectTool(ToolSelection.CLI)
            nav.clearPerRadioSelection()
            assertNull(nav.chatsSelectedRoute.value)
            assertNull(nav.selectedTool.value)
        }

    @Test
    fun `clearPerRadioSelection preserves the offline Line of Sight tool`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.selectTool(ToolSelection.LINE_OF_SIGHT)
        nav.clearPerRadioSelection()
        assertEquals(ToolSelection.LINE_OF_SIGHT, nav.selectedTool.value)
    }

    @Test
    fun `clearPerRadioSelection pops RX Log and Trace Path`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.selectTool(ToolSelection.RX_LOG)
        assertTrue(ToolSelection.RX_LOG.requiresRadio)
        nav.clearPerRadioSelection()
        assertNull(nav.selectedTool.value)

        nav.selectTool(ToolSelection.TRACE_PATH)
        assertTrue(ToolSelection.TRACE_PATH.requiresRadio)
        nav.clearPerRadioSelection()
        assertNull(nav.selectedTool.value)
    }

    @Test
    fun `clearPerDeviceSelection clears a My Device settings page but keeps app settings`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = coordinator(backgroundScope)
            nav.selectSetting(SettingsDestination.DEVICE_INFO)
            nav.clearPerDeviceSelection()
            assertNull(nav.selectedSetting.value)

            nav.selectSetting(SettingsDestination.BACKUP)
            nav.clearPerDeviceSelection()
            assertEquals(SettingsDestination.BACKUP, nav.selectedSetting.value)
        }

    // MARK: - Sidebar visibility derivation (SidebarNavigationLayoutTests through the coordinator)

    @Test
    fun markingTheSidebarWideDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val nav = coordinator(backgroundScope, prefs)
        assertFalse(nav.isSidebarWide.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.selectedTool.value)
        assertNull(nav.selectedSetting.value)
        val before = prefs.snapshot()

        nav.setSidebarWide(true)

        assertTrue(nav.isSidebarWide.value)
        assertEquals(SidebarVisibility.Tiled, nav.sidebarVisibility.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.selectedTool.value)
        assertNull(nav.selectedSetting.value)
        assertTrue(nav.tabBarVisible.value)
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun choosingASettingsPageFromTheShellDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val nav = coordinator(backgroundScope, prefs)
        assertNull(nav.selectedSetting.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertFalse(nav.isSidebarWide.value)
        assertNull(nav.selectedTool.value)
        val before = prefs.snapshot()

        nav.selectSetting(SettingsDestination.BACKUP)

        assertEquals(SettingsDestination.BACKUP, nav.selectedSetting.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertFalse(nav.isSidebarWide.value)
        assertNull(nav.selectedTool.value)
        assertTrue(nav.tabBarVisible.value)
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun consumingAnAbsentMessageScrollDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val nav = coordinator(backgroundScope, prefs)
        assertNull(nav.pendingScrollTarget.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.selectedSetting.value)
        val before = prefs.snapshot()

        assertFalse(nav.consumePendingScrollRequest(one.meshcore.shared.model.Uuid.random()))

        assertNull(nav.pendingScrollTarget.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.selectedSetting.value)
        assertNull(nav.selectedTool.value)
        assertFalse(nav.isSidebarWide.value)
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun clearingAPendingMapFocusDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val nav = coordinator(backgroundScope, prefs)
        assertNull(nav.pendingMapFocus.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.pendingScrollTarget.value)
        val before = prefs.snapshot()

        nav.clearPendingMapFocus()

        assertNull(nav.pendingMapFocus.value)
        assertNull(nav.pendingScrollTarget.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.selectedSetting.value)
        assertFalse(nav.isSidebarWide.value)
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun clearingAnEmptyDroppedMapPinDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val nav = coordinator(backgroundScope, prefs)
        assertNull(nav.droppedMapPin.value)
        assertNull(nav.pendingMapFocus.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.pendingScrollTarget.value)
        val before = prefs.snapshot()

        nav.clearDroppedMapPin()

        assertNull(nav.droppedMapPin.value)
        assertNull(nav.pendingMapFocus.value)
        assertNull(nav.pendingScrollTarget.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.selectedSetting.value)
        assertNull(nav.selectedTool.value)
        assertFalse(nav.isSidebarWide.value)
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun clearingAnEmptyContactLinkDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val nav = coordinator(backgroundScope, prefs)
        assertNull(nav.pendingContactLink.value)
        assertNull(nav.pendingChannelLink.value)
        assertNull(nav.droppedMapPin.value)
        assertNull(nav.pendingMapFocus.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.pendingScrollTarget.value)
        val before = prefs.snapshot()

        nav.clearPendingContactLink()

        assertNull(nav.pendingContactLink.value)
        assertNull(nav.pendingChannelLink.value)
        assertNull(nav.droppedMapPin.value)
        assertNull(nav.pendingMapFocus.value)
        assertNull(nav.pendingScrollTarget.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.selectedSetting.value)
        assertNull(nav.selectedTool.value)
        assertFalse(nav.isSidebarWide.value)
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun clearingAnEmptyChannelLinkDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val nav = coordinator(backgroundScope, prefs)
        assertNull(nav.pendingContactLink.value)
        assertNull(nav.pendingChannelLink.value)
        assertNull(nav.droppedMapPin.value)
        assertNull(nav.pendingMapFocus.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.pendingScrollTarget.value)
        val before = prefs.snapshot()

        nav.clearPendingChannelLink()

        assertNull(nav.pendingContactLink.value)
        assertNull(nav.pendingChannelLink.value)
        assertNull(nav.droppedMapPin.value)
        assertNull(nav.pendingMapFocus.value)
        assertNull(nav.pendingScrollTarget.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.selectedSetting.value)
        assertNull(nav.selectedTool.value)
        assertFalse(nav.isSidebarWide.value)
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun `A wide container tiles the sidebar when no collapsing tool is open`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.setSidebarWide(true)
        nav.selectTab(AppTab.NODES)
        assertEquals(SidebarVisibility.Tiled, nav.sidebarVisibility.value)
    }

    @Test
    fun keepingTheSidebarNarrowDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val nav = coordinator(backgroundScope, prefs)
        assertFalse(nav.isSidebarWide.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.selectedTool.value)
        assertNull(nav.selectedSetting.value)
        val before = prefs.snapshot()
        assertEquals(
            mapOf(NavigationCoordinator.SELECTED_TAB_KEY to AppTab.CHATS.index),
            before,
        )

        nav.setSidebarWide(false)

        assertFalse(nav.isSidebarWide.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertNull(nav.selectedTool.value)
        assertNull(nav.selectedSetting.value)
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun `A narrow container collapses to the section's hidden shape`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.setSidebarWide(false)
        nav.selectTab(AppTab.NODES)
        assertEquals(SidebarVisibility.ContentDetail, nav.sidebarVisibility.value)
        nav.selectTab(AppTab.MAP)
        assertEquals(SidebarVisibility.DetailOnly, nav.sidebarVisibility.value)
    }

    @Test
    fun `A sidebar-collapsing tool collapses the sidebar even when wide`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.setSidebarWide(true)
        nav.selectTab(AppTab.TOOLS)
        assertEquals(SidebarVisibility.Tiled, nav.sidebarVisibility.value)
        nav.selectTool(ToolSelection.LINE_OF_SIGHT)
        assertEquals(SidebarVisibility.ContentDetail, nav.sidebarVisibility.value)
        nav.selectTool(ToolSelection.RX_LOG)
        assertEquals(SidebarVisibility.Tiled, nav.sidebarVisibility.value)
    }

    // MARK: - Back stack

    @Test
    fun `push and pop maintain a per-tab back stack`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        assertFalse(nav.canPop(AppTab.NODES))
        nav.push(AppTab.NODES, NavKey.ContactDetail("c1"))
        assertTrue(nav.canPop(AppTab.NODES))
        assertFalse(nav.canPop(AppTab.CHATS)) // stacks are independent per tab
        assertTrue(nav.pop(AppTab.NODES))
        assertFalse(nav.canPop(AppTab.NODES))
        assertFalse(nav.pop(AppTab.NODES)) // popping the root is a no-op
    }

    @Test
    fun `popping the last Chats detail restores the tab bar and clears the route`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = coordinator(backgroundScope)
            nav.navigateToChat("chat1")
            assertFalse(nav.tabBarVisible.value)
            assertTrue(nav.pop(AppTab.CHATS))
            assertTrue(nav.tabBarVisible.value)
            assertNull(nav.chatsSelectedRoute.value)
        }

    @Test
    fun `popping the last Nodes detail clears the hoisted contact selection`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToContactDetail("c1")
        assertEquals("c1", nav.selectedContactId.value)
        assertTrue(nav.pop(AppTab.NODES))
        assertNull(nav.selectedContactId.value)
        assertFalse(nav.nodesShowingDiscovery.value)
    }

    @Test
    fun `popToRoot clears stack and tab-local tool or setting`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.navigateToChat("chat1")
        nav.popToRoot(AppTab.CHATS)
        assertNull(nav.chatsSelectedRoute.value)
        assertTrue(nav.tabBarVisible.value)
        assertFalse(nav.canPop(AppTab.CHATS))

        nav.selectTab(AppTab.TOOLS)
        nav.selectTool(ToolSelection.RX_LOG)
        nav.popToRoot(AppTab.TOOLS)
        assertNull(nav.selectedTool.value)

        nav.selectTab(AppTab.SETTINGS)
        nav.selectSetting(SettingsDestination.APPEARANCE)
        nav.popToRoot(AppTab.SETTINGS)
        assertNull(nav.selectedSetting.value)
    }

    @Test
    fun `handleSystemBack pops tool then stack then yields at root`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        assertFalse(nav.canHandleSystemBack())
        assertFalse(nav.handleSystemBack())

        nav.selectTab(AppTab.TOOLS)
        nav.selectTool(ToolSelection.LINE_OF_SIGHT)
        assertTrue(nav.canHandleSystemBack())
        assertTrue(nav.handleSystemBack())
        assertNull(nav.selectedTool.value)
        assertFalse(nav.canHandleSystemBack())

        nav.navigateToChat("chat1")
        assertTrue(nav.handleSystemBack())
        assertNull(nav.chatsSelectedRoute.value)
        assertFalse(nav.canHandleSystemBack())
    }

    @Test
    fun `handleSystemBack closes settings destination`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.selectTab(AppTab.SETTINGS)
        nav.selectSetting(SettingsDestination.BACKUP)
        assertTrue(nav.handleSystemBack())
        assertNull(nav.selectedSetting.value)
    }

    @Test
    fun `handleSystemBack pops nested settings to the parent page`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.selectTab(AppTab.SETTINGS)
        nav.selectSetting(SettingsDestination.REGION_MANAGEMENT)
        assertTrue(nav.handleSystemBack())
        assertEquals(SettingsDestination.ADVANCED, nav.selectedSetting.value)
        assertTrue(nav.handleSystemBack())
        assertNull(nav.selectedSetting.value)
    }

    @Test
    fun `handleSystemBack pops public key to device info`() = runTest(UnconfinedTestDispatcher()) {
        val nav = coordinator(backgroundScope)
        nav.selectTab(AppTab.SETTINGS)
        nav.selectSetting(SettingsDestination.PUBLIC_KEY)
        assertTrue(nav.handleSystemBack())
        assertEquals(SettingsDestination.DEVICE_INFO, nav.selectedSetting.value)
    }

    // MARK: - Tab persistence across process death

    @Test
    fun `selecting a tab persists its index`() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val nav = coordinator(backgroundScope, prefs)
        nav.selectTab(AppTab.MAP)
        assertEquals(AppTab.MAP.index, prefs.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
    }

    @Test
    fun `a tab persist fault does not stop later tab writes`() = runTest(UnconfinedTestDispatcher()) {
        val backing = InMemoryPreferencesStore()
        var writes = 0
        val prefs = object : PreferencesStore by backing {
            override suspend fun putInt(key: String, value: Int) {
                writes += 1
                if (writes == 1) error("datastore down")
                backing.putInt(key, value)
            }
        }
        val nav = coordinator(backgroundScope, prefs)
        nav.selectTab(AppTab.MAP)
        assertEquals(AppTab.MAP.index, backing.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
    }

    @Test
    fun `a persisted tab index is restored on construction`() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore(mapOf(NavigationCoordinator.SELECTED_TAB_KEY to AppTab.TOOLS.index))
        val nav = coordinator(backgroundScope, prefs)
        assertEquals(AppTab.TOOLS.index, nav.selectedTab.value)
    }

    @Test
    fun `a tab restore fault still persists later writes`() = runTest(UnconfinedTestDispatcher()) {
        val backing = InMemoryPreferencesStore(mapOf(NavigationCoordinator.SELECTED_TAB_KEY to AppTab.TOOLS.index))
        val prefs = object : PreferencesStore by backing {
            override suspend fun getInt(key: String): Int? {
                if (key == NavigationCoordinator.SELECTED_TAB_KEY) error("datastore down")
                return backing.getInt(key)
            }
        }
        val nav = coordinator(backgroundScope, prefs)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        nav.selectTab(AppTab.MAP)
        assertEquals(AppTab.MAP.index, backing.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
    }

    @Test
    fun `a cancelled tab restore does not persist later writes`() = runTest(UnconfinedTestDispatcher()) {
        val backing = InMemoryPreferencesStore()
        val prefs = object : PreferencesStore by backing {
            override suspend fun getInt(key: String): Int? {
                if (key == NavigationCoordinator.SELECTED_TAB_KEY) throw CancellationException("stopped")
                return backing.getInt(key)
            }
        }
        val nav = coordinator(backgroundScope, prefs)
        nav.selectTab(AppTab.MAP)
        assertNull(backing.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
        assertEquals(AppTab.MAP.index, nav.selectedTab.value)
    }

    @Test
    fun `a tab restore error does not persist later writes`() = runTest(UnconfinedTestDispatcher()) {
        val thrown = mutableListOf<Throwable>()
        val scope = recordingScope(thrown)
        val backing = InMemoryPreferencesStore()
        val prefs = object : PreferencesStore by backing {
            override suspend fun getInt(key: String): Int? {
                if (key == NavigationCoordinator.SELECTED_TAB_KEY) throw TabPersistProbeError()
                return backing.getInt(key)
            }
        }
        val nav = coordinator(scope, prefs)
        nav.selectTab(AppTab.MAP)
        assertIs<TabPersistProbeError>(thrown.single())
        assertNull(backing.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
        assertEquals(AppTab.MAP.index, nav.selectedTab.value)
    }

    @Test
    fun `a cancelled tab write stops later writes`() = runTest(UnconfinedTestDispatcher()) {
        val backing = InMemoryPreferencesStore()
        var writes = 0
        val prefs = object : PreferencesStore by backing {
            override suspend fun putInt(key: String, value: Int) {
                writes += 1
                if (writes == 1) throw CancellationException("stopped")
                backing.putInt(key, value)
            }
        }
        val nav = coordinator(backgroundScope, prefs)
        nav.selectTab(AppTab.MAP)
        assertEquals(1, writes)
        assertNull(backing.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
        assertEquals(AppTab.MAP.index, nav.selectedTab.value)
    }

    @Test
    fun `a tab write error stops later writes`() = runTest(UnconfinedTestDispatcher()) {
        val thrown = mutableListOf<Throwable>()
        val scope = recordingScope(thrown)
        val backing = InMemoryPreferencesStore()
        var writes = 0
        val prefs = object : PreferencesStore by backing {
            override suspend fun putInt(key: String, value: Int) {
                writes += 1
                if (writes == 1) throw TabPersistProbeError()
                backing.putInt(key, value)
            }
        }
        val nav = coordinator(scope, prefs)
        nav.selectTab(AppTab.MAP)
        assertEquals(1, writes)
        assertIs<TabPersistProbeError>(thrown.single())
        assertNull(backing.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
        assertEquals(AppTab.MAP.index, nav.selectedTab.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun kotlinx.coroutines.test.TestScope.recordingScope(thrown: MutableList<Throwable>): CoroutineScope =
    CoroutineScope(
        SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error ->
            thrown += error
        },
    )

private class TabPersistProbeError : Error("probe")

private fun coordinator(scope: kotlinx.coroutines.CoroutineScope, prefs: PreferencesStore? = null) =
    NavigationCoordinator(scope, prefs)
