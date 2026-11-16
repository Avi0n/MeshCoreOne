import MC1Services

extension ConnectionActivity {
  var statusTitle: String {
    switch phase {
    case .connecting: L10n.Settings.BleStatus.Status.connecting
    case .reconnecting: L10n.Settings.BleStatus.Status.reconnecting
    case .waitingToReconnect: L10n.Settings.BleStatus.Status.waitingToReconnect
    case .connected: L10n.Settings.BleStatus.Status.connected
    case .syncing: L10n.Settings.BleStatus.Status.syncing
    case .ready: L10n.Settings.BleStatus.Status.ready
    }
  }

  var stopTitle: String {
    switch phase {
    case .connecting: L10n.Settings.BleStatus.cancelConnection
    case .reconnecting, .waitingToReconnect: L10n.Settings.BleStatus.stopReconnecting
    case .connected, .syncing, .ready: L10n.Settings.BleStatus.disconnect
    }
  }

  var accessibilityValue: String {
    guard let targetName else { return statusTitle }
    return L10n.Settings.BleStatus.accessibilityValue(targetName, statusTitle)
  }
}
