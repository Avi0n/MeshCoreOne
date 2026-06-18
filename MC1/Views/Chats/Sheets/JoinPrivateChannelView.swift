import SwiftUI
import MC1Services

/// View for joining a private channel by entering name and hex secret key
struct JoinPrivateChannelView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    let availableSlots: [UInt8]
    let onComplete: (ChannelDTO?) -> Void

    @State private var channelName = ""
    @State private var secretKeyHex = ""
    @State private var selectedSlot: UInt8
    @State private var isJoining = false
    @State private var errorMessage: String?

    init(availableSlots: [UInt8], onComplete: @escaping (ChannelDTO?) -> Void) {
        self.availableSlots = availableSlots
        self.onComplete = onComplete
        self._selectedSlot = State(initialValue: availableSlots.first ?? 1)
    }

    private var isValidSecret: Bool {
        let cleaned = secretKeyHex.replacing(" ", with: "").uppercased()
        return cleaned.count == ProtocolLimits.channelSecretSize * 2 && cleaned.allSatisfy { $0.isHexDigit }
    }

    var body: some View {
        Form {
            Section {
                TextField(L10n.Chats.Chats.CreatePrivate.channelName, text: $channelName)
                    .textContentType(.name)
                    .onChange(of: channelName) { _, newValue in
                        if newValue.utf8.count > ProtocolLimits.maxUsableNameBytes {
                            channelName = newValue.utf8Prefix(maxBytes: ProtocolLimits.maxUsableNameBytes)
                        }
                    }

                TextField(L10n.Chats.Chats.JoinPrivate.secretKeyPlaceholder, text: $secretKeyHex)
                    .textContentType(.password)
                    .font(.system(.body, design: .monospaced))
                    .textInputAutocapitalization(.characters)
                    .onChange(of: secretKeyHex) { _, newValue in
                        // Clean and format as user types
                        secretKeyHex = newValue.uppercased().filter { $0.isHexDigit }
                    }
            } header: {
                Text(L10n.Chats.Chats.CreatePrivate.Section.details)
            } footer: {
                if !secretKeyHex.isEmpty && !isValidSecret {
                    Text(L10n.Chats.Chats.JoinPrivate.Error.invalidSecret)
                        .foregroundStyle(.red)
                } else {
                    Text(L10n.Chats.Chats.JoinPrivate.footer)
                }
            }
            .themedRowBackground(theme)

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(.red)
                }
                .themedRowBackground(theme)
            }

            Section {
                Button {
                    Task {
                        await joinChannel()
                    }
                } label: {
                    HStack {
                        Spacer()
                        if isJoining {
                            ProgressView()
                        } else {
                            Text(L10n.Chats.Chats.JoinPrivate.joinButton)
                        }
                        Spacer()
                    }
                }
                .disabled(channelName.isEmpty || !isValidSecret || isJoining)
            }
            .themedRowBackground(theme)
        }
        .themedCanvas(theme)
        .navigationTitle(L10n.Chats.Chats.JoinPrivate.title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func joinChannel() async {
        guard let radioID = appState.connectedDevice?.radioID else {
            errorMessage = L10n.Chats.Chats.Error.noDeviceConnected
            return
        }

        guard let secret = Data(hexString: secretKeyHex) else {
            errorMessage = L10n.Chats.Chats.JoinPrivate.Error.invalidFormat
            return
        }

        isJoining = true
        defer { isJoining = false }
        errorMessage = nil

        do {
            guard let channelService = appState.services?.channelService else {
                errorMessage = L10n.Chats.Chats.Error.servicesUnavailable
                return
            }
            try await channelService.setChannelWithSecret(
                radioID: radioID,
                index: selectedSlot,
                name: channelName,
                secret: secret
            )

            // Fetch the joined channel to return it
            var joinedChannel: ChannelDTO?
            if let channels = try? await appState.services?.dataStore.fetchChannels(radioID: radioID) {
                joinedChannel = channels.first { $0.index == selectedSlot }
            }
            onComplete(joinedChannel)
        } catch {
            errorMessage = error.userFacingMessage
        }
    }
}

#Preview {
    NavigationStack {
        JoinPrivateChannelView(availableSlots: [1, 2, 3], onComplete: { _ in })
    }
    .environment(\.appState, AppState())
}
