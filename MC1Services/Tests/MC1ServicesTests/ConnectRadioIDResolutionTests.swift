import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import SwiftData
import Testing

/// Reconnecting the same radio over a fresh CoreBluetooth handle must not
/// re-partition its data. `buildServicesAndSaveDevice` resolves the radioID by
/// missing on `fetchDevice(id:)` (the volatile BLE UUID changed) and falling
/// back to `fetchDevice(publicKey:)`, so every per-radio row persisted under the
/// first connection stays reachable. Losing that fallback would mint a fresh
/// radioID and orphan the radio's `PendingSend` rows.
@Suite("Connect-path radioID resolution")
@MainActor
struct ConnectRadioIDResolutionTests {
  private static let radioPublicKey = Data(repeating: 0xC3, count: 32)

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
  /// fails fast with `.notConnected`; the ceremony swallows it and proceeds.
  private func makeOfflineSession() -> MeshCoreSession {
    MeshCoreSession(
      transport: SimulatorMockTransport(),
      configuration: SessionConfiguration(defaultTimeout: 0.5, clientIdentifier: "RadioIDResolutionTest")
    )
  }

  @Test
  func `Reconnect with a changed BLE id but same publicKey resolves the original radioID and keeps its PendingSends reachable`() async throws {
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

    // The second connect hydrates the chat send queue, whose DM drain
    // terminally deletes any envelope missing its Contact row ("contact
    // deleted") or its Message row (sendFailed). A complete fixture — the
    // same Contact + Message + PendingSend triple a real enqueue leaves on
    // disk — makes the offline session's failure transient instead, so the
    // drain parks and the row deterministically survives to the assertion
    // below.
    let contactID = UUID()
    let contact = ContactDTO(
      id: contactID,
      radioID: originalRadioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: "QueuedRecipient",
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
    try await firstConnect.services.dataStore.saveContact(contact)

    // A per-radio PendingSend row enqueued while connected to this radio.
    let pendingMessageID = UUID()
    let message = MessageDTO(from: Message(
      id: pendingMessageID,
      radioID: originalRadioID,
      contactID: contactID,
      text: "queued before reconnect",
      timestamp: 1_700_000_000
    ))
    try await firstConnect.services.dataStore.saveMessage(message)

    let pending = PendingSendDTO(
      id: UUID(),
      radioID: originalRadioID,
      messageID: pendingMessageID,
      kind: .dm,
      contactID: contactID,
      channelIndex: nil,
      isResend: false,
      messageText: "queued before reconnect",
      messageTimestamp: 1_700_000_000,
      localNodeName: "TestNode",
      sequence: 1,
      enqueuedAt: Date(timeIntervalSince1970: 1_700_000_000)
    )
    try await firstConnect.services.dataStore.upsertPendingSend(pending)

    // Reconnect the SAME radio presenting a DIFFERENT CoreBluetooth handle
    // (re-pair re-mints the peripheral UUID) but the SAME publicKey.
    let secondBLEID = UUID()
    #expect(secondBLEID != firstBLEID)
    let secondConnect = try await manager.buildServicesAndSaveDevice(
      deviceID: secondBLEID,
      session: makeOfflineSession(),
      selfInfo: Self.makeSelfInfo(),
      capabilities: Self.testCapabilities
    )

    // The publicKey fallback must recover the original partition key rather
    // than mint a fresh one for the changed BLE id.
    #expect(secondConnect.radioID == originalRadioID)

    // The pre-reconnect PendingSend is still reachable under the resolved radioID.
    // Read back through the store that wrote it: both PersistenceStores share the
    // in-memory ModelContainer, and a first cross-context fetch on the second
    // store can miss the just-committed row, so query the context that owns it.
    let rows = try await firstConnect.services.dataStore.fetchPendingSends(radioID: secondConnect.radioID)
    #expect(rows.contains { $0.messageID == pendingMessageID })
  }
}
