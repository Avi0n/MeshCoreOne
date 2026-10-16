import MC1Services
import SwiftUI

private typealias Strings = L10n.RemoteNodes.RemoteNodes.Room

struct RoomInfoSheet: View {
  @Environment(\.dismiss) private var dismiss
  @Environment(\.chatViewModel) private var viewModel
  @Environment(\.appTheme) private var theme

  let session: RemoteNodeSessionDTO

  @State private var notificationLevel: NotificationLevel
  @State private var isPinned: Bool
  @State private var notificationTask: Task<Void, Never>?
  @State private var pinTask: Task<Void, Never>?
  @State private var pinErrorMessage: String?
  @State private var ignorePinChange = false
  @State private var pinGeneration = 0
  @State private var showTelemetry = false
  @State private var showSettings = false
  @State private var headerHeight: CGFloat = 150

  init(session: RemoteNodeSessionDTO) {
    self.session = session
    _notificationLevel = State(initialValue: session.notificationLevel)
    _isPinned = State(initialValue: session.isPinned)
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
          .scrollRevealHeaderHeight(into: $headerHeight)
          .listRowBackground(Color.clear)
        }

        ConversationQuickActionsSection(
          isPinned: $isPinned,
          notificationLevel: $notificationLevel,
          availableLevels: NotificationLevel.roomLevels
        )
        .onChange(of: notificationLevel) { _, newValue in
          notificationTask?.cancel()
          notificationTask = Task {
            await viewModel?.setNotificationLevel(.room(session), level: newValue)
          }
        }
        .onChange(of: isPinned) { oldValue, newValue in
          if ignorePinChange {
            ignorePinChange = false
            return
          }
          pinGeneration += 1
          let generation = pinGeneration
          pinTask?.cancel()
          pinTask = Task {
            await savePin(newValue, revertingTo: oldValue, generation: generation)
          }
        }
        .onDisappear {
          notificationTask?.cancel()
          pinTask?.cancel()
        }

        if let pinErrorMessage {
          Section {
            Text(pinErrorMessage)
              .foregroundStyle(.red)
          }
          .themedRowBackground(theme)
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
      .scrollRevealNavigationTitle(session.name, revealAfter: headerHeight)
      .contentMargins(.top, 0, for: .scrollContent)
      .toolbar {
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

  private func savePin(_ newValue: Bool, revertingTo previous: Bool, generation: Int) async {
    guard let viewModel else {
      revertPin(to: previous, message: L10n.Chats.Chats.Error.pinSaveFailed, generation: generation)
      return
    }
    do {
      try await viewModel.setPinned(.room(session), isPinned: newValue)
      guard generation == pinGeneration else { return }
      pinErrorMessage = nil
    } catch {
      revertPin(to: previous, message: error.userFacingMessage, generation: generation)
    }
  }

  private func revertPin(to previous: Bool, message: String, generation: Int) {
    guard generation == pinGeneration else { return }
    pinErrorMessage = message
    guard isPinned != previous else { return }
    ignorePinChange = true
    isPinned = previous
  }
}
