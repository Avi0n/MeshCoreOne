import MC1Services
import SwiftUI

/// Descendant routes for Settings navigation.
enum SettingsSubpage: Hashable {
  case publicKey(Data)
  case configExport
  case configImport
  case textRuleChats(TextRulesPreference.Kind)
  case recentlyBlocked
  case blockedChannelSenders
  case blockedContacts
  case trustedContacts
  case remoteCLIContacts
  case translateIntoLanguage
  case presetLocation
  case offlineMaps
  case manageRegions
  case support
  case bluetoothLink
  case wifiLink
  case deviceInfoTelemetryHistory(publicKey: Data)
  case deviceInfoLocationMap(publicKey: Data, fix: NodeLocationFix, name: String?)

  func isAdmitted(for detail: SettingsDetail?, device: DeviceDTO?) -> Bool {
    switch self {
    case .bluetoothLink, .wifiLink:
      detail == .connection
    case .publicKey:
      detail == .deviceInfo
    case let .deviceInfoTelemetryHistory(publicKey):
      detail == .deviceInfo && device?.publicKey == publicKey
    case let .deviceInfoLocationMap(publicKey, _, _):
      detail == .deviceInfo && device?.publicKey == publicKey
    case .configExport, .configImport, .trustedContacts:
      detail == .advanced
    case .remoteCLIContacts:
      detail == .advanced && device?.supportsRadioCLI == true
    case .textRuleChats, .recentlyBlocked, .blockedChannelSenders, .blockedContacts:
      detail == .blocksAndFilters
    case .translateIntoLanguage:
      detail == .language
    case .presetLocation:
      detail == .radio && device?.clientRepeat != true
    case .offlineMaps:
      detail == .maps
    case .manageRegions:
      detail == .advanced && device?.supportsDefaultFloodScope == true
    case .support:
      detail == .appearance
    }
  }

  static func admitted(
    _ path: [SettingsSubpage],
    for detail: SettingsDetail?,
    device: DeviceDTO?
  ) -> [SettingsSubpage] {
    path.filter { $0.isAdmitted(for: detail, device: device) }
  }
}

/// Context owned by the page that registers the stack. Route values stay free of services.
private enum SettingsSubpageContext {
  case standard
  case radio(PresetLocationSession)
  case advanced(RegionDiscoveryState, SettingsEditorPresentation.RegionRequest, NodeConfigImportViewModel)
  case connection(RadioWiFiViewModel)
  case deviceInfo(DeviceInfoTelemetryViewModel)
}

extension View {
  func settingsSubpageDestinations() -> some View {
    modifier(SettingsSubpageRegistration(context: .standard))
  }

  func settingsSubpageDestinations(presetLocation session: PresetLocationSession) -> some View {
    modifier(SettingsSubpageRegistration(context: .radio(session)))
  }

  func settingsSubpageDestinations(
    regions discovery: RegionDiscoveryState,
    requestRegionAdd: @escaping SettingsEditorPresentation.RegionRequest,
    configImport: NodeConfigImportViewModel
  ) -> some View {
    modifier(SettingsSubpageRegistration(context: .advanced(discovery, requestRegionAdd, configImport)))
  }

  func settingsSubpageDestinations(radioWiFi: RadioWiFiViewModel) -> some View {
    modifier(SettingsSubpageRegistration(context: .connection(radioWiFi)))
  }

  func settingsSubpageDestinations(deviceInfoTelemetry model: DeviceInfoTelemetryViewModel) -> some View {
    modifier(SettingsSubpageRegistration(context: .deviceInfo(model)))
  }
}

/// Stack destinations are siblings, so child pages receive their root's context explicitly.
private struct SettingsSubpageRegistration: ViewModifier {
  let context: SettingsSubpageContext

  func body(content: Content) -> some View {
    content.navigationDestination(for: SettingsSubpage.self) { subpage in
      SettingsSubpageDestination(subpage: subpage, context: context)
    }
  }
}

private struct SettingsSubpageDestination: View {
  let subpage: SettingsSubpage
  let context: SettingsSubpageContext

  var body: some View {
    switch subpage {
    case let .publicKey(publicKey):
      PublicKeyView(publicKey: publicKey)
    case .configExport:
      NodeConfigExportView()
    case .configImport:
      if case let .advanced(_, _, configImport) = context {
        NodeConfigImportView(viewModel: configImport)
          .onAppear { configImport.beginImport() }
      }
    case let .textRuleChats(kind):
      TextRuleChatsView(kind: kind)
    case .recentlyBlocked:
      RecentlyBlockedView()
    case .blockedChannelSenders:
      BlockedChannelSendersView()
    case .blockedContacts:
      BlockedContactsView()
    case .trustedContacts:
      TrustedContactsPickerView()
    case .remoteCLIContacts:
      RemoteCLIContactsPickerView()
    case .translateIntoLanguage:
      TranslateIntoLanguageView()
    case .offlineMaps:
      OfflineMapSettingsView()
    case .support:
      SupportDevelopmentView()
    case .presetLocation:
      if case let .radio(session) = context {
        PresetLocationView()
          .environment(session)
      }
    case .manageRegions:
      if case let .advanced(discovery, requestRegionAdd, _) = context {
        ManageRegionsPage(discovery: discovery, requestRegionAdd: requestRegionAdd)
      }
    case .bluetoothLink:
      ConnectionBluetoothPage()
    case .wifiLink:
      if case let .connection(radioWiFi) = context {
        ConnectionWiFiPage(radioWiFi: radioWiFi)
      }
    case .deviceInfoTelemetryHistory:
      if case let .deviceInfo(model) = context {
        TelemetryHistoryView(fetchSnapshots: model.fetchHistory, ocvArray: model.ocvValues)
      }
    case let .deviceInfoLocationMap(_, fix, name):
      nodeLocationMap(fix: fix, name: name)
    }
  }
}

private struct ManageRegionsPage: View {
  @Environment(\.appState) private var appState
  let discovery: RegionDiscoveryState
  let requestRegionAdd: SettingsEditorPresentation.RegionRequest

  var body: some View {
    RegionManagementView(
      knownRegions: discovery.knownRegions,
      isDiscovering: Binding(get: { discovery.isDiscovering }, set: { _ in }),
      discoveryMessage: Binding(get: { discovery.discoveryMessage }, set: { _ in }),
      onRemoveRegion: { discovery.removeKnownRegion($0) },
      onAddRegion: { discovery.addKnownRegion($0) },
      onDiscoverTapped: { discovery.start() },
      requestAddRegion: {
        requestRegionAdd({ discovery.knownRegions }, { discovery.addKnownRegion($0) })
      }
    )
    .onChange(of: appState.connectedDevice?.knownRegions) { _, _ in
      discovery.noteRegionsChanged()
    }
  }
}
