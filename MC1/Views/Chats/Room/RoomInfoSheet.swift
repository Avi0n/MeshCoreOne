import MC1Services
import SwiftUI

private typealias Strings = L10n.RemoteNodes.RemoteNodes.Room

struct RoomInfoSheet: View {
  @Environment(\.dismiss) private var dismiss
  @Environment(\.chatViewModel) private var viewModel
  @Environment(\.appTheme) private var theme

  let session: RemoteNodeSessionDTO

  @State private var notificationLevel: NotificationLevel
  @State private var isFavorite: Bool
  @State private var notificationTask: Task<Void, Never>?
  @State private var favoriteTask: Task<Void, Never>?
  @State private var showTelemetry = false
  @State private var showSettings = false

  init(session: RemoteNodeSessionDTO) {
    self.session = session
    _notificationLevel = State(initialValue: session.notificationLevel)
    _isFavorite = State(initialValue: session.isFavorite)
  }

  var body: some View {
    NavigationStack {
      List {
        Section {
          VStack(spacing: 12) {
            NodeAvatar(publicKey: session.publicKey, role: .roomServer, size: 150)

            VStack(spacing: 4) {
              Text(session.name)
                .font(.title2)
                .bold()

              Text(L10n.RemoteNodes.RemoteNodes.Auth.typeRoom)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            }
          }
          .frame(maxWidth: .infinity)
          .listRowBackground(Color.clear)
        }

        ConversationQuickActionsSection(
          notificationLevel: $notificationLevel,
          availableLevels: NotificationLevel.roomLevels
        )
        .onChange(of: notificationLevel) { _, newValue in
          notificationTask?.cancel()
          notificationTask = Task {
            await viewModel?.setNotificationLevel(.room(session), level: newValue)
          }
        }
        .onChange(of: isFavorite) { _, newValue in
          favoriteTask?.cancel()
          favoriteTask = Task {
            await viewModel?.setFavorite(.room(session), isFavorite: newValue)
          }
        }
        .onDisappear {
          notificationTask?.cancel()
          favoriteTask?.cancel()
        }

        if session.isConnected {
          Section {
            Button { showTelemetry = true } label: {
              Label(L10n.Contacts.Contacts.Detail.telemetry, systemImage: "chart.line.uptrend.xyaxis")
            }
            if session.isAdmin {
              Button { showSettings = true } label: {
                Label(L10n.Contacts.Contacts.Detail.management, systemImage: "gearshape.2")
              }
            }
          }
          .themedRowBackground(theme)
        }

        Section(Strings.details) {
          LabeledContent(L10n.RemoteNodes.RemoteNodes.name, value: session.name)
          LabeledContent(Strings.permission, value: session.permissionLevel.localizedName)
          if session.isConnected {
            LabeledContent(Strings.status, value: Strings.connected)
          }
        }
        .themedRowBackground(theme)

        if let lastConnected = session.lastConnectedDate {
          Section(Strings.activity) {
            LabeledContent(Strings.lastConnected) {
              Text(lastConnected, format: .relative(presentation: .named))
            }
          }
          .themedRowBackground(theme)
        }

        Section(Strings.identification) {
          VStack(alignment: .leading, spacing: 4) {
            Text(Strings.publicKey)
              .font(.caption)
              .foregroundStyle(.secondary)
            Text(session.publicKeyHex)
              .font(.system(.caption, design: .monospaced))
              .textSelection(.enabled)
          }
        }
        .themedRowBackground(theme)
      }
      .themedCanvas(theme)
      .navigationBarTitleDisplayMode(.inline)
      .scrollRevealNavigationTitle(session.name)
      .toolbar {
        ToolbarItem(placement: .topBarLeading) {
          Button {
            isFavorite.toggle()
          } label: {
            Image(systemName: isFavorite ? "star.fill" : "star")
              .foregroundStyle(isFavorite ? .yellow : .secondary)
          }
        }

        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Localizable.Common.done) { dismiss() }
        }
      }
    }
    .sheet(isPresented: $showTelemetry) {
      RoomStatusView(session: session)
    }
    .sheet(isPresented: $showSettings) {
      NavigationStack {
        RoomSettingsView(session: session)
      }
    }
  }
}
