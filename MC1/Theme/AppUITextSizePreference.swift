import SwiftUI

/// Global app text-size preference, independent of which theme is selected.
/// Raw values are pinned (persisted + backed up); a rename must not change the on-disk format.
enum AppUITextSizePreference: String, CaseIterable, Identifiable {
  case system
  case large
  case xLarge
  case xxLarge

  var id: String {
    rawValue
  }

  /// `nil` means "defer to the system" — the value passed to `.dynamicTypeSize(_:)`.
  /// `.system` maps to `nil` so the app keeps its current appearance: the root
  /// override falls back to the `.large` preset, which `dynamicTypeSize(_:)`
  /// treats as "no override" (no visual change for existing users).
  var dynamicTypeSize: DynamicTypeSize? {
    switch self {
    case .system: nil
    case .large: .xLarge
    case .xLarge: .xxLarge
    case .xxLarge: .accessibility1
    }
  }

  /// Render-scale factor applied on Mac ("Designed for iPad"), where Dynamic Type
  /// is disabled by the platform and no text/trait override works.
  var uiScale: CGFloat {
    switch self {
    case .system: 1.0
    case .large: 1.2
    case .xLarge: 1.35
    case .xxLarge: 1.5
    }
  }
}
