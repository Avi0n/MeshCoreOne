import MC1Services
import SwiftUI

struct ContactActionsSection: View {
  @Environment(\.appState) private var appState

  let currentContact: ContactDTO
  @Binding var isFavorite: Bool
  let isSettingFavorite: Bool
  let showFromDirectChat: Bool
  let isPinging: Bool
  let pingResult: PingResult?
  let onJoinRoom: () -> Void
  let onShowTelemetry: () -> Void
  let onShowRemoteCLI: () -> Void
  let onShowAdminAccess: () -> Void
  let onPingRepeater: () -> Void
  let onShareQR: () -> Void
  let onShareViaAdvert: () -> Void
  let isSharing: Bool
  let showShareSuccess: Bool
  let historyRoute: ((ContactRoute.TelemetryHistory) -> NodesRoute)?
  let onRowPosition: (ContactDetailRowPosition.Measurement) -> Void

  var body: some View {
    let isRadioReady = appState.connectionState == .ready
    let showsSendMessage = !showFromDirectChat && !currentContact.isBlocked

    Section {
      switch currentContact.type {
      case .room:
        Button(action: onJoinRoom) {
          Label(L10n.Contacts.Contacts.Detail.joinRoom, systemImage: "door.left.hand.open")
        }
        .radioDisabled(for: appState.connectionState)
        .modifier(ContactDetailRowPosition(section: .actions, onPosition: onRowPosition))

        NodeActionRows(
          contact: currentContact,
          pingLabel: L10n.Contacts.Contacts.Detail.ping,
          isPinging: isPinging,
          pingResult: pingResult,
          connectionState: appState.connectionState,
          onShowTelemetry: onShowTelemetry,
          onShowAdminAccess: onShowAdminAccess,
          onPing: onPingRepeater,
          historyRoute: historyRoute,
          onRowPosition: nil
        )

      case .repeater, .sensor:
        NodeActionRows(
          contact: currentContact,
          pingLabel: L10n.Contacts.Contacts.Detail.ping,
          isPinging: isPinging,
          pingResult: pingResult,
          connectionState: appState.connectionState,
          onShowTelemetry: onShowTelemetry,
          onShowAdminAccess: onShowAdminAccess,
          onPing: onPingRepeater,
          historyRoute: historyRoute,
          onRowPosition: onRowPosition
        )

      case .chat:
        if showsSendMessage {
          Button {
            appState.navigation.navigateToChat(with: currentContact)
          } label: {
            Label(L10n.Contacts.Contacts.Detail.sendMessage, systemImage: "message.fill")
          }
          .radioDisabled(for: appState.connectionState)
          .modifier(ContactDetailRowPosition(section: .actions, onPosition: onRowPosition))
        }

        Button(action: onShowTelemetry) {
          Label(L10n.Contacts.Contacts.Detail.telemetry, systemImage: "chart.line.uptrend.xyaxis")
        }
        .radioDisabled(for: appState.connectionState)
        .modifier(ContactDetailRowPosition(section: .actions, onPosition: showsSendMessage ? nil : onRowPosition))

        ContactSavedHistoryLink(
          route: ContactRoute.TelemetryHistory(
            publicKey: currentContact.publicKey,
            radioID: currentContact.radioID,
            showNeighbors: false
          ),
          nodesRoute: historyRoute
        )

        if currentContact.showsRemoteCLI(localRadioSupportsCLI: appState.connectedDevice?.supportsRadioCLI == true) {
          Button(action: onShowRemoteCLI) {
            Label(L10n.Contacts.Contacts.Detail.remoteCLI, systemImage: "terminal")
          }
          .radioDisabled(for: appState.connectionState)
        }
      }

      Button(action: onShareQR) {
        Label(L10n.Contacts.Contacts.Detail.shareContact, systemImage: "square.and.arrow.up")
      }

      Button(action: onShareViaAdvert) {
        if isSharing || showShareSuccess {
          AsyncActionLabel(isLoading: isSharing, showSuccess: showShareSuccess) {
            EmptyView()
          }
        } else {
          Label(L10n.Contacts.Contacts.Detail.shareViaAdvert, systemImage: "antenna.radiowaves.left.and.right")
        }
      }
      .radioDisabled(for: appState.connectionState, or: isSharing || showShareSuccess)

      Toggle(isOn: $isFavorite) {
        Label(L10n.Contacts.Contacts.Detail.favorite, systemImage: "star")
          .foregroundStyle(isRadioReady ? .primary : .secondary)
      }
      .disabled(!isRadioReady || isSettingFavorite)
      .accessibilityHint(
        L10n.Localizable.Accessibility.requiresRadioConnection,
        isEnabled: !isRadioReady
      )
    }
  }
}
