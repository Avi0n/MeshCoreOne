import Foundation
@testable import MC1Services
import Testing

@Suite("Device field writer contracts")
@MainActor
struct DeviceWriterTests {
  @Test(arguments: [false, true])
  func `ocv edit cannot restore removed identity or undo canonicalization`(remove: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let current = DeviceDTO.testDevice(publicKey: env.ble.publicKey)
    let canonical = DeviceDTO.testDevice(publicKey: Data(repeating: 0xB9, count: 32), isActive: false).copy {
      $0.connectionMethods = []
    }
    let gate = Gate()
    let sink = CallbackSink()
    let store = HeldOCVStore(store: env.manager.persistenceStore, gate: gate)
    let service = DeviceService(dataStore: store)
    var writing: Task<Void, Error>?
    do {
      try await env.manager.persistenceStore.saveDevice(current)
      try await env.manager.persistenceStore.saveDevice(canonical)
      await service.setDeviceUpdateCallback { await sink.record($0) }
      let write = Task {
        try await service.updateOCVSettings(deviceID: current.id, preset: "custom", customArray: "4100,3900")
      }
      writing = write
      try await waitUntil { gate.entered }
      if remove {
        try await env.manager.deleteDevice(id: current.id)
      } else {
        #expect(try await env.manager.persistenceStore.reconcileGhostIdentity(
          currentDeviceID: current.id, newPublicKey: canonical.publicKey
        ) == canonical.radioID)
      }
      gate.release()
      let result = await write.result
      if remove {
        #expect(try await env.manager.persistenceStore.fetchDevice(id: current.id) == nil)
        let ghost = try #require(try await env.manager.persistenceStore.fetchDevice(publicKey: current.publicKey))
        #expect(ghost.radioID == current.radioID)
        #expect(ghost.connectionMethods.isEmpty)
        #expect(await sink.value == nil, "A removed row cannot authorize a captured DTO callback")
      } else {
        try result.get()
        let saved = try #require(try await env.manager.persistenceStore.fetchDevice(id: current.id))
        #expect(saved.radioID == canonical.radioID)
        #expect(saved.publicKey == canonical.publicKey)
        #expect(saved.ocvPreset == "custom")
        #expect(saved.customOCVArrayString == "4100,3900")
        #expect(await sink.value?.radioID == canonical.radioID)
        #expect(await sink.value?.publicKey == canonical.publicKey)
      }
    } catch {
      gate.release()
      _ = await writing?.result
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

  private actor CallbackSink {
    private(set) var value: DeviceDTO?
    func record(_ value: DeviceDTO) {
      self.value = value
    }
  }

  private actor HeldOCVStore: DevicePersisting {
    let store: PersistenceStore
    let gate: Gate

    init(store: PersistenceStore, gate: Gate) {
      self.store = store
      self.gate = gate
    }

    func fetchDevice(id: UUID) async throws -> DeviceDTO? {
      try await store.fetchDevice(id: id)
    }

    func fetchDevice(radioID: UUID) async throws -> DeviceDTO? {
      try await store.fetchDevice(radioID: radioID)
    }

    func updateDeviceLastContactSync(radioID: UUID, timestamp: UInt32) async throws {
      try await store.updateDeviceLastContactSync(radioID: radioID, timestamp: timestamp)
    }

    func saveDevice(_ dto: DeviceDTO) async throws {
      await gate.wait()
      try await store.saveDevice(dto)
    }

    func updateDeviceOCVSettings(id: UUID, preset: String, customArray: String?) async throws -> DeviceDTO? {
      await gate.wait()
      return try await store.updateDeviceOCVSettings(id: id, preset: preset, customArray: customArray)
    }
  }
}
