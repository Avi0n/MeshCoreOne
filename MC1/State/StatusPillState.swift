import SwiftUI

/// Represents the current state of the status pill UI component
enum StatusPillState: Hashable {
  case hidden
  case connecting
  case reconnecting
  case waitingToReconnect
  case syncing
  case syncingMessages
  case ready
  case disconnected
  case failed(message: String)

  var displayText: String {
    switch self {
    case let .failed(message): message
    case .syncing: L10n.Localizable.Common.Status.syncing
    case .syncingMessages: L10n.Localizable.Common.Status.syncingMessages
    case .connecting: L10n.Localizable.Common.Status.connecting
    case .reconnecting: L10n.Settings.BleStatus.Status.reconnecting
    case .waitingToReconnect: L10n.Settings.BleStatus.Status.waitingToReconnect
    case .ready: L10n.Localizable.Common.Status.ready
    case .disconnected: L10n.Localizable.Common.Status.disconnected
    case .hidden: ""
    }
  }

  /// Hidden and disconnected keep the connecting label. A blank title or the disconnect warning would look like pairing had stopped.
  var busyProgressTitle: String {
    switch self {
    case .hidden, .disconnected:
      L10n.Localizable.Common.Status.connecting
    default:
      displayText
    }
  }

  var systemImageName: String {
    switch self {
    case .failed: "exclamationmark.triangle.fill"
    case .disconnected, .waitingToReconnect: "exclamationmark.triangle"
    case .ready: "checkmark.circle"
    case .connecting, .reconnecting, .syncing, .syncingMessages: "arrow.trianglehead.2.clockwise"
    case .hidden: ""
    }
  }

  var isFailure: Bool {
    if case .failed = self { return true }
    return false
  }

  var textColor: Color {
    if isFailure { return .red }
    if self == .disconnected || self == .waitingToReconnect { return .orange }
    return .primary
  }
}
