import MC1Services
import SwiftUI

/// The phone's link to the connected radio. Bluetooth, then Wi-Fi.
struct ConnectionSettingsView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  let descendantPath: [SettingsSubpage]
  @State private var radioWiFi = RadioWiFiViewModel()

  var body: some View {
    let presentation = linkPresentation
    List {
      Section {
        ForEach(presentation.rows) { row in
          ConnectionLinkChoiceRow(
            row: row,
            isChecked: presentation.checked == row.link,
            isTarget: presentation.target == row.link,
            isLive: liveLink == row.link,
            attemptInProgress: presentation.target != nil,
            onSwitch: { switchLink(row.link) }
          )
        }
      }
      .themedRowBackground(theme)
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Settings.Connection.header)
    .navigationBarTitleDisplayMode(.inline)
    .refreshable {
      await radioWiFi.refresh()
    }
    .errorAlert(connectionRecoveryMessage, retryAction: { Task { await radioWiFi.refresh() } })
    .settingsSubpageDestinations(radioWiFi: radioWiFi)
    .task(id: radioWiFiRefreshID) {
      radioWiFi.configure(
        settingsService: { appState.services?.settingsService },
        connectionState: { appState.connectionState },
        connectedDevice: { appState.connectedDevice },
        currentTransportType: { appState.connectionManager.currentTransportType },
        reboot: {
          guard let settingsService = appState.services?.settingsService else {
            throw SettingsServiceError.notConnected
          }
          try await settingsService.reboot()
        }
      )
      await radioWiFi.noteSessionReadiness()
    }
  }

  private var liveLink: TransportType? {
    appState.connectionManager.currentTransportType
  }

  private var linkPresentation: ConnectionLinkRows.Snapshot {
    let device = appState.connectedDevice
    let live = appState.connectionManager.currentTransportType
    let snapshotHost = radioWiFi.statusConnected ? radioWiFi.ipAddress : nil
    let wifi = ConnectionLinkWiFiDial.capture(
      liveTransport: live,
      savedWiFi: device?.wifiConnectionMethod,
      snapshotHost: snapshotHost
    )
    return ConnectionLinkRows.make(
      liveTransport: live,
      savedPeripheralID: device?.bluetoothPeripheralID,
      wifiSupport: radioWiFi.wifiSettingsSupport,
      wifiAssociated: radioWiFi.statusConnected,
      wifiDial: wifi,
      activeSwitch: appState.connectionLinkSwitch
    )
  }

  private var connectionRecoveryMessage: Binding<String?> {
    Binding(
      get: {
        guard radioWiFi.recoverySurface(wifiPageVisible: descendantPath.contains(.wifiLink)) == .connection else {
          return nil
        }
        return radioWiFi.errorMessage
      },
      set: { radioWiFi.errorMessage = $0 }
    )
  }

  /// Reloads radio WiFi once per ready settings service. A resize does not change it.
  private var radioWiFiRefreshID: RadioWiFiRefreshID {
    RadioWiFiRefreshID(
      service: (appState.services?.settingsService).map(ObjectIdentifier.init),
      isReady: appState.connectionState == .ready
    )
  }

  private func switchLink(_ link: TransportType) {
    guard link != liveLink else { return }
    guard appState.connectionLinkSwitch?.target == nil else { return }
    guard linkPresentation.rows.contains(where: { $0.link == link && $0.canSwitch }) else { return }
    let snapshotHost = radioWiFi.statusConnected ? radioWiFi.ipAddress : nil
    Task {
      await appState.beginLinkSwitch(to: link, snapshotWiFiHost: snapshotHost)
    }
  }

  private struct RadioWiFiRefreshID: Equatable {
    var service: ObjectIdentifier?
    var isReady: Bool
  }
}

private enum ConnectionLinkChoiceMetrics {
  static let checkColumnWidth: CGFloat = 24
  static let rowSpacing: CGFloat = 12
  static let labelSpacing: CGFloat = 2
}

private struct ConnectionLinkChoiceRow: View {
  let row: ConnectionLinkRows.Row
  let isChecked: Bool
  let isTarget: Bool
  let isLive: Bool
  let attemptInProgress: Bool
  let onSwitch: () -> Void

  var body: some View {
    HStack(alignment: .center, spacing: ConnectionLinkChoiceMetrics.rowSpacing) {
      Button(action: onSwitch) {
        HStack(alignment: .center, spacing: ConnectionLinkChoiceMetrics.rowSpacing) {
          marker
          VStack(alignment: .leading, spacing: ConnectionLinkChoiceMetrics.labelSpacing) {
            Text(title)
            if let address = row.address {
              Text(Self.spelledOut(address))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            }
          }
          Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
      }
      .buttonStyle(.borderless)
      .disabled(attemptInProgress || (!isLive && !row.canSwitch))
      .accessibilityLabel(title)
      .accessibilityAddTraits(isLive ? .isSelected : [])
      .modifier(ConnectionLinkSpokenValue(value: spokenValue, hint: switchHint))

      NavigationLink(value: infoPage) {
        Image(systemName: "info.circle")
          .foregroundStyle(Color.accentColor)
      }
      .buttonStyle(.borderless)
      .navigationLinkIndicatorVisibility(.hidden)
      .accessibilityLabel(infoLabel)
    }
  }

  private var title: String {
    switch row.link {
    case .bluetooth:
      L10n.Settings.Bluetooth.header
    case .wifi:
      L10n.Settings.Connection.Link.wifi
    }
  }

  private var infoPage: SettingsSubpage {
    switch row.link {
    case .bluetooth:
      .bluetoothLink
    case .wifi:
      .wifiLink
    }
  }

  private var infoLabel: String {
    switch row.link {
    case .bluetooth:
      L10n.Settings.Connection.Link.bluetoothInfo
    case .wifi:
      L10n.Settings.Connection.Link.wifiInfo
    }
  }

  /// In use follows the live transport. Switching follows the target. The address is the other row's value once the attempt has ended.
  private var spokenValue: Text? {
    if isLive {
      return Text(L10n.Settings.Connection.Link.inUse)
    }
    if isTarget {
      return Text(L10n.Settings.Connection.Link.switching)
    }
    guard !attemptInProgress, let address = row.address else { return nil }
    return Text(Self.spelledOut(address))
  }

  private static func spelledOut(_ address: String) -> AttributedString {
    var spoken = AttributedString(address)
    spoken.accessibilitySpeechSpellsOutCharacters = true
    return spoken
  }

  private var switchHint: Text? {
    if attemptInProgress || isLive || row.canSwitch == false {
      return nil
    }
    return Text(L10n.Settings.Connection.Link.switchHint)
  }

  @ViewBuilder
  private var marker: some View {
    if isTarget {
      ProgressView()
        .controlSize(.small)
        .frame(width: ConnectionLinkChoiceMetrics.checkColumnWidth)
    } else if isChecked {
      Image(systemName: "checkmark")
        .fontWeight(.semibold)
        .foregroundStyle(Color.accentColor)
        .frame(width: ConnectionLinkChoiceMetrics.checkColumnWidth)
        .accessibilityHidden(true)
    } else {
      Color.clear
        .frame(width: ConnectionLinkChoiceMetrics.checkColumnWidth, height: 1)
    }
  }
}

/// Applies each accessibility annotation only when the row has one.
private struct ConnectionLinkSpokenValue: ViewModifier {
  let value: Text?
  let hint: Text?

  func body(content: Content) -> some View {
    switch (value, hint) {
    case let (value?, hint?):
      content.accessibilityValue(value).accessibilityHint(hint)
    case let (value?, nil):
      content.accessibilityValue(value)
    case let (nil, hint?):
      content.accessibilityHint(hint)
    case (nil, nil):
      content
    }
  }
}
