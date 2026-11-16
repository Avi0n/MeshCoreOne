import Foundation
@testable import MC1Services
import Testing

@MainActor
final class NativeConnectionPortHarness {
  let native = BLETransportHarness()
  let machine: BLEStateMachine
  let adapter: iOSBLETransport
  let manager: ConnectionManager
  private let suite = "test.native-connection-port.\(UUID())"
  private let previousLogs = DebugLogBuffer.shared
  private let bootstrap: DebugLogBuffer
  private var buffers: [DebugLogBuffer]

  init() async throws {
    let defaults = try #require(UserDefaults(suiteName: suite))
    machine = BLEStateMachine(centralManager: native.central)
    adapter = iOSBLETransport(stateMachine: machine)
    manager = try ConnectionManager(
      modelContainer: PersistenceStore.createContainer(inMemory: true), defaults: defaults,
      stateMachine: machine, transport: adapter
    )
    bootstrap = DebugLogBuffer(dataStore: manager.persistenceStore)
    buffers = [bootstrap]
    DebugLogBuffer.shared = bootstrap
    let bridge = await machine.delegateHandler
    native.enableAutomaticCallbacks(bridge, onWrite: BLETransportHarness.emptyRadioFrames)
    await manager.wireTransportHandlers()
  }

  func remember(_ link: ConnectionLink) {
    buffers.append(link.services.debugLogBuffer)
  }

  func close() async {
    manager.testConnectionSelectionHandler = nil
    manager.onConnectionReady = nil
    _ = await manager.disconnect(reason: .userInitiated)
    await manager.retirementTask?.value
    await manager.metadataCommitTask?.value
    await machine.shutdown()
    for buffer in buffers {
      await buffer.shutdown()
    }
    if let current = DebugLogBuffer.shared, buffers.contains(where: { $0 === current }) {
      DebugLogBuffer.shared = previousLogs
    }
    UserDefaults().removePersistentDomain(forName: suite)
  }
}
