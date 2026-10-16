import MC1Services
import SwiftUI

/// Settings section for automatic cleanup of stale non-favorite nodes
struct StaleNodeCleanupSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @AppStorage(AppStorageKey.autoDeleteStaleNodesDays.rawValue)
  private var threshold: Int = AppStorageKey.defaultAutoDeleteStaleNodesDays
  @AppStorage(AppStorageKey.lastStaleCleanupDate.rawValue)
  private var lastCleanupTimestamp: Double = AppStorageKey.defaultLastStaleCleanupDate

  @State private var isEnabled = false

  var body: some View {
    Section {
      Toggle(L10n.Settings.Nodes.StaleCleanup.header, isOn: $isEnabled)

      if isEnabled {
        Picker(L10n.Settings.Nodes.StaleCleanup.threshold, selection: $threshold) {
          Text(L10n.Settings.Nodes.StaleCleanup.select).tag(0)
          Text(L10n.Settings.Nodes.StaleCleanup.days(7)).tag(7)
          Text(L10n.Settings.Nodes.StaleCleanup.days(14)).tag(14)
          Text(L10n.Settings.Nodes.StaleCleanup.days(30)).tag(30)
          Text(L10n.Settings.Nodes.StaleCleanup.days(90)).tag(90)
        }
        .pickerStyle(.menu)

        if lastCleanupTimestamp > 0, threshold > 0 {
          Text(L10n.Settings.Nodes.StaleCleanup.lastRun(
            Date(timeIntervalSinceReferenceDate: lastCleanupTimestamp)
              .formatted(.relative(presentation: .named))
          ))
          .foregroundStyle(.secondary)
          .font(.subheadline)
        }
      }
    } footer: {
      if !isEnabled {
        Text(L10n.Settings.Nodes.StaleCleanup.footerDisabled)
      } else if threshold == 0 {
        Text(L10n.Settings.Nodes.StaleCleanup.footerSelect)
      } else if appState.connectionState == .ready {
        Text(L10n.Settings.Nodes.StaleCleanup.footerEnabled(threshold))
      } else {
        Text(L10n.Settings.Nodes.StaleCleanup.footerDisconnected)
      }
    }
    .themedRowBackground(theme)
    .radioDisabled(for: appState.connectionState)
    .onAppear {
      isEnabled = threshold > 0
    }
    .onChange(of: isEnabled) { _, newValue in
      if !newValue {
        threshold = 0
      }
    }
    .onChange(of: threshold) { _, newThreshold in
      guard newThreshold > 0, appState.connectionState == .ready else { return }
      appState.performStaleNodeCleanup(force: true)
    }
  }
}

#Preview {
  Form {
    StaleNodeCleanupSection()
  }
}
