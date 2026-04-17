import SwiftUI
import TipKit

/// Tip shown near the Backup & Restore entry until the user has exported at least once
struct BackupTip: Tip {
    static let exportCompleted = Tips.Event(id: "exportCompleted")

    var title: Text {
        Text(L10n.Settings.Settings.Backup.Tip.title)
    }

    var message: Text? {
        Text(L10n.Settings.Settings.Backup.Tip.message)
    }

    var image: Image? {
        Image(systemName: "archivebox.fill")
    }

    var rules: [Rule] {
        #Rule(Self.exportCompleted) {
            $0.donations.count == 0
        }
    }
}
