import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("RxLogService region multi-match reprocess", .serialized)
struct RxLogServiceRegionReprocessTests {
  @Test
  func `replaceScopeKeyCache rewrites sticky first-match to ambiguous multi-match`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)

    try await dataStore.saveDevice(.testDevice(id: radioID).copy { $0.knownRegions = ["First"] })

    let scopeKey = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    let payload = Data([0x10, 0x20, 0x30, 0x40, 0x50])
    let payloadTypeBits: UInt8 = 5
    let code = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey,
      payloadTypeBits: payloadTypeBits,
      payload: payload
    )
    var transportCode = Data(count: 2)
    transportCode[0] = UInt8(code & 0xFF)
    transportCode[1] = UInt8((code >> 8) & 0xFF)
    let senderTimestamp: UInt32 = 1_704_000_000

    let parsed = ParsedRxLogData(
      snr: 5,
      rssi: -80,
      rawPayload: payload,
      routeType: .tcFlood,
      payloadType: .groupText,
      payloadVersion: 0,
      payloadTypeBits: payloadTypeBits,
      transportCode: transportCode,
      pathLength: 0,
      pathNodes: [],
      packetPayload: payload
    )
    let entry = RxLogEntryDTO(
      radioID: radioID,
      from: parsed,
      channelIndex: 0,
      channelName: "Public",
      decryptStatus: .success,
      senderTimestamp: senderTimestamp,
      regionScope: "First",
      regionScopeMatches: ["First"]
    )
    try await dataStore.saveRxLogEntry(entry)

    // Correlated channel message must follow the RxLog sticky → ambiguous rewrite.
    let message = MessageDTO.testChannelMessage(
      radioID: radioID,
      channelIndex: 0,
      timestamp: senderTimestamp,
      direction: .incoming,
      status: .delivered
    )
    try await dataStore.saveMessage(message)

    await service.startEventMonitoring(radioID: radioID)
    defer { Task { await service.stopEventMonitoring() } }

    let stream = service.regionUpdateEvents()
    let collector = IDCollector()
    let task = Task {
      for await ids in stream {
        await collector.record(ids)
        if await collector.count > 0 { break }
      }
    }

    // Two display names sharing one scope key — the multi-match fixture.
    await service.replaceScopeKeyCacheAndReprocess([
      (name: "First", key: scopeKey),
      (name: "Second", key: scopeKey)
    ])

    let updated = try #require(
      try await dataStore.fetchRxLogEntries(radioID: radioID).first { $0.id == entry.id }
    )
    #expect(updated.regionScope == nil, "ambiguous must clear sticky regionScope")
    #expect(Set(updated.regionScopeMatches) == Set(["First", "Second"]))

    try await waitUntil("region update event should fire for multi-match message rewrite") {
      await collector.count > 0
    }
    task.cancel()

    let receivedIDs = await collector.ids
    #expect(receivedIDs.contains(message.id))

    let savedMessages = try await dataStore.fetchMessages(radioID: radioID, channelIndex: 0)
    let savedMessage = try #require(savedMessages.first { $0.id == message.id })
    #expect(savedMessage.regionScope == nil, "ambiguous must clear message regionScope")
    #expect(Set(savedMessage.regionScopeMatches) == Set(["First", "Second"]))
  }

  @Test
  func `empty known regions clears sticky labels`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)

    try await dataStore.saveDevice(.testDevice(id: radioID).copy { $0.knownRegions = ["Germany"] })

    let scopeKey = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    let payload = Data([0xAA, 0xBB, 0xCC])
    let code = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey, payloadTypeBits: 5, payload: payload
    )
    var transportCode = Data(count: 2)
    transportCode[0] = UInt8(code & 0xFF)
    transportCode[1] = UInt8((code >> 8) & 0xFF)

    let parsed = ParsedRxLogData(
      snr: 5,
      rssi: -80,
      rawPayload: payload,
      routeType: .tcFlood,
      payloadType: .groupText,
      payloadVersion: 0,
      payloadTypeBits: 5,
      transportCode: transportCode,
      pathLength: 0,
      pathNodes: [],
      packetPayload: payload
    )
    let entry = RxLogEntryDTO(
      radioID: radioID,
      from: parsed,
      decryptStatus: .success,
      senderTimestamp: 1_704_000_100,
      regionScope: "Germany",
      regionScopeMatches: ["Germany"]
    )
    try await dataStore.saveRxLogEntry(entry)

    await service.startEventMonitoring(radioID: radioID)
    defer { Task { await service.stopEventMonitoring() } }

    // Wait for loadSecrets so knownRegions is ["Germany"] before clearing.
    // Calling updateKnownRegions([]) while still default-empty is a no-op.
    try await waitUntil("known regions loaded from device") {
      await service.knownRegions == ["Germany"]
    }

    await service.updateKnownRegions([])

    let updated = try #require(
      try await dataStore.fetchRxLogEntries(radioID: radioID).first { $0.id == entry.id }
    )
    #expect(updated.regionScope == nil)
    #expect(updated.regionScopeMatches == [])
  }

  @Test
  func `live resolve persists unique name and matches array together`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)

    try await dataStore.saveDevice(.testDevice(id: radioID).copy { $0.knownRegions = ["Germany"] })

    await service.startEventMonitoring(radioID: radioID)
    defer { Task { await service.stopEventMonitoring() } }

    // Wait for loadSecrets so the live path resolves against the device regions.
    try await waitUntil("known regions loaded from device") {
      await service.knownRegions == ["Germany"]
    }

    let scopeKey = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    let payload = Data([0x01, 0x02, 0x03, 0x04])
    let code = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey, payloadTypeBits: 5, payload: payload
    )
    var transportCode = Data(count: 2)
    transportCode[0] = UInt8(code & 0xFF)
    transportCode[1] = UInt8((code >> 8) & 0xFF)

    let parsed = ParsedRxLogData(
      snr: 5,
      rssi: -80,
      rawPayload: payload,
      routeType: .tcFlood,
      payloadType: .groupText,
      payloadVersion: 0,
      payloadTypeBits: 5,
      transportCode: transportCode,
      pathLength: 0,
      pathNodes: [],
      packetPayload: payload
    )
    await service.process(parsed)

    let entries = try await dataStore.fetchRxLogEntries(radioID: radioID)
    let live = try #require(entries.first { $0.transportCode == transportCode })
    #expect(live.regionScope == "Germany")
    #expect(live.regionScopeMatches == ["Germany"])
  }

  @Test
  func `regionUpdateEvents yields message IDs after channel message reprocess`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)

    // Non-matching seed so loadSecrets can finish without resolving the Germany code.
    try await dataStore.saveDevice(.testDevice(id: radioID).copy { $0.knownRegions = ["Placeholder"] })

    let scopeKey = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    let payload = Data([0xDE, 0xAD, 0xBE, 0xEF])
    let code = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey, payloadTypeBits: 5, payload: payload
    )
    var transportCode = Data(count: 2)
    transportCode[0] = UInt8(code & 0xFF)
    transportCode[1] = UInt8((code >> 8) & 0xFF)
    let senderTimestamp: UInt32 = 1_704_111_000

    let parsed = ParsedRxLogData(
      snr: 5,
      rssi: -80,
      rawPayload: payload,
      routeType: .tcFlood,
      payloadType: .groupText,
      payloadVersion: 0,
      payloadTypeBits: 5,
      transportCode: transportCode,
      pathLength: 0,
      pathNodes: [],
      packetPayload: payload
    )
    let entry = RxLogEntryDTO(
      radioID: radioID,
      from: parsed,
      channelIndex: 0,
      channelName: "Public",
      decryptStatus: .success,
      senderTimestamp: senderTimestamp,
      regionScope: nil,
      regionScopeMatches: []
    )
    try await dataStore.saveRxLogEntry(entry)

    let message = MessageDTO.testChannelMessage(
      radioID: radioID,
      channelIndex: 0,
      timestamp: senderTimestamp,
      direction: .incoming,
      status: .delivered
    )
    try await dataStore.saveMessage(message)

    await service.startEventMonitoring(radioID: radioID)
    defer { Task { await service.stopEventMonitoring() } }

    let stream = service.regionUpdateEvents()
    let collector = IDCollector()
    let task = Task {
      for await ids in stream {
        await collector.record(ids)
        if await collector.count > 0 { break }
      }
    }

    // Wait for loadSecrets so its empty/placeholder cache cannot clobber Germany.
    try await waitUntil("known regions loaded from device") {
      await service.knownRegions == ["Placeholder"]
    }

    await service.updateKnownRegions(["Germany"])

    try await waitUntil("region update event should fire for correlated message") {
      await collector.count > 0
    }
    task.cancel()

    let receivedIDs = await collector.ids
    #expect(receivedIDs.contains(message.id))
    let saved = try await dataStore.fetchMessages(radioID: radioID, channelIndex: 0)
    #expect(saved.first?.regionScope == "Germany")
    #expect(saved.first?.regionScopeMatches == ["Germany"])
  }

  @Test
  func `regionUpdateEvents yields message IDs after DM message reprocess`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)

    // Non-matching seed so loadSecrets can finish without resolving the Germany code.
    try await dataStore.saveDevice(.testDevice(id: radioID).copy { $0.knownRegions = ["Placeholder"] })

    let scopeKey = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    // Byte at offset 1 is the unencrypted sender prefix used for DM correlation.
    let senderPrefixByte: UInt8 = 0xAB
    let packetPayload = Data([0x00, senderPrefixByte, 0xCC, 0xDD, 0xEE])
    let code = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey, payloadTypeBits: 5, payload: packetPayload
    )
    var transportCode = Data(count: 2)
    transportCode[0] = UInt8(code & 0xFF)
    transportCode[1] = UInt8((code >> 8) & 0xFF)
    let senderTimestamp: UInt32 = 1_704_222_000
    let contactID = UUID()

    let parsed = ParsedRxLogData(
      snr: 5,
      rssi: -80,
      rawPayload: packetPayload,
      routeType: .tcFlood,
      payloadType: .textMessage,
      payloadVersion: 0,
      payloadTypeBits: 5,
      transportCode: transportCode,
      pathLength: 0,
      pathNodes: [],
      packetPayload: packetPayload
    )
    // nil channelIndex marks a DM entry; correlation uses packetPayload[1].
    let entry = RxLogEntryDTO(
      radioID: radioID,
      from: parsed,
      channelIndex: nil,
      channelName: nil,
      decryptStatus: .success,
      senderTimestamp: senderTimestamp,
      regionScope: nil,
      regionScopeMatches: []
    )
    try await dataStore.saveRxLogEntry(entry)

    let message = MessageDTO.testDirectMessage(
      radioID: radioID,
      contactID: contactID,
      timestamp: senderTimestamp,
      direction: .incoming,
      status: .delivered,
      senderKeyPrefix: Data([senderPrefixByte, 0x11, 0x22, 0x33])
    )
    try await dataStore.saveMessage(message)

    await service.startEventMonitoring(radioID: radioID)
    defer { Task { await service.stopEventMonitoring() } }

    let stream = service.regionUpdateEvents()
    let collector = IDCollector()
    let task = Task {
      for await ids in stream {
        await collector.record(ids)
        if await collector.count > 0 { break }
      }
    }

    // Wait for loadSecrets so its empty/placeholder cache cannot clobber Germany.
    try await waitUntil("known regions loaded from device") {
      await service.knownRegions == ["Placeholder"]
    }

    await service.updateKnownRegions(["Germany"])

    try await waitUntil("region update event should fire for correlated DM") {
      await collector.count > 0
    }
    task.cancel()

    let receivedIDs = await collector.ids
    #expect(receivedIDs.contains(message.id))

    let saved = try #require(try await dataStore.fetchMessage(id: message.id))
    #expect(saved.regionScope == "Germany")
    #expect(saved.regionScopeMatches == ["Germany"])
  }

  @Test
  func `reprocess overwrites intermediate resolution with final cache`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)

    try await dataStore.saveDevice(.testDevice(id: radioID).copy { $0.knownRegions = [] })

    let scopeKey = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    let payload = Data([0xCA, 0xFE, 0xBA, 0xBE])
    let payloadTypeBits: UInt8 = 5
    let code = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey,
      payloadTypeBits: payloadTypeBits,
      payload: payload
    )
    var transportCode = Data(count: 2)
    transportCode[0] = UInt8(code & 0xFF)
    transportCode[1] = UInt8((code >> 8) & 0xFF)
    let senderTimestamp: UInt32 = 1_704_333_000

    let parsed = ParsedRxLogData(
      snr: 5,
      rssi: -80,
      rawPayload: payload,
      routeType: .tcFlood,
      payloadType: .groupText,
      payloadVersion: 0,
      payloadTypeBits: payloadTypeBits,
      transportCode: transportCode,
      pathLength: 0,
      pathNodes: [],
      packetPayload: payload
    )
    let entry = RxLogEntryDTO(
      radioID: radioID,
      from: parsed,
      channelIndex: 0,
      channelName: "Public",
      decryptStatus: .success,
      senderTimestamp: senderTimestamp,
      regionScope: "Stale",
      regionScopeMatches: ["Stale"]
    )
    try await dataStore.saveRxLogEntry(entry)

    let message = MessageDTO.testChannelMessage(
      radioID: radioID,
      channelIndex: 0,
      timestamp: senderTimestamp,
      direction: .incoming,
      status: .delivered
    )
    try await dataStore.saveMessage(message)

    await service.startEventMonitoring(radioID: radioID)
    defer { Task { await service.stopEventMonitoring() } }

    // Intermediate unique cache must land before the final multi-match overwrite.
    await service.replaceScopeKeyCacheAndReprocess([(name: "Germany", key: scopeKey)])

    let mid = try #require(
      try await dataStore.fetchRxLogEntries(radioID: radioID).first { $0.id == entry.id }
    )
    #expect(mid.regionScope == "Germany")
    #expect(mid.regionScopeMatches == ["Germany"])

    await service.replaceScopeKeyCacheAndReprocess([
      (name: "First", key: scopeKey),
      (name: "Second", key: scopeKey)
    ])

    let finalEntry = try #require(
      try await dataStore.fetchRxLogEntries(radioID: radioID).first { $0.id == entry.id }
    )
    #expect(finalEntry.regionScope == nil)
    #expect(Set(finalEntry.regionScopeMatches) == Set(["First", "Second"]))

    let finalMessage = try #require(try await dataStore.fetchMessage(id: message.id))
    #expect(finalMessage.regionScope == nil)
    #expect(Set(finalMessage.regionScopeMatches) == Set(["First", "Second"]))
  }

  private actor IDCollector {
    private(set) var ids: [UUID] = []
    var count: Int {
      ids.count
    }

    func record(_ newIDs: [UUID]) {
      ids.append(contentsOf: newIDs)
    }
  }
}
