import Foundation

/// Typed wrapper for `@SceneStorage` key strings: per-scene UI state that must
/// survive view recreation and backgrounding via SwiftUI state restoration.
///
/// Unlike `AppStorageKey`, these values live in the scene restoration archive,
/// not `UserDefaults`. They are intentionally device-local and per-scene, so a
/// key here is never registered in `BackupUserDefaults`.
///
/// Raw values are pinned to their exact on-disk key so a case rename can't
/// silently mint a new key and orphan restored state. Keep them pinned.
public enum SceneStorageKey: String {
    // swiftlint:disable redundant_string_enum_value
    /// The user's last map camera region, serialized by `MapCameraStore`.
    case mapCameraRegion = "mapCameraRegion"
    // swiftlint:enable redundant_string_enum_value
}
