import Foundation
import Testing
@testable import MC1Services

/// Covers the platform-aware BLE connect retry budget in `connect(to:forceReconnect:)`.
///
/// On macOS "Designed for iPad" there is no system pairing registry, so CoreBluetooth cannot
/// pre-reject a tap on an out-of-range radio — the bound exists to stop a user staring at a
/// full multi-attempt spinner. Background reconnects (`forceReconnect == false`) must keep the
/// full budget so unattended recovery still gets every attempt.
@Suite("ConnectionManager Retry Budget")
@MainActor
struct ConnectionManagerRetryBudgetTests {

    /// Minimal `DevicePairingService` with a configurable `hasSystemPairingRegistry` and, for the
    /// registration-guard case, a configurable `isSessionActive`/`isDeviceConnectable`. The
    /// defaults (`sessionActive: false`, `deviceConnectable: true`) make `connect(to:)` skip its
    /// registration guard and reach the retry loop on either platform. Discovery is never
    /// exercised by these tests.
    @MainActor
    private final class StubPairingService: DevicePairingService {
        var delegate: (any DevicePairingDelegate)?
        let hasSystemPairingRegistry: Bool
        let sessionActive: Bool
        let deviceConnectable: Bool

        init(hasSystemPairingRegistry: Bool, sessionActive: Bool = false, deviceConnectable: Bool = true) {
            self.hasSystemPairingRegistry = hasSystemPairingRegistry
            self.sessionActive = sessionActive
            self.deviceConnectable = deviceConnectable
        }

        var isSessionActive: Bool { sessionActive }
        var registeredDeviceCount: Int { 0 }
        var supportsSystemRename: Bool { false }

        func activate() async throws {}
        func discoverDevice() async throws -> UUID { throw CancellationError() }
        func isDeviceConnectable(_ id: UUID) -> Bool { deviceConnectable }
        func registeredDeviceInfos() -> [(id: UUID, name: String)] { [] }
        func removeDevice(_ id: UUID) async throws {}
        func renameDevice(_ id: UUID) async throws {}
        func clearStaleRegistrations() async {}
    }

    /// `expectsBoundedBudget` is the hand-specified intent for each scenario (true only for a
    /// macOS user-initiated tap), kept independent of the production formula so the test catches
    /// a flipped boolean or a dropped qualifier rather than mirroring whatever the code computes.
    struct RetryBudgetCase: Sendable, CustomTestStringConvertible {
        let hasSystemPairingRegistry: Bool
        let forceReconnect: Bool
        let expectsBoundedBudget: Bool

        var testDescription: String {
            "registry=\(hasSystemPairingRegistry) force=\(forceReconnect) → bounded=\(expectsBoundedBudget)"
        }
    }

    @Test(
        "connect uses the unverified budget only for a macOS user-initiated tap, the full budget otherwise",
        arguments: [
            // macOS user tap on an unreachable radio: bounded.
            RetryBudgetCase(hasSystemPairingRegistry: false, forceReconnect: true, expectsBoundedBudget: true),
            // macOS background reconnect: full budget, so the forceReconnect qualifier can't be dropped.
            RetryBudgetCase(hasSystemPairingRegistry: false, forceReconnect: false, expectsBoundedBudget: false),
            // iOS user tap: the registry validates the device, so the full budget always applies.
            RetryBudgetCase(hasSystemPairingRegistry: true, forceReconnect: true, expectsBoundedBudget: false)
        ]
    )
    func retryBudgetMatchesPlatformAndIntent(_ testCase: RetryBudgetCase) async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let suiteName = "test.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { UserDefaults().removePersistentDomain(forName: suiteName) }

        let stateMachine = MockBLEStateMachine()
        let transport = MockMeshTransport()
        await transport.setConnectError(ConnectionError.connectionFailed("simulated unreachable radio"))

        let manager = ConnectionManager(
            modelContainer: container,
            defaults: defaults,
            stateMachine: stateMachine,
            transport: transport,
            pairing: StubPairingService(hasSystemPairingRegistry: testCase.hasSystemPairingRegistry)
        )

        let deviceID = UUID()
        await #expect(throws: Error.self) {
            try await manager.connect(to: deviceID, forceReconnect: testCase.forceReconnect)
        }

        let expectedAttempts = testCase.expectsBoundedBudget
            ? ConnectionManager.unverifiedConnectAttempts
            : ConnectionManager.defaultConnectAttempts
        let attempts = await transport.connectInvocations.count
        #expect(attempts == expectedAttempts)
    }

    /// When the pairing registry is active and reports the device as not connectable (iOS, where
    /// the saved peripheral is no longer registered), `connect(to:)` must fail fast with
    /// `deviceNotFound` before spending a single transport attempt — the registry, not the retry
    /// budget, is the authority on reachability.
    @Test("connect rejects an unconnectable registered device before any transport attempt")
    func connectRejectsUnconnectableRegisteredDevice() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let suiteName = "test.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { UserDefaults().removePersistentDomain(forName: suiteName) }

        let stateMachine = MockBLEStateMachine()
        let transport = MockMeshTransport()

        let manager = ConnectionManager(
            modelContainer: container,
            defaults: defaults,
            stateMachine: stateMachine,
            transport: transport,
            pairing: StubPairingService(
                hasSystemPairingRegistry: true,
                sessionActive: true,
                deviceConnectable: false
            )
        )

        let deviceID = UUID()
        try await #expect {
            try await manager.connect(to: deviceID)
        } throws: { error in
            guard let e = error as? ConnectionError, case .deviceNotFound = e else { return false }
            return true
        }

        let attempts = await transport.connectInvocations.count
        #expect(attempts == 0)
    }
}
