import AppIntents
import MC1Services

/// Read-only voice/Shortcuts glance at the connected radio: its name, whether
/// it is connected, and its last cached battery level. Deliberately cached-only
/// and zero-await so it answers instantly and works while the device is locked
/// (no send, no state change, no radio round-trip), which is exactly when a
/// pocketed responder wants it. The battery value is honestly staleness-labeled
/// because it is whatever the last background poll read, not a fresh reading.
struct StatusQueryIntent: AppIntent {
    static let title = LocalizedStringResource("intent.status.title", table: "Tools")
    static let description = IntentDescription(
        LocalizedStringResource("intent.status.description", table: "Tools")
    )
    static let openAppWhenRun = false

    /// Reads only cached `@Observable` state and never touches the radio, so it is
    /// safe to answer from the lock screen. Without this the default
    /// `.requiresAuthentication` would force an unlock first, defeating the
    /// pocketed-responder glance the intent exists for.
    static let authenticationPolicy: IntentAuthenticationPolicy = .alwaysAllowed

    @Dependency var bridge: IntentBridge

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard let appState = bridge.appState else {
            return .result(dialog: IntentDialog(stringLiteral: L10n.Tools.Intent.Status.Dialog.notReady))
        }
        return .result(dialog: IntentDialog(stringLiteral: Self.dialogText(for: appState)))
    }

    /// Builds the spoken line from synchronous `@Observable` state only. Connected
    /// rungs report cached battery (or "no reading" when the radio is mains-powered
    /// or the value is absent); other rungs report the connection state with the
    /// best offline-readable radio name.
    @MainActor
    static func dialogText(for appState: AppState) -> String {
        let connectionState = appState.connectionManager.connectionState

        if connectionState.isConnected {
            let name = appState.connectedDevice?.nodeName ?? offlineName(for: appState) ?? L10n.Tools.Intent.Status.radioFallbackName
            guard let battery = appState.batteryMonitor.deviceBattery,
                  battery.isBatteryPresent else {
                return L10n.Tools.Intent.Status.Dialog.connectedNoBattery(name)
            }
            let ocvArray = appState.batteryMonitor.activeBatteryOCVArray(for: appState.connectedDevice)
            let percent = battery.percentage(using: ocvArray)
            return L10n.Tools.Intent.Status.Dialog.connectedWithBattery(name, percent)
        }

        if connectionState == .connecting {
            let name = offlineName(for: appState) ?? L10n.Tools.Intent.Status.radioFallbackName
            return L10n.Tools.Intent.Status.Dialog.connecting(name)
        }

        if let name = offlineName(for: appState) {
            return L10n.Tools.Intent.Status.Dialog.disconnectedNamed(name)
        }
        return L10n.Tools.Intent.Status.Dialog.disconnectedUnknown
    }

    /// Last-connected radio name for offline display, or nil when this install has
    /// never connected so a caller can speak a generic fallback instead.
    @MainActor
    private static func offlineName(for appState: AppState) -> String? {
        appState.connectionManager.lastConnectedDeviceName
    }
}
