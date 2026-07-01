import MC1Services
import SwiftUI

/// View for re-adding the public channel on slot 0
struct JoinPublicChannelView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  let onComplete: (ChannelDTO?) -> Void

  @State private var isJoining = false
  @State private var errorMessage: String?

  var body: some View {
    Form {
      Section {
        HStack {
          Spacer()
          VStack(spacing: 16) {
            Image(systemName: "globe")
              .font(.system(size: 60))
              .foregroundStyle(.green)

            Text(L10n.Chats.Chats.JoinPublic.channelName)
              .font(.title2)
              .bold()

            Text(L10n.Chats.Chats.JoinPublic.description)
              .font(.subheadline)
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
            await joinPublicChannel()
          }
        } label: {
          HStack {
            Spacer()
            if isJoining {
              ProgressView()
            } else {
              Text(L10n.Chats.Chats.JoinPublic.addButton)
            }
            Spacer()
          }
        }
        .disabled(isJoining)
      }
      .themedRowBackground(theme)
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Chats.Chats.JoinPublic.title)
    .navigationBarTitleDisplayMode(.inline)
  }

  private func joinPublicChannel() async {
    guard let radioID = appState.connectedDevice?.radioID else {
      errorMessage = L10n.Chats.Chats.Error.noDeviceConnected
      return
    }

    isJoining = true
    errorMessage = nil

    do {
      guard let channelService = appState.services?.channelService else {
        errorMessage = L10n.Chats.Chats.Error.servicesUnavailable
        return
      }
      try await channelService.setupPublicChannel(radioID: radioID)

      // Fetch the public channel (slot 0) to return it
      var publicChannel: ChannelDTO?
      if let channels = try? await appState.services?.dataStore.fetchChannels(radioID: radioID) {
        publicChannel = channels.first { $0.index == 0 }
      }
      onComplete(publicChannel)
    } catch {
      errorMessage = error.userFacingMessage
    }

    isJoining = false
  }
}

#Preview {
  NavigationStack {
    JoinPublicChannelView(onComplete: { _ in })
  }
  .environment(\.appState, AppState())
}
