import Foundation
import Testing
@testable import MC1Services

@Suite("BluetoothScanPairingService")
@MainActor
struct BluetoothScanPairingServiceTests {

    @Test("discoverDevice presents the picker and resolves with the selected id")
    func selectResolvesDiscovery() async throws {
        let service = BluetoothScanPairingService()
        let expected = UUID()

        let task = Task { try await service.discoverDevice() }
        try await waitUntil("picker should request presentation") { service.isPresenting }

        service.select(expected)

        let result = try await task.value
        #expect(result == expected)
        #expect(service.isPresenting == false)
    }

    @Test("cancel surfaces DevicePairingError.cancelled and lowers presentation")
    func cancelThrowsCancelled() async throws {
        let service = BluetoothScanPairingService()

        let task = Task { try await service.discoverDevice() }
        try await waitUntil("picker should request presentation") { service.isPresenting }

        service.cancel()

        await #expect(throws: DevicePairingError.self) {
            _ = try await task.value
        }
        #expect(service.isPresenting == false)
    }

    @Test("a new discovery resolves a stranded prior discovery")
    func newDiscoveryResolvesPrior() async throws {
        let service = BluetoothScanPairingService()

        let first = Task { try await service.discoverDevice() }
        try await waitUntil("first discovery should present") { service.isPresenting }

        // Starting a second discovery must resolve the first's stranded continuation before
        // installing its own. Awaiting `first` is the real synchronization point: it can only
        // throw once the second call has run `resolveDiscovery` on the prior continuation.
        let second = Task { try await service.discoverDevice() }

        await #expect(throws: DevicePairingError.self) {
            _ = try await first.value
        }

        // The second continuation is now installed; selecting resolves it with the chosen id.
        let expected = UUID()
        service.select(expected)
        #expect(try await second.value == expected)
    }

    @Test("cancelling the discovery task resolves it and lowers presentation")
    func taskCancellationResolvesDiscovery() async throws {
        let service = BluetoothScanPairingService()

        let task = Task { try await service.discoverDevice() }
        try await waitUntil("discovery should present") { service.isPresenting }

        task.cancel()

        // Cancellation must surface via the same shared path as an explicit cancel():
        // onCancel -> cancel() -> resolveDiscovery(.failure(cancelled)), not a bare
        // CancellationError, so call sites keep one cancellation path on both platforms.
        await #expect(throws: DevicePairingError.self) {
            _ = try await task.value
        }
        try await waitUntil("presentation should clear after cancellation") { !service.isPresenting }
    }

    @Test("a selection racing a cancelled discovery task surfaces cancelled, not the selection")
    func selectionRacingCancellationSurfacesCancelled() async throws {
        let service = BluetoothScanPairingService()

        let task = Task { try await service.discoverDevice() }
        try await waitUntil("discovery should present") { service.isPresenting }

        // Cancelling the task runs `onCancel` synchronously, which sets the cancellation flag
        // before its hop to `cancel()` is scheduled. A `select(_:)` landing on the MainActor in
        // that window (here, before the hop runs) must not resolve the continuation with the stale
        // selection — discovery must still surface `.cancelled`.
        task.cancel()
        service.select(UUID())

        await #expect(throws: DevicePairingError.self) {
            _ = try await task.value
        }
        try await waitUntil("presentation should clear after cancellation") { !service.isPresenting }
    }

    @Test("system-registry operations are inert on the macOS path")
    func registryOperationsAreNoOps() async throws {
        let service = BluetoothScanPairingService()

        #expect(service.isSessionActive == false)
        #expect(service.hasSystemPairingRegistry == false)
        #expect(service.registeredDeviceCount == 0)
        #expect(service.supportsSystemRename == false)
        #expect(service.isDeviceConnectable(UUID()) == true)
        #expect(service.registeredDeviceInfos().isEmpty)

        // None of these should throw or have observable effect.
        try await service.activate()
        try await service.removeDevice(UUID())
        try await service.renameDevice(UUID())
        await service.clearStaleRegistrations()
    }
}
