import MC1Services
import SwiftUI

/// Section for configuring the device's persisted default flood scope (firmware v11+).
///
/// Lets the user choose between ``FloodScope/disabled`` (clear) or one of the
/// radio's already-known regions. New regions are added via Manage Regions.
/// Selections are sent via ``SettingsService/setDefaultFloodScopeVerified(name:)``
/// and the accepted value is cached in ``DeviceDTO/defaultFloodScopeName``.
struct DefaultFloodScopeSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss
  @Environment(RegionDiscoveryState.self) private var discovery
  @State private var isApplying = false
  @State private var errorMessage: String?
  @State private var retryAlert = RetryAlertState()

  var body: some View {
    Section {
      disabledRow

      ForEach(sortedKnownRegions, id: \.self) { region in
        scopeRow(region)
      }

      discoveryStatusRow
      discoverButton
      manageRegionsButton
    } header: {
      Text(L10n.Settings.DefaultFloodScope.header)
    } footer: {
      Text(L10n.Settings.DefaultFloodScope.footer)
    }
    .themedRowBackground(theme)
    .radioDisabled(for: appState.connectionState, or: isApplying)
    .errorAlert($errorMessage)
    .retryAlert(retryAlert)
  }

  // MARK: - Rows

  private var disabledRow: some View {
    Button {
      apply(name: nil)
    } label: {
      row(title: L10n.Settings.DefaultFloodScope.disabled, selected: currentScope == nil)
    }
    .buttonStyle(.plain)
  }

  private func scopeRow(_ region: String) -> some View {
    Button {
      apply(name: region)
    } label: {
      row(title: region, selected: currentScope == region)
    }
    .buttonStyle(.plain)
  }

  @ViewBuilder
  private var discoveryStatusRow: some View {
    if discovery.isDiscovering {
      HStack {
        ProgressView()
        Text(L10n.Chats.Chats.ChannelInfo.Region.discovering)
          .foregroundStyle(.secondary)
      }
    } else if let discoveryMessage = discovery.discoveryMessage {
      Text(discoveryMessage)
        .font(.caption)
        .foregroundStyle(.secondary)
    }
  }

  private var discoverButton: some View {
    Button(
      L10n.Chats.Chats.ChannelInfo.Region.discover,
      systemImage: "antenna.radiowaves.left.and.right",
      action: { discovery.start() }
    )
    .disabled(discovery.isDiscovering)
  }

  private var manageRegionsButton: some View {
    NavigationLink(value: SettingsSubpage.manageRegions) {
      Label(L10n.Chats.Chats.ChannelInfo.Region.manageRegions, systemImage: "list.bullet")
    }
  }

  private func row(title: String, selected: Bool) -> some View {
    HStack {
      Text(title)
        .foregroundStyle(Color.primary)
      Spacer()
      if selected {
        Image(systemName: "checkmark")
          .foregroundStyle(.tint)
      }
    }
    .contentShape(.rect)
  }

  // MARK: - Derived state

  private var currentScope: String? {
    appState.connectedDevice?.defaultFloodScopeName
  }

  private var sortedKnownRegions: [String] {
    discovery.knownRegions
      .sorted { $0.localizedStandardCompare($1) == .orderedAscending }
  }

  // MARK: - Actions

  private func apply(name: String?) {
    isApplying = true
    Task {
      do {
        guard let settingsService = appState.services?.settingsService else {
          throw ConnectionError.notConnected
        }
        _ = try await settingsService.setDefaultFloodScopeVerified(name: name)
        retryAlert.reset()
      } catch let error as SettingsServiceError where error.isRetryable {
        retryAlert.show(
          message: error.userFacingMessage,
          onRetry: { apply(name: name) },
          onMaxRetriesExceeded: { dismiss() }
        )
      } catch {
        errorMessage = error.userFacingMessage
      }
      isApplying = false
    }
  }
}
