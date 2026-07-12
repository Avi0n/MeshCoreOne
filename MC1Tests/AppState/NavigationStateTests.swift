import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Navigation State Tests")
@MainActor
struct NavigationStateTests {
  // MARK: - Test Helpers

  private static func makeContact(
    id: UUID = UUID(),
    name: String = "TestContact"
  ) -> ContactDTO {
    ContactDTO(
      id: id,
      radioID: UUID(),
      publicKey: Data(repeating: 0xAA, count: 32),
      name: name,
      typeRawValue: 0x01,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      unreadMentionCount: 0,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }

  private static func makeChannel(
    id: UUID = UUID(),
    name: String = "TestChannel",
    index: UInt8 = 0
  ) -> ChannelDTO {
    ChannelDTO(
      id: id,
      radioID: UUID(),
      index: index,
      name: name,
      secret: Data(),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0,
      unreadMentionCount: 0,
      notificationLevel: .all,
      isFavorite: false
    )
  }

  private static func makeRoomSession(
    id: UUID = UUID(),
    name: String = "TestRoom"
  ) -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      id: id,
      radioID: UUID(),
      publicKey: Data(repeating: 0xBB, count: 32),
      name: name,
      role: .roomServer,
      latitude: 0,
      longitude: 0,
      isConnected: false,
      permissionLevel: .readWrite,
      lastConnectedDate: nil,
      lastBatteryMillivolts: nil,
      lastUptimeSeconds: nil,
      lastNoiseFloor: nil,
      unreadCount: 0,
      notificationLevel: .all,
      isFavorite: false,
      lastRxAirtimeSeconds: nil,
      neighborCount: 0,
      lastSyncTimestamp: 0,
      lastMessageDate: nil
    )
  }

  // MARK: - Default State

  @Test
  func `Default navigation state is tab 0 with no pending navigation`() {
    let appState = AppState()
    #expect(appState.navigation.selectedTab == 0)
    #expect(appState.navigation.pendingChatContact == nil)
    #expect(appState.navigation.pendingChannel == nil)
    #expect(appState.navigation.pendingRoomSession == nil)
    #expect(appState.navigation.pendingRoomAuthentication == nil)
    #expect(appState.navigation.pendingDiscoveryNavigation == false)
    #expect(appState.navigation.pendingContactDetail == nil)
    #expect(appState.navigation.pendingScrollToMessageID == nil)
    #expect(appState.navigation.chatsSelectedRoute == nil)
    #expect(appState.navigation.tabBarVisibility == .visible)
  }

  // MARK: - navigateToChat

  @Test
  func `navigateToChat sets contact, route, and tab`() {
    let appState = AppState()
    let contact = Self.makeContact()

    appState.navigation.navigateToChat(with: contact)

    #expect(appState.navigation.pendingChatContact == contact)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    #expect(appState.navigation.selectedTab == 0)
    #expect(appState.navigation.tabBarVisibility == .hidden)
    #expect(appState.navigation.pendingScrollToMessageID == nil)
  }

  @Test
  func `navigateToChat with scrollToMessageID sets message ID`() {
    let appState = AppState()
    let contact = Self.makeContact()
    let messageID = UUID()

    appState.navigation.navigateToChat(with: contact, scrollToMessageID: messageID)

    #expect(appState.navigation.pendingChatContact == contact)
    #expect(appState.navigation.pendingScrollToMessageID == messageID)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    #expect(appState.navigation.selectedTab == 0)
  }

  @Test
  func `navigateToChat switches to Chats tab from another tab`() {
    let appState = AppState()
    appState.navigation.selectedTab = 3 // Settings tab
    let contact = Self.makeContact()

    appState.navigation.navigateToChat(with: contact)

    #expect(appState.navigation.selectedTab == 0)
    #expect(appState.navigation.pendingChatContact == contact)
  }

  // MARK: - navigateToRoom

  @Test
  func `navigateToRoom sets session, route, and tab`() {
    let appState = AppState()
    let session = Self.makeRoomSession()

    appState.navigation.navigateToRoom(with: session)

    #expect(appState.navigation.pendingRoomSession == session)
    #expect(appState.navigation.chatsSelectedRoute == .room(session))
    #expect(appState.navigation.selectedTab == 0)
    #expect(appState.navigation.tabBarVisibility == .hidden)
  }

  // MARK: - navigateToChannel

  @Test
  func `navigateToChannel sets channel, route, and tab`() {
    let appState = AppState()
    let channel = Self.makeChannel()

    appState.navigation.navigateToChannel(with: channel)

    #expect(appState.navigation.pendingChannel == channel)
    #expect(appState.navigation.chatsSelectedRoute == .channel(channel))
    #expect(appState.navigation.selectedTab == 0)
    #expect(appState.navigation.tabBarVisibility == .hidden)
    #expect(appState.navigation.pendingScrollToMessageID == nil)
  }

  @Test
  func `navigateToChannel with scrollToMessageID sets message ID`() {
    let appState = AppState()
    let channel = Self.makeChannel()
    let messageID = UUID()

    appState.navigation.navigateToChannel(with: channel, scrollToMessageID: messageID)

    #expect(appState.navigation.pendingChannel == channel)
    #expect(appState.navigation.pendingScrollToMessageID == messageID)
  }

  // MARK: - navigateToDiscovery

  @Test
  func `navigateToDiscovery sets pending flag and contacts tab`() {
    let appState = AppState()

    appState.navigation.navigateToDiscovery()

    #expect(appState.navigation.pendingDiscoveryNavigation == true)
    #expect(appState.navigation.selectedTab == 1)
  }

  @Test
  func `navigateToDiscovery does not hide tab bar`() {
    let appState = AppState()

    appState.navigation.navigateToDiscovery()

    #expect(appState.navigation.tabBarVisibility == .visible)
  }

  // MARK: - navigateToContacts

  @Test
  func `navigateToContacts switches to contacts tab`() {
    let appState = AppState()
    appState.navigation.selectedTab = 3

    appState.navigation.navigateToContacts()

    #expect(appState.navigation.selectedTab == 1)
  }

  // MARK: - navigateToContactDetail

  @Test
  func `navigateToContactDetail sets contact and contacts tab`() {
    let appState = AppState()
    let contact = Self.makeContact()

    appState.navigation.navigateToContactDetail(contact)

    #expect(appState.navigation.pendingContactDetail == contact)
    #expect(appState.navigation.selectedTab == 1)
  }

  // MARK: - Clear Methods

  @Test
  func `clearPendingNavigation clears chat contact`() {
    let appState = AppState()
    appState.navigation.pendingChatContact = Self.makeContact()

    appState.navigation.clearPendingNavigation()

    #expect(appState.navigation.pendingChatContact == nil)
  }

  @Test
  func `clearPendingRoomNavigation clears room session`() {
    let appState = AppState()
    appState.navigation.pendingRoomSession = Self.makeRoomSession()

    appState.navigation.clearPendingRoomNavigation()

    #expect(appState.navigation.pendingRoomSession == nil)
  }

  @Test
  func `clearPendingRoomAuthentication clears room auth session`() {
    let appState = AppState()
    appState.navigation.pendingRoomAuthentication = Self.makeRoomSession()

    appState.navigation.clearPendingRoomAuthentication()

    #expect(appState.navigation.pendingRoomAuthentication == nil)
  }

  @Test
  func `clearPendingChannelNavigation clears channel`() {
    let appState = AppState()
    appState.navigation.pendingChannel = Self.makeChannel()

    appState.navigation.clearPendingChannelNavigation()

    #expect(appState.navigation.pendingChannel == nil)
  }

  @Test
  func `clearPendingDiscoveryNavigation clears discovery flag`() {
    let appState = AppState()
    appState.navigation.pendingDiscoveryNavigation = true

    appState.navigation.clearPendingDiscoveryNavigation()

    #expect(appState.navigation.pendingDiscoveryNavigation == false)
  }

  @Test
  func `clearPendingScrollToMessage clears message ID`() {
    let appState = AppState()
    appState.navigation.pendingScrollToMessageID = UUID()

    appState.navigation.clearPendingScrollToMessage()

    #expect(appState.navigation.pendingScrollToMessageID == nil)
  }

  @Test
  func `clearPendingContactDetailNavigation clears contact detail`() {
    let appState = AppState()
    appState.navigation.pendingContactDetail = Self.makeContact()

    appState.navigation.clearPendingContactDetailNavigation()

    #expect(appState.navigation.pendingContactDetail == nil)
  }

  // MARK: - Cross-Tab Navigation

  @Test
  func `navigateToChat from contacts tab hides tab bar and switches tab`() {
    let appState = AppState()
    appState.navigation.selectedTab = 1 // Contacts tab
    let contact = Self.makeContact()

    appState.navigation.navigateToChat(with: contact)

    #expect(appState.navigation.tabBarVisibility == .hidden)
    #expect(appState.navigation.selectedTab == 0)
    #expect(appState.navigation.pendingChatContact == contact)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
  }

  @Test
  func `Multiple navigation calls overwrite pending state`() {
    let appState = AppState()
    let contact1 = Self.makeContact(name: "First")
    let contact2 = Self.makeContact(name: "Second")

    appState.navigation.navigateToChat(with: contact1)
    appState.navigation.navigateToChat(with: contact2)

    #expect(appState.navigation.pendingChatContact == contact2)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact2))
  }

  @Test
  func `Device menu tip donation is pending by default when false`() {
    let appState = AppState()
    #expect(appState.navigation.pendingDeviceMenuTipDonation == false)
  }
}
