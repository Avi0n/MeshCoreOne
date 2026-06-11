import Foundation

/// Shared `UserDefaults` keys used by both the services layer and the app
/// target. Centralising the string literals prevents drift between writer and
/// reader — e.g. `ConnectionManager` persisting under one name while a
/// migration reads under a stale name and silently no-ops.
///
/// Lane: connection-infrastructure identity (reverse-DNS `com.pocketmesh.*`)
/// plus the theme keys, which predate the typed enum. User preferences and UI
/// state belong in `AppStorageKey` instead; the two namespaces never share a key.
public enum PersistenceKeys {
    public static let lastConnectedDeviceID = "com.pocketmesh.lastConnectedDeviceID"
    public static let lastConnectedDeviceName = "com.pocketmesh.lastConnectedDeviceName"
    public static let lastConnectedRadioID = "com.pocketmesh.lastConnectedRadioID"
    public static let lastDisconnectDiagnostic = "com.pocketmesh.lastDisconnectDiagnostic"
    public static let userExplicitlyDisconnected = "com.pocketmesh.userExplicitlyDisconnected"

    /// Selected theme ID (bare-string key, no `com.pocketmesh.` prefix — matches the
    /// `BackupUserDefaults` string-mapping convention and the value's L10n-key form).
    public static let selectedThemeID = "selectedThemeID"

    /// App color-scheme preference raw value (`"system"` | `"light"` | `"dark"`). Bare string.
    public static let appColorSchemePreference = "appColorSchemePreference"
}
