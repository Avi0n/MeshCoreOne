import CoreLocation
import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Navigation Coordinator Notification Handler Tests")
@MainActor
struct NavigationCoordinatorNotificationTests {
  // MARK: - Test Helpers

  private static func makeContact(
    id: UUID = UUID(),
    radioID: UUID = UUID(),
    name: String = "TestContact"
  ) -> ContactDTO {
    ContactDTO(
      id: id,
      radioID: radioID,
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
    radioID: UUID = UUID(),
    name: String = "TestChannel",
    index: UInt8 = 0
  ) -> ChannelDTO {
    ChannelDTO(
      id: id,
      radioID: radioID,
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

  private static func makeDeviceDTO(manualAddContacts: Bool = false) -> DeviceDTO {
    DeviceDTO(
      id: UUID(),
      publicKey: Data(repeating: 0xBB, count: 32),
      nodeName: "TestNode",
      firmwareVersion: 1,
      firmwareVersionString: "1.12.0",
      manufacturerName: "Test",
      buildDate: "2025-01-01",
      maxContacts: 100,
      maxChannels: 8,
      frequency: 915_000,
      bandwidth: 250_000,
      spreadingFactor: 10,
      codingRate: 5,
      txPower: 20,
      maxTxPower: 20,
      latitude: 0,
      longitude: 0,
      blePin: 0,
      manualAddContacts: manualAddContacts,
      multiAcks: 2,
      telemetryModeBase: 2,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: true,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }

  /// Creates an in-memory data store seeded with a contact and channel.
  private static func makeSeededDataStore(
    contact: ContactDTO,
    channel: ChannelDTO
  ) async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    try await dataStore.saveContact(contact)
    try await dataStore.saveChannel(channel)
    return dataStore
  }

  // MARK: - DM Notification Tap

  @Test
  func `DM notification tap navigates to chat with contact`() async throws {
    let contact = Self.makeContact()
    let dataStore = try await Self.makeSeededDataStore(
      contact: contact,
      channel: Self.makeChannel()
    )
    let coordinator = NavigationCoordinator()
    let notificationService = NotificationService()

    coordinator.configureNotificationHandlers(
      notificationService: notificationService,
      dataStore: dataStore,
      connectedDevice: { nil }
    )

    // Invoke the handler directly
    await notificationService.onNotificationTapped?(contact.id)

    #expect(coordinator.chatsSelectedRoute == .direct(contact))
    #expect(coordinator.selectedTab == AppTab.chats.rawValue)
  }

  // MARK: - New Contact Notification Tap

  @Test
  func `New contact notification with manualAddContacts navigates to discovery`() async throws {
    let contact = Self.makeContact()
    let dataStore = try await Self.makeSeededDataStore(
      contact: contact,
      channel: Self.makeChannel()
    )
    let coordinator = NavigationCoordinator()
    let notificationService = NotificationService()
    let device = Self.makeDeviceDTO(manualAddContacts: true)

    coordinator.configureNotificationHandlers(
      notificationService: notificationService,
      dataStore: dataStore,
      connectedDevice: { device }
    )

    await notificationService.onNewContactNotificationTapped?(contact.id)

    #expect(coordinator.nodesShowingDiscovery == true)
    #expect(coordinator.selectedContact == nil)
    #expect(coordinator.selectedTab == AppTab.nodes.rawValue)
  }

  @Test
  func `New contact notification without manualAddContacts navigates to contact detail`() async throws {
    let contact = Self.makeContact()
    let dataStore = try await Self.makeSeededDataStore(
      contact: contact,
      channel: Self.makeChannel()
    )
    let coordinator = NavigationCoordinator()
    let notificationService = NotificationService()
    let device = Self.makeDeviceDTO(manualAddContacts: false)

    coordinator.configureNotificationHandlers(
      notificationService: notificationService,
      dataStore: dataStore,
      connectedDevice: { device }
    )

    await notificationService.onNewContactNotificationTapped?(contact.id)

    #expect(coordinator.selectedContact?.id == contact.id)
    #expect(coordinator.nodesShowingDiscovery == false)
    #expect(coordinator.selectedTab == AppTab.nodes.rawValue)
  }

  // MARK: - Channel Notification Tap

  @Test
  func `Channel notification tap navigates to channel`() async throws {
    let radioID = UUID()
    let channelIndex: UInt8 = 3
    let channel = Self.makeChannel(radioID: radioID, index: channelIndex)
    let dataStore = try await Self.makeSeededDataStore(
      contact: Self.makeContact(),
      channel: channel
    )
    let coordinator = NavigationCoordinator()
    let notificationService = NotificationService()

    coordinator.configureNotificationHandlers(
      notificationService: notificationService,
      dataStore: dataStore,
      connectedDevice: { nil }
    )

    await notificationService.onChannelNotificationTapped?(radioID, channelIndex)

    #expect(coordinator.chatsSelectedRoute == .channel(channel))
    #expect(coordinator.selectedTab == AppTab.chats.rawValue)
  }

  // MARK: - Reaction Notification Tap

  @Test
  func `Reaction notification on DM navigates to chat with scrollToMessageID`() async throws {
    let contact = Self.makeContact()
    let messageID = UUID()
    let dataStore = try await Self.makeSeededDataStore(
      contact: contact,
      channel: Self.makeChannel()
    )
    let coordinator = NavigationCoordinator()
    let notificationService = NotificationService()

    coordinator.configureNotificationHandlers(
      notificationService: notificationService,
      dataStore: dataStore,
      connectedDevice: { nil }
    )

    await notificationService.onReactionNotificationTapped?(contact.id, nil, nil, messageID)

    #expect(coordinator.pendingScrollToMessageID == messageID)
    #expect(coordinator.pendingScrollTarget?.conversationID == contact.id)
    #expect(coordinator.selectedTab == AppTab.chats.rawValue)
  }

  @Test
  func `Reaction notification on channel navigates to channel with scrollToMessageID`() async throws {
    let radioID = UUID()
    let channelIndex: UInt8 = 1
    let channel = Self.makeChannel(radioID: radioID, index: channelIndex)
    let messageID = UUID()
    let dataStore = try await Self.makeSeededDataStore(
      contact: Self.makeContact(),
      channel: channel
    )
    let coordinator = NavigationCoordinator()
    let notificationService = NotificationService()

    coordinator.configureNotificationHandlers(
      notificationService: notificationService,
      dataStore: dataStore,
      connectedDevice: { nil }
    )

    // contactID is nil → falls through to channel branch
    await notificationService.onReactionNotificationTapped?(nil, channelIndex, radioID, messageID)

    #expect(coordinator.chatsSelectedRoute == .channel(channel))
    #expect(coordinator.pendingScrollToMessageID == messageID)
    #expect(coordinator.pendingScrollTarget?.kind == .channel)
    #expect(coordinator.selectedTab == AppTab.chats.rawValue)
  }

  // MARK: - Cross-kind and room authentication

  private static func makeRoomSession(
    id: UUID = UUID(),
    radioID: UUID = UUID(),
    isConnected: Bool
  ) -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      id: id,
      radioID: radioID,
      publicKey: Data(repeating: 0xBB, count: 32),
      name: "TestRoom",
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

  @Test
  func `room notification after a DM reaction does not keep the DM scroll target`() async throws {
    let contact = Self.makeContact()
    let session = Self.makeRoomSession(isConnected: true)
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    try await dataStore.saveContact(contact)
    try await dataStore.saveChannel(Self.makeChannel())
    try await dataStore.saveRemoteNodeSessionDTO(session)
    let coordinator = NavigationCoordinator()
    let notificationService = NotificationService()
    coordinator.configureNotificationHandlers(
      notificationService: notificationService,
      dataStore: dataStore,
      connectedDevice: { nil }
    )

    await notificationService.onReactionNotificationTapped?(contact.id, nil, nil, UUID())
    await notificationService.onRoomNotificationTapped?(session.id)

    #expect(coordinator.chatsSelectedRoute == .room(session))
    #expect(coordinator.pendingScrollTarget == nil)
    #expect(coordinator.pendingRoomAuthentication == nil)
  }

  @Test
  func `disconnected room notification requests authentication instead of selecting the room`() async throws {
    let session = Self.makeRoomSession(isConnected: false)
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    try await dataStore.saveContact(Self.makeContact())
    try await dataStore.saveChannel(Self.makeChannel())
    try await dataStore.saveRemoteNodeSessionDTO(session)
    let coordinator = NavigationCoordinator()
    let notificationService = NotificationService()
    coordinator.configureNotificationHandlers(
      notificationService: notificationService,
      dataStore: dataStore,
      connectedDevice: { nil }
    )

    await notificationService.onRoomNotificationTapped?(session.id)

    #expect(coordinator.pendingRoomAuthentication?.id == session.id)
    #expect(coordinator.chatsSelectedRoute == nil)
    #expect(coordinator.selectedTab == AppTab.chats.rawValue)
  }

  @Test
  func `per-radio clear while another tab is selected retires unconsumed chat intents`() {
    let coordinator = NavigationCoordinator()
    let contact = Self.makeContact()
    coordinator.selectedTab = AppTab.nodes.rawValue
    coordinator.navigateToChat(with: contact, scrollToMessageID: UUID())
    #expect(coordinator.selectedTab == AppTab.chats.rawValue)

    coordinator.selectedTab = AppTab.nodes.rawValue
    coordinator.clearPerRadioSelection()

    #expect(coordinator.pendingScrollTarget == nil)
    #expect(coordinator.chatsSelectedRoute == nil)
  }
}

@Suite("NavigationCoordinator Map Navigation Tests")
@MainActor
struct NavigationCoordinatorMapTests {
  @Test
  func `navigateToMap sets pendingMapFocus and selects the map tab`() {
    let coordinator = NavigationCoordinator()
    let coordinate = CLLocationCoordinate2D(latitude: 37.3349, longitude: -122.00902)

    coordinator.navigateToMap(coordinate: coordinate)

    #expect(coordinator.pendingMapFocus?.latitude == 37.3349)
    #expect(coordinator.pendingMapFocus?.longitude == -122.00902)
    #expect(coordinator.pendingMapFocus?.coordinate.latitude == 37.3349)
    #expect(coordinator.selectedTab == AppTab.map.rawValue)
  }

  @Test
  func `ChatViewModel.navigateToMap forwards the coordinate to the navigation sink`() {
    let coordinator = NavigationCoordinator()
    let viewModel = ChatViewModel()
    viewModel.onNavigateToMap = { coordinator.navigateToMap(coordinate: $0) }
    let coordinate = CLLocationCoordinate2D(latitude: 51.5074, longitude: -0.1278)

    // The thumbnail tap path ends at ChatViewModel.navigateToMap, which forwards
    // to the same navigation sink ChatsView.handleMeshCoreLink uses for the text link.
    viewModel.navigateToMap(coordinate)

    #expect(coordinator.pendingMapFocus?.latitude == 51.5074)
    #expect(coordinator.pendingMapFocus?.longitude == -0.1278)
    #expect(coordinator.selectedTab == AppTab.map.rawValue)
  }

  @Test
  func `clearPendingMapFocus resets the pending focus`() {
    let coordinator = NavigationCoordinator()
    coordinator.navigateToMap(coordinate: CLLocationCoordinate2D(latitude: 1, longitude: 2))

    coordinator.clearPendingMapFocus()

    #expect(coordinator.pendingMapFocus == nil)
  }
}

@Suite("NavigationCoordinator Settings Navigation Tests")
@MainActor
struct NavigationCoordinatorSettingsTests {
  @Test
  func `navigateToSetting sets selectedSetting and selects the settings tab`() {
    let coordinator = NavigationCoordinator()
    coordinator.selectedTab = AppTab.chats.rawValue

    coordinator.navigateToSetting(.support)

    #expect(coordinator.selectedSetting == .support)
    #expect(coordinator.selectedTab == AppTab.settings.rawValue)
    #expect(coordinator.settingsRootNavigationGeneration == 1)
  }

  @Test
  func `navigateToSetting replaces an open page and bumps generation`() {
    let coordinator = NavigationCoordinator()
    coordinator.navigateToSetting(.chats)
    coordinator.navigateToSetting(.language)

    #expect(coordinator.selectedSetting == .language)
    #expect(coordinator.settingsRootNavigationGeneration == 2)
  }
}

@Suite("NavigationCoordinator Pending Link Tests")
@MainActor
struct NavigationCoordinatorPendingLinkTests {
  @Test
  func `pendingContactLink starts nil and clears via helper`() {
    let coordinator = NavigationCoordinator()
    #expect(coordinator.pendingContactLink == nil)
    coordinator.pendingContactLink = MeshCoreURLParser.ContactResult(
      name: "Alice",
      publicKey: Data(repeating: 0xAB, count: 32),
      contactType: .chat
    )
    #expect(coordinator.pendingContactLink != nil)
    coordinator.clearPendingContactLink()
    #expect(coordinator.pendingContactLink == nil)
  }

  @Test
  func `pendingChannelLink starts nil and clears via helper`() {
    let coordinator = NavigationCoordinator()
    #expect(coordinator.pendingChannelLink == nil)
    coordinator.pendingChannelLink = MeshCoreURLParser.ChannelResult(
      name: "general",
      secret: Data(repeating: 0xCC, count: 16)
    )
    #expect(coordinator.pendingChannelLink != nil)
    coordinator.clearPendingChannelLink()
    #expect(coordinator.pendingChannelLink == nil)
  }

  @Test
  func `pendingHashtag starts nil and clears via helper`() {
    let coordinator = NavigationCoordinator()
    #expect(coordinator.pendingHashtag == nil)
    coordinator.pendingHashtag = HashtagJoinRequest(id: "#general")
    #expect(coordinator.pendingHashtag != nil)
    coordinator.clearPendingHashtag()
    #expect(coordinator.pendingHashtag == nil)
  }

  // MARK: - clearPendingLinks (per-radio teardown)

  private static func makeContact(name: String = "TestContact") -> ContactDTO {
    ContactDTO(
      id: UUID(),
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

  @Test
  func `clearPendingLinks clears the hoisted Nodes selected contact`() {
    let coordinator = NavigationCoordinator()
    coordinator.selectedContact = Self.makeContact()
    #expect(coordinator.selectedContact != nil)

    coordinator.clearPendingLinks()

    #expect(coordinator.selectedContact == nil)
  }

  @Test
  func `clearPendingLinks clears the hoisted Nodes discovery flag`() {
    let coordinator = NavigationCoordinator()
    coordinator.nodesShowingDiscovery = true

    coordinator.clearPendingLinks()

    #expect(coordinator.nodesShowingDiscovery == false)
  }

  @Test
  func `clearPendingLinks clears every staged per-radio field at once`() {
    let coordinator = NavigationCoordinator()
    coordinator.pendingContactLink = MeshCoreURLParser.ContactResult(
      name: "Alice",
      publicKey: Data(repeating: 0xAB, count: 32),
      contactType: .chat
    )
    coordinator.pendingChannelLink = MeshCoreURLParser.ChannelResult(
      name: "general",
      secret: Data(repeating: 0xCC, count: 16)
    )
    coordinator.pendingHashtag = HashtagJoinRequest(id: "#general")
    coordinator.selectedContact = Self.makeContact()
    coordinator.nodesShowingDiscovery = true
    coordinator.chatsSelectedRoute = .direct(Self.makeContact())
    coordinator.selectedTool = .cli

    coordinator.clearPendingLinks()

    #expect(coordinator.pendingContactLink == nil)
    #expect(coordinator.pendingChannelLink == nil)
    #expect(coordinator.pendingHashtag == nil)
    #expect(coordinator.selectedContact == nil)
    #expect(coordinator.nodesShowingDiscovery == false)
    #expect(coordinator.chatsSelectedRoute == nil)
    #expect(coordinator.selectedTool == nil)
  }

  // MARK: - clearPerRadioSelection

  @Test
  func `clearPerRadioSelection clears the hoisted Chats route`() {
    let coordinator = NavigationCoordinator()
    coordinator.chatsSelectedRoute = .direct(Self.makeContact())

    coordinator.clearPerRadioSelection()

    #expect(coordinator.chatsSelectedRoute == nil)
  }

  @Test
  func `clearPerRadioSelection clears a radio-requiring tool`() {
    let coordinator = NavigationCoordinator()
    coordinator.selectedTool = .cli
    #expect(coordinator.selectedTool?.requiresRadio == true)

    coordinator.clearPerRadioSelection()

    #expect(coordinator.selectedTool == nil)
  }

  @Test
  func `clearPerRadioSelection preserves the offline Line of Sight tool`() {
    let coordinator = NavigationCoordinator()
    coordinator.selectedTool = .lineOfSight
    #expect(coordinator.selectedTool?.requiresRadio == false)

    coordinator.clearPerRadioSelection()

    #expect(coordinator.selectedTool == .lineOfSight)
  }

  @Test
  func `tool workspace is active only on the Tools tab with a matching selection`() {
    let coordinator = NavigationCoordinator()
    coordinator.selectedTab = AppTab.tools.rawValue
    coordinator.selectedTool = .noiseFloor

    #expect(coordinator.isToolWorkspaceActive(.noiseFloor))
    #expect(!coordinator.isToolWorkspaceActive(.tracePath))

    coordinator.selectedTab = AppTab.chats.rawValue
    #expect(!coordinator.isToolWorkspaceActive(.noiseFloor))
  }

  @Test
  func `clearPerRadioSelection clears a per-device settings page`() {
    let coordinator = NavigationCoordinator()
    coordinator.selectedSetting = .radio
    #expect(coordinator.selectedSetting?.requiresDevice == true)

    coordinator.clearPerRadioSelection()

    #expect(coordinator.selectedSetting == nil)
  }

  @Test
  func `clearPerRadioSelection preserves a device-independent settings page`() {
    let coordinator = NavigationCoordinator()
    coordinator.selectedSetting = .appearance
    #expect(coordinator.selectedSetting?.requiresDevice == false)

    coordinator.clearPerRadioSelection()

    #expect(coordinator.selectedSetting == .appearance)
  }

  @Test
  func `language settings page is device-independent`() {
    let coordinator = NavigationCoordinator()
    coordinator.selectedSetting = .language
    #expect(coordinator.selectedSetting?.requiresDevice == false)
    coordinator.clearPerDeviceSelection()
    #expect(coordinator.selectedSetting == .language)
  }

  @Test
  func `navigateToContactDetail bumps nodes generation without requiring a new identity`() {
    let coordinator = NavigationCoordinator()
    let contact = Self.makeContact()
    coordinator.navigateToContactDetail(contact)
    let generation = coordinator.nodesRootNavigationGeneration
    coordinator.navigateToContactDetail(contact)
    #expect(coordinator.selectedContact?.id == contact.id)
    #expect(coordinator.nodesRootNavigationGeneration == generation + 1)
  }

  @Test
  func `navigateToDiscovery bumps nodes generation and clears the contact`() {
    let coordinator = NavigationCoordinator()
    coordinator.navigateToContactDetail(Self.makeContact())
    let generation = coordinator.nodesRootNavigationGeneration
    coordinator.navigateToDiscovery()
    #expect(coordinator.selectedContact == nil)
    #expect(coordinator.nodesShowingDiscovery == true)
    #expect(coordinator.nodesRootNavigationGeneration == generation + 1)
  }

  @Test
  func `clearSelectedContact only drops the matching root`() {
    let coordinator = NavigationCoordinator()
    let contact = Self.makeContact()
    coordinator.selectedContact = contact
    coordinator.clearSelectedContact(matching: UUID())
    #expect(coordinator.selectedContact?.id == contact.id)
    coordinator.clearSelectedContact(matching: contact.id)
    #expect(coordinator.selectedContact == nil)
  }
}
