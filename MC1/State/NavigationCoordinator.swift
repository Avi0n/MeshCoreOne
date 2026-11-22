import CoreLocation
import MC1Services

/// Manages tab selection, pending navigation targets, and cross-tab navigation coordination.
@Observable
@MainActor
final class NavigationCoordinator {
  /// Selected tab index
  var selectedTab: Int = 0

  /// The currently selected route in the Chats split view detail pane
  var chatsSelectedRoute: ChatRoute?

  /// Whether the selected room was connected when it was opened. A later
  /// snapshot presents auth only when this is true and the room has dropped.
  var lastSelectedRoomIsConnected: Bool?

  /// Room session a notification tap wants the user to authenticate into, set
  /// when the tapped room is not currently connected. ChatsView presents
  /// RoomAuthenticationSheet, mirroring a disconnected-room list tap.
  var pendingRoomAuthentication: RemoteNodeSessionDTO?

  /// Kept in memory because Contact payloads carry radio identity and public keys.
  var nodesSelection: NodesSelection? {
    didSet {
      switch (oldValue, nodesSelection) {
      case let (.contact(previous), .contact(current)) where previous.id == current.id:
        break
      case (.discovery, .discovery), (nil, nil):
        break
      case let (.watchlist(previous, _), .watchlist(current, _)) where previous == current:
        break
      default:
        nodesPresentationRevision += 1
      }
    }
  }

  /// Identifies a Nodes root lifetime, including a clear and reopen in one update.
  private(set) var nodesPresentationRevision = 0

  /// Tools stack path, kept across tab switches. Selections with `requiresRadio` clear on disconnect.
  var selectedTool: ToolSelection?

  /// Which tool is on screen. It is not the lifetime of a trace, a scan, or an admin job.
  /// Resize does not change it.
  func isToolWorkspaceActive(_ tool: ToolSelection) -> Bool {
    selectedTab == AppTab.tools.rawValue && selectedTool == tool
  }

  /// The selected settings page. Settings list and detail bind this at every
  /// width. My Device pages clear on disconnect (`requiresDevice`).
  var selectedSetting: SettingsDetail? {
    didSet {
      if selectedSetting != oldValue {
        settingsPresentationRevision += 1
      }
    }
  }

  /// Identifies a Settings root lifetime, including a clear and reopen in one update.
  private(set) var settingsPresentationRevision = 0

  /// Reaction/deeplink scroll intent bound to one conversation and a per-tap request id.
  var pendingScrollTarget: PendingScrollTarget?

  var pendingScrollToMessageID: UUID? {
    pendingScrollTarget?.messageID
  }

  /// Bumped by explicit root-conversation navigation so a stable host can reset
  /// nested destinations even when the selected root identity is unchanged.
  var chatsRootNavigationGeneration = 0

  /// Explicit navigation resets descendants even when it reopens the same root.
  var nodesRootNavigationGeneration = 0

  /// Bumped by explicit settings-page navigation so nested subpages reset even
  /// when the selected page identity is unchanged.
  var settingsRootNavigationGeneration = 0

  /// Whether device menu tip donation is pending (waiting for valid tab)
  var pendingDeviceMenuTipDonation = false

  /// Coordinate the Map tab should drop a pin on and center, set by a chat coordinate tap.
  var pendingMapFocus: MapFocusRequest?

  /// Pending contact-add confirmation triggered by a `meshcore://contact/add` link tap inside any chat surface.
  var pendingContactLink: MeshCoreURLParser.ContactResult?

  /// Pending channel-join confirmation triggered by a `meshcore://channel/...` link tap inside any chat surface.
  var pendingChannelLink: MeshCoreURLParser.ChannelResult?

  /// Pending hashtag-channel join sheet triggered by a `meshcoreone://hashtag/...` link tap inside any chat surface.
  var pendingHashtag: HashtagJoinRequest?

  // MARK: - Navigation

  func navigateToChat(with contact: ContactDTO, scrollToMessageID: UUID? = nil) {
    let route = ChatRoute.direct(contact)
    replaceConversationIntents()
    if let scrollToMessageID {
      pendingScrollTarget = PendingScrollTarget(route: route, messageID: scrollToMessageID)
    }
    selectChatsRoot(route)
    selectedTab = AppTab.chats.rawValue
  }

  func navigateToRoom(with session: RemoteNodeSessionDTO) {
    replaceConversationIntents()
    selectedTab = AppTab.chats.rawValue
    if session.isConnected {
      selectChatsRoot(.room(session))
    } else {
      presentDisconnectedRoom(session)
    }
  }

  /// Closes the open chat and asks Chats to present room auth. Does not change the selected tab.
  func presentDisconnectedRoom(_ session: RemoteNodeSessionDTO) {
    setChatsRoute(nil)
    pendingRoomAuthentication = session
  }

  /// Applies one conversation snapshot. A room that was open while connected
  /// and is now disconnected goes through ``presentDisconnectedRoom``.
  func refreshChatsSelection(
    from conversations: [Conversation],
    retainingDirectContactIDs: Set<UUID>?
  ) {
    let refreshed = chatsSelectedRoute?.refreshedPayload(
      from: conversations,
      retainingDirectContactIDs: retainingDirectContactIDs
    )
    if lastSelectedRoomIsConnected == true,
       case let .room(session) = refreshed,
       !session.isConnected {
      presentDisconnectedRoom(session)
      return
    }
    setChatsRoute(refreshed)
  }

  /// Sets the open chat and the room-connection flag together.
  func setChatsRoute(_ route: ChatRoute?) {
    chatsSelectedRoute = route
    lastSelectedRoomIsConnected = route?.roomIsConnected
  }

  func navigateToChannel(with channel: ChannelDTO, scrollToMessageID: UUID? = nil) {
    let route = ChatRoute.channel(channel)
    replaceConversationIntents()
    if let scrollToMessageID {
      pendingScrollTarget = PendingScrollTarget(route: route, messageID: scrollToMessageID)
    }
    selectChatsRoot(route)
    selectedTab = AppTab.chats.rawValue
  }

  func navigateToDiscovery() {
    nodesSelection = .discovery
    nodesRootNavigationGeneration += 1
    selectedTab = AppTab.nodes.rawValue
  }

  func navigateToWatchlist(radioID: UUID) {
    nodesSelection = .watchlist(radioID: radioID)
    nodesRootNavigationGeneration += 1
    selectedTab = AppTab.nodes.rawValue
  }

  func navigateToContacts() {
    selectedTab = AppTab.nodes.rawValue
  }

  func navigateToContactDetail(_ contact: ContactDTO) {
    nodesSelection = .contact(contact)
    nodesRootNavigationGeneration += 1
    selectedTab = AppTab.nodes.rawValue
  }

  func navigateToMap(coordinate: CLLocationCoordinate2D) {
    pendingMapFocus = MapFocusRequest(latitude: coordinate.latitude,
                                      longitude: coordinate.longitude)
    selectedTab = AppTab.map.rawValue
  }

  /// Switches to the Settings tab and opens the given detail page. Compact and
  /// regular hosts both bind `selectedSetting` as the split detail.
  func navigateToSetting(_ detail: SettingsDetail) {
    selectedSetting = detail
    settingsRootNavigationGeneration += 1
    selectedTab = AppTab.settings.rawValue
  }

  func clearPendingRoomAuthentication() {
    pendingRoomAuthentication = nil
  }

  func clearPendingScrollToMessage() {
    pendingScrollTarget = nil
  }

  /// Returns the pending message id when it belongs to this conversation, then
  /// retires the intent. A different conversation leaves the target in place.
  func takePendingScrollTarget(matchingKind kind: ChatRoute.Kind, conversationID: UUID) -> UUID? {
    guard let target = pendingScrollTarget,
          target.matches(kind: kind, conversationID: conversationID) else {
      return nil
    }
    pendingScrollTarget = nil
    return target.messageID
  }

  func updateSelectedContact(_ contact: ContactDTO) {
    guard nodesSelection?.contact?.id == contact.id else { return }
    switch nodesSelection {
    case .contact:
      nodesSelection = .contact(contact)
    case let .watchlist(radioID, _):
      nodesSelection = .watchlist(radioID: radioID, contact: contact)
    case .discovery, nil:
      break
    }
  }

  func clearSelectedContact(matching id: UUID) {
    guard nodesSelection?.contact?.id == id else { return }
    switch nodesSelection {
    case .contact:
      nodesSelection = nil
    case let .watchlist(radioID, _):
      nodesSelection = .watchlist(radioID: radioID)
    case .discovery, nil:
      break
    }
  }

  func clearNodesRoot(matchingPresentation revision: Int) {
    guard nodesPresentationRevision == revision else { return }
    nodesSelection = nil
  }

  func clearWatchlistRoot() {
    guard case .watchlist = nodesSelection else { return }
    nodesSelection = nil
  }

  func clearWatchlistRoot(matching radioID: UUID) {
    guard nodesSelection?.watchlistRadioID == radioID else { return }
    nodesSelection = nil
  }

  func clearPendingMapFocus() {
    pendingMapFocus = nil
  }

  func clearPendingContactLink() {
    pendingContactLink = nil
  }

  func clearPendingChannelLink() {
    pendingChannelLink = nil
  }

  func clearPendingHashtag() {
    pendingHashtag = nil
  }

  /// Clears pending links and connection-dependent selections. Watchlist remains
  /// readable for the remembered radio until that radio is replaced or forgotten.
  func clearPendingLinks() {
    pendingContactLink = nil
    pendingChannelLink = nil
    pendingHashtag = nil
    if nodesSelection?.watchlistRadioID == nil { nodesSelection = nil }
    setChatsRoute(nil)
    replaceConversationIntents()
    clearPerDeviceSelection()
  }

  /// Clears radio-scoped detail selections before a replacement can display them.
  /// Radio-independent tools and app-wide settings remain selected.
  func clearPerRadioSelection(preservingConnection: Bool = false) {
    nodesSelection = nil
    setChatsRoute(nil)
    replaceConversationIntents()
    clearPerDeviceSelection(preservingConnection: preservingConnection)
  }

  /// Drops pending room-auth and scroll intents so a later open cannot inherit
  /// another conversation's target. Link-confirmation sheets stay put.
  private func replaceConversationIntents() {
    pendingRoomAuthentication = nil
    pendingScrollTarget = nil
  }

  private func selectChatsRoot(_ route: ChatRoute) {
    setChatsRoute(route)
    chatsRootNavigationGeneration += 1
  }

  /// Clears only device-scoped selections (the radio-requiring tool and the My Device settings
  /// page), but keeps an open Chats/Nodes detail so a manual disconnect does not eject the user from
  /// an open conversation. Line of Sight and app-wide settings are radio-independent and preserved.
  func clearPerDeviceSelection(preservingConnection: Bool = false) {
    if selectedTool?.requiresRadio == true {
      selectedTool = nil
    }
    guard selectedSetting?.requiresDevice == true else { return }
    if preservingConnection, selectedSetting == .connection {
      return
    }
    selectedSetting = nil
  }

  /// Tabs where BLEStatusIndicatorView exists and the device menu tip can anchor (Chats, Contacts, Map).
  var isOnValidTabForDeviceMenuTip: Bool {
    selectedTab == AppTab.chats.rawValue
      || selectedTab == AppTab.nodes.rawValue
      || selectedTab == AppTab.map.rawValue
  }

  // MARK: - Notification Handlers

  /// Configure notification tap handlers that navigate to conversations.
  /// Called from AppState.configureNotificationHandlers() when services become available.
  func configureNotificationHandlers(
    notificationService: NotificationService,
    dataStore: PersistenceStore,
    connectedDevice: @escaping @Sendable @MainActor () -> DeviceDTO?
  ) {
    // Direct message notification tap
    notificationService.onNotificationTapped = { [weak self] contactID in
      guard let self else { return }
      guard let contact = try? await dataStore.fetchContact(id: contactID) else { return }
      navigateToChat(with: contact)
    }

    // New contact notification tap
    notificationService.onNewContactNotificationTapped = { [weak self] contactID in
      guard let self else { return }
      if connectedDevice()?.manualAddContacts == true {
        navigateToDiscovery()
      } else {
        guard let contact = try? await dataStore.fetchContact(id: contactID) else {
          navigateToContacts()
          return
        }
        navigateToContactDetail(contact)
      }
    }

    // Channel notification tap
    notificationService.onChannelNotificationTapped = { [weak self] radioID, channelIndex in
      guard let self else { return }
      guard let channel = try? await dataStore.fetchChannel(radioID: radioID, index: channelIndex) else { return }
      navigateToChannel(with: channel)
    }

    // Reaction notification tap
    notificationService.onReactionNotificationTapped = { [weak self] contactID, channelIndex, radioID, messageID in
      guard let self else { return }
      if let contactID,
         let contact = try? await dataStore.fetchContact(id: contactID) {
        navigateToChat(with: contact, scrollToMessageID: messageID)
      } else if let channelIndex, let radioID,
                let channel = try? await dataStore.fetchChannel(radioID: radioID, index: channelIndex) {
        navigateToChannel(with: channel, scrollToMessageID: messageID)
      }
    }

    // Room notification tap. Resolves the full session from the stable
    // sessionID carried in the notification. A connected room opens directly;
    // a disconnected room is routed to the auth sheet, mirroring the list-row
    // gate, instead of the iPad detail pane rendering an ungated read-only room.
    notificationService.onRoomNotificationTapped = { [weak self] sessionID in
      guard let self else { return }
      guard let session = try? await dataStore.fetchRemoteNodeSession(id: sessionID) else { return }
      navigateToRoom(with: session)
    }
  }
}
