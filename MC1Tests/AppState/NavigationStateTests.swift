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
      lastHeardTimestamp: nil,
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
    name: String = "TestRoom",
    isConnected: Bool = false
  ) -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      id: id,
      radioID: UUID(),
      publicKey: Data(repeating: 0xBB, count: 32),
      name: name,
      role: .roomServer,
      latitude: 0,
      longitude: 0,
      isConnected: isConnected,
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
    #expect(appState.navigation.pendingRoomAuthentication == nil)
    #expect(appState.navigation.pendingScrollToMessageID == nil)
    #expect(appState.navigation.pendingScrollTarget == nil)
    #expect(appState.navigation.chatsSelectedRoute == nil)
    #expect(appState.navigation.chatsRootNavigationGeneration == 0)
    #expect(appState.navigation.nodesRootNavigationGeneration == 0)
    #expect(appState.navigation.settingsRootNavigationGeneration == 0)
    #expect(appState.navigation.selectedContact == nil)
    #expect(appState.navigation.selectedSetting == nil)
  }

  // MARK: - navigateToChat

  @Test
  func `navigateToChat sets contact, route, and tab`() {
    let appState = AppState()
    let contact = Self.makeContact()

    appState.navigation.navigateToChat(with: contact)

    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    #expect(appState.navigation.selectedTab == AppTab.chats.rawValue)
    #expect(appState.navigation.pendingScrollToMessageID == nil)
    #expect(appState.navigation.chatsRootNavigationGeneration == 1)
  }

  @Test
  func `navigateToChat with scrollToMessageID sets message ID`() {
    let appState = AppState()
    let contact = Self.makeContact()
    let messageID = UUID()

    appState.navigation.navigateToChat(with: contact, scrollToMessageID: messageID)

    #expect(appState.navigation.pendingScrollToMessageID == messageID)
    #expect(appState.navigation.pendingScrollTarget?.conversationID == contact.id)
    #expect(appState.navigation.pendingScrollTarget?.kind == .direct)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    #expect(appState.navigation.selectedTab == AppTab.chats.rawValue)
  }

  @Test
  func `navigateToChat switches to Chats tab from another tab`() {
    let appState = AppState()
    appState.navigation.selectedTab = 3 // Settings tab
    let contact = Self.makeContact()

    appState.navigation.navigateToChat(with: contact)

    #expect(appState.navigation.selectedTab == AppTab.chats.rawValue)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
  }

  // MARK: - navigateToRoom

  @Test
  func `navigateToRoom sets session, route, and tab`() {
    let appState = AppState()
    let session = Self.makeRoomSession(isConnected: true)

    appState.navigation.navigateToRoom(with: session)

    #expect(appState.navigation.chatsSelectedRoute == .room(session))
    #expect(appState.navigation.selectedTab == AppTab.chats.rawValue)
    #expect(appState.navigation.pendingRoomAuthentication == nil)
  }

  // MARK: - navigateToChannel

  @Test
  func `navigateToChannel sets channel, route, and tab`() {
    let appState = AppState()
    let channel = Self.makeChannel()

    appState.navigation.navigateToChannel(with: channel)

    #expect(appState.navigation.chatsSelectedRoute == .channel(channel))
    #expect(appState.navigation.selectedTab == AppTab.chats.rawValue)
    #expect(appState.navigation.pendingScrollToMessageID == nil)
  }

  @Test
  func `navigateToChannel with scrollToMessageID sets message ID`() {
    let appState = AppState()
    let channel = Self.makeChannel()
    let messageID = UUID()

    appState.navigation.navigateToChannel(with: channel, scrollToMessageID: messageID)

    #expect(appState.navigation.chatsSelectedRoute == .channel(channel))
    #expect(appState.navigation.pendingScrollToMessageID == messageID)
  }

  // MARK: - navigateToDiscovery

  @Test
  func `navigateToDiscovery shows discovery on the nodes tab`() {
    let appState = AppState()

    appState.navigation.navigateToDiscovery()

    #expect(appState.navigation.nodesShowingDiscovery == true)
    #expect(appState.navigation.selectedTab == AppTab.nodes.rawValue)
  }

  @Test
  func `navigateToDiscovery does not select a chats route`() {
    let appState = AppState()

    appState.navigation.navigateToDiscovery()

    #expect(appState.navigation.chatsSelectedRoute == nil)
  }

  // MARK: - navigateToContacts

  @Test
  func `navigateToContacts switches to contacts tab`() {
    let appState = AppState()
    appState.navigation.selectedTab = 3

    appState.navigation.navigateToContacts()

    #expect(appState.navigation.selectedTab == AppTab.nodes.rawValue)
  }

  // MARK: - navigateToContactDetail

  @Test
  func `navigateToContactDetail sets contact and contacts tab`() {
    let appState = AppState()
    let contact = Self.makeContact()

    appState.navigation.navigateToContactDetail(contact)

    #expect(appState.navigation.selectedContact == contact)
    #expect(appState.navigation.nodesShowingDiscovery == false)
    #expect(appState.navigation.selectedTab == AppTab.nodes.rawValue)
  }

  // MARK: - Clear Methods

  @Test
  func `clearPendingRoomAuthentication clears room auth session`() {
    let appState = AppState()
    appState.navigation.pendingRoomAuthentication = Self.makeRoomSession()

    appState.navigation.clearPendingRoomAuthentication()

    #expect(appState.navigation.pendingRoomAuthentication == nil)
  }

  @Test
  func `clearPendingScrollToMessage clears message ID`() {
    let appState = AppState()
    let contact = Self.makeContact()
    appState.navigation.pendingScrollTarget = PendingScrollTarget(
      route: .direct(contact),
      messageID: UUID()
    )

    appState.navigation.clearPendingScrollToMessage()

    #expect(appState.navigation.pendingScrollToMessageID == nil)
    #expect(appState.navigation.pendingScrollTarget == nil)
  }

  // MARK: - Cross-Tab Navigation

  @Test
  func `navigateToChat from contacts tab switches tab and selects the conversation`() {
    let appState = AppState()
    appState.navigation.selectedTab = 1 // Contacts tab
    let contact = Self.makeContact()

    appState.navigation.navigateToChat(with: contact)

    #expect(appState.navigation.selectedTab == AppTab.chats.rawValue)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    #expect(appState.navigation.chatsRootNavigationGeneration == 1)
  }

  @Test
  func `Multiple navigation calls overwrite pending state`() {
    let appState = AppState()
    let contact1 = Self.makeContact(name: "First")
    let contact2 = Self.makeContact(name: "Second")

    appState.navigation.navigateToChat(with: contact1)
    appState.navigation.navigateToChat(with: contact2)

    #expect(appState.navigation.chatsSelectedRoute == .direct(contact2))
  }

  @Test
  func `Device menu tip donation is pending by default when false`() {
    let appState = AppState()
    #expect(appState.navigation.pendingDeviceMenuTipDonation == false)
  }

  // MARK: - Cross-kind replacement

  @Test
  func `navigateToRoom replaces a DM reaction scroll target`() {
    let appState = AppState()
    let contact = Self.makeContact()
    let session = Self.makeRoomSession(isConnected: true)
    let messageID = UUID()

    appState.navigation.navigateToChat(with: contact, scrollToMessageID: messageID)
    appState.navigation.navigateToRoom(with: session)

    #expect(appState.navigation.pendingScrollTarget == nil)
    #expect(appState.navigation.chatsSelectedRoute == .room(session))
  }

  @Test
  func `navigateToChannel replaces a pending DM`() {
    let appState = AppState()
    let contact = Self.makeContact()
    let channel = Self.makeChannel()

    appState.navigation.navigateToChat(with: contact, scrollToMessageID: UUID())
    appState.navigation.navigateToChannel(with: channel)

    #expect(appState.navigation.pendingScrollTarget == nil)
    #expect(appState.navigation.chatsSelectedRoute == .channel(channel))
  }

  @Test
  func `navigateToChat replaces pending room authentication`() {
    let appState = AppState()
    let session = Self.makeRoomSession(isConnected: false)
    let contact = Self.makeContact()

    appState.navigation.navigateToRoom(with: session)
    appState.navigation.navigateToChat(with: contact)

    #expect(appState.navigation.pendingRoomAuthentication == nil)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
  }

  @Test
  func `disconnected navigateToRoom keeps authentication intent and does not select the room`() {
    let appState = AppState()
    let session = Self.makeRoomSession(isConnected: false)

    appState.navigation.navigateToRoom(with: session)

    #expect(appState.navigation.pendingRoomAuthentication == session)
    #expect(appState.navigation.chatsSelectedRoute == nil)
    #expect(appState.navigation.selectedTab == AppTab.chats.rawValue)
  }

  // MARK: - Reaction scroll consumption

  @Test
  func `takePendingScrollTarget consumes only the matching conversation`() {
    let appState = AppState()
    let contact = Self.makeContact()
    let other = Self.makeContact(name: "Other")
    let messageID = UUID()
    appState.navigation.navigateToChat(with: contact, scrollToMessageID: messageID)

    let stolen = appState.navigation.takePendingScrollTarget(
      matchingKind: .direct,
      conversationID: other.id
    )
    #expect(stolen == nil)
    #expect(appState.navigation.pendingScrollToMessageID == messageID)

    let taken = appState.navigation.takePendingScrollTarget(
      matchingKind: .direct,
      conversationID: contact.id
    )
    #expect(taken == messageID)
    #expect(appState.navigation.pendingScrollTarget == nil)

    let again = appState.navigation.takePendingScrollTarget(
      matchingKind: .direct,
      conversationID: contact.id
    )
    #expect(again == nil)
  }

  @Test
  func `repeated reaction taps mint distinct request identities`() {
    let appState = AppState()
    let contact = Self.makeContact()
    let messageID = UUID()

    appState.navigation.navigateToChat(with: contact, scrollToMessageID: messageID)
    let first = appState.navigation.pendingScrollTarget?.requestID
    appState.navigation.navigateToChat(with: contact, scrollToMessageID: messageID)
    let second = appState.navigation.pendingScrollTarget?.requestID

    #expect(first != nil)
    #expect(second != nil)
    #expect(first != second)
    #expect(appState.navigation.pendingScrollToMessageID == messageID)
  }

  @Test
  func `explicit root navigation bumps generation for the same conversation`() {
    let appState = AppState()
    let contact = Self.makeContact()

    appState.navigation.navigateToChat(with: contact)
    let afterFirst = appState.navigation.chatsRootNavigationGeneration
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    let afterTabSwitch = appState.navigation.chatsRootNavigationGeneration
    appState.navigation.navigateToChat(with: contact)

    #expect(afterFirst == 1)
    #expect(afterTabSwitch == afterFirst)
    #expect(appState.navigation.chatsRootNavigationGeneration == 2)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
  }

  // MARK: - Payload identity

  @Test
  func `ChatRoute equality ignores payload refresh`() {
    let id = UUID()
    let original = Self.makeContact(id: id, name: "Original")
    let refreshed = Self.makeContact(id: id, name: "Renamed")

    #expect(ChatRoute.direct(original) == ChatRoute.direct(refreshed))
    #expect(ChatRoute.direct(original) != ChatRoute.direct(Self.makeContact(name: "Other")))
  }

  // MARK: - Discovery / contact exclusivity

  @Test
  func `navigateToDiscovery clears a selected contact`() {
    let appState = AppState()
    let contact = Self.makeContact()
    appState.navigation.navigateToContactDetail(contact)

    appState.navigation.navigateToDiscovery()

    #expect(appState.navigation.nodesShowingDiscovery == true)
    #expect(appState.navigation.selectedContact == nil)
  }

  @Test
  func `navigateToContactDetail clears Discovery`() {
    let appState = AppState()
    let contact = Self.makeContact()
    appState.navigation.navigateToDiscovery()

    appState.navigation.navigateToContactDetail(contact)

    #expect(appState.navigation.nodesShowingDiscovery == false)
    #expect(appState.navigation.selectedContact == contact)
  }

  // MARK: - Invalidation

  @Test
  func `explicit disconnect preserves offline routes`() {
    let appState = AppState()
    let contact = Self.makeContact()
    appState.navigation.chatsSelectedRoute = .direct(contact)
    appState.navigation.selectedContact = contact
    appState.navigation.selectedTool = .lineOfSight
    appState.navigation.selectedSetting = .notifications

    appState.navigation.clearPerDeviceSelection()

    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    #expect(appState.navigation.selectedContact == contact)
    #expect(appState.navigation.selectedTool == .lineOfSight)
    #expect(appState.navigation.selectedSetting == .notifications)
  }

  @Test
  func `explicit disconnect clears radio-dependent tool and My Device settings`() {
    let appState = AppState()
    appState.navigation.selectedTool = .tracePath
    appState.navigation.selectedSetting = .radio
    appState.navigation.chatsSelectedRoute = .direct(Self.makeContact())

    appState.navigation.clearPerDeviceSelection()

    #expect(appState.navigation.selectedTool == nil)
    #expect(appState.navigation.selectedSetting == nil)
    #expect(appState.navigation.chatsSelectedRoute != nil)
  }

  @Test
  func `full-radio invalidation clears chats, nodes, and pending conversation intents`() {
    let appState = AppState()
    let contact = Self.makeContact()
    let session = Self.makeRoomSession(isConnected: false)
    appState.navigation.navigateToChat(with: contact, scrollToMessageID: UUID())
    appState.navigation.pendingRoomAuthentication = session
    appState.navigation.selectedContact = contact
    appState.navigation.nodesShowingDiscovery = true
    appState.navigation.selectedTool = .tracePath
    appState.navigation.selectedSetting = .radio
    appState.navigation.pendingContactLink = MeshCoreURLParser.ContactResult(
      name: "Alex",
      publicKey: Data(repeating: 0xAB, count: 32),
      contactType: .chat
    )

    appState.navigation.clearPerRadioSelection()

    #expect(appState.navigation.chatsSelectedRoute == nil)
    #expect(appState.navigation.pendingScrollTarget == nil)
    #expect(appState.navigation.pendingRoomAuthentication == nil)
    #expect(appState.navigation.selectedContact == nil)
    #expect(appState.navigation.nodesShowingDiscovery == false)
    #expect(appState.navigation.selectedTool == nil)
    #expect(appState.navigation.selectedSetting == nil)
    #expect(appState.navigation.pendingContactLink != nil)
  }
}
