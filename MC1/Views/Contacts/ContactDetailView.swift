import MapKit
import MC1Services
import SwiftUI
import UIKit

/// Result of a ping operation
enum PingResult {
  case success(latencyMs: Int, snrThere: Double, snrBack: Double)
  case error(String)
}

/// Displays ping result with latency and bidirectional SNR
struct PingResultRow: View {
  let result: PingResult

  var body: some View {
    switch result {
    case let .success(latencyMs, snrThere, snrBack):
      let snrFormat = FloatingPointFormatStyle<Double>.number.precision(.fractionLength(2))
      Label {
        Text("\(latencyMs) ms  ·  SNR ↑ \(snrThere, format: snrFormat) dB  ↓ \(snrBack, format: snrFormat) dB")
          .font(.subheadline)
      } icon: {
        Image(systemName: "checkmark.circle.fill")
          .foregroundStyle(.green)
      }
      .accessibilityElement(children: .combine)
      .accessibilityLabel(L10n.Contacts.Contacts.Detail.pingSuccessLabel(latencyMs, Int(snrThere), Int(snrBack)))
    case let .error(message):
      Label {
        Text(message)
          .font(.subheadline)
          .foregroundStyle(.secondary)
      } icon: {
        Image(systemName: "exclamationmark.circle.fill")
          .foregroundStyle(.orange)
      }
      .accessibilityElement(children: .combine)
      .accessibilityLabel(L10n.Contacts.Contacts.Detail.pingFailureLabel(message))
    }
  }
}

/// Detailed view for a single contact
struct ContactDetailView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss

  let contact: ContactDTO
  let showFromDirectChat: Bool
  let onClearMessages: () -> Void

  /// Sheet types for the contact detail view
  private enum ActiveSheet: Identifiable, Hashable {
    case nodeAuth
    case repeaterStatus(RemoteNodeSessionDTO)
    case roomStatus(RemoteNodeSessionDTO)
    case nodeTelemetry(ContactDTO)
    case adminSettings(RemoteNodeSessionDTO)

    var id: String {
      switch self {
      case .nodeAuth: "auth"
      case let .repeaterStatus(session): "status-\(session.id)"
      case let .roomStatus(session): "room-status-\(session.id)"
      case let .nodeTelemetry(contact): "telemetry-\(contact.id)"
      case let .adminSettings(session): "admin-settings-\(session.id)"
      }
    }
  }

  @State private var currentContact: ContactDTO
  @State private var nickname = ""
  @State private var isEditingNickname = false
  @State private var showingBlockAlert = false
  @State private var showingDeleteAlert = false
  @State private var showingClearMessagesAlert = false
  @State private var isClearingMessages = false
  @State private var isSaving = false
  @State private var isTogglingFavorite = false
  @State private var errorMessage: String?
  @State private var pathViewModel = PathManagementViewModel()
  @State private var showRoomJoinSheet = false
  @State private var activeSheet: ActiveSheet?
  @State private var pendingSheet: ActiveSheet?
  // Admin access navigation state (separate from telemetry sheet flow)
  @State private var showRepeaterAdminAuth = false
  @State private var adminSession: RemoteNodeSessionDTO?
  /// QR sharing state
  @State private var showQRShareSheet = false
  // Ping state
  @State private var isPinging = false
  @State private var pingResult: PingResult?
  @State private var isSharing = false
  @State private var showShareSuccess = false

  init(contact: ContactDTO, showFromDirectChat: Bool = false, onClearMessages: @escaping () -> Void = {}) {
    self.contact = contact
    self.showFromDirectChat = showFromDirectChat
    self.onClearMessages = onClearMessages
    _currentContact = State(initialValue: contact)
  }

  var body: some View {
    List {
      // Profile header
      ContactProfileSection(
        currentContact: currentContact,
        contactTypeLabel: contactTypeLabel
      )

      // Quick actions
      ContactActionsSection(
        currentContact: currentContact,
        showFromDirectChat: showFromDirectChat,
        isPinging: isPinging,
        pingResult: pingResult,
        onJoinRoom: { showRoomJoinSheet = true },
        onShowTelemetry: {
          if currentContact.type == .chat {
            activeSheet = .nodeTelemetry(currentContact)
          } else {
            activeSheet = .nodeAuth
          }
        },
        onShowAdminAccess: {
          adminSession = nil
          showRepeaterAdminAuth = true
        },
        onPingRepeater: { Task { await pingRepeater() } },
        onShareQR: { showQRShareSheet = true },
        onShareViaAdvert: { Task { await shareContact() } },
        isSharing: isSharing,
        showShareSuccess: showShareSuccess
      )
      .themedRowBackground(theme)

      // Info section
      ContactInfoSection(
        currentContact: currentContact,
        nickname: $nickname,
        isEditingNickname: $isEditingNickname,
        isSaving: isSaving,
        onSaveNickname: { Task { await saveNickname() } }
      )
      .themedRowBackground(theme)

      // Location section (if available)
      if currentContact.hasLocation {
        ContactLocationSection(currentContact: currentContact)
          .themedRowBackground(theme)
      }

      // Network path controls
      ContactNetworkPathSection(
        currentContact: currentContact,
        pathViewModel: pathViewModel
      )
      .themedRowBackground(theme)

      // Technical details
      ContactTechnicalSection(
        currentContact: currentContact,
        contactTypeLabel: contactTypeLabel
      )
      .themedRowBackground(theme)

      // Danger zone
      ContactDangerSection(
        currentContact: currentContact,
        contactTypeLabel: contactTypeLabel,
        isClearingMessages: isClearingMessages,
        onClearMessages: { showingClearMessagesAlert = true },
        onToggleBlock: {
          if currentContact.isBlocked {
            Task { await toggleBlocked() }
          } else {
            showingBlockAlert = true
          }
        },
        onDelete: { showingDeleteAlert = true }
      )
      .themedRowBackground(theme)
    }
    .themedCanvas(theme)
    .errorAlert($errorMessage)
    .navigationBarTitleDisplayMode(.inline)
    .scrollRevealNavigationTitle(currentContact.displayName)
    .contentMargins(.top, 0, for: .scrollContent)
    .toolbar {
      ToolbarItem(placement: .topBarLeading) {
        Button {
          Task { await toggleFavorite() }
        } label: {
          Image(systemName: currentContact.isFavorite ? "star.fill" : "star")
            .foregroundStyle(currentContact.isFavorite ? .yellow : .secondary)
        }
        .disabled(isTogglingFavorite)
        .radioDisabled(for: appState.connectionState)
      }

      ToolbarItem(placement: .confirmationAction) {
        Button(L10n.Localizable.Common.done) { dismiss() }
      }
    }
    .alert(L10n.Contacts.Contacts.Detail.Alert.Block.title, isPresented: $showingBlockAlert) {
      Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {}
      Button(L10n.Contacts.Contacts.Action.block, role: .destructive) {
        Task {
          await toggleBlocked()
        }
      }
    } message: {
      Text(L10n.Contacts.Contacts.Detail.Alert.Block.message(currentContact.displayName))
    }
    .alert(L10n.Contacts.Contacts.Detail.Alert.Delete.title(contactTypeLabel), isPresented: $showingDeleteAlert) {
      Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {}
      Button(L10n.Contacts.Contacts.Common.delete, role: .destructive) {
        Task {
          await deleteContact()
        }
      }
    } message: {
      Text(L10n.Contacts.Contacts.Detail.Alert.Delete.message(currentContact.displayName))
    }
    .alert(L10n.Contacts.Contacts.Detail.Alert.ClearMessages.title, isPresented: $showingClearMessagesAlert) {
      Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {}
      Button(L10n.Contacts.Contacts.Detail.clearMessages, role: .destructive) {
        Task {
          await clearMessages()
        }
      }
    } message: {
      Text(L10n.Contacts.Contacts.Detail.Alert.ClearMessages.message(currentContact.displayName))
    }
    .onAppear {
      nickname = currentContact.nickname ?? ""
    }
    .task {
      pathViewModel.configure(
        dataStore: { appState.services?.dataStore },
        contactService: { appState.services?.contactService },
        connectedDevice: { appState.connectedDevice }
      ) {
        Task { @MainActor in
          await refreshContact()
        }
      }
      await pathViewModel.loadContacts(radioID: currentContact.radioID)

      // Fetch fresh contact data from device to catch external changes
      // (e.g., user modified path in official MeshCore app)
      if let freshContact = try? await appState.services?.contactService.getContact(
        radioID: currentContact.radioID,
        publicKey: currentContact.publicKey
      ) {
        currentContact = freshContact
      }

      // React to path discovery push responses while this view is open.
      // The view-scoped task cancels the subscription on dismiss; the
      // stream is multicast, so a second detail column (iPad split view)
      // can subscribe concurrently.
      guard let advertisementService = appState.services?.advertisementService else { return }
      for await event in advertisementService.events() {
        if case let .pathDiscoveryResponse(response) = event {
          pathViewModel.handleDiscoveryResponse(hopCount: response.outHopCount)
        }
      }
    }
    .onDisappear {
      pathViewModel.cancelDiscovery()
    }
    .sheet(
      isPresented: $pathViewModel.showingPathEditor,
      onDismiss: { pathViewModel.insertionIntent = nil }
    ) {
      PathEditingSheet(viewModel: pathViewModel, contact: currentContact)
    }
    .alert(
      L10n.Contacts.Contacts.Detail.Alert.pathError,
      isPresented: Binding(
        get: { pathViewModel.errorMessage != nil },
        set: { if !$0 { pathViewModel.errorMessage = nil } }
      )
    ) {
      Button(L10n.Contacts.Contacts.Common.ok, role: .cancel) {}
    } message: {
      Text(pathViewModel.errorMessage ?? L10n.Contacts.Contacts.Common.errorOccurred)
    }
    .alert(L10n.Contacts.Contacts.Detail.Alert.pathDiscovery, isPresented: $pathViewModel.showDiscoveryResult) {
      Button(L10n.Contacts.Contacts.Common.ok, role: .cancel) {}
    } message: {
      Text(pathViewModel.discoveryResult?.description ?? "")
    }
    .sheet(isPresented: $showRoomJoinSheet) {
      if let role = RemoteNodeRole(contactType: currentContact.type) {
        NodeAuthenticationSheet(contact: currentContact, role: role) { session in
          // Navigate to Chats tab with the room conversation
          appState.navigation.navigateToRoom(with: session)
        }
        .presentationSizing(.page)
      }
    }
    .sheet(item: $activeSheet, onDismiss: presentPendingSheet) { sheet in
      switch sheet {
      case .nodeAuth:
        if let role = RemoteNodeRole(contactType: currentContact.type) {
          NodeAuthenticationSheet(
            contact: currentContact,
            role: role,
            customTitle: L10n.Contacts.Contacts.Detail.telemetryAccess
          ) { session in
            if currentContact.type == .room {
              pendingSheet = .roomStatus(session)
            } else {
              pendingSheet = .repeaterStatus(session)
            }
            activeSheet = nil // Triggers dismissal, then onDismiss fires
          }
          .presentationSizing(.page)
        }
      case let .repeaterStatus(session):
        RepeaterStatusView(session: session)
      case let .roomStatus(session):
        RoomStatusView(session: session)
      case let .nodeTelemetry(contact):
        NodeTelemetryView(contact: contact)
      case let .adminSettings(session):
        // A sheet with its own stack, not a push onto the value/path-based Contacts stack:
        // the telemetry tab's history graphs push value-based routes, and pushing this screen
        // there instead would rebuild it and reset the selected tab whenever a graph is tapped.
        NavigationStack {
          Group {
            if session.isRoom {
              RoomSettingsView(session: session)
            } else {
              RepeaterSettingsView(session: session)
            }
          }
          .toolbar {
            ToolbarItem(placement: .confirmationAction) {
              Button(L10n.RemoteNodes.RemoteNodes.done) { activeSheet = nil }
            }
          }
        }
        .presentationSizing(.page)
      }
    }
    .sheet(isPresented: $showRepeaterAdminAuth, onDismiss: {
      // Trigger navigation after sheet is fully dismissed to avoid race conditions
      if let session = adminSession {
        if session.isAdmin {
          activeSheet = .adminSettings(session)
        } else if session.isRoom {
          activeSheet = .roomStatus(session)
        } else {
          activeSheet = .repeaterStatus(session)
        }
      }
    }) {
      if let role = RemoteNodeRole(contactType: currentContact.type) {
        NodeAuthenticationSheet(contact: currentContact, role: role) { session in
          adminSession = session
          showRepeaterAdminAuth = false
          // Navigation triggers in onDismiss above
        }
        .presentationSizing(.page)
      }
    }
    .sheet(isPresented: $showQRShareSheet) {
      ContactQRShareSheet(
        contactName: currentContact.name,
        publicKey: currentContact.publicKey,
        contactType: currentContact.type
      )
      .presentationDetents([.medium, .large])
      .presentationDragIndicator(.visible)
    }
    .navigationDestination(for: ContactRoute.TelemetryHistory.self) { route in
      TelemetryHistoryOverviewView(
        publicKey: route.publicKey,
        radioID: route.radioID,
        showNeighbors: route.showNeighbors
      )
    }
  }

  // MARK: - Sheet Management

  private func presentPendingSheet() {
    if let next = pendingSheet {
      pendingSheet = nil
      activeSheet = next
    }
  }

  // MARK: - Actions

  private func toggleFavorite() async {
    isTogglingFavorite = true
    defer { isTogglingFavorite = false }

    do {
      try await appState.services?.contactService.setContactFavorite(
        currentContact.id,
        isFavorite: !currentContact.isFavorite
      )
      await refreshContact()
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  private func toggleBlocked() async {
    do {
      try await appState.services?.contactService.updateContactPreferences(
        contactID: currentContact.id,
        isBlocked: !currentContact.isBlocked
      )
      await refreshContact()
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  private func deleteContact() async {
    do {
      try await appState.services?.contactService.removeContact(
        radioID: currentContact.radioID,
        publicKey: currentContact.publicKey
      )
      dismiss()
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  private func clearMessages() async {
    guard let contactService = appState.services?.contactService else {
      errorMessage = L10n.Contacts.Contacts.Detail.Error.servicesUnavailable
      return
    }

    isClearingMessages = true
    errorMessage = nil

    do {
      try await contactService.clearContactMessages(contactID: currentContact.id)
      await appState.services?.notificationService.removeDeliveredNotifications(forContactID: currentContact.id)
      await appState.services?.notificationService.updateBadgeCount()
      onClearMessages()
      dismiss()
    } catch {
      errorMessage = error.userFacingMessage
      isClearingMessages = false
    }
  }

  private func shareContact() async {
    isSharing = true
    do {
      try await appState.services?.contactService.shareContact(publicKey: currentContact.publicKey)
      isSharing = false
      withAnimation { showShareSuccess = true }
      try? await Task.sleep(for: .seconds(1.5))
      withAnimation { showShareSuccess = false }
    } catch ContactServiceError.shareContactUnavailable {
      isSharing = false
      errorMessage = L10n.Contacts.Contacts.Detail.shareContactUnavailable
    } catch {
      isSharing = false
      errorMessage = error.userFacingMessage
    }
  }

  private func pingRepeater() async {
    guard !isPinging else { return }
    isPinging = true
    pingResult = nil
    pingResult = await PingHelper.zeroHopPing(contact: currentContact, appState: appState)
    isPinging = false
  }

  private func refreshContact() async {
    if let updated = try? await appState.services?.dataStore.fetchContact(id: currentContact.id) {
      currentContact = updated
    }
  }

  // MARK: - Helpers

  private var contactTypeLabel: String {
    currentContact.type.localizedName
  }

  private func saveNickname() async {
    isSaving = true
    do {
      try await appState.services?.contactService.updateContactPreferences(
        contactID: currentContact.id,
        nickname: nickname.isEmpty ? nil : nickname
      )
      await refreshContact()
    } catch {
      errorMessage = error.userFacingMessage
    }
    isEditingNickname = false
    isSaving = false
  }
}

// MARK: - Extracted Views

private struct ContactDetailAvatarView: View {
  let contact: ContactDTO

  var body: some View {
    switch contact.type {
    case .chat:
      ContactAvatar(contact: contact, size: 150)
    case .repeater:
      NodeAvatar(publicKey: contact.publicKey, role: .repeater, size: 150)
    case .room:
      NodeAvatar(publicKey: contact.publicKey, role: .roomServer, size: 150)
    }
  }
}

private struct ContactProfileSection: View {
  let currentContact: ContactDTO
  let contactTypeLabel: String

  var body: some View {
    Section {
      VStack(spacing: 12) {
        ContactDetailAvatarView(contact: currentContact)

        VStack(spacing: 4) {
          Text(currentContact.displayName)
            .font(.title2)
            .bold()

          Text(contactTypeLabel)
            .font(.subheadline)
            .foregroundStyle(.secondary)

          // Status indicators
          VStack(spacing: 8) {
            if currentContact.isBlocked {
              HStack(spacing: 4) {
                Image(systemName: "hand.raised.fill")
                Text(L10n.Contacts.Contacts.Detail.blocked)
              }
              .font(.caption)
              .foregroundStyle(.red)
            }

            if currentContact.hasLocation {
              HStack(spacing: 4) {
                Image(systemName: "location.fill")
                Text(L10n.Contacts.Contacts.Detail.hasLocation)
              }
              .font(.caption)
              .foregroundStyle(.green)
            }
          }
          .padding(.top, 4)
        }
      }
      .frame(maxWidth: .infinity)
      .listRowBackground(Color.clear)
    }
  }
}

private struct ContactActionsSection: View {
  @Environment(\.appState) private var appState

  let currentContact: ContactDTO
  let showFromDirectChat: Bool
  let isPinging: Bool
  let pingResult: PingResult?
  let onJoinRoom: () -> Void
  let onShowTelemetry: () -> Void
  let onShowAdminAccess: () -> Void
  let onPingRepeater: () -> Void
  let onShareQR: () -> Void
  let onShareViaAdvert: () -> Void
  let isSharing: Bool
  let showShareSuccess: Bool

  var body: some View {
    Section {
      // Role-specific actions based on contact type
      switch currentContact.type {
      case .room:
        Button(action: onJoinRoom) {
          Label(L10n.Contacts.Contacts.Detail.joinRoom, systemImage: "door.left.hand.open")
        }
        .radioDisabled(for: appState.connectionState)

        NodeActionRows(
          contact: currentContact,
          pingLabel: L10n.Contacts.Contacts.Detail.ping,
          isPinging: isPinging,
          pingResult: pingResult,
          connectionState: appState.connectionState,
          onShowTelemetry: onShowTelemetry,
          onShowAdminAccess: onShowAdminAccess,
          onPing: onPingRepeater
        )

      case .repeater:
        NodeActionRows(
          contact: currentContact,
          pingLabel: L10n.Contacts.Contacts.Detail.ping,
          isPinging: isPinging,
          pingResult: pingResult,
          connectionState: appState.connectionState,
          onShowTelemetry: onShowTelemetry,
          onShowAdminAccess: onShowAdminAccess,
          onPing: onPingRepeater
        )

      case .chat:
        // Send message - only show when NOT from direct chat and NOT blocked
        if !showFromDirectChat, !currentContact.isBlocked {
          Button {
            appState.navigation.navigateToChat(with: currentContact)
          } label: {
            Label(L10n.Contacts.Contacts.Detail.sendMessage, systemImage: "message.fill")
          }
          .radioDisabled(for: appState.connectionState)
        }

        Button(action: onShowTelemetry) {
          Label(L10n.Contacts.Contacts.Detail.telemetry, systemImage: "chart.line.uptrend.xyaxis")
        }
        .radioDisabled(for: appState.connectionState)

        NavigationLink(value: ContactRoute.TelemetryHistory(
          publicKey: currentContact.publicKey,
          radioID: currentContact.radioID,
          showNeighbors: false
        )) {
          Label(L10n.Contacts.Contacts.Detail.savedHistory, systemImage: "clock.arrow.trianglehead.counterclockwise.rotate.90")
            .foregroundStyle(.tint)
        }
      }

      // Share Contact via QR
      Button(action: onShareQR) {
        Label(L10n.Contacts.Contacts.Detail.shareContact, systemImage: "square.and.arrow.up")
      }

      // Share Contact via Advert
      Button(action: onShareViaAdvert) {
        if isSharing || showShareSuccess {
          AsyncActionLabel(isLoading: isSharing, showSuccess: showShareSuccess) {
            EmptyView()
          }
        } else {
          Label(L10n.Contacts.Contacts.Detail.shareViaAdvert, systemImage: "antenna.radiowaves.left.and.right")
        }
      }
      .radioDisabled(for: appState.connectionState, or: isSharing || showShareSuccess)
    }
  }
}

private struct NodeActionRows: View {
  let contact: ContactDTO
  let pingLabel: String
  let isPinging: Bool
  let pingResult: PingResult?
  let connectionState: DeviceConnectionState
  let onShowTelemetry: () -> Void
  let onShowAdminAccess: () -> Void
  let onPing: () -> Void

  var body: some View {
    Button(action: onShowTelemetry) {
      Label(L10n.Contacts.Contacts.Detail.telemetry, systemImage: "chart.line.uptrend.xyaxis")
    }
    .radioDisabled(for: connectionState)

    NavigationLink(value: ContactRoute.TelemetryHistory(
      publicKey: contact.publicKey,
      radioID: contact.radioID
    )) {
      Label(L10n.Contacts.Contacts.Detail.savedHistory, systemImage: "clock.arrow.trianglehead.counterclockwise.rotate.90")
        .foregroundStyle(.tint)
    }

    Button(action: onShowAdminAccess) {
      Label(L10n.Contacts.Contacts.Detail.management, systemImage: "gearshape.2")
    }
    .radioDisabled(for: connectionState)

    Button(action: onPing) {
      HStack {
        Label(pingLabel, systemImage: "wave.3.right")
        if isPinging {
          Spacer()
          ProgressView()
        }
      }
    }
    .disabled(isPinging)
    .radioDisabled(for: connectionState)

    if let result = pingResult {
      PingResultRow(result: result)
    }
  }
}

private struct ContactInfoSection: View {
  let currentContact: ContactDTO
  @Binding var nickname: String
  @Binding var isEditingNickname: Bool
  let isSaving: Bool
  let onSaveNickname: () -> Void

  var body: some View {
    Section {
      // Nickname
      HStack {
        Text(L10n.Contacts.Contacts.Detail.nickname)

        Spacer()

        if isEditingNickname {
          TextField(L10n.Contacts.Contacts.Detail.nickname, text: $nickname)
            .textFieldStyle(.roundedBorder)
            .frame(width: 150)
            .onSubmit {
              onSaveNickname()
            }

          Button(L10n.Contacts.Contacts.Common.save) {
            onSaveNickname()
          }
          .disabled(isSaving)
        } else {
          Text(currentContact.nickname ?? L10n.Contacts.Contacts.Detail.nicknameNone)
            .foregroundStyle(.secondary)

          Button(action: {
            isEditingNickname = true
          }) {
            Image(systemName: "pencil")
          }
          .buttonStyle(.borderless)
        }
      }

      // Original name
      HStack {
        Text(L10n.Contacts.Contacts.Detail.name)
        Spacer()
        Text(currentContact.name)
          .foregroundStyle(.secondary)
      }

      // Last advert
      if currentContact.lastAdvertTimestamp > 0 {
        HStack {
          Text(L10n.Contacts.Contacts.Detail.lastAdvert)
          Spacer()
          ConversationTimestamp(date: Date(timeIntervalSince1970: TimeInterval(currentContact.lastAdvertTimestamp)), font: .body)
        }
      }

      // Unread count
      if currentContact.unreadCount > 0 {
        HStack {
          Text(L10n.Contacts.Contacts.Detail.unreadMessages)
          Spacer()
          Text(currentContact.unreadCount, format: .number)
            .foregroundStyle(.blue)
        }
      }
    } header: {
      Text(L10n.Contacts.Contacts.Detail.info)
    }
  }
}

private struct ContactLocationSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.appTheme) private var theme

  let currentContact: ContactDTO

  @State private var showFullMap = false

  var body: some View {
    Section {
      // Mini map
      ZStack(alignment: .topTrailing) {
        MC1MapView(
          points: [MapPoint(
            id: currentContact.id,
            coordinate: currentContact.coordinate,
            pinStyle: currentContact.type.pinStyle,
            label: currentContact.displayName,
            isClusterable: false,
            hopIndex: nil,
            badgeText: nil
          )],
          lines: [],
          mapStyle: .standard,
          isDarkMode: colorScheme == .dark,
          isOffline: !appState.offlineMapService.isNetworkAvailable,
          showLabels: false,
          showsUserLocation: false,
          isInteractive: false,
          showsScale: false,
          cameraRegion: .constant(MKCoordinateRegion(
            center: currentContact.coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
          )),
          cameraRegionVersion: currentContact.latitude.hashValue ^ currentContact.longitude.hashValue,
          onPointTap: { _, _ in showFullMap = true },
          onMapTap: { _ in showFullMap = true },
          onCameraRegionChange: nil
        )

        Image(systemName: "arrow.up.left.and.arrow.down.right")
          .font(.caption.weight(.semibold))
          .padding(6)
          .background(.regularMaterial, in: .rect(cornerRadius: 6))
          .padding(8)
          .allowsHitTesting(false)
          .accessibilityHidden(true)
      }
      .frame(height: 200)
      .clipShape(.rect(cornerRadius: 12))
      .listRowInsets(EdgeInsets())
      .listRowBackground(Color.clear)
      .padding(.bottom, 8)
      .listRowSeparator(.hidden)
      .sheet(isPresented: $showFullMap) {
        ContactFullMapView(contact: currentContact)
      }

      // Coordinates
      HStack {
        Text(L10n.Contacts.Contacts.Detail.coordinates)
        Spacer()
        Text("\(currentContact.latitude, format: .number.precision(.fractionLength(4))), \(currentContact.longitude, format: .number.precision(.fractionLength(4)))")
          .foregroundStyle(.secondary)
      }
      .listRowBackground(
        UnevenRoundedRectangle(topLeadingRadius: 10, topTrailingRadius: 10)
          .fill(theme.surfaces?.card ?? Color(.secondarySystemGroupedBackground))
      )

      // Open in Maps
      Button {
        openInMaps()
      } label: {
        Label(L10n.Contacts.Contacts.Detail.openInMaps, systemImage: "map")
      }
    } header: {
      Text(L10n.Contacts.Contacts.Detail.location)
    }
  }

  private func openInMaps() {
    let mapItem = MKMapItem(placemark: MKPlacemark(coordinate: currentContact.coordinate))
    mapItem.name = currentContact.displayName
    mapItem.openInMaps()
  }
}

private struct ContactFullMapView: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss
  @Environment(\.colorScheme) private var colorScheme

  let contact: ContactDTO

  @State private var cameraRegion: MKCoordinateRegion?
  @State private var cameraRegionVersion = 0

  var body: some View {
    NavigationStack {
      MC1MapView(
        points: [MapPoint(
          id: contact.id,
          coordinate: contact.coordinate,
          pinStyle: contact.type.pinStyle,
          label: contact.displayName,
          isClusterable: false,
          hopIndex: nil,
          badgeText: nil
        )],
        lines: [],
        mapStyle: .standard,
        isDarkMode: colorScheme == .dark,
        isOffline: !appState.offlineMapService.isNetworkAvailable,
        showLabels: true,
        showsUserLocation: true,
        isInteractive: true,
        showsScale: true,
        cameraRegion: $cameraRegion,
        cameraRegionVersion: cameraRegionVersion,
        onPointTap: nil,
        onMapTap: nil,
        onCameraRegionChange: { cameraRegion = $0 }
      )
      .ignoresSafeArea()
      .navigationTitle(contact.displayName)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Localizable.Common.done) { dismiss() }
        }
      }
      .onAppear {
        cameraRegion = MKCoordinateRegion(
          center: contact.coordinate,
          span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
        )
        cameraRegionVersion = 1
      }
    }
  }
}

private struct ContactNetworkPathSection: View {
  @Environment(\.appState) private var appState

  let currentContact: ContactDTO
  let pathViewModel: PathManagementViewModel

  private var pathDisplayWithNames: String {
    let pathData = currentContact.outPath
    let byteLength = currentContact.pathByteLength
    let hashSize = currentContact.pathHashSize
    guard byteLength > 0 else { return L10n.Contacts.Contacts.Route.direct }

    let relevantPath = pathData.prefix(byteLength)
    return stride(from: 0, to: relevantPath.count, by: hashSize).map { start in
      let end = min(start + hashSize, relevantPath.count)
      let hopBytes = Data(relevantPath[start..<end])
      if let name = pathViewModel.resolveHashToName(hopBytes) {
        return name
      }
      return hopBytes.uppercaseHexString()
    }.joined(separator: " \u{2192} ")
  }

  private func routeDisplayText(pathDisplay: String) -> String {
    if currentContact.isFloodRouted {
      L10n.Contacts.Contacts.Route.flood
    } else if currentContact.pathHopCount == 0 {
      L10n.Contacts.Contacts.Route.direct
    } else {
      pathDisplay
    }
  }

  private var networkPathFooterText: String {
    if currentContact.isFloodRouted {
      L10n.Contacts.Contacts.Detail.floodFooter
    } else {
      L10n.Contacts.Contacts.Detail.pathFooter
    }
  }

  private func pathAccessibilityLabel(pathDisplay: String) -> String {
    if currentContact.isFloodRouted {
      L10n.Contacts.Contacts.Detail.routeFlood
    } else if currentContact.pathHopCount == 0 {
      L10n.Contacts.Contacts.Detail.routeDirect
    } else {
      L10n.Contacts.Contacts.Detail.routePrefix(pathDisplay)
    }
  }

  var body: some View {
    let pathDisplay = pathDisplayWithNames
    let isRoutePopulated = !currentContact.isFloodRouted && currentContact.pathHopCount > 0
    let routeIDPrefixes = currentContact.pathNodesHex.joined(separator: ",")
    return Section {
      // Current routing path
      Label {
        VStack(alignment: .leading, spacing: 4) {
          Text(L10n.Contacts.Contacts.Detail.route)
            .font(.subheadline)
            .foregroundStyle(.secondary)

          Text(routeDisplayText(pathDisplay: pathDisplay))
            .font(.caption.monospaced())
            .foregroundStyle(.primary)
        }
      } icon: {
        Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
          .foregroundStyle(.secondary)
      }
      .accessibilityElement(children: .combine)
      .accessibilityLabel(pathAccessibilityLabel(pathDisplay: pathDisplay))
      .copyRouteContextMenu(route: routeIDPrefixes, enabled: isRoutePopulated)
      .accessibilityAction(named: L10n.Contacts.Contacts.Detail.copyRoute) {
        if isRoutePopulated { UIPasteboard.general.string = routeIDPrefixes }
      }

      // Hops away: only when a deliberate or discovered out-path exists, not the passively
      // heard inbound advert hops
      if !currentContact.isFloodRouted {
        Label {
          VStack(alignment: .leading, spacing: 4) {
            Text(L10n.Contacts.Contacts.Detail.hopsAway)
              .font(.subheadline)
              .foregroundStyle(.secondary)

            Text(currentContact.pathHopCount, format: .number)
              .font(.caption.monospaced())
              .foregroundStyle(.primary)
          }
        } icon: {
          Image(systemName: "arrowshape.bounce.right")
            .foregroundStyle(.secondary)
        }
      }

      // Path Discovery button (prominent)
      if pathViewModel.isDiscovering {
        VStack(alignment: .leading, spacing: 4) {
          HStack {
            Label(L10n.Contacts.Contacts.Detail.discoveringPath, systemImage: "antenna.radiowaves.left.and.right")
            Spacer()
            ProgressView()
            Button(L10n.Contacts.Contacts.Common.cancel) {
              pathViewModel.cancelDiscovery()
            }
            .buttonStyle(.borderless)
            .font(.subheadline)
          }

          if let remaining = pathViewModel.discoverySecondsRemaining, remaining > 0 {
            Text(L10n.Contacts.Contacts.Detail.secondsRemaining(remaining))
              .font(.caption)
              .foregroundStyle(.secondary)
          }
        }
      } else {
        Button {
          Task {
            await pathViewModel.discoverPath(for: currentContact)
          }
        } label: {
          Label(L10n.Contacts.Contacts.Detail.discoverPath, systemImage: "antenna.radiowaves.left.and.right")
        }
        .radioDisabled(for: appState.connectionState)
      }

      // Edit Path button (secondary)
      Button {
        Task {
          await pathViewModel.loadContacts(radioID: currentContact.radioID)
          pathViewModel.initializeEditablePath(from: currentContact)
          pathViewModel.showingPathEditor = true
        }
      } label: {
        Label(L10n.Contacts.Contacts.Detail.editPath, systemImage: "pencil")
      }
      .radioDisabled(for: appState.connectionState)

      // Reset Path button (destructive, disabled when already flood)
      Button(role: .destructive) {
        Task {
          await pathViewModel.resetPath(for: currentContact)
        }
      } label: {
        HStack {
          Label(L10n.Contacts.Contacts.Detail.resetPath, systemImage: "arrow.triangle.2.circlepath")
          if pathViewModel.isSettingPath {
            Spacer()
            ProgressView()
              .scaleEffect(0.8)
          }
        }
      }
      .radioDisabled(for: appState.connectionState, or: pathViewModel.isSettingPath || currentContact.isFloodRouted)
    } header: {
      Text(L10n.Contacts.Contacts.Detail.outboundPath)
    } footer: {
      Text(networkPathFooterText)
    }
  }
}

private struct ContactTechnicalSection: View {
  let currentContact: ContactDTO
  let contactTypeLabel: String

  var body: some View {
    Section {
      // Public key
      VStack(alignment: .leading, spacing: 4) {
        Text(L10n.Contacts.Contacts.Detail.publicKey)
          .font(.caption)
          .foregroundStyle(.secondary)
        Text(currentContact.publicKey.uppercaseHexString(separator: " "))
          .font(.system(.caption, design: .monospaced))
          .textSelection(.enabled)
      }

      // Contact type
      HStack {
        Text(L10n.Contacts.Contacts.Detail.type)
        Spacer()
        Text(contactTypeLabel)
          .foregroundStyle(.secondary)
      }
    } header: {
      Text(L10n.Contacts.Contacts.Detail.technical)
    }
  }
}

private struct ContactDangerSection: View {
  @Environment(\.appState) private var appState

  let currentContact: ContactDTO
  let contactTypeLabel: String
  let isClearingMessages: Bool
  let onClearMessages: () -> Void
  let onToggleBlock: () -> Void
  let onDelete: () -> Void

  var body: some View {
    Section {
      if currentContact.type == .chat {
        Button(action: onToggleBlock) {
          Label(
            currentContact.isBlocked ? L10n.Contacts.Contacts.Detail.unblockContact : L10n.Contacts.Contacts.Detail.blockContact,
            systemImage: currentContact.isBlocked ? "hand.raised.slash" : "hand.raised"
          )
        }
        .radioDisabled(for: appState.connectionState)

        Button(role: .destructive, action: onClearMessages) {
          HStack {
            Label(L10n.Contacts.Contacts.Detail.clearMessages, systemImage: "xmark.circle")
            if isClearingMessages {
              Spacer()
              ProgressView()
            }
          }
        }
        .disabled(isClearingMessages)
      }

      Button(role: .destructive, action: onDelete) {
        Label(L10n.Contacts.Contacts.Detail.deleteType(contactTypeLabel), systemImage: "trash")
      }
      .radioDisabled(for: appState.connectionState)
    } header: {
      Text(L10n.Contacts.Contacts.Detail.dangerZone)
    }
  }
}

#Preview("Default") {
  NavigationStack {
    ContactDetailView(contact: ContactDTO(from: Contact(
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: 32),
      name: "Alice",
      latitude: 37.7749,
      longitude: -122.4194,
      isFavorite: true
    )))
  }
  .environment(\.appState, AppState())
}

#Preview("From Direct Chat") {
  NavigationStack {
    ContactDetailView(
      contact: ContactDTO(from: Contact(
        radioID: UUID(),
        publicKey: Data(repeating: 0x42, count: 32),
        name: "Alice",
        latitude: 37.7749,
        longitude: -122.4194,
        isFavorite: true
      )),
      showFromDirectChat: true
    )
  }
  .environment(\.appState, AppState())
}

private extension View {
  /// Gates the whole `contextMenu`, not the button inside it: an always-present
  /// menu with an empty body still triggers the press-and-hold lift with no items.
  @ViewBuilder
  func copyRouteContextMenu(route: String, enabled: Bool) -> some View {
    if enabled {
      contextMenu {
        Button {
          UIPasteboard.general.string = route
        } label: {
          Label(L10n.Contacts.Contacts.Detail.copyRoute, systemImage: "doc.on.doc")
        }
      }
    } else {
      self
    }
  }
}
