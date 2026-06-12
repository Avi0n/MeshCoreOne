import SwiftUI
import MC1Services

/// View for joining a hashtag channel (public, name-based)
@MainActor
struct JoinHashtagChannelView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    let availableSlots: [UInt8]
    let onComplete: (ChannelDTO?) -> Void

    @State private var channelName = ""
    @State private var selectedSlot: UInt8
    @State private var isJoining = false
    @State private var errorMessage: String?
    @State private var existingChannels: [ChannelDTO] = []

    private var existingChannel: ChannelDTO? {
        guard !channelName.isEmpty else { return nil }
        let fullName = "#\(channelName)"
        return existingChannels.first {
            $0.name.localizedCaseInsensitiveCompare(fullName) == .orderedSame
        }
    }

    init(availableSlots: [UInt8], onComplete: @escaping (ChannelDTO?) -> Void) {
        self.availableSlots = availableSlots
        self.onComplete = onComplete
        self._selectedSlot = State(initialValue: availableSlots.first ?? 1)
    }

    private var isValidName: Bool {
        HashtagUtilities.isValidHashtagName(channelName)
    }

    var body: some View {
        Form {
            Section {
                HStack {
                    Text("#")
                        .font(.title2)
                        .foregroundStyle(.secondary)

                    TextField(L10n.Chats.Chats.JoinHashtag.placeholder, text: $channelName)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onChange(of: channelName) { _, newValue in
                            channelName = HashtagUtilities.sanitizeHashtagNameInput(newValue)
                        }
                }
            } header: {
                Text(L10n.Chats.Chats.JoinHashtag.Section.header)
            } footer: {
                Text(L10n.Chats.Chats.JoinHashtag.footer)
            }
            .themedRowBackground(theme)

            Section {
                HStack {
                    Spacer()
                    VStack(spacing: 8) {
                        Image(systemName: "number")
                            .font(.system(size: 40))
                            .foregroundStyle(.cyan)

                        if !channelName.isEmpty {
                            Text("#\(channelName)")
                                .font(.headline)
                        }

                        Text(L10n.Chats.Chats.JoinHashtag.encryptionDescription)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding()
                    Spacer()
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
                        guard !isJoining else { return }
                        if let existing = existingChannel {
                            onComplete(existing)
                        } else {
                            await joinChannel()
                        }
                    }
                } label: {
                    HStack {
                        Spacer()
                        if isJoining {
                            ProgressView()
                        } else if existingChannel != nil {
                            Text(L10n.Chats.Chats.JoinHashtag.goToButton(channelName))
                        } else {
                            Text(L10n.Chats.Chats.JoinHashtag.joinButton(channelName))
                        }
                        Spacer()
                    }
                }
                .disabled(!isValidName || (isJoining && existingChannel == nil))
                .accessibilityHint(existingChannel != nil ? L10n.Chats.Chats.JoinHashtag.existingHint : L10n.Chats.Chats.JoinHashtag.newHint)
            } footer: {
                if existingChannel != nil {
                    Label(L10n.Chats.Chats.JoinHashtag.alreadyJoined, systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                        .accessibilityLabel(L10n.Chats.Chats.JoinHashtag.alreadyJoinedAccessibility)
                        .frame(maxWidth: .infinity)
                }
            }
            .themedRowBackground(theme)
        }
        .themedCanvas(theme)
        .navigationTitle(L10n.Chats.Chats.JoinHashtag.title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard let radioID = appState.connectedDevice?.radioID,
                  let dataStore = appState.services?.dataStore else { return }
            do {
                existingChannels = try await dataStore.fetchChannels(radioID: radioID)
            } catch {
                // Fail open - allow creation if fetch fails
            }
        }
    }

    private func joinChannel() async {
        guard let radioID = appState.connectedDevice?.radioID else {
            errorMessage = L10n.Chats.Chats.Error.noDeviceConnected
            return
        }

        guard let channelService = appState.services?.channelService else {
            errorMessage = L10n.Chats.Chats.Error.servicesUnavailable
            return
        }

        isJoining = true
        errorMessage = nil

        do {
            // For hashtag channels, hash the full name including "#" prefix
            // to match meshcore spec: sha256("#channelname")[0:16]
            try await channelService.setChannel(
                radioID: radioID,
                index: selectedSlot,
                name: "#\(channelName)",
                passphrase: "#\(channelName)"
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

        isJoining = false
    }
}

#Preview {
    NavigationStack {
        JoinHashtagChannelView(availableSlots: [1, 2, 3], onComplete: { _ in })
    }
    .environment(\.appState, AppState())
}
