import Foundation
@testable import MC1Services

extension PersistenceStore {
  /// Creates an in-memory persistence store pre-populated with a test device.
  static func createTestDataStore(
    radioID: UUID,
    maxChannels: UInt8 = 8,
    lastContactSync: UInt32 = 0
  ) async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let device = DeviceDTO.testDevice(
      id: radioID,
      radioID: radioID,
      firmwareVersion: 8,
      firmwareVersionString: "v1.0.0",
      maxChannels: maxChannels,
      multiAcks: 0,
      lastContactSync: lastContactSync
    )
    try await store.saveDevice(device)
    return store
  }
}
