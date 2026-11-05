import MC1Services
import os
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "NodeAuthenticationSheet")

/// Reusable password entry sheet for remote nodes
struct NodeAuthenticationSheet: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss

  let contact: ContactDTO
  let role: RemoteNodeRole
  let requireAdmin: Bool
  /// When true, hides the Node Details section (used when re-joining known rooms from chat list)
  let hideNodeDetails: Bool
  /// Optional custom title. If nil, uses default based on role ("Join Room" or "Admin Access")
  let customTitle: String?
  let onSuccess: (RemoteNodeSessionDTO) -> Void

  @State private var password: String = ""
  @State private var rememberPassword = true
  @State private var useFloodRouting: Bool
  @State private var didResetPath = false
  @State private var isRetryingViaFlood = false
  @State private var isAuthenticating = false
  @State private var errorMessage: String?
  @State private var hasSavedPassword = false
  @State private var pathViewModel = NodeAuthPathViewModel()

  // Countdown state
  @State private var authSecondsRemaining: Int?
  @State private var authStartTime: Date?
  @State private var authTimeoutSeconds: Int?
  @State private var countdownTask: Task<Void, Never>?
  @State private var authenticationTask: Task<Void, Never>?

  @State private var authEpoch = Epoch()

  private let maxPasswordBytes = ProtocolLimits.maxRemotePasswordBytes

  init(
    contact: ContactDTO,
    role: RemoteNodeRole,
    hideNodeDetails: Bool = false,
    requireAdmin: Bool = true,
    customTitle: String? = nil,
    onSuccess: @escaping (RemoteNodeSessionDTO) -> Void
  ) {
    self.contact = contact
    self.role = role
    self.hideNodeDetails = hideNodeDetails
    self.requireAdmin = requireAdmin
    self.customTitle = customTitle
    self.onSuccess = onSuccess
    _useFloodRouting = State(initialValue: contact.isFloodRouted)
  }

  var body: some View {
    NavigationStack {
      Form {
        if !hideNodeDetails {
          makeNodeDetailsSection()
        }
        makeAuthenticationSection()
        makePathSection()
        makeConnectButton()
      }
      .themedCanvas(theme)
      .navigationTitle(customTitle ?? (role == .roomServer ? L10n.RemoteNodes.RemoteNodes.Auth.joinRoom : L10n.RemoteNodes.RemoteNodes.Auth.management))
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.RemoteNodes.RemoteNodes.Auth.cancel) {
            cancelAuthentication()
            dismiss()
          }
        }
      }
      .task {
        guard let source = appState.connectionSnapshot(for: contact.radioID) else { return }
        let context = ConnectionCallbackContext(source)
        if let saved = await source.services.remoteNodeService.retrievePassword(forContact: contact) {
          guard accepts(context), password.isEmpty, !isAuthenticating else { return }
          password = saved
          hasSavedPassword = true
        }
      }
      .task {
        guard !contact.isFloodRouted, contact.pathHopCount > 0,
              let source = appState.connectionSnapshot(for: contact.radioID) else { return }
        let context = ConnectionCallbackContext(source)
        await pathViewModel.load(
          contact: contact,
          services: source.services,
          radioID: contact.radioID,
          userLocation: appState.bestAvailableLocation,
          isCurrent: { accepts(context) }
        )
      }
      .sensoryFeedback(.error, trigger: errorMessage)
      .onChange(of: appState.connectionManager.currentConnection?.generation) { _, _ in
        cancelAuthentication()
        didResetPath = false
      }
      .onDisappear { cancelAuthentication() }
    }
  }

  // MARK: - Sections

  private func makeNodeDetailsSection() -> some View {
    NodeDetailsSection(
      displayName: contact.displayName,
      role: role
    )
  }

  private func makeAuthenticationSection() -> some View {
    AuthenticationSection(
      password: $password,
      rememberPassword: $rememberPassword,
      errorMessage: $errorMessage,
      authSecondsRemaining: $authSecondsRemaining,
      isRetryingViaFlood: isRetryingViaFlood,
      maxPasswordBytes: maxPasswordBytes
    )
  }

  private func makePathSection() -> some View {
    PathSection(
      contact: contact,
      useFloodRouting: $useFloodRouting,
      pathViewModel: pathViewModel
    )
  }

  private func makeConnectButton() -> some View {
    ConnectButton(
      role: role,
      isAuthenticating: isAuthenticating,
      onAuthenticate: { authenticate() }
    )
  }

  // MARK: - Authentication

  private func cancelAuthentication() {
    authEpoch.bump()
    authenticationTask?.cancel()
    authenticationTask = nil
    isAuthenticating = false
    isRetryingViaFlood = false
    cleanupCountdownState()
  }

  private func authenticate() {
    guard password.utf8.count <= maxPasswordBytes else {
      errorMessage = RemoteNodeError.invalidPasswordLength.userFacingMessage
      return
    }
    guard let source = appState.connectionSnapshot(for: contact.radioID) else {
      errorMessage = RemoteNodeError.notConnected.userFacingMessage
      return
    }
    let context = ConnectionCallbackContext(source)
    let submittedPassword = password
    let submittedRememberPassword = rememberPassword
    cancelAuthentication()
    let ticket = authEpoch.ticket()
    errorMessage = nil
    isAuthenticating = true

    authenticationTask = Task {
      do {
        guard accepts(context, ticket: ticket) else { return }
        let services = source.services

        // Once any reset has run, later attempts flood-route even if this contact snapshot still has a path.
        let pathLength: UInt8
        if useFloodRouting && !contact.isFloodRouted && !didResetPath {
          try await services.contactService.resetPath(
            radioID: contact.radioID,
            publicKey: contact.publicKey
          )
          guard accepts(context, ticket: ticket) else { return }
          didResetPath = true
          pathLength = PacketBuilder.floodPathSentinel
        } else if useFloodRouting || didResetPath {
          pathLength = PacketBuilder.floodPathSentinel
        } else {
          pathLength = contact.outPathLength
        }

        let session: RemoteNodeSessionDTO
        do {
          session = try await performLogin(
            services: services,
            context: context,
            password: submittedPassword,
            rememberPassword: submittedRememberPassword,
            ticket: ticket,
            pathLength: pathLength
          )
        } catch RemoteNodeError.timeout where pathLength != PacketBuilder.floodPathSentinel {
          guard accepts(context, ticket: ticket) else { return }
          // The stored route produced no reply. Clear it and let the network find a path.
          isRetryingViaFlood = true
          cleanupCountdownState()
          try await services.contactService.resetPath(
            radioID: contact.radioID,
            publicKey: contact.publicKey
          )
          guard accepts(context, ticket: ticket) else { return }
          didResetPath = true
          session = try await performLogin(
            services: services,
            context: context,
            password: submittedPassword,
            rememberPassword: submittedRememberPassword,
            ticket: ticket,
            pathLength: PacketBuilder.floodPathSentinel
          )
        }
        guard accepts(context, ticket: ticket) else { return }

        // Sensor login handles its own credential update.
        if role != .sensor, hasSavedPassword, !submittedRememberPassword {
          do {
            try await services.remoteNodeService.deletePassword(forContact: contact)
          } catch {
            logger.warning("Failed to delete saved password: \(error)")
          }
        }

        guard accepts(context, ticket: ticket) else { return }
        authenticationTask = nil
        isRetryingViaFlood = false
        cleanupCountdownState()
        dismiss()
        onSuccess(session)
      } catch is CancellationError {
        guard accepts(context, ticket: ticket) else { return }
        cancelAuthentication()
      } catch RemoteNodeError.timeout {
        guard accepts(context, ticket: ticket) else { return }
        cancelAuthentication()
        errorMessage = L10n.RemoteNodes.RemoteNodes.Status.requestTimedOut
      } catch {
        guard accepts(context, ticket: ticket) else { return }
        cancelAuthentication()
        if role == .sensor, requireAdmin, case RemoteNodeError.permissionDenied = error {
          errorMessage = L10n.RemoteNodes.RemoteNodes.Sensor.adminRequired
        } else {
          errorMessage = error.userFacingMessage
        }
      }
    }
  }

  private func performLogin(
    services: ServiceContainer,
    context: ConnectionCallbackContext,
    password: String,
    rememberPassword: Bool,
    ticket: Epoch.Ticket,
    pathLength: UInt8
  ) async throws -> RemoteNodeSessionDTO {
    let onTimeoutKnown: @Sendable (Int) async -> Void = { [self] seconds in
      await MainActor.run {
        guard accepts(context, ticket: ticket) else { return }
        authTimeoutSeconds = seconds
        authStartTime = Date.now
        authSecondsRemaining = seconds
        startCountdownTask(context: context, ticket: ticket)
      }
    }

    guard accepts(context, ticket: ticket) else { throw CancellationError() }
    switch role {
    case .roomServer:
      return try await services.roomServerService.joinRoom(
        radioID: contact.radioID,
        contact: contact,
        password: password,
        rememberPassword: rememberPassword,
        pathLength: pathLength,
        onTimeoutKnown: onTimeoutKnown
      )
    case .sensor:
      return try await services.remoteNodeService.connectSensor(
        radioID: contact.radioID, contact: contact, password: password,
        rememberPassword: rememberPassword, requireAdmin: requireAdmin,
        pathLength: pathLength, onTimeoutKnown: onTimeoutKnown
      )
    case .repeater:
      return try await services.repeaterAdminService.connectAsAdmin(
        radioID: contact.radioID,
        contact: contact,
        password: password,
        rememberPassword: rememberPassword,
        pathLength: pathLength,
        onTimeoutKnown: onTimeoutKnown
      )
    }
  }

  private func accepts(_ context: ConnectionCallbackContext, ticket: Epoch.Ticket? = nil) -> Bool {
    !Task.isCancelled && context.isCurrent(appState.connectionManager)
      && appState.connectionSnapshot(for: contact.radioID) != nil
      && (ticket?.isCurrent(in: authEpoch) ?? true)
  }

  // MARK: - Countdown

  private static let countdownTickInterval: Duration = .seconds(1)

  private func startCountdownTask(context: ConnectionCallbackContext, ticket: Epoch.Ticket) {
    countdownTask = Task {
      while !Task.isCancelled, let timeout = authTimeoutSeconds, let startTime = authStartTime {
        do {
          try await Task.sleep(for: Self.countdownTickInterval)
        } catch {
          break
        }
        guard accepts(context, ticket: ticket) else { return }

        let elapsed = Date.now.timeIntervalSince(startTime)
        let remaining = max(0, timeout - Int(elapsed))
        authSecondsRemaining = remaining
        if remaining == 0 {
          break
        }
      }
    }
  }

  private func cleanupCountdownState() {
    countdownTask?.cancel()
    countdownTask = nil
    authSecondsRemaining = nil
    authStartTime = nil
    authTimeoutSeconds = nil
  }
}

// MARK: - Node Details Section

private struct NodeDetailsSection: View {
  private var typeLabel: String {
    switch role {
    case .roomServer: L10n.RemoteNodes.RemoteNodes.Auth.typeRoom
    case .repeater: L10n.RemoteNodes.RemoteNodes.Auth.typeRepeater
    case .sensor: L10n.RemoteNodes.RemoteNodes.Auth.typeSensor
    }
  }

  @Environment(\.appTheme) private var theme
  let displayName: String
  let role: RemoteNodeRole

  var body: some View {
    Section {
      LabeledContent(L10n.RemoteNodes.RemoteNodes.Auth.name, value: displayName)
      LabeledContent(L10n.RemoteNodes.RemoteNodes.Auth.type, value: typeLabel)
    } header: {
      Text(L10n.RemoteNodes.RemoteNodes.Auth.nodeDetails)
    }
    .themedRowBackground(theme)
  }
}

// MARK: - Authentication Section

private struct AuthenticationSection: View {
  /// Countdown values at which VoiceOver gets a time-remaining announcement.
  private static let announcementThresholds = [30, 15, 10]
  /// Below this many seconds, every countdown tick is announced.
  private static let finalCountdownSeconds = 5

  @Environment(\.appTheme) private var theme
  @Binding var password: String
  @Binding var rememberPassword: Bool
  @Binding var errorMessage: String?
  @Binding var authSecondsRemaining: Int?
  let isRetryingViaFlood: Bool
  let maxPasswordBytes: Int

  var body: some View {
    Section {
      SecureField(L10n.RemoteNodes.RemoteNodes.Auth.password, text: $password)
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()

      Toggle(L10n.RemoteNodes.RemoteNodes.Auth.rememberPassword, isOn: $rememberPassword)
    } header: {
      Text(L10n.RemoteNodes.RemoteNodes.Auth.authentication)
    } footer: {
      if let errorMessage {
        Label(errorMessage, systemImage: "exclamationmark.circle.fill")
          .foregroundStyle(.orange)
          .accessibilityLabel(L10n.RemoteNodes.RemoteNodes.Auth.errorPrefix(errorMessage))
      } else if password.utf8.count > maxPasswordBytes {
        Text(L10n.Localizable.Error.RemoteNode.invalidPasswordLength(maxPasswordBytes))
      } else if isRetryingViaFlood {
        VStack(alignment: .leading, spacing: 2) {
          Text(L10n.RemoteNodes.RemoteNodes.Auth.floodRetryStatus)
          if let remaining = authSecondsRemaining, remaining > 0 {
            Text(L10n.RemoteNodes.RemoteNodes.Auth.secondsRemaining(remaining))
          }
        }
      } else if let remaining = authSecondsRemaining, remaining > 0 {
        Text(L10n.RemoteNodes.RemoteNodes.Auth.secondsRemaining(remaining))
      } else {
        Text(" ")
          .accessibilityHidden(true)
      }
    }
    .themedRowBackground(theme)
    .onChange(of: password) {
      if errorMessage != nil {
        errorMessage = nil
      }
    }
    .onChange(of: authSecondsRemaining) { oldValue, newValue in
      guard let remaining = newValue, remaining > 0 else { return }
      // Threshold-crossing rather than exact equality so a skipped tick
      // can't silently drop an announcement.
      let crossedThreshold = Self.announcementThresholds.contains { threshold in
        remaining <= threshold && (oldValue ?? Int.max) > threshold
      }
      let shouldAnnounce = oldValue == nil || crossedThreshold || remaining <= Self.finalCountdownSeconds
      if shouldAnnounce {
        AccessibilityNotification.Announcement(L10n.RemoteNodes.RemoteNodes.Auth.secondsRemainingAnnouncement(remaining)).post()
      }
    }
  }
}

// MARK: - Path Section

private struct PathSection: View {
  @Environment(\.appTheme) private var theme
  let contact: ContactDTO
  @Binding var useFloodRouting: Bool
  let pathViewModel: NodeAuthPathViewModel

  @State private var isPathExpanded = false

  private var hasStoredPath: Bool {
    !contact.isFloodRouted
  }

  var body: some View {
    Section {
      if hasStoredPath, !useFloodRouting {
        if !pathViewModel.hops.isEmpty {
          DisclosureGroup(isExpanded: $isPathExpanded) {
            ForEach(pathViewModel.hops) { hop in
              NodePathHopRow(hex: hop.hex, resolution: hop.resolution)
            }
          } label: {
            NodePathSummaryLabel(contact: contact)
          }
        } else {
          NodePathSummaryLabel(contact: contact)
        }
      } else if !hasStoredPath {
        Label {
          Text(L10n.RemoteNodes.RemoteNodes.Auth.noRouteSet)
            .foregroundStyle(.secondary)
        } icon: {
          Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
            .foregroundStyle(.secondary)
            .accessibilityHidden(true)
        }
        .accessibilityHidden(true)
      }

      Toggle(L10n.RemoteNodes.RemoteNodes.Auth.floodRouting, isOn: $useFloodRouting)
        .disabled(!hasStoredPath)
        .accessibilityHint(hasStoredPath ? "" : L10n.RemoteNodes.RemoteNodes.Auth.noRouteFooter)
    } header: {
      Text(L10n.RemoteNodes.RemoteNodes.Auth.path)
    } footer: {
      if !hasStoredPath {
        Text(L10n.RemoteNodes.RemoteNodes.Auth.noRouteFooter)
      } else if useFloodRouting {
        Text(L10n.RemoteNodes.RemoteNodes.Auth.floodFooter)
      } else {
        Text(L10n.RemoteNodes.RemoteNodes.Auth.pathFooter)
      }
    }
    .themedRowBackground(theme)
    .animation(.default, value: useFloodRouting)
  }
}

// MARK: - Connect Button

private struct ConnectButton: View {
  @Environment(\.appTheme) private var theme
  let role: RemoteNodeRole
  let isAuthenticating: Bool
  let onAuthenticate: () -> Void

  private var buttonLabel: String {
    role == .roomServer ? L10n.RemoteNodes.RemoteNodes.Auth.joinRoom : L10n.RemoteNodes.RemoteNodes.Auth.connect
  }

  var body: some View {
    Section {
      Button {
        onAuthenticate()
      } label: {
        if isAuthenticating {
          ProgressView()
            .frame(maxWidth: .infinity)
        } else {
          Text(buttonLabel)
            .frame(maxWidth: .infinity)
        }
      }
      .disabled(isAuthenticating)
    }
    .themedRowBackground(theme)
  }
}

#Preview {
  NodeAuthenticationSheet(
    contact: ContactDTO(from: Contact(
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: 32),
      name: "Test Room",
      typeRawValue: ContactType.room.rawValue,
      lastHeardTimestamp: 0
    )),
    role: .roomServer,
    onSuccess: { _ in }
  )
  .environment(\.appState, AppState())
}
