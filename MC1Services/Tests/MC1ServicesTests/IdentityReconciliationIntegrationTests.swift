import Foundation
@testable import MC1Services
import MeshCore
import SwiftData
import Testing

@Suite("Identity reconciliation after config import")
struct IdentityReconciliationIntegrationTests {
  private func createStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private func randomKey() -> Data {
    Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })
  }

  @Test
  func `Remove + erase/flash + re-pair + import config re-links orphaned children`() async throws {
    let store = try await createStore()

    // 1. User pairs a radio. MC1 saves a Device row + child Contact.
    let originalDeviceID = UUID()
    let originalRadioID = UUID()
    let originalPublicKey = randomKey()
    let original = DeviceDTO.testDevice(
      id: originalDeviceID,
      publicKey: originalPublicKey,
      firmwareVersion: 8,
      firmwareVersionString: "v1.11.0",
      multiAcks: 0,
      isActive: true
    ).copy { $0.radioID = originalRadioID }
    try await store.saveDevice(original)

    let contactKey = randomKey()
    let frame = ContactFrame(
      publicKey: contactKey,
      type: .chat,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: "Bob",
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )
    try await store.saveContact(radioID: originalRadioID, from: frame)

    // 2. User removes node from MC1 (keep data) — Device row demoted to ghost.
    try await store.demoteDeviceToGhost(id: originalDeviceID)

    // 3. User erases & flashes the radio. Radio firmware regenerates its keypair.
    //    User re-pairs in MC1 — new BLE peripheral UUID, new publicKey.
    let newDeviceID = UUID()
    let newRadioID = UUID()
    let newPublicKey = randomKey()
    let postPair = DeviceDTO.testDevice(
      id: newDeviceID,
      publicKey: newPublicKey,
      firmwareVersion: 8,
      firmwareVersionString: "v1.11.0",
      multiAcks: 0,
      isActive: true
    ).copy { $0.radioID = newRadioID }
    try await store.saveDevice(postPair)

    // Sanity: contact is orphaned under originalRadioID, new device sees nothing.
    let preReconcileContacts = try await store.fetchContacts(radioID: newRadioID)
    #expect(preReconcileContacts.isEmpty)
    let orphanedContacts = try await store.fetchContacts(radioID: originalRadioID)
    #expect(orphanedContacts.count == 1)

    // 4. User imports the radio's config file. importIdentity restores the original
    //    privateKey. The radio's selfInfo now reports originalPublicKey again.
    let reconciled = try await store.reconcileGhostIdentity(
      currentDeviceID: newDeviceID,
      newPublicKey: originalPublicKey
    )
    #expect(reconciled == originalRadioID)

    // 5. Verify: connected Device carries original radioID, ghost is gone,
    //    orphaned contact is reachable from the reconciled radioID.
    let updated = try await store.fetchDevice(id: newDeviceID)
    #expect(updated?.radioID == originalRadioID)
    #expect(updated?.publicKey == originalPublicKey)

    let ghostByOriginalID = try await store.fetchDevice(id: originalDeviceID)
    #expect(ghostByOriginalID == nil, "Ghost row should be deleted")

    let linkedContacts = try await store.fetchContacts(radioID: originalRadioID)
    #expect(linkedContacts.count == 1)
    #expect(linkedContacts.first?.name == "Bob")
  }

  @Test
  func `No reconciliation when radio's publicKey didn't change after re-pair`() async throws {
    let store = try await createStore()

    let publicKey = randomKey()
    let radioID = UUID()
    let device = DeviceDTO.testDevice(
      id: UUID(),
      publicKey: publicKey,
      firmwareVersion: 8,
      firmwareVersionString: "v1.11.0",
      multiAcks: 0,
      isActive: true
    ).copy { $0.radioID = radioID }
    try await store.saveDevice(device)

    let result = try await store.reconcileGhostIdentity(
      currentDeviceID: device.id,
      newPublicKey: publicKey
    )
    #expect(result == nil)

    let unchanged = try await store.fetchDevice(id: device.id)
    #expect(unchanged?.radioID == radioID)
    #expect(unchanged?.publicKey == publicKey)
  }

  /// Documents the trade-off: rows written under the temporary post-pair radioID
  /// are NOT migrated to the reconciled radioID. They become orphaned.
  @Test
  func `Reconciliation does not migrate post-pair child rows to the reconciled radioID`() async throws {
    let store = try await createStore()

    // Original pair → demote to ghost
    let oldDeviceID = UUID()
    let oldRadioID = UUID()
    let oldPublicKey = randomKey()
    let original = DeviceDTO.testDevice(
      id: oldDeviceID,
      publicKey: oldPublicKey,
      firmwareVersion: 8,
      firmwareVersionString: "v1.11.0",
      multiAcks: 0,
      isActive: true
    ).copy { $0.radioID = oldRadioID }
    try await store.saveDevice(original)
    try await store.demoteDeviceToGhost(id: oldDeviceID)

    // Re-pair (different BLE id, different pubkey, different radioID)
    let newDeviceID = UUID()
    let newRadioID = UUID()
    let newPublicKey = randomKey()
    let postPair = DeviceDTO.testDevice(
      id: newDeviceID,
      publicKey: newPublicKey,
      firmwareVersion: 8,
      firmwareVersionString: "v1.11.0",
      multiAcks: 0,
      isActive: true
    ).copy { $0.radioID = newRadioID }
    try await store.saveDevice(postPair)

    // Sync writes a contact under the temporary newRadioID
    let postPairFrame = ContactFrame(
      publicKey: randomKey(),
      type: .chat,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: "PostPair",
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )
    try await store.saveContact(radioID: newRadioID, from: postPairFrame)

    // Reconcile (config import restores the keypair)
    let reconciled = try await store.reconcileGhostIdentity(
      currentDeviceID: newDeviceID,
      newPublicKey: oldPublicKey
    )
    #expect(reconciled == oldRadioID)

    // Post-pair contact stays under newRadioID (documented orphaning)
    let underNew = try await store.fetchContacts(radioID: newRadioID)
    #expect(underNew.count == 1, "Post-pair contact stays under newRadioID")
    #expect(underNew.first?.name == "PostPair")

    let underOld = try await store.fetchContacts(radioID: oldRadioID)
    #expect(underOld.isEmpty, "Old radioID had no orphans pre-populated")

    let updated = try await store.fetchDevice(id: newDeviceID)
    #expect(updated?.radioID == oldRadioID)
    #expect(updated?.publicKey == oldPublicKey)
  }
}
