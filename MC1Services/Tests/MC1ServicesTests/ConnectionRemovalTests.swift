#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Acquired identity removal contracts")
@MainActor
struct ConnectionRemovalTests {
  enum Boundary {
    case unidentified, authenticated, registered, published
  }

  enum ConnectionPath {
    case initialWiFi, initialDual, recoveringWiFi, recoveringDual

    var recovering: Bool {
      self == .recoveringWiFi || self == .recoveringDual
    }

    var dualMethod: Bool {
      self == .initialDual || self == .recoveringDual
    }
  }

  struct RemovalSample: Sendable {
    var boundary: Boundary
    var path: ConnectionPath
  }

  /// Each removal boundary, plus one recovering start and one device that also has Bluetooth.
  nonisolated static let removalSamples: [RemovalSample] = [
    RemovalSample(boundary: .unidentified, path: .initialWiFi),
    RemovalSample(boundary: .authenticated, path: .initialWiFi),
    RemovalSample(boundary: .registered, path: .initialWiFi),
    RemovalSample(boundary: .published, path: .initialWiFi),
    RemovalSample(boundary: .unidentified, path: .recoveringWiFi),
    RemovalSample(boundary: .unidentified, path: .initialDual),
  ]

  @Test(arguments: removalSamples)
  func `removal cannot restore an in flight wi fi device`(_ sample: RemovalSample) async throws {
    let boundary = sample.boundary
    let path = sample.path
    let dualMethod = path.dualMethod
    let recovering = path.recovering
    let env = try ConnectionManagerRadioTestHarness()
    let saved = DeviceDTO.testDevice(publicKey: env.wifi.publicKey, isActive: false).copy {
      $0.connectionMethods = [.wifi(host: "radio.test", port: 5123, displayName: nil)]
      if dualMethod { $0.connectionMethods.append(.bluetooth(peripheralUUID: $0.id, displayName: nil)) }
    }
    let selection = Gate()
    var connecting: Task<ConnectionResult, Error>?
    var deleting: Task<Void, Error>?
    var removalFinished = false
    do {
      try await env.manager.persistenceStore.saveDevice(saved)
      env.pairing.isSessionActive = true
      env.pairing.setPairedAccessories([ASAccessory(bluetoothIdentifier: saved.id, displayName: "Saved")])
      let previousGeneration: UUID?
      if recovering {
        _ = try await env.connect(.wifi)
        previousGeneration = env.manager.currentConnection?.generation
        env.manager.testWiFiReconnectCooldown = 0
      } else {
        previousGeneration = nil
      }
      let command: CommandCode = boundary == .unidentified ? .appStart : .getAutoAddConfig
      if boundary == .registered {
        env.manager.testConnectionSelectionHandler = { _ in await selection.wait() }
      } else if boundary != .published {
        await env.wifi.holdReplies(for: command)
      }
      let task = Task {
        if recovering {
          await env.wifi.simulateRemoteLoss()
          try await waitUntil {
            env.manager.desiredRequest?.target == .disconnected
              || (env.manager.currentConnection?.generation != previousGeneration && env.manager.connectionState == .ready)
          }
          guard let current = env.manager.currentConnection else { return ConnectionResult.superseded }
          return ConnectionResult.operational(current)
        }
        return try await env.connect(.wifi)
      }
      connecting = task
      if boundary == .registered {
        try await waitUntil { selection.entered }
      } else if boundary == .published {
        #expect(try await env.manager.isCurrent(task.value))
      } else {
        let initialCount = recovering ? 1 : 0
        try await waitUntil {
          await env.wifi.sentData.filter { $0.first == command.rawValue }.count > initialCount
        }
      }
      let removal = Task {
        try await env.manager.deleteDevice(id: saved.id)
        removalFinished = true
      }
      deleting = removal
      try await waitUntil {
        removalFinished || env.manager.desiredRequest?.target == .disconnected
      }
      selection.release()
      await env.wifi.releaseAllGates()
      _ = await task.result
      try await removal.value
      await env.manager.metadataCommitTask?.value
      let hasConnection = env.manager.currentConnection != nil
      #expect(!hasConnection)
      #expect(!env.manager.connectionIntent.wantsConnection)
      #expect(try await env.manager.persistenceStore.fetchDevice(id: saved.id) == nil)
      let ghost = try #require(try await env.manager.persistenceStore.fetchDevice(publicKey: saved.publicKey))
      #expect(ghost.radioID == saved.radioID)
      #expect(ghost.connectionMethods.isEmpty)
      #expect(!ghost.isActive)
      env.manager.testConnectionSelectionHandler = nil
      let fresh = try await env.connect(.wifi)
      #expect(env.manager.isCurrent(fresh), "A fresh explicit request is new connection authority")
      #expect(env.manager.connectedDevice?.radioID == saved.radioID)
      #expect(try await env.manager.session?.getTime() != nil)
    } catch {
      selection.release()
      await env.wifi.releaseAllGates()
      _ = await connecting?.result
      _ = await deleting?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `unidentified removal exclusion survives launch fallback`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let saved = DeviceDTO.testDevice(id: env.deviceID, publicKey: env.wifi.publicKey, isActive: false).copy {
      $0.connectionMethods = [.bluetooth(peripheralUUID: $0.id, displayName: nil),
                              .wifi(host: "radio.test", port: 5123, displayName: nil)]
    }
    var launch: Task<Void, Never>?
    do {
      try await env.manager.persistenceStore.saveDevice(saved)
      await env.wifi.setConnectError(BLEError.connectionFailed("Unavailable endpoint"))
      await env.wifi.holdConnect()
      let worker = Task { await env.manager.reconnectLastUsedTransport(device: saved, lastDeviceID: saved.id) }
      launch = worker
      try await waitUntil { await env.wifi.connectIsHeld }
      try await env.manager.deleteDevice(id: saved.id)
      await env.wifi.releaseConnect()
      await worker.value
      await env.manager.retirementTask?.value
      await env.manager.metadataCommitTask?.value
      #expect(await env.ble.connectCount == 0)
      let hasConnection = env.manager.currentConnection != nil
      #expect(!hasConnection)
      #expect(!env.manager.connectionIntent.wantsConnection)
      let ghost = try #require(try await env.manager.persistenceStore.fetchDevice(publicKey: saved.publicKey))
      #expect(ghost.radioID == saved.radioID)
      #expect(ghost.connectionMethods.isEmpty)
      #expect(try await env.manager.isCurrent(env.connect(.bluetooth)))
      let observedTime = try await #require(env.manager.session).getTime()
      #expect(observedTime.timeIntervalSince1970 > 0)
    } catch {
      await env.wifi.releaseAllGates()
      await launch?.value
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `unidentified endpoint may belong to an unrelated radio`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let other = DeviceDTO.testDevice(publicKey: Data(repeating: 0xA8, count: 32), isActive: false).copy {
      $0.connectionMethods = [.wifi(host: "radio.test", port: 5123, displayName: nil)]
    }
    var connecting: Task<ConnectionResult, Error>?
    do {
      try await env.manager.persistenceStore.saveDevice(other)
      await env.wifi.holdReplies(for: .appStart)
      let task = Task { try await env.connect(.wifi) }
      connecting = task
      try await waitUntil { await env.wifi.sentData.contains { $0.first == CommandCode.appStart.rawValue } }
      try await env.manager.deleteDevice(id: other.id)
      await env.wifi.releaseAllGates()
      #expect(try await env.manager.isCurrent(task.value))
      #expect(env.manager.connectedDevice?.publicKey == env.wifi.publicKey)
      #expect(env.manager.connectedDevice?.radioID != other.radioID)
      #expect(try await env.manager.session?.getTime() != nil)
    } catch {
      await env.wifi.releaseAllGates()
      _ = await connecting?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @MainActor
  private final class Gate {
    private(set) var entered = false
    private var continuation: CheckedContinuation<Void, Never>?
    private var released = false

    func wait() async {
      entered = true
      guard !released else { return }
      await withCheckedContinuation { continuation = $0 }
    }

    func release() {
      released = true
      continuation?.resume()
      continuation = nil
    }
  }
}
