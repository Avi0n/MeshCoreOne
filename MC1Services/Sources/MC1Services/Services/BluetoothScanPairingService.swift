import Foundation
import os

/// macOS "Designed for iPad" `DevicePairingService`.
///
/// AccessorySetupKit does not function when the iOS binary runs on macOS, so device
/// discovery falls back to an in-app CoreBluetooth scan picker. This service owns only the
/// presentation handshake: `discoverDevice()` raises `isPresenting` and suspends on a
/// continuation; the view layer observes `isPresenting`, presents the scan picker (which
/// runs `ConnectionManager.startBLEScanning()` itself), and calls `select(_:)` or `cancel()`
/// to resolve the continuation. The selected UUID then flows through the same `connect(to:)`
/// ceremony as the iOS path.
///
/// Every system-registry operation is a no-op: macOS CoreBluetooth manages bonds at the OS
/// level with no app-visible registry.
@MainActor
@Observable
public final class BluetoothScanPairingService: DevicePairingService {
    public weak var delegate: (any DevicePairingDelegate)?

    /// `true` while the in-app scan picker should be presented. Observed by the view layer.
    public private(set) var isPresenting = false

    private var discoveryContinuation: CheckedContinuation<UUID, Error>?

    /// Set synchronously by the `onCancel` handler the instant the discovery task is cancelled,
    /// before its `Task { @MainActor ... }` hop to `cancel()` is scheduled, and read on the
    /// resolution path so a `select(_:)` that lands on the MainActor inside that hop window
    /// resolves the continuation with `.cancelled` rather than a stale selection. Lives behind a
    /// lock because `onCancel` may run off the MainActor.
    private let cancellationRequested = OSAllocatedUnfairLock(initialState: false)

    public init() {}

    public var isSessionActive: Bool { false }
    public var registeredDeviceCount: Int { 0 }
    public var hasSystemPairingRegistry: Bool { false }
    public var supportsSystemRename: Bool { false }

    public func activate() async throws {}

    /// Single-flight invariant: the only production caller is `ConnectionManager.pairNewDevice()`,
    /// which is serialized by its `isPairingInProgress` gate, so two discoveries never overlap at
    /// runtime — the prior continuation has always resolved before the next call begins. The
    /// stranded-discovery resolution below, the `onCancel` handler, and the `cancellationRequested`
    /// flag are defensive: they keep a single continuation consistent against cancellation, not
    /// against concurrent callers. If a second concurrent caller of this method is ever added, add a
    /// per-discovery token (mirroring `ConnectionManager.bleScanRequestID`) so a stale cancel cannot
    /// resolve a newer continuation.
    public func discoverDevice() async throws -> UUID {
        // Clear any prior cancellation and resolve any stranded prior discovery before starting one.
        cancellationRequested.withLock { $0 = false }
        resolveDiscovery(with: .failure(DevicePairingError.cancelled))

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                // If the task was already cancelled before the continuation is installed,
                // `onCancel` has already run and found nothing to resolve. Resume here rather
                // than installing a continuation that nothing will ever resume (which would
                // leak it and strand `isPresenting`).
                guard !Task.isCancelled else {
                    continuation.resume(throwing: DevicePairingError.cancelled)
                    return
                }
                self.discoveryContinuation = continuation
                self.isPresenting = true
            }
        } onCancel: {
            // Set synchronously so a `select(_:)` racing this cancellation on the MainActor can't
            // resolve with a stale selection before the hop below runs `cancel()`.
            self.cancellationRequested.withLock { $0 = true }
            Task { @MainActor in self.cancel() }
        }
    }

    public func isDeviceConnectable(_ id: UUID) -> Bool { true }
    public func registeredDeviceInfos() -> [(id: UUID, name: String)] { [] }
    public func removeDevice(_ id: UUID) async throws {}
    public func renameDevice(_ id: UUID) async throws {}
    public func clearStaleRegistrations() async {}

    /// Called by the scan picker when the user selects a device.
    public func select(_ id: UUID) {
        resolveDiscovery(with: .success(id))
    }

    /// Called when the user cancels or dismisses the scan picker.
    /// Surfaces as `DevicePairingError.cancelled` so call sites reuse one cancellation path
    /// across both platforms.
    public func cancel() {
        resolveDiscovery(with: .failure(DevicePairingError.cancelled))
    }

    private func resolveDiscovery(with result: Result<UUID, Error>) {
        isPresenting = false
        guard let continuation = discoveryContinuation else { return }
        discoveryContinuation = nil
        // A selection that lands after cancellation has been signalled must not win the race:
        // surface the cancellation instead of the stale selection.
        if case .success = result, cancellationRequested.withLock({ $0 }) {
            continuation.resume(throwing: DevicePairingError.cancelled)
            return
        }
        continuation.resume(with: result)
    }
}
