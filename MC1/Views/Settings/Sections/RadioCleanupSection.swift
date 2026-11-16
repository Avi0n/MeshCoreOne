import MC1Services
import SwiftUI

struct RadioCleanupSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  private typealias Strings = L10n.Settings.RadioCleanup

  private var operations: [ConnectionManager.RadioCleanup] {
    appState.connectionManager.radioCleanup.values.sorted {
      $0.target.radioName.localizedStandardCompare($1.target.radioName) == .orderedAscending
    }
  }

  var body: some View {
    if !operations.isEmpty {
      Section(Strings.title) {
        ForEach(operations) { operation in
          VStack(alignment: .leading) {
            Text(operation.target.radioName)
              .font(.headline)
            switch operation.state {
            case .running:
              ProgressView(Strings.running)
            case .failed:
              Text(Strings.failed)
                .foregroundStyle(.secondary)
              Button(L10n.Localizable.Common.tryAgain) {
                appState.connectionManager.retryRadioErase(operation.target)
              }
              .accessibilityLabel(Strings.retryAccessibility(operation.target.radioName))
            case .completed:
              Text(Strings.completed)
                .foregroundStyle(.secondary)
            }
          }
        }
      }
      .themedRowBackground(theme)
    }
  }
}
