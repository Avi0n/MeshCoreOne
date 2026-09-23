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

  /// Room session a notification tap wants the user to authenticate into, set
  /// when the tapped room is not currently connected. ChatsView presents
  /// RoomAuthenticationSheet, mirroring a disconnected-room list tap.
  var pendingRoomAuthentication: RemoteNodeSessionDTO?

  /// The currently selected contact in the Nodes split view detail pane. Kept in memory
  /// only and never persisted: it carries a public key and `radioID` (identity-bearing
  /// data) that must not be written outside the encrypted backup envelope.
  var selectedContact: ContactDTO?

  /// Whether the Nodes split view detail pane is showing Discovery rather than a contact.
  /// Shared so the iPad content and detail columns agree on which detail to render.
  var nodesShowingDiscovery = false

  /// Tools stack path, kept across tab switches. Selections with `requiresRadio` clear on disconnect.
  var selectedTool: ToolSelection?

  /// Listening and polling run only while Tools is selected and this workspace
  /// is on the stack. Resize does not change either value.
  func isToolWorkspaceActive(_ tool: ToolSelection) -> Bool {
    selectedTab == AppTab.tools.rawValue && selectedTool == tool
  }

  /// The selected settings page. Settings list and detail bind this at every
  /// width. My Device pages clear on disconnect (`requiresDevice`).
  var selectedSetting: SettingsDetail?

  /// Reaction/deeplink scroll intent bound to one conversation and a per-tap request id.
  var pendingScrollTarget: PendingScrollTarget?

  var pendingScrollToMessageID: UUID? {
    pendingScrollTarget?.messageID
  }

  /// Bumped by explicit root-conversation navigation so a stable host can reset
  /// nested destinations even when the selected root identity is unchanged.
  var chatsRootNavigationGeneration = 0

  /// Bumped by explicit Nodes root navigation (contact or Discovery) so nested
  /// destinations reset even when the selected contact identity is unchanged.
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
      pendingRoomAuthentication = session
    }
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
    nodesShowingDiscovery = true
    selectedContact = nil
    nodesRootNavigationGeneration += 1
    selectedTab = AppTab.nodes.rawValue
  }

  func navigateToContacts() {
    selectedTab = AppTab.nodes.rawValue
  }

  func navigateToContactDetail(_ contact: ContactDTO) {
    selectedContact = contact
    nodesShowingDiscovery = false
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

  /// Drops the Nodes root when the matching contact was actually removed.
  func clearSelectedContact(matching id: UUID) {
    if selectedContact?.id == id {
      selectedContact = nil
    }
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

  /// Clears deep-link confirmation state and every per-radio detail selection so a pending sheet
  /// cannot re-present, and a selection made on the previous radio cannot drive a detail pane,
  /// after the connection is torn down and replaced.
  func clearPendingLinks() {
    pendingContactLink = nil
    pendingChannelLink = nil
    pendingHashtag = nil
    clearPerRadioSelection()
  }

  /// Resets every detail selection scoped to the current radio so a stale selection cannot aim a
  /// section's detail pane at the wrong radio. Runs on disconnect and on a direct radio-to-radio
  /// switch. Line of Sight is preserved because it runs offline and is not radio-scoped.
  func clearPerRadioSelection() {
    selectedContact = nil
    nodesShowingDiscovery = false
    chatsSelectedRoute = nil
    replaceConversationIntents()
    clearPerDeviceSelection()
  }

  /// Drops pending room-auth and scroll intents so a later open cannot inherit
  /// another conversation's target. Link-confirmation sheets stay put.
  private func replaceConversationIntents() {
    pendingRoomAuthentication = nil
    pendingScrollTarget = nil
  }

  private func selectChatsRoot(_ route: ChatRoute) {
    chatsSelectedRoute = route
    chatsRootNavigationGeneration += 1
  }

  /// Clears only device-scoped selections (the radio-requiring tool and the My Device settings
  /// page), but keeps an open Chats/Nodes detail so a manual disconnect does not eject the user from
  /// an open conversation. Line of Sight and app-wide settings are radio-independent and preserved.
  func clearPerDeviceSelection() {
    if selectedTool?.requiresRadio == true {
      selectedTool = nil
    }
    if selectedSetting?.requiresDevice == true {
      selectedSetting = nil
    }
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
