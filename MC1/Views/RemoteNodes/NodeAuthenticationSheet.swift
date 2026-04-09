import os
import SwiftUI
import MC1Services

private let logger = Logger(subsystem: "com.mc1", category: "NodeAuthenticationSheet")

/// Reusable password entry sheet for both room servers and repeaters
struct NodeAuthenticationSheet: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss

    let contact: ContactDTO
    let role: RemoteNodeRole
    /// When true, hides the Node Details section (used when re-joining known rooms from chat list)
    let hideNodeDetails: Bool
    /// Optional custom title. If nil, uses default based on role ("Join Room" or "Admin Access")
    let customTitle: String?
    let onSuccess: (RemoteNodeSessionDTO) -> Void

    @State private var password: String = ""
    @State private var rememberPassword = true
    @State private var useFloodRouting: Bool
    @State private var didResetPath = false
    @State private var isAuthenticating = false
    @State private var errorMessage: String?
    @State private var hasSavedPassword = false

    // Countdown state
    @State private var authSecondsRemaining: Int?
    @State private var authStartTime: Date?
    @State private var authTimeoutSeconds: Int?
    @State private var countdownTask: Task<Void, Never>?
    @State private var authenticationTask: Task<Void, Never>?

    private let maxPasswordLength = 15

    init(
        contact: ContactDTO,
        role: RemoteNodeRole,
        hideNodeDetails: Bool = false,
        customTitle: String? = nil,
        onSuccess: @escaping (RemoteNodeSessionDTO) -> Void
    ) {
        self.contact = contact
        self.role = role
        self.hideNodeDetails = hideNodeDetails
        self.customTitle = customTitle
        self.onSuccess = onSuccess
        self._useFloodRouting = State(initialValue: contact.isFloodRouted)
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
            .navigationTitle(customTitle ?? (role == .roomServer ? L10n.RemoteNodes.RemoteNodes.Auth.joinRoom : L10n.RemoteNodes.RemoteNodes.Auth.management))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.RemoteNodes.RemoteNodes.Auth.cancel) {
                        authenticationTask?.cancel()
                        dismiss()
                    }
                }
            }
            .task {
                if let remoteNodeService = appState.services?.remoteNodeService,
                   let saved = await remoteNodeService.retrievePassword(forContact: contact) {
                    password = saved
                    hasSavedPassword = true
                }
            }
            .sensoryFeedback(.error, trigger: errorMessage)
            .onDisappear {
                authenticationTask?.cancel()
                cleanupCountdownState()
            }
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
            role: role,
            maxPasswordLength: maxPasswordLength
        )
    }

    private func makePathSection() -> some View {
        PathSection(
            contact: contact,
            useFloodRouting: $useFloodRouting
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

    private func authenticate() {
        // Clear any previous error
        errorMessage = nil
        isAuthenticating = true
        authenticationTask?.cancel()
        cleanupCountdownState()

        authenticationTask = Task {
            do {
                guard let device = appState.connectedDevice else {
                    throw RemoteNodeError.notConnected
                }

                guard let services = appState.services else {
                    throw RemoteNodeError.notConnected
                }

                // Reset the firmware's stored path so the login packet is flood-routed.
                // Only needed once per session — subsequent retries skip the BLE round-trip.
                let pathLength: UInt8
                if useFloodRouting && !contact.isFloodRouted && !didResetPath {
                    try await services.contactService.resetPath(
                        deviceID: device.id,
                        publicKey: contact.publicKey
                    )
                    didResetPath = true
                    pathLength = 0xFF
                } else if useFloodRouting {
                    pathLength = 0xFF
                } else {
                    pathLength = contact.outPathLength
                }

                let session: RemoteNodeSessionDTO
                // MeshCore repeaters and rooms only support 15-character passwords, truncate if needed
                let passwordToUse = password.count > maxPasswordLength
                    ? String(password.prefix(maxPasswordLength))
                    : password

                // Callback to start countdown when firmware timeout is known
                let onTimeoutKnown: @Sendable (Int) async -> Void = { [self] seconds in
                    await MainActor.run {
                        self.authTimeoutSeconds = seconds
                        self.authStartTime = Date.now
                        self.authSecondsRemaining = seconds
                        self.startCountdownTask()
                    }
                }

                if role == .roomServer {
                    session = try await services.roomServerService.joinRoom(
                        deviceID: device.id,
                        contact: contact,
                        password: passwordToUse,
                        rememberPassword: rememberPassword,
                        pathLength: pathLength,
                        onTimeoutKnown: onTimeoutKnown
                    )
                } else {
                    session = try await services.repeaterAdminService.connectAsAdmin(
                        deviceID: device.id,
                        contact: contact,
                        password: passwordToUse,
                        rememberPassword: rememberPassword,
                        pathLength: pathLength,
                        onTimeoutKnown: onTimeoutKnown
                    )
                }

                // Delete saved password if user unchecked "Remember Password"
                if hasSavedPassword && !rememberPassword {
                    do {
                        try await services.remoteNodeService.deletePassword(forContact: contact)
                    } catch {
                        logger.warning("Failed to delete saved password: \(error)")
                    }
                }

                await MainActor.run {
                    authenticationTask = nil
                    cleanupCountdownState()
                    dismiss()
                    onSuccess(session)
                }
            } catch is CancellationError {
                await MainActor.run {
                    authenticationTask = nil
                    cleanupCountdownState()
                    isAuthenticating = false
                }
            } catch RemoteNodeError.timeout {
                await MainActor.run {
                    authenticationTask = nil
                    cleanupCountdownState()
                    errorMessage = L10n.RemoteNodes.RemoteNodes.Status.requestTimedOut
                    isAuthenticating = false
                }
            } catch {
                await MainActor.run {
                    authenticationTask = nil
                    cleanupCountdownState()
                    errorMessage = error.localizedDescription
                    isAuthenticating = false
                }
            }
        }
    }

    // MARK: - Countdown

    private func startCountdownTask() {
        countdownTask = Task {
            while !Task.isCancelled, let timeout = authTimeoutSeconds, let startTime = authStartTime {
                do {
                    try await Task.sleep(for: .seconds(5))
                } catch {
                    break
                }

                let elapsed = Date.now.timeIntervalSince(startTime)
                let remaining = max(0, timeout - Int(elapsed))
                authSecondsRemaining = remaining
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
    let displayName: String
    let role: RemoteNodeRole

    var body: some View {
        Section {
            LabeledContent(L10n.RemoteNodes.RemoteNodes.Auth.name, value: displayName)
            LabeledContent(L10n.RemoteNodes.RemoteNodes.Auth.type, value: role == .roomServer ? L10n.RemoteNodes.RemoteNodes.Auth.typeRoom : L10n.RemoteNodes.RemoteNodes.Auth.typeRepeater)
        } header: {
            Text(L10n.RemoteNodes.RemoteNodes.Auth.nodeDetails)
        }
    }
}

// MARK: - Authentication Section

private struct AuthenticationSection: View {
    @Binding var password: String
    @Binding var rememberPassword: Bool
    @Binding var errorMessage: String?
    @Binding var authSecondsRemaining: Int?
    let role: RemoteNodeRole
    let maxPasswordLength: Int

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
                    .foregroundStyle(.red)
                    .accessibilityLabel(L10n.RemoteNodes.RemoteNodes.Auth.errorPrefix(errorMessage))
            } else if password.count > maxPasswordLength {
                Text(role == .repeater ? L10n.RemoteNodes.RemoteNodes.Auth.passwordTooLongRepeaters(maxPasswordLength) : L10n.RemoteNodes.RemoteNodes.Auth.passwordTooLongRooms(maxPasswordLength))
            } else if let remaining = authSecondsRemaining, remaining > 0 {
                Text(L10n.RemoteNodes.RemoteNodes.Auth.secondsRemaining(remaining))
            } else {
                Text(" ")
                    .accessibilityHidden(true)
            }
        }
        .onChange(of: password) {
            if errorMessage != nil {
                errorMessage = nil
            }
        }
        .onChange(of: authSecondsRemaining) { oldValue, newValue in
            guard let remaining = newValue, remaining > 0 else { return }
            let shouldAnnounce = oldValue == nil || remaining == 30 || remaining == 15 || remaining == 10 || remaining <= 5
            if shouldAnnounce {
                AccessibilityNotification.Announcement(L10n.RemoteNodes.RemoteNodes.Auth.secondsRemainingAnnouncement(remaining)).post()
            }
        }
    }
}

// MARK: - Path Section

private struct PathSection: View {
    let contact: ContactDTO
    @Binding var useFloodRouting: Bool

    private var hasStoredPath: Bool {
        !contact.isFloodRouted
    }

    private var pathDisplayText: String {
        if contact.pathHopCount == 0 {
            return L10n.Contacts.Contacts.Route.direct
        } else {
            return contact.pathString
        }
    }

    private var pathAccessibilityLabel: String {
        if contact.pathHopCount == 0 {
            return L10n.Contacts.Contacts.Detail.routeDirect
        } else {
            return L10n.Contacts.Contacts.Detail.routePrefix(pathDisplayText)
        }
    }

    var body: some View {
        Section {
            if hasStoredPath && !useFloodRouting {
                Label {
                    Text(pathDisplayText)
                        .font(.caption.monospaced())
                        .foregroundStyle(.primary)
                        .lineLimit(nil)
                } icon: {
                    Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
                .accessibilityLabel(pathAccessibilityLabel)
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
            if hasStoredPath {
                Text(L10n.RemoteNodes.RemoteNodes.Auth.pathFooter)
            } else {
                Text(L10n.RemoteNodes.RemoteNodes.Auth.noRouteFooter)
            }
        }
        .animation(.default, value: useFloodRouting)
    }
}

// MARK: - Connect Button

private struct ConnectButton: View {
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
    }
}

#Preview {
    NodeAuthenticationSheet(
        contact: ContactDTO(from: Contact(
            deviceID: UUID(),
            publicKey: Data(repeating: 0x42, count: 32),
            name: "Test Room",
            typeRawValue: ContactType.room.rawValue
        )),
        role: .roomServer,
        onSuccess: { _ in }
    )
    .environment(\.appState, AppState())
}
