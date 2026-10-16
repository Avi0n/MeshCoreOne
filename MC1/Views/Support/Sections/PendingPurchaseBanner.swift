import SwiftUI

/// Non-blocking "Awaiting approval" banner; rendered only when a pending purchase exists.
struct PendingPurchaseBanner: View {
  @Environment(\.appTheme) private var theme

  var body: some View {
    Section {
      Label(L10n.Settings.Support.Pending.banner, systemImage: "clock.badge.questionmark")
        .font(.footnote)
        .foregroundStyle(.secondary)
    }
    .themedRowBackground(theme)
  }
}
