package one.meshcore.shared.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.toUppercaseHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deep-link-to-route integration: parser → pure `ChatLinkRouter` match rules → coordinator mutation,
 * mirroring the iOS `ChatLinkRouter`. Covers the map/contact/channel dispatch, the open-existing vs
 * stage-confirmation branch, the add-yourself no-op, and `routeExternalOpen`'s switch-to-Chats-then-
 * restore behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeepLinkRouterTest {
    private val ownKey = ByteArray(32) { 0x11 }
    private val strangerKeyHex = ByteArray(32) { 0x22 }.toUppercaseHex()

    private fun contactUrl(keyByte: Int): String {
        val keyHex = ByteArray(32) { keyByte.toByte() }.toUppercaseHex()
        return "meshcore://contact/add?name=Stranger&public_key=$keyHex&type=1"
    }

    private fun channelUrl(secretByte: Int): String {
        val secretHex = ByteArray(16) { secretByte.toByte() }.toUppercaseHex()
        return "meshcore://channel/add?name=General&secret=$secretHex"
    }

    private class Lookups(
        val own: ByteArray? = null,
        val contacts: Set<String> = emptySet(),
        val channels: Set<String> = emptySet(),
        val channelIdsByName: Map<String, String> = emptyMap(),
    ) : DeepLinkLookups {
        override suspend fun ownPublicKey(): ByteArray? = own
        override suspend fun existingContactKeyHexes(): Set<String> = contacts
        override suspend fun existingChannelSecretHexes(): Set<String> = channels
        override suspend fun channelIdForName(fullChannelName: String): String? = channelIdsByName[fullChannelName]
    }

    @Test
    fun `a map link centers the Map tab`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val router = DeepLinkRouter(nav, Lookups())
        assertTrue(router.route("meshcore://map?lat=12.5&lon=-40.25"))
        assertEquals(AppTab.MAP.index, nav.selectedTab.value)
        assertEquals(MapFocusRequest(12.5, -40.25), nav.pendingMapFocus.value)
    }

    @Test
    fun `a contact link for a new key stages the add-contact confirmation`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val router = DeepLinkRouter(nav, Lookups(own = ownKey))
        assertTrue(router.route(contactUrl(0x22)))
        assertEquals(strangerKeyHex, nav.pendingContactLink.value?.publicKey?.toUppercaseHex())
    }

    @Test
    fun `a contact link for an existing key opens the contact detail`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val router = DeepLinkRouter(nav, Lookups(own = ownKey, contacts = setOf(strangerKeyHex)))
        assertTrue(router.route(contactUrl(0x22)))
        assertNull(nav.pendingContactLink.value)
        assertEquals(AppTab.NODES.index, nav.selectedTab.value)
        assertEquals(strangerKeyHex, nav.selectedContactId.value)
    }

    @Test
    fun `a contact link for the radio's own key is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val router = DeepLinkRouter(nav, Lookups(own = ownKey))
        assertTrue(router.route(contactUrl(0x11))) // claimed by the router
        assertNull(nav.pendingContactLink.value) // but adds nothing
        assertNull(nav.selectedContactId.value)
    }

    @Test
    fun `a channel link for a new secret stages the join confirmation`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val router = DeepLinkRouter(nav, Lookups())
        assertTrue(router.route(channelUrl(0x33)))
        assertEquals("General", nav.pendingChannelLink.value?.name)
    }

    @Test
    fun `a channel link for an existing secret opens the channel`() = runTest(UnconfinedTestDispatcher()) {
        val secretHex = ByteArray(16) { 0x33 }.toUppercaseHex()
        val nav = NavigationCoordinator(backgroundScope)
        val router = DeepLinkRouter(nav, Lookups(channels = setOf(secretHex)))
        assertTrue(router.route(channelUrl(0x33)))
        assertNull(nav.pendingChannelLink.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertEquals(ChatRoute.Channel(secretHex), nav.chatsSelectedRoute.value)
    }

    @Test
    fun `an unparseable meshcore url is not claimed`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val router = DeepLinkRouter(nav, Lookups())
        assertFalse(router.route("meshcore://map?lat=999&lon=0")) // out of range → parser returns null
    }

    @Test
    fun `routeExternalOpen switches to Chats before staging a confirmation`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        nav.selectTab(AppTab.SETTINGS)
        val router = DeepLinkRouter(nav, Lookups(own = ownKey))
        assertTrue(router.routeExternalOpen(contactUrl(0x22)))
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertEquals(strangerKeyHex, nav.pendingContactLink.value?.publicKey?.toUppercaseHex())
    }

    @Test
    fun `routeExternalOpen restores the previous tab when nothing is handled`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        nav.selectTab(AppTab.SETTINGS)
        val router = DeepLinkRouter(nav, Lookups())
        assertFalse(router.routeExternalOpen("meshcore://map?lat=999&lon=0"))
        assertEquals(AppTab.SETTINGS.index, nav.selectedTab.value)
    }

    @Test
    fun `a hashtag link for a joined channel opens it`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val router = DeepLinkRouter(nav, Lookups(channelIdsByName = mapOf("#wardriving" to CHANNEL_ROW_ID)))
        assertTrue(router.route("meshcoreone://hashtag/wardriving"))
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        assertEquals(ChatRoute.Channel(CHANNEL_ROW_ID), nav.chatsSelectedRoute.value)
        assertNull(nav.pendingHashtagJoin.value)
    }

    @Test
    fun `a hashtag link for an unjoined channel stages the join confirmation`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val router = DeepLinkRouter(nav, Lookups())
        assertTrue(router.route("meshcoreone://hashtag/wardriving"))
        assertEquals("wardriving", nav.pendingHashtagJoin.value)
        assertNull(nav.chatsSelectedRoute.value)
    }

    @Test
    fun `routeExternalOpen claims a hashtag link instead of restoring the previous tab`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = NavigationCoordinator(backgroundScope)
            nav.selectTab(AppTab.SETTINGS)
            val router = DeepLinkRouter(nav, Lookups())
            assertTrue(router.routeExternalOpen("meshcoreone://hashtag/wardriving"))
            assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        }

    @Test
    fun `the launcher shortcut urls select their places in the app`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val router = DeepLinkRouter(nav, Lookups())

        nav.selectTab(AppTab.SETTINGS)
        assertTrue(router.route("meshcore://chats"))
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)

        assertTrue(router.route("meshcore://map"))
        assertEquals(AppTab.MAP.index, nav.selectedTab.value)
        assertNull(nav.pendingMapFocus.value) // a bare map shortcut drops no pin

        assertTrue(router.route("meshcore://status"))
        assertEquals(AppTab.SETTINGS.index, nav.selectedTab.value)
        assertEquals(SettingsDestination.DEVICE_INFO, nav.selectedSetting.value)
    }

    @Test
    fun `a map link with coordinates still focuses the map rather than the bare shortcut`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = NavigationCoordinator(backgroundScope)
            val router = DeepLinkRouter(nav, Lookups())
            assertTrue(router.route("meshcore://map?lat=1.5&lon=2.5"))
            assertEquals(MapFocusRequest(1.5, 2.5), nav.pendingMapFocus.value)
        }

    @Test
    fun `a send url stages the shortcut send`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val router = DeepLinkRouter(nav, Lookups())
        assertTrue(
            router.route(
                "meshcore://send?kind=channel&id=B0000000-0000-0000-0000-000000000002&text=status",
            ),
        )
        val staged = assertNotNull(nav.pendingSendMessage.value)
        assertEquals(MeshCoreDeepLink.SendMessage.Kind.CHANNEL, staged.kind)
        assertEquals("status", staged.text)
    }

    @Test
    fun `an advert url stages the shortcut advert`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val router = DeepLinkRouter(nav, Lookups())
        assertTrue(router.route("meshcore://advert?reach=flood"))
        val staged = assertNotNull(nav.pendingSendAdvert.value)
        assertEquals(true, staged.flood)
    }

    @Test
    fun `an unknown meshcore host is not claimed`() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        val router = DeepLinkRouter(nav, Lookups())
        assertFalse(router.route("meshcore://garbage"))
    }

    private companion object {
        const val CHANNEL_ROW_ID = "channel-row-1"
    }
}
