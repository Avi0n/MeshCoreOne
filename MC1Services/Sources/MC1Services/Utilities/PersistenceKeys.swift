import Foundation

/// Shared `UserDefaults` keys used by both the services layer and the app
/// target. Centralising the string literals prevents drift between writer and
/// reader — e.g. `ConnectionManager` persisting under one name while a
/// migration reads under a stale name and silently no-ops.
public enum PersistenceKeys {
    public static let lastConnectedDeviceID = "com.pocketmesh.lastConnectedDeviceID"
    public static let lastConnectedDeviceName = "com.pocketmesh.lastConnectedDeviceName"
    public static let lastConnectedRadioID = "com.pocketmesh.lastConnectedRadioID"

    /// Selected theme ID (bare-string key, no `com.pocketmesh.` prefix — matches the
    /// `BackupUserDefaults` string-mapping convention and the value's L10n-key form).
    public static let selectedThemeID = "selectedThemeID"

    /// App color-scheme preference raw value (`"system"` | `"light"` | `"dark"`). Bare string.
    public static let appColorSchemePreference = "appColorSchemePreference"
}
