import Testing
import Foundation
@testable import MC1Services
@testable import MeshCore

@Suite("RxLogService inbound advert hop count")
struct RxLogServiceAdvertHopTests {

    private static let advertiserKey = Data((0..<ProtocolLimits.publicKeySize).map { UInt8($0) })

    @Test("advert RX-log entry stamps the inbound hop count keyed by full pubkey")
    func advertStampsInboundHopCount() async throws {
        let radioID = UUID()
        let store = MockPersistenceStore()
        let session = MeshCoreSession(transport: MockTransport())
        let service = RxLogService(session: session, dataStore: store, heardRepeatsService: nil)
        await service.startEventMonitoring(radioID: radioID)
        defer { Task { await service.stopEventMonitoring() } }

        let hops = 3
        let payload = Self.advertPayload(pubKey: Self.advertiserKey)
        await service.process(Self.makeParsed(
            payloadType: .advert,
            pathLength: encodePathLen(hashSize: 1, hopCount: hops),
            packetPayload: payload
        ))

        let calls = await store.inboundHopCountCalls
        #expect(calls.count == 1)
        #expect(calls.first?.publicKey == Self.advertiserKey)
        #expect(calls.first?.hopCount == hops)
    }

    @Test("a directly-heard advert stamps a hop count of zero")
    func advertDirectStampsZero() async throws {
        let radioID = UUID()
        let store = MockPersistenceStore()
        let session = MeshCoreSession(transport: MockTransport())
        let service = RxLogService(session: session, dataStore: store, heardRepeatsService: nil)
        await service.startEventMonitoring(radioID: radioID)
        defer { Task { await service.stopEventMonitoring() } }

        await service.process(Self.makeParsed(
            payloadType: .advert,
            pathLength: encodePathLen(hashSize: 1, hopCount: 0),
            packetPayload: Self.advertPayload(pubKey: Self.advertiserKey)
        ))

        let calls = await store.inboundHopCountCalls
        #expect(calls.count == 1)
        #expect(calls.first?.hopCount == 0)
    }

    @Test("a truncated advert payload (shorter than a public key) is never stamped")
    func truncatedAdvertSkipsWrite() async throws {
        let radioID = UUID()
        let store = MockPersistenceStore()
        let session = MeshCoreSession(transport: MockTransport())
        let service = RxLogService(session: session, dataStore: store, heardRepeatsService: nil)
        await service.startEventMonitoring(radioID: radioID)
        defer { Task { await service.stopEventMonitoring() } }

        await service.process(Self.makeParsed(
            payloadType: .advert,
            pathLength: encodePathLen(hashSize: 1, hopCount: 1),
            packetPayload: Data([0x01, 0x02, 0x03])
        ))

        let calls = await store.inboundHopCountCalls
        #expect(calls.isEmpty, "A payload shorter than a public key must not be stamped")
    }

    @Test("advert timestamp is extracted from the payload and passed to the store")
    func advertTimestampPassedThrough() async throws {
        let radioID = UUID()
        let store = MockPersistenceStore()
        let session = MeshCoreSession(transport: MockTransport())
        let service = RxLogService(session: session, dataStore: store, heardRepeatsService: nil)
        await service.startEventMonitoring(radioID: radioID)
        defer { Task { await service.stopEventMonitoring() } }

        // Payload: 32-byte key + 4-byte little-endian timestamp 0x00000064 (100 decimal).
        let timestamp: UInt32 = 100
        var tsBytes = Data(count: 4)
        tsBytes.withUnsafeMutableBytes { $0.storeBytes(of: timestamp.littleEndian, as: UInt32.self) }
        let payload = Self.advertiserKey + tsBytes
        await service.process(Self.makeParsed(
            payloadType: .advert,
            pathLength: encodePathLen(hashSize: 1, hopCount: 2),
            packetPayload: payload
        ))

        let calls = await store.inboundHopCountCalls
        #expect(calls.count == 1)
        #expect(calls.first?.advertTimestamp == timestamp)
    }

    @Test("a payload exactly 32 bytes (no timestamp) passes nil advertTimestamp")
    func payloadExactlyPubKeySizePassesNilTimestamp() async throws {
        let radioID = UUID()
        let store = MockPersistenceStore()
        let session = MeshCoreSession(transport: MockTransport())
        let service = RxLogService(session: session, dataStore: store, heardRepeatsService: nil)
        await service.startEventMonitoring(radioID: radioID)
        defer { Task { await service.stopEventMonitoring() } }

        // Payload is exactly publicKeySize: no timestamp bytes present.
        await service.process(Self.makeParsed(
            payloadType: .advert,
            pathLength: encodePathLen(hashSize: 1, hopCount: 1),
            packetPayload: Self.advertiserKey
        ))

        let calls = await store.inboundHopCountCalls
        #expect(calls.count == 1)
        #expect(calls.first?.advertTimestamp == nil)
    }

    @Test("a non-advert entry never stamps an inbound hop count")
    func nonAdvertSkipsWrite() async throws {
        let radioID = UUID()
        let store = MockPersistenceStore()
        let session = MeshCoreSession(transport: MockTransport())
        let service = RxLogService(session: session, dataStore: store, heardRepeatsService: nil)
        await service.startEventMonitoring(radioID: radioID)
        defer { Task { await service.stopEventMonitoring() } }

        await service.process(Self.makeParsed(
            payloadType: .groupText,
            pathLength: encodePathLen(hashSize: 1, hopCount: 2),
            packetPayload: Self.advertPayload(pubKey: Self.advertiserKey)
        ))

        let calls = await store.inboundHopCountCalls
        #expect(calls.isEmpty, "Only advert payloads carry an inbound hop count")
    }

    @Test("a direct-routed advert is never stamped: its path length is route, not hops traversed")
    func directRoutedAdvertSkipsWrite() async throws {
        let radioID = UUID()
        let store = MockPersistenceStore()
        let session = MeshCoreSession(transport: MockTransport())
        let service = RxLogService(session: session, dataStore: store, heardRepeatsService: nil)
        await service.startEventMonitoring(radioID: radioID)
        defer { Task { await service.stopEventMonitoring() } }

        await service.process(Self.makeParsed(
            payloadType: .advert,
            pathLength: encodePathLen(hashSize: 1, hopCount: 2),
            packetPayload: Self.advertPayload(pubKey: Self.advertiserKey),
            routeType: .direct
        ))

        let calls = await store.inboundHopCountCalls
        #expect(calls.isEmpty, "Only flood-routed adverts accumulate a hop path")
    }

    // MARK: - Helpers

    /// An advert wire payload begins with the advertiser's full 32-byte public key.
    private static func advertPayload(pubKey: Data) -> Data {
        pubKey + Data([0xAA, 0xBB, 0xCC, 0xDD])
    }

    private static func makeParsed(
        payloadType: PayloadType,
        pathLength: UInt8,
        packetPayload: Data,
        routeType: RouteType = .flood
    ) -> ParsedRxLogData {
        ParsedRxLogData(
            snr: 8.0,
            rssi: -70,
            rawPayload: packetPayload,
            routeType: routeType,
            payloadType: payloadType,
            payloadVersion: 0,
            payloadTypeBits: 0,
            transportCode: nil,
            pathLength: pathLength,
            pathNodes: [],
            packetPayload: packetPayload
        )
    }
}
