import Foundation
@testable import MC1Services
import MeshCoreTestSupport
import Testing

@Suite("ConnectionManager Promotion Tests")
@MainActor
struct ConnectionManagerPromotionTests {
  private func usableSyncResult(
    channels: SyncPhaseStatus = .clean,
    messages: SyncPhaseStatus = .clean
  ) -> FullSyncResult {
    FullSyncResult(contacts: .clean, channels: channels, messages: messages)
  }

  private func unusableSyncResult() -> FullSyncResult {
    FullSyncResult(contacts: .failed("sync failed"), channels: .skipped, messages: .skipped)
  }

  private func makeTestServices() async throws -> ServiceContainer {
    let transport = SimulatorMockTransport()
    let session = MeshCoreSession(transport: transport)
    return try await ServiceContainer.forTesting(session: session)
  }

  /// Sets up manager state for promotion tests. Sets session and connectedDevice
  /// so the .ready invariant can pass when promotion succeeds.
  private func setupForPromotion(
    manager: ConnectionManager,
    services: ServiceContainer,
    connectionState: DeviceConnectionState = .connected,
    connectionIntent: ConnectionIntent = .wantsConnection(),
    firmwareVersion: UInt8 = 9
  ) {
    let mockTransport = SimulatorMockTransport()
    let session = MeshCoreSession(transport: mockTransport)
    manager.setTestState(
      connectionState: connectionState,
      services: services,
      session: session,
      connectedDevice: DeviceDTO.testDevice(firmwareVersion: firmwareVersion),
      connectionIntent: connectionIntent
    )
  }

  // MARK: - Suppression: services replaced

  @Test
  func `promoteToReady suppressed when services replaced during sync`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let originalServices = try await makeTestServices()
    let replacementServices = try await makeTestServices()

    setupForPromotion(manager: manager, services: originalServices)

    // Simulate: disconnect + new connection replaced services
    manager.setTestState(services: replacementServices)

    let promoted = await manager.promoteToReady(
      syncResult: usableSyncResult(),
      expectedServices: originalServices,
      transportType: .bluetooth
    )

    #expect(!promoted)
    #expect(manager.connectionState == .connected)
  }

  // MARK: - Suppression: services nil (disconnected)

  @Test
  func `promoteToReady suppressed when services nil (disconnected)`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let originalServices = try await makeTestServices()

    manager.setTestState(
      connectionState: .disconnected,
      services: .some(nil),
      connectionIntent: .wantsConnection()
    )

    let promoted = await manager.promoteToReady(
      syncResult: usableSyncResult(),
      expectedServices: originalServices,
      transportType: .bluetooth
    )

    #expect(!promoted)
    #expect(manager.connectionState == .disconnected)
  }

  // MARK: - Sync failure sets .syncing (resync loop continues from there)

  @Test
  func `promoteToReady sets .syncing when sync failed`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let services = try await makeTestServices()

    setupForPromotion(manager: manager, services: services)

    let promoted = await manager.promoteToReady(
      syncResult: unusableSyncResult(),
      expectedServices: services,
      transportType: .bluetooth
    )

    #expect(promoted)
    #expect(manager.connectionState == .syncing)
  }

  // MARK: - Sync failure skips onDeviceSynced

  @Test
  func `promoteToReady skips onDeviceSynced when sync failed`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let services = try await makeTestServices()
    var onDeviceSyncedCalled = false
    var becameUsable = false

    setupForPromotion(manager: manager, services: services)
    manager.onConnectionBecameUsable = { becameUsable = true }
    manager.onDeviceSynced = { _ in onDeviceSyncedCalled = true }

    let promoted = await manager.promoteToReady(
      syncResult: unusableSyncResult(),
      expectedServices: services,
      transportType: .bluetooth
    )

    #expect(promoted, "Should still promote to .syncing for resync loop")
    #expect(!onDeviceSyncedCalled, "onDeviceSynced should be skipped on sync failure")
    #expect(!becameUsable)
  }

  // MARK: - Sync success sets .ready and fires onDeviceSynced

  @Test
  func `promoteToReady sets .ready when sync succeeded`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let services = try await makeTestServices()

    setupForPromotion(manager: manager, services: services)

    let promoted = await manager.promoteToReady(
      syncResult: usableSyncResult(),
      expectedServices: services,
      transportType: .bluetooth
    )

    #expect(promoted)
    #expect(manager.connectionState == .ready)
  }

  @Test
  func `promoteToReady calls onDeviceSynced when sync succeeded`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let services = try await makeTestServices()
    var onDeviceSyncedCalled = false

    setupForPromotion(manager: manager, services: services)
    manager.onDeviceSynced = { _ in onDeviceSyncedCalled = true }

    await manager.promoteToReady(
      syncResult: usableSyncResult(),
      expectedServices: services,
      transportType: .bluetooth
    )

    #expect(onDeviceSyncedCalled)
  }

  @Test
  func `promoteToReady does NOT call onDeviceSynced when sync failed`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let services = try await makeTestServices()
    var onDeviceSyncedCalled = false

    setupForPromotion(manager: manager, services: services)
    manager.onDeviceSynced = { _ in onDeviceSyncedCalled = true }

    await manager.promoteToReady(
      syncResult: unusableSyncResult(),
      expectedServices: services,
      transportType: .bluetooth
    )

    #expect(!onDeviceSyncedCalled)
  }

  // MARK: - Happy path

  @Test
  func `promoteToReady sets .ready and fires onDeviceSynced on successful sync`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let services = try await makeTestServices()
    var onDeviceSyncedCalled = false
    var becameUsableCount = 0

    setupForPromotion(manager: manager, services: services)
    manager.onConnectionBecameUsable = { [weak manager] in
      #expect(manager?.connectionState == .ready)
      #expect(!onDeviceSyncedCalled)
      becameUsableCount += 1
    }
    manager.onDeviceSynced = { hasCleanRecipientData in
      #expect(hasCleanRecipientData)
      #expect(becameUsableCount == 1)
      onDeviceSyncedCalled = true
    }

    let promoted = await manager.promoteToReady(
      syncResult: usableSyncResult(),
      expectedServices: services,
      transportType: .bluetooth
    )

    #expect(promoted)
    #expect(manager.connectionState == .ready)
    #expect(manager.currentTransportType == .bluetooth)
    #expect(onDeviceSyncedCalled, "onDeviceSynced should fire on successful sync")
    #expect(becameUsableCount == 1)
    manager.setTestState(connectionState: .ready)
    #expect(becameUsableCount == 1)
  }

  // MARK: - Suppression: user disconnected

  @Test
  func `promoteToReady suppressed when user disconnected during sync`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let services = try await makeTestServices()

    setupForPromotion(manager: manager, services: services, connectionIntent: .userDisconnected)

    let promoted = await manager.promoteToReady(
      syncResult: usableSyncResult(),
      expectedServices: services,
      transportType: .bluetooth
    )

    #expect(!promoted)
    #expect(manager.connectionState == .connected)
  }

  // MARK: - Additional guard suppresses promotion

  @Test
  func `promoteToReady suppressed when additionalGuard returns false`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let services = try await makeTestServices()

    setupForPromotion(manager: manager, services: services)

    let promoted = await manager.promoteToReady(
      syncResult: usableSyncResult(),
      expectedServices: services,
      transportType: .bluetooth,
      additionalGuard: { false }
    )

    #expect(!promoted)
    #expect(manager.connectionState == .connected)
  }

  @Test
  func `promoteToReady completes for a radio CLI device when timezone sync fails`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let services = try await makeTestServices()
    setupForPromotion(manager: manager, services: services, firmwareVersion: 14)

    let promoted = await manager.promoteToReady(
      syncResult: usableSyncResult(),
      expectedServices: services,
      transportType: .bluetooth
    )

    #expect(promoted)
    #expect(manager.connectionState == .ready)
  }

  @Test
  func `disconnect for power off leaves user disconnected`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let services = try await makeTestServices()
    setupForPromotion(
      manager: manager,
      services: services,
      connectionState: .ready,
      connectionIntent: .wantsConnection()
    )

    await manager.disconnect(reason: .powerOff)

    #expect(manager.connectionIntent.isUserDisconnected)
    #expect(manager.connectionState == .disconnected)
  }

  @Test
  func `promoteToReady passes false when channels are partial`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let services = try await makeTestServices()
    var eligibility: Bool?
    setupForPromotion(manager: manager, services: services)
    manager.onDeviceSynced = { eligibility = $0 }

    let promoted = await manager.promoteToReady(
      syncResult: usableSyncResult(channels: .partial),
      expectedServices: services,
      transportType: .bluetooth
    )

    #expect(promoted)
    #expect(manager.connectionState == .ready)
    #expect(eligibility == false)
  }

  @Test
  func `promoteToReady passes true when channels are clean and messages failed`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let services = try await makeTestServices()
    var eligibility: Bool?
    setupForPromotion(manager: manager, services: services)
    manager.onDeviceSynced = { eligibility = $0 }

    let promoted = await manager.promoteToReady(
      syncResult: usableSyncResult(messages: .failed("catch-up")),
      expectedServices: services,
      transportType: .bluetooth
    )

    #expect(promoted)
    #expect(manager.connectionState == .ready)
    #expect(eligibility == true)
  }

  @Test
  func `promoteToReady skips onDeviceSynced for a nil sync result`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let services = try await makeTestServices()
    var onDeviceSyncedCalled = false
    setupForPromotion(manager: manager, services: services)
    manager.onDeviceSynced = { _ in onDeviceSyncedCalled = true }

    let promoted = await manager.promoteToReady(
      syncResult: nil,
      expectedServices: services,
      transportType: .bluetooth
    )

    #expect(promoted)
    #expect(manager.connectionState == .syncing)
    #expect(!onDeviceSyncedCalled)
  }

  // MARK: - Post-time-sync re-validation (not unit-testable)

  // promoteToReady re-checks connectionIntent, services identity, and additionalGuard
  // after syncDeviceTimeIfNeeded() returns. These guards are structurally identical to
  // the pre-await guards tested above. Exercising them requires mutating state during
  // the syncDeviceTimeIfNeeded() suspension point, which needs concurrency interleaving
  // that unit tests cannot reliably control. Covering this path requires integration
  // tests with a real (or delay-injected) session where state can change mid-await.
}
