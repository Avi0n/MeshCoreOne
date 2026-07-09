import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import SwiftData
import Testing

/// A radio that loses its bond mid-session is recovered through the guided
/// "remove and retry" flow, which calls `removeFailedPairing`. That path must
/// preserve the radio's data: demoting the `Device` row to a ghost keeps the
/// publicKey ↔ radioID bridge alive, so re-pairing the same physical radio
/// resolves the original radioID and reattaches its contacts, messages, and
/// channels. A hard delete would drop the bridge and orphan every child row.
@Suite("Bond-loss pairing recovery preserves radio data")
@MainActor
struct BondLossPairingRecoveryTests {
  private static let radioPublicKey = Data(repeating: 0x7B, count: 32)

  private static let testCapabilities = DeviceCapabilities(
    firmwareVersion: 9,
    maxContacts: 100,
    maxChannels: 8,
    blePin: 0,
    firmwareBuild: "01 Jan 2025",
    model: "T-Deck",
    version: "v1.13.0"
  )

  private static func makeSelfInfo(publicKey: Data = radioPublicKey) -> SelfInfo {
    SelfInfo(
      advertisementType: 0,
      txPower: 20,
      maxTxPower: 20,
      publicKey: publicKey,
      latitude: 0,
      longitude: 0,
      multiAcks: 2,
      advertisementLocationPolicy: 0,
      telemetryModeEnvironment: 0,
      telemetryModeLocation: 0,
      telemetryModeBase: 2,
      manualAddContacts: false,
      radioFrequency: 915.0,
      radioBandwidth: 250.0,
      radioSpreadingFactor: 10,
      radioCodingRate: 5,
      name: "TestNode"
    )
  }

  /// A never-connected session so the up-front `getAutoAddConfig` roundtrip
  /// fails fast; the connect ceremony swallows it and proceeds.
  private func makeOfflineSession() -> MeshCoreSession {
    MeshCoreSession(
      transport: SimulatorMockTransport(),
      configuration: SessionConfiguration(defaultTimeout: 0.5, clientIdentifier: "BondLossRecoveryTest")
    )
  }

  @Test
  func `removeFailedPairing keeps the radio's data reachable when the same radio re-pairs`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    // First connect: fresh pairing mints a radioID for this publicKey.
    let firstBLEID = UUID()
    let firstConnect = try await manager.buildServicesAndSaveDevice(
      deviceID: firstBLEID,
      session: makeOfflineSession(),
      selfInfo: Self.makeSelfInfo(),
      capabilities: Self.testCapabilities
    )
    let originalRadioID = firstConnect.radioID
    let store = firstConnect.services.dataStore

    // Seed the per-radio children a synced radio accumulates.
    let contactID = UUID()
    let contact = ContactDTO(
      id: contactID,
      radioID: originalRadioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: "FieldContact",
      typeRawValue: 0,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
    try await store.saveContact(contact)

    let messageID = UUID()
    let message = MessageDTO(from: Message(
      id: messageID,
      radioID: originalRadioID,
      contactID: contactID,
      text: "message before bond loss",
      timestamp: 1_700_000_000
    ))
    try await store.saveMessage(message)

    let channel = ChannelDTO(
      id: UUID(),
      radioID: originalRadioID,
      index: 3,
      name: "FieldChannel",
      secret: Data(repeating: 1, count: 16),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0,
      floodScope: .inherit
    )
    try await store.saveChannel(channel)

    // Guided recovery from a dead bond: the destructive "Remove & Retry" button.
    await manager.removeFailedPairing(deviceID: firstBLEID)

    // The children survive the demotion; no cascade delete.
    #expect(try await store.fetchContacts(radioID: originalRadioID).contains { $0.id == contactID })
    #expect(try await store.fetchMessages(contactID: contactID).contains { $0.id == messageID })
    #expect(try await store.fetchChannels(radioID: originalRadioID).contains { $0.name == "FieldChannel" })

    // Re-pair the same radio over a fresh CoreBluetooth handle. The publicKey
    // fallback must recover the original partition key from the ghost row.
    let secondBLEID = UUID()
    #expect(secondBLEID != firstBLEID)
    let secondConnect = try await manager.buildServicesAndSaveDevice(
      deviceID: secondBLEID,
      session: makeOfflineSession(),
      selfInfo: Self.makeSelfInfo(),
      capabilities: Self.testCapabilities
    )

    #expect(secondConnect.radioID == originalRadioID)

    // Every child reattaches to the resolved radioID rather than being orphaned.
    #expect(try await store.fetchContacts(radioID: secondConnect.radioID).contains { $0.id == contactID })
    #expect(try await store.fetchMessages(contactID: contactID).contains { $0.id == messageID })
    #expect(try await store.fetchChannels(radioID: secondConnect.radioID).contains { $0.name == "FieldChannel" })
  }
}
