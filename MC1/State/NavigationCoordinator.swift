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

    /// Contact to navigate to
    var pendingChatContact: ContactDTO?

    /// The currently selected route in the Chats split view detail pane
    var chatsSelectedRoute: ChatRoute?

    /// Channel to navigate to
    var pendingChannel: ChannelDTO?

    /// Room session to navigate to
    var pendingRoomSession: RemoteNodeSessionDTO?

    /// Whether to navigate to Discovery
    var pendingDiscoveryNavigation = false

    /// Contact to navigate to (for detail view on Contacts tab)
    var pendingContactDetail: ContactDTO?

    /// Message to scroll to after navigation (for reaction notifications)
    var pendingScrollToMessageID: UUID?

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

    func clearPendingNavigation() {
        pendingChatContact = nil
    }

    func clearPendingRoomNavigation() {
        pendingRoomSession = nil
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

    func clearPendingContactLink() {
        pendingContactLink = nil
    }

    func clearPendingChannelLink() {
        pendingChannelLink = nil
    }

    func clearPendingHashtag() {
        pendingHashtag = nil
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
    }
}
