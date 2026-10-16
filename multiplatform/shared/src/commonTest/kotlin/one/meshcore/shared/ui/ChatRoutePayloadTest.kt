package one.meshcore.shared.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.Conversation
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.navigation.NavKey
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.backStackOf
import one.meshcore.shared.navigation.clearPendingRoomAuth
import one.meshcore.shared.navigation.navigateToChannel
import one.meshcore.shared.navigation.navigateToChat
import one.meshcore.shared.navigation.navigateToContactDetailFromChat
import one.meshcore.shared.navigation.navigateToRoom
import one.meshcore.shared.navigation.navigationIdentity
import one.meshcore.shared.navigation.pop
import one.meshcore.shared.navigation.push
import one.meshcore.shared.navigation.reconcileSelectedChatRoute
import one.meshcore.shared.navigation.selectTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRoutePayloadTest {
    @Test
    fun matchingContactRefreshesTheOpenRouteTitle() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val contact = ChatFixtures.contact("Renamed", id = ChatFixtures.CONTACT, lastMessageDate = 1.0)
        nav.navigateToChat(contact.id.value, displayTitle = "Old name")
        val conversations = listOf(Conversation.Direct(contact))
        nav.reconcileSelectedChatRoute(setOf(contact.id)) { it.refreshedPayload(conversations) }
        assertEquals("Renamed", nav.chatsSelectedRoute.value?.displayTitle)
    }

    @Test
    fun matchedDisconnectedRoomClosesSelectionAndStagesAuthentication() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val room = ChatFixtures.room("Room")
        nav.navigateToRoom(room.id.value, displayTitle = room.name)
        val conversations = listOf(Conversation.Room(room.copy(isConnected = false)))
        nav.reconcileSelectedChatRoute(emptySet()) { it.refreshedPayload(conversations) }
        assertEquals(room.id.value, nav.pendingRoomAuth.value)
        assertNull(nav.chatsSelectedRoute.value)
        assertTrue(nav.backStackOf(AppTab.CHATS).isEmpty())
    }

    @Test
    fun refreshUnderContactInfoKeepsStackDepthAndPendingNotificationIdentity() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val contact = ChatFixtures.contact("Updated", id = ChatFixtures.CONTACT, lastMessageDate = 1.0)
        val messageID = ChatFixtures.freshUuid()
        nav.navigateToChat(contact.id.value, scrollToMessageId = messageID.value, displayTitle = "Old")
        val request = nav.pendingScrollTarget.value
        nav.navigateToContactDetailFromChat(contact.id.value)
        val depth = nav.backStackOf(AppTab.CHATS).size
        nav.selectTab(AppTab.NODES)
        nav.reconcileSelectedChatRoute(setOf(contact.id)) { it.refreshedPayload(listOf(Conversation.Direct(contact))) }
        assertEquals(AppTab.NODES.index, nav.selectedTab.value)
        assertSame(request, nav.pendingScrollTarget.value)
        assertEquals(depth, nav.backStackOf(AppTab.CHATS).size)
        assertEquals("Updated", (nav.backStackOf(AppTab.CHATS).first() as NavKey.ChatDetail).route.displayTitle)
        nav.pop(AppTab.CHATS)
        assertEquals("Updated", nav.chatsSelectedRoute.value?.displayTitle)
    }

    @Test
    fun channelRenamePreservesItsIdentityAndScrollRequest() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val channel = ChatFixtures.channel("Updated")
        nav.navigateToChannel(
            channel.id.value,
            scrollToMessageId = ChatFixtures.freshUuid().value,
            displayTitle = "Old",
        )
        val request = nav.pendingScrollTarget.value
        nav.reconcileSelectedChatRoute(emptySet()) { it.refreshedPayload(listOf(Conversation.Channel(channel))) }
        assertSame(request, nav.pendingScrollTarget.value)
        assertEquals(ChatRoute.Channel(channel.id.value, "Updated"), nav.chatsSelectedRoute.value)
        assertEquals(1, nav.backStackOf(AppTab.CHATS).size)
    }

    @Test
    fun snapshotRoomAuthenticationKeepsTheCurrentTabAndClearsAllChatsHistory() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val room = ChatFixtures.room("Room")
        nav.navigateToChat(ChatFixtures.CONTACT.value)
        nav.navigateToRoom(room.id.value)
        nav.selectTab(AppTab.NODES)
        nav.reconcileSelectedChatRoute(emptySet()) { it.refreshedPayload(listOf(Conversation.Room(room))) }
        assertEquals(AppTab.NODES.index, nav.selectedTab.value)
        assertEquals(room.id.value, nav.pendingRoomAuth.value)
        assertTrue(nav.backStackOf(AppTab.CHATS).isEmpty())
        assertNull(nav.chatsSelectedRoute.value)
        nav.clearPendingRoomAuth()
        nav.reconcileSelectedChatRoute(emptySet()) { it.refreshedPayload(listOf(Conversation.Room(room))) }
        assertNull(nav.pendingRoomAuth.value)
    }

    @Test
    fun explicitDisconnectedRoomTapSelectsChatsAndDoesNotRestoreAnOlderChat() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        nav.navigateToChat(ChatFixtures.CONTACT.value)
        nav.selectTab(AppTab.NODES)
        nav.navigateToRoom("room", isConnected = false)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertEquals("room", nav.pendingRoomAuth.value)
        assertNull(nav.chatsSelectedRoute.value)
        assertTrue(nav.backStackOf(AppTab.CHATS).isEmpty())
    }

    @Test
    fun missingRoomRowClosesWithoutInventingADisconnection() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        nav.navigateToRoom("room")
        nav.reconcileSelectedChatRoute(null) { null }
        assertNull(nav.chatsSelectedRoute.value)
        assertNull(nav.pendingRoomAuth.value)
    }

    @Test
    fun alreadyDisconnectedRoomPayloadDoesNotTriggerAuthenticationAgain() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val session = ChatFixtures.room("Room")
        val route = ChatRoute.Room(session.id.value, session.name, session)
        nav.chatsRouteState.value = route
        nav.push(AppTab.CHATS, NavKey.ChatDetail(route))
        nav.reconcileSelectedChatRoute(emptySet()) { it.refreshedPayload(listOf(Conversation.Room(session))) }
        assertNull(nav.pendingRoomAuth.value)
        assertEquals(route, nav.chatsSelectedRoute.value)
    }

    @Test
    fun connectedRoomPayloadRefreshesPermissionsWithoutAffectingNavigationIdentity() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = NavigationCoordinator(backgroundScope)
            val session = ChatFixtures.room("Room").copy(isConnected = true)
            nav.navigateToRoom(session.id.value)
            nav.reconcileSelectedChatRoute(emptySet()) { it.refreshedPayload(listOf(Conversation.Room(session))) }
            val updated = session.copy(
                name = "Updated",
                permissionLevel = one.meshcore.shared.model.RoomPermissionLevel.READ_WRITE,
            )
            nav.reconcileSelectedChatRoute(emptySet()) { it.refreshedPayload(listOf(Conversation.Room(updated))) }
            val route = nav.chatsSelectedRoute.value as ChatRoute.Room
            assertEquals(updated, route.sessionSnapshot)
            assertEquals("Updated", route.displayTitle)
            assertEquals(ChatRoute.Room(session.id.value), route.navigationIdentity())
            assertNull(nav.pendingRoomAuth.value)
        }

    @Test
    fun reopeningARefreshedRoomDoesNotPushTheSameDestinationAgain() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val session = ChatFixtures.room("Room").copy(isConnected = true)
        nav.navigateToRoom(session.id.value)
        nav.reconcileSelectedChatRoute(emptySet()) { it.refreshedPayload(listOf(Conversation.Room(session))) }
        nav.navigateToRoom(session.id.value)
        assertEquals(1, nav.backStackOf(AppTab.CHATS).size)
        assertEquals(session.id.value, (nav.chatsSelectedRoute.value as ChatRoute.Room).sessionId)
    }
}
