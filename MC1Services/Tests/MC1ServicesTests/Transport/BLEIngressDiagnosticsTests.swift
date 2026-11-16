@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("BLE native ingress diagnostic correlation")
@MainActor
struct BLEIngressDiagnosticsTests {
  @Test
  func `stream totals retain their binding across native renewal`() async throws {
    let fixture = try await Fixture.make()
    do {
      let firstBinding = String(describing: ObjectIdentifier(fixture.binding))
      let firstStream = await fixture.view.receivedData
      fixture.native.rx.value = Data([1])
      for _ in 0..<514 {
        fixture.bridge.peripheral(fixture.native.peripheral, didUpdateValueFor: fixture.native.rx, error: nil)
      }
      let consumer = Task { for await _ in firstStream {} }
      consumer.cancel()
      await consumer.value
      fixture.bridge.peripheral(fixture.native.peripheral, didUpdateValueFor: fixture.native.rx, error: nil)

      let replacement = SessionTransportBinding()
      let nextBinding = String(describing: ObjectIdentifier(replacement))
      let nextView = await fixture.adapter.makeSessionTransport(deviceID: fixture.native.deviceID, binding: replacement)
      await fixture.adapter.refreshDataStream(sessionBinding: replacement)
      let nextStream = await nextView.receivedData
      let stale = BLETransportHarness(deviceID: fixture.native.deviceID)
      stale.rx.value = Data([9])
      fixture.bridge.peripheral(stale.peripheral, didUpdateValueFor: stale.rx, error: nil)
      fixture.native.rx.value = Data([2])
      fixture.bridge.peripheral(fixture.native.peripheral, didUpdateValueFor: fixture.native.rx, error: nil)
      await fixture.machine.shutdown()
      var received: [Data] = []
      for await frame in nextStream {
        received.append(frame)
      }
      #expect(received == [Data([2])])
      #expect(fixture.native.connectCount == 1)
      let messages = try await fixture.records { records in
        records.contains { $0.contains("Ingress finished") && $0.contains("binding=\(nextBinding)") }
      }
      let first = messages.first { $0.contains("Ingress finished") && $0.contains("binding=\(firstBinding)") }
      let next = messages.first { $0.contains("Ingress finished") && $0.contains("binding=\(nextBinding)") }
      #expect(first?.contains("stream=1 ") == true)
      #expect(first?.contains("accepted=512 dropped=2 rejected=0 terminated=1") == true)
      #expect(next?.contains("stream=2 ") == true)
      #expect(next?.contains("accepted=1 dropped=0 rejected=1 terminated=0") == true)
      #expect(messages.contains { $0.contains("reason=buffer-full") && $0.contains("dropped=1") })
      #expect(messages.contains { $0.contains("reason=stream-terminated") && $0.contains("terminated=1") })
      #expect(messages.contains { $0.contains("Ingress rejected origin") && $0.contains("rejected=1") })
      for message in messages where message.contains("[BLE] Ingress") {
        for field in ["payload=", "pin=", "password=", "host=", "secret="] {
          #expect(!message.localizedCaseInsensitiveContains(field))
        }
      }
      await fixture.close()
    } catch {
      await fixture.close()
      throw error
    }
  }

  @Test
  func `liveness records distinguish a rejected binding from its successor`() async throws {
    let fixture = try await Fixture.make()
    do {
      let oldBinding = String(describing: ObjectIdentifier(fixture.binding))
      await fixture.machine.setAppSessionLive(deviceID: fixture.native.deviceID, sessionBinding: fixture.binding)
      let replacement = SessionTransportBinding()
      let currentBinding = String(describing: ObjectIdentifier(replacement))
      _ = await fixture.adapter.makeSessionTransport(deviceID: fixture.native.deviceID, binding: replacement)
      await fixture.machine.setAppSessionLive(deviceID: fixture.native.deviceID, sessionBinding: replacement)
      await fixture.machine.setAppSessionLive(deviceID: nil, sessionBinding: fixture.binding)
      #expect(await fixture.machine.isAppSessionLive(deviceID: fixture.native.deviceID))
      replacement.revoke()
      await fixture.machine.setAppSessionLive(deviceID: fixture.native.deviceID, sessionBinding: replacement)
      await fixture.machine.setAppSessionLive(deviceID: nil, sessionBinding: replacement)
      #expect(await fixture.machine.currentAppSessionLiveDeviceID == nil)
      let messages = try await fixture.records { records in
        records.contains { $0.contains("Session revoked") && $0.contains("binding=\(currentBinding)") }
      }
      #expect(messages.contains {
        $0.contains("Session live binding=\(currentBinding)") && $0.contains("device=\(fixture.native.deviceID.uuidString.prefix(8))")
      })
      #expect(messages.contains {
        $0.contains("Session live rejected") && $0.contains("binding=\(oldBinding)")
          && $0.contains("currentBinding=\(currentBinding)") && $0.contains("reason=bindingMismatch")
      })
      #expect(messages.contains {
        $0.contains("Session live rejected") && $0.contains("binding=\(currentBinding)") && $0.contains("reason=revoked")
      })
      #expect(messages.contains { $0.contains("Session revoked binding=\(currentBinding)") })
      await fixture.close()
    } catch {
      await fixture.close()
      throw error
    }
  }

  @MainActor
  private struct Fixture {
    let native: BLETransportHarness
    let machine: BLEStateMachine
    let bridge: BLEDelegateHandler
    let adapter: iOSBLETransport
    let binding: SessionTransportBinding
    let view: SessionBoundTransport
    let store: PersistenceStore
    let buffer: DebugLogBuffer
    let previousBuffer: DebugLogBuffer?
    let capturedPreference: Any?

    static func make(power: CBManagerState = .poweredOn) async throws -> Self {
      let native = BLETransportHarness()
      native.power = power
      let machine = BLEStateMachine(centralManager: native.central)
      let bridge = await machine.delegateHandler
      native.enableAutomaticCallbacks(bridge, onWrite: { _ in [] })
      let adapter = iOSBLETransport(stateMachine: machine)
      let binding = SessionTransportBinding()
      let view = await adapter.makeSessionTransport(deviceID: native.deviceID, binding: binding)
      let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
      let buffer = DebugLogBuffer(dataStore: store)
      let previous = DebugLogBuffer.shared
      let captured = UserDefaults.standard.object(forKey: AppStorageKey.collectDebugLogs.rawValue)
      DebugLogBuffer.enableCollection()
      DebugLogBuffer.shared = buffer
      do {
        try await view.connect()
        return Self(
          native: native, machine: machine, bridge: bridge, adapter: adapter, binding: binding,
          view: view, store: store, buffer: buffer, previousBuffer: previous, capturedPreference: captured
        )
      } catch {
        await machine.shutdown()
        await buffer.shutdown()
        if DebugLogBuffer.shared === buffer { DebugLogBuffer.shared = previous }
        restorePreference(captured)
        throw error
      }
    }

    func records(until isComplete: ([String]) -> Bool) async throws -> [String] {
      let deadline = ContinuousClock.now.advanced(by: .seconds(2))
      var messages: [String] = []
      repeat {
        await buffer.flush()
        messages = try await store.fetchDebugLogEntries(since: .distantPast, limit: 2000).map(\.message)
        if isComplete(messages) { return messages }
        await Task.yield()
      } while ContinuousClock.now < deadline
      return messages
    }

    private static func restorePreference(_ value: Any?) {
      if let value {
        UserDefaults.standard.set(value, forKey: AppStorageKey.collectDebugLogs.rawValue)
      } else {
        UserDefaults.standard.removeObject(forKey: AppStorageKey.collectDebugLogs.rawValue)
      }
    }

    func close() async {
      await machine.shutdown()
      await buffer.shutdown()
      if DebugLogBuffer.shared === buffer { DebugLogBuffer.shared = previousBuffer }
      Self.restorePreference(capturedPreference)
    }
  }
}
