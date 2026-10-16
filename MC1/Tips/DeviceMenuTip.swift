import SwiftUI
import TipKit

/// Tip shown after onboarding to introduce the device menu and its controls
struct DeviceMenuTip: Tip {
  static let hasCompletedOnboarding = Tips.Event(id: "hasCompletedOnboarding")

  /// Live connection state. The tip introduces the connected device menu, so it
  /// must stay hidden while disconnected, when the menu offers only Connect Device.
  @Parameter
  static var isConnected: Bool = false

  var title: Text {
    Text(L10n.Chats.Chats.Tip.DeviceMenu.title)
  }

  var message: Text? {
    Text(L10n.Chats.Chats.Tip.DeviceMenu.message)
  }

  var image: Image? {
    Image(systemName: "antenna.radiowaves.left.and.right")
  }

  var options: [TipOption] {
    [Tips.MaxDisplayCount(1)]
  }

  var rules: [Rule] {
    #Rule(Self.hasCompletedOnboarding) { $0.donations.count >= 1 }
    #Rule(Self.$isConnected) { $0 == true }
  }
}
