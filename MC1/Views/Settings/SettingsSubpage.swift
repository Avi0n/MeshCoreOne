import SwiftUI

/// Second-level Settings pushes: rows inside a Settings detail page that drill one level
/// deeper. Value-based so each push rebuilds the destination instead of reusing stale
/// `@State` from a prior visit.
enum SettingsSubpage: Hashable {
  case publicKey(Data)
  case configExport
  case configImport
  case blockedChannelSenders
  case blockedContacts
  case trustedContacts
  case translateIntoLanguage
  case presetLocation
}

extension View {
  /// Registers `SettingsSubpage` pushes on each host list. Destinations are stack siblings,
  /// so they do not inherit the host's environment.
  @MainActor
  func settingsSubpageDestinations(
    presetLocationSession: PresetLocationSession? = nil
  ) -> some View {
    navigationDestination(for: SettingsSubpage.self) { subpage in
      switch subpage {
      case let .publicKey(publicKey):
        PublicKeyView(publicKey: publicKey)
      case .configExport:
        NodeConfigExportView()
      case .configImport:
        NodeConfigImportView()
      case .blockedChannelSenders:
        BlockedChannelSendersView()
      case .blockedContacts:
        BlockedContactsView()
      case .trustedContacts:
        TrustedContactsPickerView()
      case .translateIntoLanguage:
        TranslateIntoLanguageView()
      case .presetLocation:
        if let presetLocationSession {
          PresetLocationView()
            .environment(presetLocationSession)
        }
      }
    }
  }
}
