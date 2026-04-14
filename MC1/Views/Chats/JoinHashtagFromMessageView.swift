import SwiftUI
import MC1Services
import OSLog

private let logger = Logger(subsystem: "com.mc1", category: "JoinHashtagFromMessageView")

/// Sheet view for joining a hashtag channel tapped in a message
@MainActor
struct JoinHashtagFromMessageView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appState) private var appState

    let channelName: String
    let onComplete: (ChannelDTO?) -> Void

    @State private var availableSlots: [UInt8] = []
    @State private var isJoining = false
    @State private var isLoading = true
    @State private var isMissingDevice = false
    @State private var errorMessage: String?
    @State private var successTrigger = 0

    private var normalizedName: String {
        HashtagUtilities.normalizeHashtagName(channelName)
    }

    private var fullChannelName: String {
        "#\(normalizedName)"
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if isLoading {
                    HashtagLoadingContent()
                } else if isMissingDevice {
                    HashtagMissingDeviceContent(
                        fullChannelName: fullChannelName,
                        onDismiss: {
                            onComplete(nil)
                            dismiss()
                        }
                    )
                } else if availableSlots.isEmpty {
                    HashtagNoSlotsContent(
                        fullChannelName: fullChannelName,
                        onDismiss: {
                            onComplete(nil)
                            dismiss()
                        }
                    )
                } else {
                    HashtagJoinConfirmationContent(
                        fullChannelName: fullChannelName,
                        errorMessage: errorMessage,
                        isJoining: isJoining,
                        onJoin: { Task { await joinChannel() } }
                    )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(.systemGroupedBackground))
            .navigationTitle(L10n.Chats.Chats.JoinFromMessage.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Chats.Chats.Common.cancel) {
                        onComplete(nil)
                        dismiss()
                    }
                }
            }
            .task {
                await loadAvailableSlots()
            }
            .sensoryFeedback(.success, trigger: successTrigger)
        }
    }

    // MARK: - Private Methods

    private func loadAvailableSlots() async {
        guard let radioID = appState.connectedDevice?.radioID else {
            isMissingDevice = true
            isLoading = false
            return
        }

        isMissingDevice = false

        do {
            let existingChannels = try await appState.services?.dataStore.fetchChannels(radioID: radioID) ?? []
            let usedSlots = Set(existingChannels.map(\.index))

            let maxChannels = appState.connectedDevice?.maxChannels ?? 0
            if maxChannels > 1 {
                availableSlots = (1..<maxChannels).filter { !usedSlots.contains($0) }
            }
        } catch {
            logger.error("Failed to load channel slots: \(error)")
        }

        isLoading = false
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

        guard let selectedSlot = availableSlots.first else {
            errorMessage = L10n.Chats.Chats.JoinFromMessage.Error.noSlots
            return
        }

        guard HashtagUtilities.isValidHashtagName(normalizedName) else {
            errorMessage = L10n.Chats.Chats.JoinFromMessage.Error.invalidName
            return
        }

        isJoining = true
        errorMessage = nil

        do {
            try await channelService.setChannel(
                radioID: radioID,
                index: selectedSlot,
                name: fullChannelName,
                passphrase: fullChannelName
            )

            if let newChannel = try await appState.services?.dataStore.fetchChannel(radioID: radioID, index: selectedSlot) {
                successTrigger += 1
                onComplete(newChannel)
                dismiss()
            } else {
                errorMessage = L10n.Chats.Chats.JoinFromMessage.Error.loadFailed
            }
        } catch {
            logger.error("Failed to join channel: \(error)")
            errorMessage = error.localizedDescription
        }

        isJoining = false
    }
}

// MARK: - Extracted Views

private struct HashtagLoadingContent: View {
    var body: some View {
        ProgressView(L10n.Chats.Chats.JoinFromMessage.loading)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct HashtagMissingDeviceContent: View {
    let fullChannelName: String
    let onDismiss: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label(L10n.Chats.Chats.JoinFromMessage.NoDevice.title, systemImage: "antenna.radiowaves.left.and.right.slash")
        } description: {
            Text(L10n.Chats.Chats.JoinFromMessage.NoDevice.description(fullChannelName))
        } actions: {
            Button(L10n.Chats.Chats.Common.ok, action: onDismiss)
                .liquidGlassProminentButtonStyle()
        }
    }
}

private struct HashtagNoSlotsContent: View {
    let fullChannelName: String
    let onDismiss: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label(L10n.Chats.Chats.JoinFromMessage.NoSlots.title, systemImage: "number.circle.fill")
        } description: {
            Text(L10n.Chats.Chats.JoinFromMessage.NoSlots.description(fullChannelName))
        } actions: {
            Button(L10n.Chats.Chats.Common.ok, action: onDismiss)
                .liquidGlassProminentButtonStyle()
        }
    }
}

private struct HashtagJoinConfirmationContent: View {
    let fullChannelName: String
    let errorMessage: String?
    let isJoining: Bool
    let onJoin: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            VStack(spacing: 16) {
                ZStack {
                    Circle()
                        .fill(.cyan)
                        .frame(width: 80, height: 80)

                    Image(systemName: "number")
                        .font(.system(size: 36, weight: .bold))
                        .foregroundStyle(.white)
                }

                Text(fullChannelName)
                    .font(.title)
                    .bold()

                Text(L10n.Chats.Chats.JoinFromMessage.description)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }

            Spacer()

            if let errorMessage {
                Text(errorMessage)
                    .font(.callout)
                    .foregroundStyle(.red)
                    .padding(.horizontal)
            }

            Button(action: onJoin) {
                if isJoining {
                    ProgressView()
                } else {
                    Text(L10n.Chats.Chats.JoinFromMessage.joinButton(fullChannelName))
                }
            }
            .liquidGlassProminentButtonStyle()
            .disabled(isJoining)
            .padding(.horizontal, 48)
            .padding(.bottom, 32)
        }
    }
}

#Preview {
    JoinHashtagFromMessageView(channelName: "#general") { _ in }
        .environment(\.appState, AppState())
        .presentationDetents([.medium])
}
