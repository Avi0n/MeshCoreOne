import SwiftUI
import MC1Services
import CoreLocation

/// Manages tab selection, pending navigation targets, and cross-tab navigation coordination.
@Observable
@MainActor
public final class NavigationCoordinator {

    /// Selected tab index
    var selectedTab: Int = 0

    var tabBarVisibility: Visibility = .visible

    /// Whether the split container can tile all three columns side by side, measured at the shell.
    /// When true, row selection leaves the sidebar tiled open instead of collapsing it. Defaults to
    /// false so an unmeasured layout takes the safe collapse-on-selection branch rather than claiming
    /// wide while actually narrow and overlaying the sidebar on a too-narrow container.
    var isSidebarWide = false

    /// Contact to navigate to
    var pendingChatContact: ContactDTO?

    /// The currently selected route in the Chats split view detail pane
    var chatsSelectedRoute: ChatRoute?

    /// Channel to navigate to
    var pendingChannel: ChannelDTO?

    /// Room session to navigate to
    var pendingRoomSession: RemoteNodeSessionDTO?

    /// Room session a notification tap wants the user to authenticate into, set
    /// when the tapped room is not currently connected. ChatsView presents
    /// RoomAuthenticationSheet, mirroring a disconnected-room list tap.
    var pendingRoomAuthentication: RemoteNodeSessionDTO?

    /// Whether to navigate to Discovery
    var pendingDiscoveryNavigation = false

    /// Contact to navigate to (for detail view on Contacts tab)
    var pendingContactDetail: ContactDTO?

    /// The currently selected contact in the Nodes split view detail pane. Kept in memory
    /// only and never persisted: it carries a public key and `radioID` (identity-bearing
    /// data) that must not be written outside the encrypted backup envelope.
    var selectedContact: ContactDTO?

    /// Whether the Nodes split view detail pane is showing Discovery rather than a contact.
    /// Shared so the iPad content and detail columns agree on which detail to render.
    var nodesShowingDiscovery = false

    /// The selected tool on the Tools tab, shared so the iPad content and detail columns agree on
    /// which tool is open. Held here rather than in the split view so it survives the section-switch
    /// teardown of the inactive Tools tree. Radio-only tools clear on disconnect (`requiresRadio`).
    var selectedTool: ToolSelection?

    /// The selected settings page on the Settings tab, shared so the iPad content and detail columns
    /// agree on which page is open. Held here rather than in the split view so it survives the
    /// section-switch teardown of the inactive Settings tree. My Device pages clear on disconnect
    /// (`requiresDevice`).
    var selectedSetting: SettingsDetail?

    /// Message to scroll to after navigation (for reaction notifications)
    var pendingScrollToMessageID: UUID?

    /// Whether device menu tip donation is pending (waiting for valid tab)
    var pendingDeviceMenuTipDonation = false

    /// Coordinate the Map tab should drop a pin on and center, set by a chat coordinate tap.
    var pendingMapFocus: MapFocusRequest?

    /// Contact the Map tab should center on (the node's own marker, not a dropped pin), set by a
    /// contact-detail map tap. Held in memory only like `selectedContact` — it carries identity-bearing data.
    var pendingMapContact: ContactDTO?

    /// Pending contact-add confirmation triggered by a `meshcore://contact/add` link tap inside any chat surface.
    var pendingContactLink: MeshCoreURLParser.ContactResult?

    /// Pending channel-join confirmation triggered by a `meshcore://channel/...` link tap inside any chat surface.
    var pendingChannelLink: MeshCoreURLParser.ChannelResult?

    /// Pending hashtag-channel join sheet triggered by a `meshcoreone://hashtag/...` link tap inside any chat surface.
    var pendingHashtag: HashtagJoinRequest?

    // MARK: - Navigation

    func navigateToChat(with contact: ContactDTO, scrollToMessageID: UUID? = nil) {
        tabBarVisibility = .hidden  // Hide tab bar BEFORE switching tabs
        pendingChatContact = contact
        pendingScrollToMessageID = scrollToMessageID
        chatsSelectedRoute = .direct(contact)
        selectedTab = 0
    }

    func navigateToRoom(with session: RemoteNodeSessionDTO) {
        tabBarVisibility = .hidden  // Hide tab bar BEFORE switching tabs
        pendingRoomSession = session
        chatsSelectedRoute = .room(session)
        selectedTab = 0
    }

    func navigateToChannel(with channel: ChannelDTO, scrollToMessageID: UUID? = nil) {
        tabBarVisibility = .hidden
        pendingChannel = channel
        pendingScrollToMessageID = scrollToMessageID
        chatsSelectedRoute = .channel(channel)
        selectedTab = 0
    }

    func navigateToDiscovery() {
        pendingDiscoveryNavigation = true
        selectedTab = 1
    }

    func navigateToContacts() {
        selectedTab = 1
    }

    func navigateToContactDetail(_ contact: ContactDTO) {
        pendingContactDetail = contact
        selectedTab = 1
    }

    func navigateToMap(coordinate: CLLocationCoordinate2D) {
        pendingMapFocus = MapFocusRequest(latitude: coordinate.latitude,
                                          longitude: coordinate.longitude)
        selectedTab = AppTab.map.rawValue
    }

    func navigateToMap(contact: ContactDTO) {
        pendingMapContact = contact
        selectedTab = AppTab.map.rawValue
    }

    func clearPendingNavigation() {
        pendingChatContact = nil
    }

    func clearPendingRoomNavigation() {
        pendingRoomSession = nil
    }

    func clearPendingRoomAuthentication() {
        pendingRoomAuthentication = nil
    }

    func clearPendingChannelNavigation() {
        pendingChannel = nil
    }

    func clearPendingDiscoveryNavigation() {
        pendingDiscoveryNavigation = false
    }

    func clearPendingScrollToMessage() {
        pendingScrollToMessageID = nil
    }

    func clearPendingContactDetailNavigation() {
        pendingContactDetail = nil
    }

    func clearPendingMapFocus() {
        pendingMapFocus = nil
    }

    func clearPendingMapContact() {
        pendingMapContact = nil
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
        clearPerDeviceSelection()
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
            self.navigateToChat(with: contact)
        }

        // New contact notification tap
        notificationService.onNewContactNotificationTapped = { [weak self] contactID in
            guard let self else { return }
            if connectedDevice()?.manualAddContacts == true {
                self.navigateToDiscovery()
            } else {
                guard let contact = try? await dataStore.fetchContact(id: contactID) else {
                    self.navigateToContacts()
                    return
                }
                self.navigateToContactDetail(contact)
            }
        }

        // Channel notification tap
        notificationService.onChannelNotificationTapped = { [weak self] radioID, channelIndex in
            guard let self else { return }
            guard let channel = try? await dataStore.fetchChannel(radioID: radioID, index: channelIndex) else { return }
            self.navigateToChannel(with: channel)
        }

        // Reaction notification tap
        notificationService.onReactionNotificationTapped = { [weak self] contactID, channelIndex, radioID, messageID in
            guard let self else { return }
            if let contactID,
               let contact = try? await dataStore.fetchContact(id: contactID) {
                self.navigateToChat(with: contact, scrollToMessageID: messageID)
            } else if let channelIndex, let radioID,
                      let channel = try? await dataStore.fetchChannel(radioID: radioID, index: channelIndex) {
                self.navigateToChannel(with: channel, scrollToMessageID: messageID)
            }
        }

        // Room notification tap. Resolves the full session from the stable
        // sessionID carried in the notification. A connected room opens directly;
        // a disconnected room is routed to the auth sheet, mirroring the list-row
        // gate, instead of the iPad detail pane rendering an ungated read-only room.
        notificationService.onRoomNotificationTapped = { [weak self] sessionID in
            guard let self else { return }
            guard let session = try? await dataStore.fetchRemoteNodeSession(id: sessionID) else { return }
            if session.isConnected {
                self.navigateToRoom(with: session)
            } else {
                self.selectedTab = 0
                self.pendingRoomAuthentication = session
            }
        }
    }
}
