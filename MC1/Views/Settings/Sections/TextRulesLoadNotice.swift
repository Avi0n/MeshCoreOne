import SwiftUI

struct TextRulesLoadNotice: View {
  @Environment(\.appState) private var appState

  var body: some View {
    if appState.isTextRulesLoading {
      ProgressView(L10n.Settings.TextRules.loading)
    } else if appState.textRulesPreference == nil {
      VStack(alignment: .leading) {
        Text(L10n.Settings.TextRules.loadFailed)
          .foregroundStyle(.secondary)
        Button(L10n.Localizable.Common.tryAgain) {
          Task { await appState.connectionManager.retryTextRulesLoad() }
        }
        .accessibilityLabel(L10n.Settings.TextRules.retryAccessibility)
      }
    }
  }
}
