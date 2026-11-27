import MC1Services
import SwiftUI

/// Reusable password entry sheet for remote nodes
struct NodeAuthenticationSheet: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss

  let role: RemoteNodeRole
  /// When true, hides the Node Details section (used when re-joining known rooms from chat list)
  let hideNodeDetails: Bool
  /// Optional custom title. If nil, uses default based on role ("Join Room" or "Admin Access")
  let customTitle: String?
  let onSuccess: (RemoteNodeSessionDTO) -> Void

  @State private var viewModel: NodeAuthenticationViewModel

  init(
    contact: ContactDTO,
    role: RemoteNodeRole,
    hideNodeDetails: Bool = false,
    requireAdmin: Bool = true,
    customTitle: String? = nil,
    onSuccess: @escaping (RemoteNodeSessionDTO) -> Void
  ) {
    self.role = role
    self.hideNodeDetails = hideNodeDetails
    self.customTitle = customTitle
    self.onSuccess = onSuccess
    _viewModel = State(
      initialValue: NodeAuthenticationViewModel(
        contact: contact,
        role: role,
        requireAdmin: requireAdmin
      )
    )
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
            viewModel.cancelAuthentication()
            dismiss()
          }
        }
      }
      .onAppear {
        configureViewModel()
      }
      .task {
        configureViewModel()
        await viewModel.loadSavedCredentials()
      }
      .task {
        configureViewModel()
        await viewModel.loadResolvedPath()
      }
      .sensoryFeedback(.error, trigger: viewModel.errorMessage)
      .onChange(of: appState.connectionManager.currentConnection?.generation) { _, _ in
        viewModel.cancelAuthentication()
      }
      .onDisappear { viewModel.cancelAuthentication() }
    }
  }

  private func configureViewModel() {
    let appState = appState
    let radioID = viewModel.currentContact.radioID
    viewModel.configure(.init(
      roomServerService: { appState.connectionSnapshot(for: radioID)?.services.roomServerService },
      remoteNodeService: { appState.connectionSnapshot(for: radioID)?.services.remoteNodeService },
      repeaterAdminService: { appState.connectionSnapshot(for: radioID)?.services.repeaterAdminService },
      contactService: { appState.connectionSnapshot(for: radioID)?.services.contactService },
      dataStore: { appState.connectionSnapshot(for: radioID)?.services.dataStore },
      connectionGeneration: { appState.connectionSnapshot(for: radioID)?.generation },
      bestAvailableLocation: { appState.bestAvailableLocation }
    ))
  }

  // MARK: - Sections

  private func makeNodeDetailsSection() -> some View {
    NodeDetailsSection(
      displayName: viewModel.currentContact.displayName,
      role: role
    )
  }

  private func makeAuthenticationSection() -> some View {
    AuthenticationSection(
      password: $viewModel.password,
      rememberPassword: $viewModel.rememberPassword,
      errorMessage: $viewModel.errorMessage,
      authSecondsRemaining: $viewModel.authSecondsRemaining,
      isAuthenticating: viewModel.isAuthenticating,
      isRetryingViaFlood: viewModel.isRetryingViaFlood,
      isWaitingForRadio: viewModel.isWaitingForRadio
    )
  }

  private func makePathSection() -> some View {
    PathSection(
      contact: viewModel.currentContact,
      useFloodRouting: $viewModel.useFloodRouting,
      routeCleared: viewModel.didManualReset,
      isAuthenticating: viewModel.isAuthenticating,
      pathViewModel: viewModel.pathViewModel
    )
  }

  private func makeConnectButton() -> some View {
    ConnectButton(
      role: role,
      isAuthenticating: viewModel.isAuthenticating,
      onAuthenticate: {
        configureViewModel()
        viewModel.authenticate { session in
          dismiss()
          onSuccess(session)
        }
      }
    )
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
  /// Grace delay before VoiceOver announces gate waiting so fast hand-offs do not chatter.
  private static let waitingAnnouncementDelay: Duration = .milliseconds(500)

  @Environment(\.appTheme) private var theme
  @Binding var password: String
  @Binding var rememberPassword: Bool
  @Binding var errorMessage: String?
  @Binding var authSecondsRemaining: Int?
  let isAuthenticating: Bool
  let isRetryingViaFlood: Bool
  let isWaitingForRadio: Bool

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
      } else if password.utf8.count > ProtocolLimits.maxRemotePasswordBytes {
        Text(L10n.Localizable.Error.RemoteNode.invalidPasswordLength(ProtocolLimits.maxRemotePasswordBytes))
      } else if isRetryingViaFlood {
        VStack(alignment: .leading, spacing: 2) {
          Text(L10n.RemoteNodes.RemoteNodes.Auth.floodRetryStatus)
          if let remaining = authSecondsRemaining, remaining > 0 {
            Text(L10n.RemoteNodes.RemoteNodes.Auth.secondsRemaining(remaining))
          }
        }
      } else if let remaining = authSecondsRemaining, remaining > 0 {
        Text(L10n.RemoteNodes.RemoteNodes.Auth.secondsRemaining(remaining))
      } else if isWaitingForRadio {
        Text(L10n.RemoteNodes.RemoteNodes.Auth.waitingForRadio)
      } else {
        Text(" ")
          .accessibilityHidden(true)
      }
    }
    .themedRowBackground(theme)
    .task(id: isWaitingForRadio) {
      guard isWaitingForRadio else { return }
      try? await Task.sleep(for: Self.waitingAnnouncementDelay)
      guard !Task.isCancelled, isWaitingForRadio else { return }
      AccessibilityNotification.Announcement(L10n.RemoteNodes.RemoteNodes.Auth.waitingForRadio).post()
    }
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
  @AppStorage(AppStorageKey.remoteFloodFallback.rawValue)
  private var remoteFloodFallback = AppStorageKey.defaultRemoteFloodFallback
  let contact: ContactDTO
  @Binding var useFloodRouting: Bool
  let routeCleared: Bool
  let isAuthenticating: Bool
  let pathViewModel: NodeAuthPathViewModel

  @State private var isPathExpanded = false

  private var choice: SignInPathFooter {
    signInPathFooter(
      hasStoredRoute: !contact.isFloodRouted,
      floodRouting: useFloodRouting,
      routeCleared: routeCleared,
      fallbackEnabled: remoteFloodFallback,
      isAuthenticating: isAuthenticating
    )
  }

  private var floodRoutingBinding: Binding<Bool> {
    if choice.canChangeFloodRouting {
      return $useFloodRouting
    }
    return .constant(choice.showsFloodRoutingOn)
  }

  var body: some View {
    Section {
      if choice.showsSavedHops {
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
      } else if choice.showsNoRouteLabel {
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

      Toggle(L10n.RemoteNodes.RemoteNodes.Auth.floodRouting, isOn: floodRoutingBinding)
        .disabled(!choice.canChangeFloodRouting)
        .accessibilityHint(choice.canChangeFloodRouting ? "" : choice.message)
    } header: {
      Text(L10n.RemoteNodes.RemoteNodes.Auth.path)
    } footer: {
      Text(choice.message)
    }
    .themedRowBackground(theme)
    .animation(.default, value: useFloodRouting)
    .animation(.default, value: routeCleared)
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
