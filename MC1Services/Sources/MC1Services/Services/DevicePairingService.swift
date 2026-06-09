import Foundation

/// Platform-neutral seam for device discovery and system pairing-registry management.
///
/// `ConnectionManager` depends on this protocol instead of AccessorySetupKit directly,
/// so the platform-specific pairing mechanism is chosen exactly once — at construction,
/// by `DevicePairingFactory` — based on `ProcessInfo.processInfo.isiOSAppOnMac`. No
/// platform branching leaks into the connection logic.
///
/// Two implementations exist:
/// - `AccessorySetupPairingService` — iOS / iPadOS, backed by AccessorySetupKit's system picker.
/// - `BluetoothScanPairingService` — macOS "Designed for iPad", where AccessorySetupKit is
///   unavailable; presents an in-app CoreBluetooth scan picker instead.
///
/// The contract is intentionally UUID-based (never `ASAccessory`) so the macOS path owes
/// nothing to AccessorySetupKit's types.
@MainActor
public protocol DevicePairingService: AnyObject {
    /// Receives system pairing events. Only fires on iOS.
    var delegate: (any DevicePairingDelegate)? { get set }

    /// Whether a system pairing session is active.
    ///
    /// iOS: reflects the AccessorySetupKit session. macOS: always `false` — there is no
    /// session, and a `false` value makes `ConnectionManager` skip its accessory-registration
    /// guard so connects proceed purely over CoreBluetooth.
    var isSessionActive: Bool { get }

    /// Number of devices registered with the system pairing registry.
    ///
    /// iOS: AccessorySetupKit accessory count. macOS: `0` — the in-app device list is
    /// sourced from SwiftData, not from a system registry.
    var registeredDeviceCount: Int { get }

    /// Whether this platform exposes an app-visible system pairing registry (AccessorySetupKit).
    ///
    /// A static platform capability, not a session state — distinct from `isSessionActive`.
    /// iOS: `true`. macOS "Designed for iPad": `false` — there is no registry, so the device
    /// picker filters on the stored connection method rather than registry membership, and a
    /// user-initiated connect to an absent cached peripheral fails fast rather than exhausting
    /// the multi-attempt retry budget (CoreBluetooth cannot pre-reject it without a registry).
    var hasSystemPairingRegistry: Bool { get }

    /// Whether `renameDevice(_:)` presents a real rename surface.
    ///
    /// iOS: `true` — AccessorySetupKit shows its system rename sheet. macOS: `false` — there is
    /// no system rename UI, so `renameDevice(_:)` is a no-op. The UI must hide the rename action
    /// when this is `false` rather than offer a control that silently does nothing.
    var supportsSystemRename: Bool { get }

    /// Activate the system pairing session. iOS: `ASAccessorySession.activate`. macOS: no-op.
    func activate() async throws

    /// Present device-discovery UI and return the selected peripheral's CoreBluetooth UUID.
    ///
    /// iOS: the AccessorySetupKit system picker. macOS: an in-app scan picker driven by
    /// `BluetoothScanPairingService`. Throws `DevicePairingError.cancelled` when
    /// the user cancels, on both platforms, so call sites share one cancellation path.
    func discoverDevice() async throws -> UUID

    /// Whether a connect attempt to this device is permitted by the platform.
    ///
    /// iOS: an AccessorySetupKit accessory exists for this id (registry membership gates the
    /// connect). macOS: always `true` — there is no registry, so CoreBluetooth decides
    /// reachability at connect time.
    func isDeviceConnectable(_ id: UUID) -> Bool

    /// Registered devices as `(id, name)`, for device-list fallbacks when SwiftData is empty.
    /// iOS: AccessorySetupKit accessories. macOS: `[]`.
    func registeredDeviceInfos() -> [(id: UUID, name: String)]

    /// Remove a device from the system pairing registry. Best-effort; a no-op when the device
    /// is not registered. iOS: `removeAccessory`. macOS: no-op (no app-managed bond).
    func removeDevice(_ id: UUID) async throws

    /// Present the system rename UI for a device. iOS: AccessorySetupKit rename sheet. macOS: no-op.
    func renameDevice(_ id: UUID) async throws

    /// Remove every device from the system pairing registry. iOS: clears stale AccessorySetupKit
    /// bonds left by a factory-reset radio. macOS: no-op.
    func clearStaleRegistrations() async
}
