import MC1Services
import OSLog
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "JoinChannelConfirmationSheet")

/// Confirmation sheet shown when tapping a meshcore://channel/add link in a chat message
@MainActor
struct JoinChannelConfirmationSheet: View {
  @Environment(\.dismiss) private var dismiss
  @Environment(\.appState) private var appState

  let channelResult: MeshCoreURLParser.ChannelResult
  let onComplete: (ChannelDTO?) -> Void

  @State private var isJoining = false
  @State private var isLoading = true
  @State private var availableSlots: [UInt8] = []
  @State private var errorMessage: String?
  @State private var successTrigger = 0

  private var isMissingDevice: Bool {
    appState.connectedDevice == nil
  }

  var body: some View {
    NavigationStack {
      VStack(spacing: 0) {
        if isLoading {
          ProgressView(L10n.Chats.Chats.JoinFromMessage.loading)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if isMissingDevice {
          ChannelMissingDeviceContent(
            channelName: channelResult.name,
            onDismiss: {
              onComplete(nil)
              dismiss()
            }
          )
        } else if availableSlots.isEmpty {
          ChannelNoSlotsContent(
            channelName: channelResult.name,
            onDismiss: {
              onComplete(nil)
              dismiss()
            }
          )
        } else {
          ChannelJoinConfirmationContent(
            channelResult: channelResult,
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
      .sensoryFeedback(.error, trigger: errorMessage)
    }
  }

  // MARK: - Private Methods

  private func loadAvailableSlots() async {
    guard let radioID = appState.connectedDevice?.radioID else {
      isLoading = false
      return
    }

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
    guard let radioID = appState.connectedDevice?.radioID else { return }

    guard let channelService = appState.services?.channelService,
          let dataStore = appState.services?.dataStore else {
      errorMessage = L10n.Chats.Chats.Error.noDeviceConnected
      return
    }

    guard let selectedSlot = availableSlots.first else {
      errorMessage = L10n.Chats.Chats.JoinFromMessage.Error.noSlots
      return
    }

    isJoining = true
    errorMessage = nil

    do {
      try await channelService.setChannelWithSecret(
        radioID: radioID,
        index: selectedSlot,
        name: channelResult.name,
        secret: channelResult.secret
      )

      if let newChannel = try await dataStore.fetchChannel(radioID: radioID, index: selectedSlot) {
        successTrigger += 1
        onComplete(newChannel)
        dismiss()
      } else {
        errorMessage = L10n.Chats.Chats.JoinFromMessage.Error.loadFailed
      }
    } catch {
      logger.error("Failed to join channel from link: \(error)")
      errorMessage = error.userFacingMessage
    }

    isJoining = false
  }
}

// MARK: - Extracted Views

private struct ChannelMissingDeviceContent: View {
  let channelName: String
  let onDismiss: () -> Void

  var body: some View {
    ContentUnavailableView {
      Label(L10n.Chats.Chats.JoinFromMessage.NoDevice.title, systemImage: "antenna.radiowaves.left.and.right.slash")
    } description: {
      Text(L10n.Chats.Chats.JoinFromMessage.NoDevice.description(channelName))
    } actions: {
      Button(L10n.Chats.Chats.Common.ok, action: onDismiss)
        .liquidGlassProminentButtonStyle()
    }
  }
}

private struct ChannelNoSlotsContent: View {
  let channelName: String
  let onDismiss: () -> Void

  var body: some View {
    ContentUnavailableView {
      Label(L10n.Chats.Chats.JoinFromMessage.NoSlots.title, systemImage: "number.circle.fill")
    } description: {
      Text(L10n.Chats.Chats.JoinFromMessage.NoSlots.description(channelName))
    } actions: {
      Button(L10n.Chats.Chats.Common.ok, action: onDismiss)
        .liquidGlassProminentButtonStyle()
    }
  }
}

private struct ChannelJoinConfirmationContent: View {
  let channelResult: MeshCoreURLParser.ChannelResult
  let errorMessage: String?
  let isJoining: Bool
  let onJoin: () -> Void

  private var truncatedSecret: String {
    let hex = channelResult.secret.uppercaseHexString()
    guard hex.count >= 16 else { return hex }
    let start = hex.prefix(8)
    let end = hex.suffix(8)
    return "\(start)...\(end)"
  }

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

        Text(channelResult.name)
          .font(.title)
          .bold()

        Text(truncatedSecret)
          .font(.caption)
          .monospaced()
          .foregroundStyle(.tertiary)
      }

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
          Text(L10n.Chats.Chats.JoinPrivate.joinButton)
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
  let result = MeshCoreURLParser.ChannelResult(
    name: "EmergencyOps",
    secret: Data(repeating: 0xBB, count: 16)
  )
  JoinChannelConfirmationSheet(channelResult: result) { _ in }
    .environment(\.appState, AppState())
    .presentationDetents([.medium, .large])
}
