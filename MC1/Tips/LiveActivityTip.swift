import SwiftUI
import TipKit

/// Tip shown after a Live Activity starts for the first time
struct LiveActivityTip: Tip {
    static let radioConnected = Tips.Event(id: "radioConnected")

    var title: Text {
        Text(L10n.Settings.LiveActivity.Tip.title)
    }

    var message: Text? {
        Text(L10n.Settings.LiveActivity.Tip.message)
    }

    var options: [TipOption] {
        [Tips.MaxDisplayCount(1)]
    }

    var rules: [Rule] {
        #Rule(Self.radioConnected) { $0.donations.count >= 1 }
    }
}
