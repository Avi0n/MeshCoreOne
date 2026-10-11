// PortedFrom: MC1Tests/AppState/NavigationCoordinatorTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/AppState/NavigationStateTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/SidebarNavigationLayoutTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation.cases

import com.meshcoreone.android.app.navigation.*
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

// Native adaptation: Same assertion body is compiled for instrumented and declared local native runners.
abstract class OriginalNavigationCases {
    protected val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    protected val messageId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    protected fun contact(name: String = "TestContact") = ContactDTO(
        id = UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)),
        radioId = radio, publicKey = Bytes(ByteArray(32) { 0xAA.toByte() }),
        name = name, typeRawValue = 1u, outPathLength = 0u, lastHeardTimestamp = null,
    )
    protected fun channel(index: UByte = 0u) = ChannelDTO(
        id = UUID.fromString("00000000-0000-0000-0000-000000000003"),
        radioId = radio, index = index, name = "TestChannel",
    )
    protected fun room() = RemoteNodeSessionDTO(
        id = UUID.fromString("00000000-0000-0000-0000-000000000004"),
        radioId = radio, publicKey = Bytes(ByteArray(32) { 0xBB.toByte() }),
        name = "TestRoom", role = RemoteNodeRole.ROOM_SERVER,
    )

    protected abstract fun lookup(
        contact: ContactDTO? = contact(), channel: ChannelDTO? = channel(), room: RemoteNodeSessionDTO? = room(),
    ): NavigationLookup

    private fun assertChat(n: NavigationCoordinator, c: ContactDTO) {
        assertEquals(c.id, n.state.value.pendingChatContact?.id)
        assertEquals(ChatSelection.Direct(c), n.state.value.chatsSelectedRoute)
        assertEquals(AppTab.CHATS, n.state.value.selectedTab)
    }

    @Test fun dmNotificationTapNavigatesToChatWithContact() = runBlocking {
        val c = contact(); val n = NavigationCoordinator()
        NotificationFixture(this, radio, n, lookup()).use {
            requireNotNull(it.service.onNotificationTapped)(EntityKey(radio, c.id))
            assertEquals(listOf(NavigationOutcome.Navigated), it.outcomes)
        }
        assertChat(n, c)
    }

    @Test fun newContactNotificationWithManualAddContactsNavigatesToDiscovery() = runBlocking {
        val n = NavigationCoordinator()
        val device = DeviceDTO(radioId = radio, publicKey = contact().publicKey, nodeName = "Fixture", manualAddContacts = true)
        NotificationFixture(this, radio, n, lookup(), { device }).use {
            requireNotNull(it.service.onNewContactNotificationTapped)(EntityKey(radio, contact().id))
            assertEquals(listOf(NavigationOutcome.Navigated), it.outcomes)
        }
        assertTrue(n.state.value.pendingDiscoveryNavigation)
        assertEquals(AppTab.NODES, n.state.value.selectedTab)
    }

    @Test fun newContactNotificationWithoutManualAddContactsNavigatesToContactDetail() = runBlocking {
        val n = NavigationCoordinator(); val c = contact()
        val device = DeviceDTO(radioId = radio, publicKey = c.publicKey, nodeName = "Fixture", manualAddContacts = false)
        NotificationFixture(this, radio, n, lookup(), { device }).use {
            requireNotNull(it.service.onNewContactNotificationTapped)(EntityKey(radio, c.id))
            assertEquals(listOf(NavigationOutcome.Navigated), it.outcomes)
        }
        assertEquals(c.id, n.state.value.pendingContactDetail?.id)
        assertEquals(AppTab.NODES, n.state.value.selectedTab)
    }

    @Test fun channelNotificationTapNavigatesToChannel() = runBlocking {
        val n = NavigationCoordinator(); val c = channel(3u)
        NotificationFixture(this, radio, n, lookup(channel = c)).use {
            requireNotNull(it.service.onChannelNotificationTapped)(radio, c.index)
            assertEquals(listOf(NavigationOutcome.Navigated), it.outcomes)
        }
        assertEquals(c, n.state.value.pendingChannel)
        assertEquals(ChatSelection.Channel(c), n.state.value.chatsSelectedRoute)
        assertEquals(AppTab.CHATS, n.state.value.selectedTab)
    }

    @Test fun reactionNotificationOnDmNavigatesToChatWithScrollToMessageId() = runBlocking {
        val n = NavigationCoordinator(); val c = contact()
        NotificationFixture(this, radio, n, lookup()).use {
            requireNotNull(it.service.onReactionNotificationTapped)(EntityKey(radio, c.id), null, null, messageId)
            assertEquals(listOf(NavigationOutcome.Navigated), it.outcomes)
        }
        assertChat(n, c); assertEquals(messageId, n.state.value.pendingScrollToMessageID)
    }

    @Test fun reactionNotificationOnChannelNavigatesToChannelWithScrollToMessageId() = runBlocking {
        val n = NavigationCoordinator(); val c = channel(1u)
        NotificationFixture(this, radio, n, lookup(channel = c)).use {
            requireNotNull(it.service.onReactionNotificationTapped)(null, c.index, radio, messageId)
            assertEquals(listOf(NavigationOutcome.Navigated), it.outcomes)
        }
        assertEquals(c, n.state.value.pendingChannel)
        assertEquals(ChatSelection.Channel(c), n.state.value.chatsSelectedRoute)
        assertEquals(messageId, n.state.value.pendingScrollToMessageID)
    }

    @Test fun navigateToMapSetsPendingMapFocusAndSelectsTheMapTab() {
        val n = NavigationCoordinator(); n.navigateToMap(37.3349, -122.00902)
        assertEquals(MapFocusRequest(37.3349, -122.00902), n.state.value.pendingMapFocus)
        assertEquals(37.3349, requireNotNull(n.state.value.pendingMapFocus).coordinate.latitude, 0.0)
        assertEquals(AppTab.MAP, n.state.value.selectedTab)
    }

    @Test fun chatConsumerForwardsMapCoordinateToNavigationSink() {
        val n = NavigationCoordinator()
        val consumer = MapNavigationConsumer()
        consumer.onNavigateToMap = { n.navigateToMap(it.latitude, it.longitude) }
        assertEquals(MapNavigationOutcome.FORWARDED, consumer.navigateToMap(MapFocusRequest(51.5074, -0.1278)))
        assertEquals(MapFocusRequest(51.5074, -0.1278), n.state.value.pendingMapFocus)
        assertEquals(AppTab.MAP, n.state.value.selectedTab)
    }

    @Test fun clearPendingMapFocusResetsThePendingFocus() {
        val n = NavigationCoordinator(); n.navigateToMap(1.0, 2.0); n.clearPendingMapFocus()
        assertNull(n.state.value.pendingMapFocus)
    }

    @Test fun navigateToSettingSetsSelectedSettingAndSelectsTheSettingsTab() {
        val n = NavigationCoordinator(); n.navigateToSetting(SettingsDetail.SUPPORT)
        assertEquals(SettingsDetail.SUPPORT, n.state.value.selectedSetting)
        assertEquals(AppTab.SETTINGS, n.state.value.selectedTab)
    }

    @Test fun pendingContactLinkStartsNilAndClearsViaHelper() {
        val n = NavigationCoordinator(); assertNull(n.state.value.pendingContactLink)
        n.stageContactLink(ContactLinkRequest("Alice", Bytes(ByteArray(32) { 0xAB.toByte() }), ContactType.CHAT))
        assertNotNull(n.state.value.pendingContactLink); n.clearPendingContactLink(); assertNull(n.state.value.pendingContactLink)
    }

    @Test fun pendingChannelLinkStartsNilAndClearsViaHelper() {
        val n = NavigationCoordinator(); assertNull(n.state.value.pendingChannelLink)
        n.stageChannelLink(ChannelLinkRequest("general", Bytes(ByteArray(16) { 0xCC.toByte() })))
        assertNotNull(n.state.value.pendingChannelLink); n.clearPendingChannelLink(); assertNull(n.state.value.pendingChannelLink)
    }

    @Test fun pendingHashtagStartsNilAndClearsViaHelper() {
        val n = NavigationCoordinator(); assertNull(n.state.value.pendingHashtag)
        n.stageHashtag(HashtagJoinRequest("#general")); assertNotNull(n.state.value.pendingHashtag)
        n.clearPendingHashtag(); assertNull(n.state.value.pendingHashtag)
    }

    @Test fun clearPendingLinksClearsTheHoistedNodesSelectedContact() {
        val n = NavigationCoordinator(); n.navigateToContactDetail(contact()); assertNotNull(n.state.value.selectedContact)
        n.clearPendingLinks(); assertNull(n.state.value.selectedContact)
    }

    @Test fun clearPendingLinksClearsTheHoistedNodesDiscoveryFlag() {
        val n = NavigationCoordinator(); n.navigateToDiscovery(); n.clearPendingLinks()
        assertFalse(n.state.value.nodesShowingDiscovery)
    }

    @Test fun clearPendingLinksClearsEveryStagedPerRadioFieldAtOnce() {
        val n = NavigationCoordinator()
        n.stageContactLink(ContactLinkRequest("Alice", contact().publicKey, ContactType.CHAT))
        n.stageChannelLink(ChannelLinkRequest("general", channel().secret))
        n.stageHashtag(HashtagJoinRequest("#general"))
        n.navigateToContactDetail(contact()); n.navigateToDiscovery(); n.navigateToChat(contact())
        n.navigateToTool(ToolSelection.CLI); n.clearPendingLinks()
        val s = n.state.value
        assertNull(s.pendingContactLink); assertNull(s.pendingChannelLink); assertNull(s.pendingHashtag)
        assertNull(s.selectedContact); assertFalse(s.nodesShowingDiscovery); assertNull(s.chatsSelectedRoute)
        assertNull(s.selectedTool)
    }

    @Test fun clearPerRadioSelectionClearsTheHoistedChatsRoute() {
        val n = NavigationCoordinator(); n.navigateToChat(contact()); n.clearPerRadioSelection()
        assertNull(n.state.value.chatsSelectedRoute)
    }

    @Test fun clearPerRadioSelectionClearsARadioRequiringTool() {
        val n = NavigationCoordinator(); n.navigateToTool(ToolSelection.CLI)
        assertTrue(requireNotNull(n.state.value.selectedTool).requiresRadio)
        n.clearPerRadioSelection(); assertNull(n.state.value.selectedTool)
    }

    @Test fun clearPerRadioSelectionPreservesTheOfflineLineOfSightTool() {
        val n = NavigationCoordinator(); n.navigateToTool(ToolSelection.LINE_OF_SIGHT)
        assertFalse(requireNotNull(n.state.value.selectedTool).requiresRadio)
        n.clearPerRadioSelection(); assertEquals(ToolSelection.LINE_OF_SIGHT, n.state.value.selectedTool)
    }

    @Test fun clearPerRadioSelectionClearsAPerDeviceSettingsPage() {
        val n = NavigationCoordinator(); n.navigateToSetting(SettingsDetail.RADIO)
        assertTrue(requireNotNull(n.state.value.selectedSetting).requiresDevice)
        n.clearPerRadioSelection(); assertNull(n.state.value.selectedSetting)
    }

    @Test fun clearPerRadioSelectionPreservesADeviceIndependentSettingsPage() {
        val n = NavigationCoordinator(); n.navigateToSetting(SettingsDetail.APPEARANCE)
        assertFalse(requireNotNull(n.state.value.selectedSetting).requiresDevice)
        n.clearPerRadioSelection(); assertEquals(SettingsDetail.APPEARANCE, n.state.value.selectedSetting)
    }

    @Test fun languageSettingsPageIsDeviceIndependent() {
        val n = NavigationCoordinator(); n.navigateToSetting(SettingsDetail.LANGUAGE)
        assertFalse(requireNotNull(n.state.value.selectedSetting).requiresDevice)
        n.clearPerDeviceSelection(); assertEquals(SettingsDetail.LANGUAGE, n.state.value.selectedSetting)
    }

    @Test fun defaultNavigationStateIsTabZeroWithNoPendingNavigation() {
        val s = NavigationCoordinator().state.value
        assertEquals(0, s.selectedTab.sourceIndex)
        assertNull(s.pendingChatContact); assertNull(s.pendingChannel); assertNull(s.pendingRoomSession)
        assertNull(s.pendingRoomAuthentication); assertFalse(s.pendingDiscoveryNavigation)
        assertNull(s.pendingContactDetail); assertNull(s.pendingScrollToMessageID); assertNull(s.chatsSelectedRoute)
        assertTrue(s.tabBarVisible)
    }

    @Test fun navigateToChatSetsContactRouteAndTab() {
        val n = NavigationCoordinator(); val c = contact(); n.navigateToChat(c)
        assertChat(n, c); assertFalse(n.state.value.tabBarVisible); assertNull(n.state.value.pendingScrollToMessageID)
    }

    @Test fun navigateToChatWithScrollToMessageIdSetsMessageId() {
        val n = NavigationCoordinator(); val c = contact(); n.navigateToChat(c, messageId)
        assertChat(n, c); assertEquals(messageId, n.state.value.pendingScrollToMessageID)
    }

    @Test fun navigateToChatSwitchesToChatsTabFromAnotherTab() {
        val n = NavigationCoordinator(); n.selectTab(AppTab.TOOLS); val c = contact(); n.navigateToChat(c)
        assertChat(n, c)
    }

    @Test fun navigateToRoomSetsSessionRouteAndTab() {
        val n = NavigationCoordinator(); val r = room(); n.navigateToRoom(r)
        assertEquals(r, n.state.value.pendingRoomSession); assertEquals(ChatSelection.Room(r), n.state.value.chatsSelectedRoute)
        assertEquals(AppTab.CHATS, n.state.value.selectedTab); assertFalse(n.state.value.tabBarVisible)
    }

    @Test fun navigateToChannelSetsChannelRouteAndTab() {
        val n = NavigationCoordinator(); val c = channel(); n.navigateToChannel(c)
        assertEquals(c, n.state.value.pendingChannel); assertEquals(ChatSelection.Channel(c), n.state.value.chatsSelectedRoute)
        assertEquals(AppTab.CHATS, n.state.value.selectedTab); assertFalse(n.state.value.tabBarVisible)
        assertNull(n.state.value.pendingScrollToMessageID)
    }

    @Test fun navigateToChannelWithScrollToMessageIdSetsMessageId() {
        val n = NavigationCoordinator(); val c = channel(); n.navigateToChannel(c, messageId)
        assertEquals(c, n.state.value.pendingChannel); assertEquals(messageId, n.state.value.pendingScrollToMessageID)
    }

    @Test fun navigateToDiscoverySetsPendingFlagAndContactsTab() {
        val n = NavigationCoordinator(); n.navigateToDiscovery()
        assertTrue(n.state.value.pendingDiscoveryNavigation); assertEquals(AppTab.NODES, n.state.value.selectedTab)
    }

    @Test fun navigateToDiscoveryDoesNotHideTabBar() {
        val n = NavigationCoordinator(); n.navigateToDiscovery(); assertTrue(n.state.value.tabBarVisible)
    }

    @Test fun navigateToContactsSwitchesToContactsTab() {
        val n = NavigationCoordinator(); n.selectTab(AppTab.TOOLS); n.navigateToContacts()
        assertEquals(AppTab.NODES, n.state.value.selectedTab)
    }

    @Test fun navigateToContactDetailSetsContactAndContactsTab() {
        val n = NavigationCoordinator(); val c = contact(); n.navigateToContactDetail(c)
        assertEquals(c, n.state.value.pendingContactDetail); assertEquals(AppTab.NODES, n.state.value.selectedTab)
    }

    @Test fun clearPendingNavigationClearsChatContact() {
        val n = NavigationCoordinator(); n.navigateToChat(contact()); n.clearPendingNavigation()
        assertNull(n.state.value.pendingChatContact)
    }

    @Test fun clearPendingRoomNavigationClearsRoomSession() {
        val n = NavigationCoordinator(); n.navigateToRoom(room()); n.clearPendingRoomNavigation()
        assertNull(n.state.value.pendingRoomSession)
    }

    @Test fun clearPendingRoomAuthenticationClearsRoomAuthSession() {
        val n = NavigationCoordinator(NavigationState(pendingRoomAuthentication = room()))
        n.clearPendingRoomAuthentication(); assertNull(n.state.value.pendingRoomAuthentication)
    }

    @Test fun clearPendingChannelNavigationClearsChannel() {
        val n = NavigationCoordinator(); n.navigateToChannel(channel()); n.clearPendingChannelNavigation()
        assertNull(n.state.value.pendingChannel)
    }

    @Test fun clearPendingDiscoveryNavigationClearsDiscoveryFlag() {
        val n = NavigationCoordinator(); n.navigateToDiscovery(); n.clearPendingDiscoveryNavigation()
        assertFalse(n.state.value.pendingDiscoveryNavigation)
    }

    @Test fun clearPendingScrollToMessageClearsMessageId() {
        val n = NavigationCoordinator(); n.navigateToChat(contact(), messageId); n.clearPendingScrollToMessage()
        assertNull(n.state.value.pendingScrollToMessageID)
    }

    @Test fun clearPendingContactDetailNavigationClearsContactDetail() {
        val n = NavigationCoordinator(); n.navigateToContactDetail(contact()); n.clearPendingContactDetailNavigation()
        assertNull(n.state.value.pendingContactDetail)
    }

    @Test fun navigateToChatFromContactsTabHidesTabBarAndSwitchesTab() {
        val n = NavigationCoordinator(); n.selectTab(AppTab.NODES); val c = contact(); n.navigateToChat(c)
        assertChat(n, c); assertFalse(n.state.value.tabBarVisible)
    }

    @Test fun multipleNavigationCallsOverwritePendingState() {
        val n = NavigationCoordinator(); n.navigateToChat(contact("First")); val c = contact("Second"); n.navigateToChat(c)
        assertEquals(c, n.state.value.pendingChatContact); assertEquals(ChatSelection.Direct(c), n.state.value.chatsSelectedRoute)
    }

    @Test fun deviceMenuTipDonationIsPendingByDefaultWhenFalse() {
        assertFalse(NavigationCoordinator().state.value.pendingDeviceMenuTipDonation)
    }

    @Test fun sectionSidebarIsNarrowEnoughThatElevenInchPortraitTilesAllThreeColumns() {
        assertTrue(NavigationLayout.TILE_MIN_WIDTH_DP <= 834)
        assertTrue(NavigationLayout.tilesListDetail(834f))
    }

    @Test fun iPadMiniPortraitIntentionallyCollapsesRatherThanTiling() {
        assertTrue(NavigationLayout.TILE_MIN_WIDTH_DP > 744)
        assertFalse(NavigationLayout.tilesListDetail(744f))
    }

    @Test fun sidebarWidthIsInIconOnlyRangeNotAFullSidebar() {
        assertTrue(NavigationLayout.RAIL_WIDTH_DP <= 115)
    }

    @Test fun aSidebarCollapsingToolCollapsesTheSidebarEvenWhenTheContainerIsWide() {
        assertFalse(NavigationLayout.showsRail(834f, ToolSelection.LINE_OF_SIGHT))
        assertFalse(NavigationLayout.showsRail(834f, ToolSelection.TRACE_PATH))
    }

    @Test fun aWideContainerTilesTheSidebarWhenNoSidebarCollapsingToolIsOpen() {
        assertTrue(NavigationLayout.showsRail(834f, null))
        assertTrue(NavigationLayout.tilesListDetail(834f))
    }

    @Test fun aNarrowContainerCollapsesToTheSectionsHiddenShape() {
        assertFalse(NavigationLayout.tilesListDetail(744f))
        assertTrue(NavigationLayout.usesRail(744f))
        assertTrue(NavigationLayout.showsRail(744f, null))
        assertFalse(NavigationLayout.showsRail(744f, ToolSelection.LINE_OF_SIGHT))
        assertFalse(NavigationLayout.showsRail(744f, ToolSelection.TRACE_PATH))
        assertFalse(NavigationLayout.usesRail(599f))
        assertTrue(NavigationLayout.usesRail(600f))
    }

    @Test fun lineOfSightAndTracePathCollapseSidebarOtherToolsKeepIt() {
        assertTrue(ToolSelection.LINE_OF_SIGHT.prefersCollapsedSidebar)
        assertTrue(ToolSelection.TRACE_PATH.prefersCollapsedSidebar)
        ToolSelection.entries.filter { it != ToolSelection.LINE_OF_SIGHT && it != ToolSelection.TRACE_PATH }.forEach {
            assertFalse(it.prefersCollapsedSidebar)
            assertTrue(NavigationLayout.showsRail(834f, it))
        }
    }
}
