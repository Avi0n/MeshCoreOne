package one.meshcore.shared.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationNodeRemovalTest {
    @Test
    fun matchingRemovalClearsNodesHistoryAndFlags() = runTest {
        val navigation = NavigationCoordinator(backgroundScope)
        navigation.navigateToBlockedContacts()
        navigation.navigateToContactDetail(CONTACT)
        navigation.clearSelectedContactIfMatching(CONTACT.lowercase())
        assertNull(navigation.selectedContactId.value)
        assertTrue(navigation.backStackOf(AppTab.NODES).isEmpty())
        assertFalse(navigation.nodesShowingDiscovery.value)
        assertFalse(navigation.nodesShowingBlocked.value)
        assertEquals(AppTab.NODES.index, navigation.selectedTab.value)
    }

    @Test
    fun anUnrelatedRemovalPreservesTheSelectedNodeAndStack() = runTest {
        val navigation = NavigationCoordinator(backgroundScope)
        navigation.navigateToContactDetail(CONTACT)
        val stack = navigation.backStackOf(AppTab.NODES)
        navigation.clearSelectedContactIfMatching("another-contact")
        assertEquals(CONTACT, navigation.selectedContactId.value)
        assertEquals(stack, navigation.backStackOf(AppTab.NODES))
    }

    @Test
    fun inactiveNodesCleanupPreservesChatsAndPendingRequests() = runTest {
        val navigation = NavigationCoordinator(backgroundScope)
        navigation.navigateToContactDetail(CONTACT)
        navigation.navigateToChat("chat", scrollToMessageId = "message")
        val request = navigation.pendingScrollTarget.value
        val chats = navigation.backStackOf(AppTab.CHATS)
        val route = navigation.chatsSelectedRoute.value
        navigation.clearSelectedContactIfMatching(CONTACT)
        assertNull(navigation.selectedContactId.value)
        assertTrue(navigation.backStackOf(AppTab.NODES).isEmpty())
        assertEquals(AppTab.CHATS.index, navigation.selectedTab.value)
        assertEquals(chats, navigation.backStackOf(AppTab.CHATS))
        assertEquals(route, navigation.chatsSelectedRoute.value)
        assertSame(request, navigation.pendingScrollTarget.value)
        assertFalse(navigation.tabBarVisible.value)
    }

    @Test
    fun noSelectedNodeLeavesDiscoveryAndItsBackPathUntouched() = runTest {
        val navigation = NavigationCoordinator(backgroundScope)
        navigation.navigateToDiscovery()
        navigation.clearSelectedContactIfMatching(CONTACT)
        assertTrue(navigation.nodesShowingDiscovery.value)
        assertEquals(listOf(NavKey.Discovery), navigation.backStackOf(AppTab.NODES))
    }

    private companion object {
        const val CONTACT = "ABC00000-0000-0000-0000-000000000001"
    }
}
