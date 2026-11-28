import MC1Services
import SwiftUI

/// Keeps tab state alive while Nodes replaces navigation containers during resizing.
struct MainTabView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var showingDeviceSelection = false
  @State private var chess = ChessCoordinator.shared
  @State private var meshGames = MeshGamesCoordinator.shared

  var body: some View {
    @Bindable var navigation = appState.navigation

    TabView(selection: $navigation.selectedTab) {
      Tab(L10n.Localizable.Tabs.chats, systemImage: "message.fill", value: AppTab.chats.rawValue) {
        ChatsView()
      }
      .badge((appState.services?.notificationService.badgeCount ?? 0) + meshGames.attentionCount + chess.attentionCount)

      Tab(L10n.Localizable.Tabs.nodes, systemImage: "flipphone", value: AppTab.nodes.rawValue) {
        ContactsListView()
      }

      Tab(L10n.Localizable.Tabs.map, systemImage: "map.fill", value: AppTab.map.rawValue) {
        MapView()
      }

      Tab(L10n.Localizable.Tabs.tools, systemImage: "wrench.and.screwdriver", value: AppTab.tools.rawValue) {
        ToolsView()
      }

      Tab(L10n.Localizable.Tabs.settings, systemImage: "gear", value: AppTab.settings.rawValue) {
        SettingsView()
      }
    }
    .themedChrome(theme)
    .syncingPillOverlay(onDisconnectedTap: { showingDeviceSelection = true })
    .onChange(of: appState.navigation.selectedTab) { _, _ in
      if appState.navigation.pendingDeviceMenuTipDonation, appState.navigation.isOnValidTabForDeviceMenuTip {
        Task {
          await appState.donateDeviceMenuTipIfOnValidTab()
        }
      }
    }
    .onChange(of: appState.connectedDevice) { _, newDevice in
      // Status-menu disconnect does not fire onConnectionLost; radio-to-radio
      // switches use clearPerRadioSelection instead.
      clearDeviceSettingsIfImportEnded(device: newDevice)
    }
    .onChange(of: appState.connectionManager.identityRebuildUnpublished) { _, _ in
      clearDeviceSettingsIfImportEnded(device: appState.connectedDevice)
    }
    .task(id: appState.servicesVersion) {
      let notifications = appState.services?.notificationService
      ChessCoordinator.shared.attach(appState.services?.chessGameService, notifications: notifications)
      MeshGamesCoordinator.shared.attach(appState.services?.meshGameService, notifications: notifications)
      notifications?.onGameNotificationTapped = { gameID in
        if gameID == "chess" {
          ChessCoordinator.shared.isBoardPresented = true
        } else {
          MeshGamesCoordinator.shared.presentedGameID = gameID
        }
      }
    }
    .sheet(isPresented: Binding(
      get: { meshGames.presentedGameID != nil },
      set: { if !$0 { meshGames.presentedGameID = nil } }
    )) {
      if let id = meshGames.presentedGameID {
        MeshGameSheet(gameID: id)
          .presentationDetents([.large])
      }
    }
    .alert(
      GameL10n.Invite.title,
      isPresented: $meshGames.isInvitePresented,
      presenting: meshGames.game(meshGames.inviteGameID)
    ) { game in
      Button(GameL10n.Sheet.play) { meshGames.answerInvite(game.id) }
      Button(GameL10n.Invite.later) {}
      Button(GameL10n.Sheet.decline, role: .destructive) { meshGames.decline(game.id) }
    } message: { game in
      Text(GameL10n.Sheet.invitesYouTo(game.opponentName, game.kind.title))
    }
    .sheet(isPresented: $chess.isBoardPresented) {
      ChessBoardSheet()
        .presentationDetents([.large])
    }
    .alert(
      GameL10n.Kind.chess,
      isPresented: $chess.isInvitePresented,
      presenting: chess.game
    ) { _ in
      Button(GameL10n.Sheet.play) { chess.accept() }
      Button(GameL10n.Sheet.decline, role: .cancel) { chess.decline() }
    } message: { game in
      Text(GameL10n.Chess.invitesYou(game.opponentName))
    }
    .sheet(isPresented: $showingDeviceSelection, onDismiss: {
      appState.handleDeviceSelectionSheetDismissed()
    }) {
      DeviceSelectionSheet()
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
  }

  /// `identityRebuildUnpublished` is not a disconnect, so a nil device keeps the open route.
  private func clearDeviceSettingsIfImportEnded(device: DeviceDTO?) {
    guard device == nil, !appState.connectionManager.identityRebuildUnpublished else { return }
    appState.navigation.clearPerDeviceSelection(
      preservingConnection: appState.connectionLinkSwitch?.target != nil
    )
  }
}
