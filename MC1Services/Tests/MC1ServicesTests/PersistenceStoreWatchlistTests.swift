import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Watchlist persistence")
struct PersistenceStoreWatchlistTests {
  @Test
  func `narrow writes update only the requested Watchlist fields`() async throws {
    let radioID = UUID()
    let store = try await store(radioID: radioID)
    let seeded = repeater(radioID: radioID, name: "North", lastHeardTimestamp: 40)
    try await store.saveContact(seeded)

    let marked = try await store.updateWatchlistMembership(
      contactID: seeded.id, radioID: radioID, isMember: true
    )
    expectWatchlist(marked, member: true, firmware: nil)
    #expect(marked.name == "North")
    #expect(marked.lastHeardTimestamp == 40)

    let checked = try await store.updateWatchlistFirmware(
      contactID: seeded.id, radioID: radioID, version: ""
    )
    expectWatchlist(checked, member: true, firmware: "")

    let named = try await store.updateWatchlistFirmware(
      contactID: seeded.id, radioID: radioID, version: "1.18.0"
    )
    expectWatchlist(named, member: true, firmware: "1.18.0")

    let stored = try #require(try await store.fetchContact(id: seeded.id))
    #expect(stored == named)
    #expect(try await store.fetchNodeStatusSnapshots(nodePublicKey: seeded.publicKey, since: nil).isEmpty)
  }

  @Test(arguments: ["membership", "firmware"])
  func `failed Watchlist writes preserve saved fields and buffered RX logs`(patch: String) async throws {
    let radioID = UUID()
    let store = try await store(radioID: radioID)
    let seeded = repeater(
      radioID: radioID, name: "East", isMyNode: true, firmware: "keep"
    )
    try await store.saveContact(seeded)
    let parsed = ParsedRxLogData(
      snr: 10.5, rssi: -65, rawPayload: Data([0x15, 0x01, 0x02, 0x03]),
      routeType: .flood, payloadType: .groupText, payloadVersion: 0,
      payloadTypeBits: 5, transportCode: nil,
      pathLength: 1, pathNodes: [0x42], packetPayload: Data([0xAB, 0xCD, 0xEF])
    )
    let rx = RxLogEntryDTO(
      radioID: radioID, from: parsed, channelIndex: 1,
      decryptStatus: .success, senderTimestamp: 42
    )
    try await store.saveRxLogEntry(rx)
    #expect(await store.rxLogInitiatedSaveCount == 0)

    await store.setWatchlistSaveFaultInjection { throw PersistenceStoreError.saveFailed(patch) }
    await #expect(throws: PersistenceStoreError.self) {
      switch patch {
      case "membership":
        _ = try await store.updateWatchlistMembership(
          contactID: seeded.id, radioID: radioID, isMember: false
        )
      case "firmware":
        _ = try await store.updateWatchlistFirmware(
          contactID: seeded.id, radioID: radioID, version: "lost"
        )
      default:
        Issue.record("Unexpected Watchlist patch \(patch)")
      }
    }
    await store.setWatchlistSaveFaultInjection(nil)

    var stored = try #require(try await store.fetchContact(id: seeded.id))
    expectWatchlist(stored, member: true, firmware: "keep")
    #expect(try await store.fetchRxLogEntries(radioID: radioID).map(\.id) == [rx.id])
    try await store.flushPendingRxLogEntries()
    #expect(try await store.findRxLogEntry(
      radioID: radioID, channelIndex: 1, senderTimestamp: 42
    )?.id == rx.id)
    #expect(try await store.rxLogEntryCountsByDevice[radioID] == (store.countRxLogEntries(radioID: radioID)))

    let renamed = try await store.updateContactLocalPreferences(
      contactID: seeded.id,
      nickname: .set("East Gate"),
      isBlocked: nil,
      isFavorite: nil,
      clearRemoteCLI: false
    )
    #expect(renamed.contact.nickname == "East Gate")
    expectWatchlist(renamed.contact, member: true, firmware: "keep")
    stored = try #require(try await store.fetchContact(id: seeded.id))
    #expect(stored.nickname == "East Gate")
    expectWatchlist(stored, member: true, firmware: "keep")
  }

  @Test
  func `a deleted contact is not recreated by a Watchlist patch`() async throws {
    let radioID = UUID()
    let store = try await store(radioID: radioID)
    let seeded = repeater(radioID: radioID, name: "Gone", isMyNode: true, firmware: "1")
    try await store.saveContact(seeded)
    try await store.deleteContact(id: seeded.id)

    await expectStoreError(.contactNotFound) {
      try await store.updateWatchlistMembership(
        contactID: seeded.id, radioID: radioID, isMember: true
      )
    }
    await expectStoreError(.contactNotFound) {
      try await store.updateWatchlistFirmware(
        contactID: seeded.id, radioID: radioID, version: "1"
      )
    }
    #expect(try await store.fetchContacts(radioID: radioID).isEmpty)
  }

  @Test
  func `patches reject the wrong radio and a non-repeater without writing`() async throws {
    let radioID = UUID()
    let store = try await store(radioID: radioID)
    let seeded = repeater(radioID: radioID, name: "Mine", firmware: "keep")
    try await store.saveContact(seeded)
    let chat = ContactDTO.testContact(radioID: radioID, name: "Chat", myNodeFirmwareVersion: "chat")
    try await store.saveContact(chat)

    await expectStoreError(.contactNotFound) {
      try await store.updateWatchlistMembership(
        contactID: seeded.id, radioID: UUID(), isMember: true
      )
    }
    await expectStoreError(.invalidData) {
      try await store.updateWatchlistFirmware(
        contactID: chat.id, radioID: radioID, version: "nope"
      )
    }

    let repeaterRow = try #require(try await store.fetchContact(id: seeded.id))
    expectWatchlist(repeaterRow, member: false, firmware: "keep")
    let chatRow = try #require(try await store.fetchContact(id: chat.id))
    #expect(chatRow.myNodeFirmwareVersion == "chat")
    #expect(!chatRow.isMyNode)
  }

  @Test
  func `stale contact saves cannot replace a newer or older Watchlist write`() async throws {
    let radioID = UUID()
    let store = try await store(radioID: radioID)
    let seeded = repeater(
      radioID: radioID,
      name: "Original",
      isMyNode: true,
      firmware: "1.0"
    )
    try await store.saveContact(seeded)

    let staleClear = ContactDTO.testContact(
      id: seeded.id,
      radioID: radioID,
      publicKey: seeded.publicKey,
      name: "Stale Clear",
      typeRawValue: ContactType.repeater.rawValue,
      lastHeardTimestamp: 40
    )
    try await store.saveContact(staleClear)
    var stored = try #require(try await store.fetchContact(id: seeded.id))
    #expect(stored.name == "Stale Clear")
    expectWatchlist(stored, member: true, firmware: "1.0")

    let snapshot = stored
    _ = try await store.updateWatchlistMembership(
      contactID: seeded.id, radioID: radioID, isMember: false
    )
    _ = try await store.updateWatchlistFirmware(
      contactID: seeded.id, radioID: radioID, version: ""
    )
    try await store.saveContact(snapshot)
    stored = try #require(try await store.fetchContact(id: seeded.id))
    #expect(stored.name == snapshot.name)
    expectWatchlist(stored, member: false, firmware: "")
  }

  @Test
  func `a Watchlist status replaces the reading in the current window and moves its time`() async throws {
    let store = try await store(radioID: UUID())
    let key = Data(repeating: 0x61, count: 32)
    let first = try await store.recordWatchlistStatus(nodePublicKey: key, status: metrics(millivolts: 3600))
    let second = try await store.recordWatchlistStatus(nodePublicKey: key, status: metrics(millivolts: 3900))

    #expect(second.id == first.id)
    #expect(second.batteryMillivolts == 3900)
    #expect(second.timestamp >= first.timestamp)
    let rows = try await store.fetchNodeStatusSnapshots(nodePublicKey: key, since: nil)
    #expect(rows.map(\.batteryMillivolts) == [3900])
  }

  @Test
  func `other status captures keep the first reading in the window`() async throws {
    let store = try await store(radioID: UUID())
    let key = Data(repeating: 0x62, count: 32)
    _ = try await store.recordWatchlistStatus(nodePublicKey: key, status: metrics(millivolts: 3600))
    _ = try await store.recordNodeStatusSnapshot(
      nodePublicKey: key, status: metrics(millivolts: 3900), telemetry: nil, neighbors: nil, location: nil
    )

    let rows = try await store.fetchNodeStatusSnapshots(nodePublicKey: key, since: nil)
    #expect(rows.map(\.batteryMillivolts) == [3600])
  }

  @Test
  func `a Watchlist status after the window adds a new history row`() async throws {
    let store = try await store(radioID: UUID())
    let key = Data(repeating: 0x63, count: 32)
    let earlier = Date.now.addingTimeInterval(-NodeSnapshotPolicy.minimumInterval - 1)
    _ = try await store.saveNodeStatusSnapshot(
      timestamp: earlier, nodePublicKey: key, batteryMillivolts: 3500, lastSNR: nil, lastRSSI: nil,
      noiseFloor: nil, uptimeSeconds: 1, rxAirtimeSeconds: nil, packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )
    _ = try await store.recordWatchlistStatus(nodePublicKey: key, status: metrics(millivolts: 3700))

    let rows = try await store.fetchNodeStatusSnapshots(nodePublicKey: key, since: nil)
    #expect(rows.map(\.batteryMillivolts) == [3500, 3700])
  }

  @Test
  func `the latest battery skips rows without a battery and other keys`() async throws {
    let store = try await store(radioID: UUID())
    let key = Data(repeating: 0x64, count: 32)
    let other = Data(repeating: 0x65, count: 32)
    let missing = Data(repeating: 0x66, count: 32)
    _ = try await store.saveNodeStatusSnapshot(
      timestamp: Date.now.addingTimeInterval(-NodeSnapshotPolicy.minimumInterval * 2), nodePublicKey: key,
      batteryMillivolts: 3550, lastSNR: nil, lastRSSI: nil, noiseFloor: nil, uptimeSeconds: 1,
      rxAirtimeSeconds: nil, packetsSent: nil, packetsReceived: nil, receiveErrors: nil
    )
    _ = try await store.recordNodeStatusSnapshot(
      nodePublicKey: key,
      status: nil,
      telemetry: [TelemetrySnapshotEntry(channel: 1, type: "voltage", value: 4.1)],
      neighbors: nil,
      location: nil
    )
    _ = try await store.recordWatchlistStatus(nodePublicKey: other, status: metrics(millivolts: 4000))

    let latest = try await store.fetchLatestBatterySnapshots(nodePublicKeys: [key, other, missing])
    #expect(latest[key]?.batteryMillivolts == 3550)
    #expect(latest[other]?.batteryMillivolts == 4000)
    #expect(latest[missing] == nil)
  }

  private func expectStoreError(
    _ expected: PersistenceStoreError,
    _ body: () async throws -> some Any
  ) async {
    do {
      _ = try await body()
      Issue.record("Expected \(expected)")
    } catch let error as PersistenceStoreError {
      switch (error, expected) {
      case (.contactNotFound, .contactNotFound), (.invalidData, .invalidData):
        break
      default:
        Issue.record("Expected \(expected), got \(error)")
      }
    } catch {
      Issue.record("Expected \(expected), got \(error)")
    }
  }

  private func store(radioID: UUID) async throws -> PersistenceStore {
    try await PersistenceStore.createTestDataStore(radioID: radioID)
  }

  private func repeater(
    radioID: UUID = UUID(),
    name: String,
    lastHeardTimestamp: UInt32? = 40,
    isMyNode: Bool = false,
    firmware: String? = nil
  ) -> ContactDTO {
    ContactDTO.testContact(
      radioID: radioID,
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      lastHeardTimestamp: lastHeardTimestamp,
      isMyNode: isMyNode,
      myNodeFirmwareVersion: firmware
    )
  }

  private func expectWatchlist(
    _ contact: ContactDTO,
    member: Bool,
    firmware: String?
  ) {
    #expect(contact.isMyNode == member)
    #expect(contact.myNodeFirmwareVersion == firmware)
  }

  private func metrics(millivolts: UInt16, uptime: UInt32 = 1) -> NodeStatusMetrics {
    NodeStatusMetrics(
      batteryMillivolts: millivolts, lastSNR: nil, lastRSSI: nil, noiseFloor: nil, uptimeSeconds: uptime,
      rxAirtimeSeconds: nil, packetsSent: nil, packetsReceived: nil, receiveErrors: nil, sentDirect: nil,
      sentFlood: nil, receivedDirect: nil, receivedFlood: nil, directDuplicates: nil, floodDuplicates: nil
    )
  }
}
