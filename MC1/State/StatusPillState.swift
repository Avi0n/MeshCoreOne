import SwiftUI

/// Represents the current state of the status pill UI component
enum StatusPillState: Hashable {
  case hidden
  case connecting
  case syncing
  case ready
  case disconnected
  case failed(message: String)

  var displayText: String {
    switch self {
    case let .failed(message): message
    case .syncing: L10n.Localizable.Common.Status.syncing
    case .connecting: L10n.Localizable.Common.Status.connecting
    case .ready: L10n.Localizable.Common.Status.ready
    case .disconnected: L10n.Localizable.Common.Status.disconnected
    case .hidden: ""
    }
  }

  var systemImageName: String {
    switch self {
    case .failed: "exclamationmark.triangle.fill"
    case .disconnected: "exclamationmark.triangle"
    case .ready: "checkmark.circle"
    case .connecting, .syncing: "arrow.trianglehead.2.clockwise"
    case .hidden: ""
    }
  }

  var isFailure: Bool {
    if case .failed = self { return true }
    return false
  }

  var textColor: Color {
    if isFailure { return .red }
    if case .disconnected = self { return .orange }
    return .primary
  }
}
