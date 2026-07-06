import Foundation
@testable import MC1Services
import Testing

@Suite("PairingCancellation")
@MainActor
struct PairingCancellationTests {
  /// When pairNewDevice is cancelled after ASK adds the device but before
  /// connect(to:) finishes, cleanupPartialPairing must remove the bond from
  /// ASK so iOS doesn't retain a paired accessory with no app-level state.
  @Test
  func `cancellation in connect(to:) phase removes accessory from ASK`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let deviceID = UUID()
    mockASK.setPickerResult(.success(deviceID))
    mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: deviceID, displayName: "test")])

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    // Pin the wait so we can deterministically cancel after the pair task
    // has entered waitForOtherAppReconnection — i.e., past showPicker.
    let waitEntered = AsyncStream<Void>.makeStream()
    let releaseWait = AsyncStream<Void>.makeStream()
    manager.otherAppWaitStrategyOverride = { _ in
      waitEntered.continuation.yield()
      for await _ in releaseWait.stream {
        break
      }
      return false
    }

    let pairTask = Task { try? await manager.pairNewDevice() }

    for await _ in waitEntered.stream {
      break
    }

    pairTask.cancel()
    releaseWait.continuation.finish()
    _ = await pairTask.value

    #expect(mockASK.removeAccessoryCallCount == 1)
    #expect(mockASK.lastRemovedDeviceID == deviceID)
  }

  /// Regression test for the silent-cancellation hole in connect(to:): a
  /// pairNewDevice task cancelled between picker dismiss and connect(to:)
  /// returning successfully must NOT drive a real BLE connect to completion.
  /// Without the entry-point `Task.checkCancellation()` in connect(to:), the
  /// transport.connect() invocation would still fire (the inner awaits all
  /// swallowed cancellation), leaving the user paired and connected to a
  /// device they tried to abandon.
  @Test
  func `cancellation before connect(to:) bails before transport.connect runs`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let mockTransport = env.transport
    let deviceID = UUID()
    mockASK.setPickerResult(.success(deviceID))
    mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: deviceID, displayName: "test")])

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    let waitEntered = AsyncStream<Void>.makeStream()
    let releaseWait = AsyncStream<Void>.makeStream()
    manager.otherAppWaitStrategyOverride = { _ in
      waitEntered.continuation.yield()
      for await _ in releaseWait.stream {
        break
      }
      return false
    }

    let pairTask = Task { try? await manager.pairNewDevice() }

    for await _ in waitEntered.stream {
      break
    }

    pairTask.cancel()
    releaseWait.continuation.finish()
    _ = await pairTask.value

    let invocations = await mockTransport.connectInvocations
    #expect(invocations.isEmpty, "Cancelled connect(to:) must not establish a BLE link")
    #expect(mockASK.removeAccessoryCallCount == 1, "ASK bond must be cleaned up after cancellation")
  }
}
