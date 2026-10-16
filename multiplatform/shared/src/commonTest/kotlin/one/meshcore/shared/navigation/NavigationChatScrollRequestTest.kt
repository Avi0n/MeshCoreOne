package one.meshcore.shared.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ui.forConversation
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.ui.coordinatorChatScroll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationChatScrollRequestTest {
    @Test
    fun everyTapHasItsOwnIdentityAndOnlyThatIdentityCanConsume() = runTest {
        val navigation = NavigationCoordinator(backgroundScope)
        navigation.navigateToChat("contact", "m1")
        val first = assertNotNull(navigation.pendingScrollTarget.value)
        navigation.navigateToChat("contact", "m1")
        val repeated = assertNotNull(navigation.pendingScrollTarget.value)
        assertNotEquals(first.requestID, repeated.requestID)
        assertEquals(first.messageID, repeated.messageID)
        assertEquals(Uuid.normalized("m1"), repeated.messageID)
        assertFalse(navigation.consumePendingScrollRequest(first.requestID))
        assertEquals(repeated, navigation.pendingScrollTarget.value)
        assertTrue(navigation.consumePendingScrollRequest(repeated.requestID))
        assertNull(navigation.pendingScrollTarget.value)
    }

    @Test
    fun thePaneMatchesRouteKindAndIdentityBeforeItCanConsume() = runTest {
        val navigation = NavigationCoordinator(backgroundScope)
        val shell = coordinatorChatScroll(navigation)
        val direct = shell.forConversation(ChatRoute.Direct("same-id").conversationId)
        val channel = shell.forConversation(ChatRoute.Channel("same-id").conversationId)
        val other = shell.forConversation(ChatRoute.Direct("other").conversationId)
        navigation.navigateToChat("same-id", "m1", displayTitle = "Title")
        val request = assertNotNull(direct.peek())
        assertNull(channel.peek())
        assertNull(other.peek())
        assertFalse(channel.consume(request.requestID))
        assertFalse(other.consume(request.requestID))
        assertNotNull(navigation.pendingScrollTarget.value)
        navigation.navigateToChannel("same-id", "m2", displayTitle = "New title")
        assertNull(direct.peek())
        assertFalse(direct.consume(request.requestID))
        val replacement = assertNotNull(channel.peek())
        assertTrue(channel.consume(replacement.requestID))
        assertNull(shell.peek())
    }

    @Test
    fun replacingConversationIntentsClearsStaleScrollAndRoomAuth() = runTest {
        val navigation = NavigationCoordinator(backgroundScope)
        navigation.navigateToChat("contact", "m1")
        navigation.navigateToRoom("room", isConnected = false)
        assertNull(navigation.pendingScrollTarget.value)
        assertEquals("room", navigation.pendingRoomAuth.value)
        navigation.navigateToChannel("channel", "m2")
        assertNull(navigation.pendingRoomAuth.value)
        assertNotNull(navigation.pendingScrollTarget.value)
        navigation.navigateToChat("contact")
        assertNull(navigation.pendingScrollTarget.value)
        navigation.navigateToRoom("room", isConnected = false)
        navigation.navigateToNewChat("new-contact")
        assertNull(navigation.pendingRoomAuth.value)
        navigation.navigateToChat("contact", "m3")
        navigation.clearPerRadioSelection()
        assertNull(navigation.pendingScrollTarget.value)
        assertNull(navigation.pendingRoomAuth.value)
    }
}
