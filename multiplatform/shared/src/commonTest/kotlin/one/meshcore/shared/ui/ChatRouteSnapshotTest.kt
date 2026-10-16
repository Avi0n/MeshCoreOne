package one.meshcore.shared.ui

import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.Conversation
import one.meshcore.shared.navigation.ChatRoute
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChatRouteSnapshotTest {
    @Test
    fun aDeletedDirectConversationIsMissingFromTheSnapshot() {
        val alice = Conversation.Direct(ChatFixtures.contact(name = "Alice", id = ChatFixtures.CONTACT))
        val route = ChatRoute.Direct(ChatFixtures.CONTACT.value)
        assertNotNull(route.refreshedPayload(listOf(alice)))
        assertNull(route.refreshedPayload(emptyList()))
    }
}
