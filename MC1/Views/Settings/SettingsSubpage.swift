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
  /// Registers the `SettingsSubpage` destinations on the enclosing navigation stack. Each
  /// hosting page applies this to its own `List` so the pushes resolve in every stack that
  /// hosts the page (the compact Settings stack and the iPad detail column).
  /// Destination content is a stack sibling of the host, so it does not inherit the host's
  /// environment.
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
