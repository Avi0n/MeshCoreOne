import Testing
import Foundation
@testable import MC1Services
@testable import MeshCore

/// Service-layer validation gates for the pipelined channel sync path (real session over a
/// MockTransport): #1 correctness with gaps, #2 mid-burst disconnect (throw and partial-drain
/// sub-cases), #4 drop-reconcile, plus serial-path parity.
@Suite("ChannelService pipelined sync")
struct ChannelServicePipelineTests {

    // MARK: - Gate #1: correctness with gaps

    @Test("pipelined sync persists non-contiguous configured channels to their own slots")
    func pipelinedSyncPersistsGapsToCorrectSlots() async throws {
        let radioID = UUID()
        let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
        let transport = MockTransport()
        await transport.setSupportsWriteWithoutResponse(true)
        let session = try await startedSession(transport)
        defer { Task { await session.stop() } }
        let service = ChannelService(session: session, dataStore: dataStore)

        let syncTask = Task {
            try await service.syncChannels(radioID: radioID, maxChannels: 8, usePipelinedRead: true)
        }

        try await waitUntil("all eight channel reads should be primed") {
            await transport.sentData.count == 9   // appStart + 8 reads
        }

        let configuredIndices: Set<UInt8> = [0, 2, 7]
        for index in UInt8(0)..<8 {
            if configuredIndices.contains(index) {
                await transport.simulateReceive(
                    makeChannelInfoPacket(index: index, name: "ch\(index)", secret: Data(repeating: 0xAB, count: 16))
                )
            } else {
                await transport.simulateReceive(
                    makeChannelInfoPacket(index: index, name: "", secret: Data(repeating: 0, count: 16))
                )
            }
        }

        let result = try await syncTask.value
        #expect(result.channelsSynced == 3)

        let stored = try await dataStore.fetchChannels(radioID: radioID).sorted { $0.index < $1.index }
        #expect(stored.map(\.index) == [0, 2, 7])
        #expect(stored.first { $0.index == 0 }?.name == "ch0")
        #expect(stored.first { $0.index == 2 }?.name == "ch2")
        #expect(stored.first { $0.index == 7 }?.name == "ch7")
    }

    // MARK: - Gate #4: drop-reconcile

    @Test("a dropped write is reconciled with a serial read and persisted")
    func droppedWriteIsReconciledAndPersisted() async throws {
        let radioID = UUID()
        let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 4)
        let transport = MockTransport()
        await transport.setSupportsWriteWithoutResponse(true)
        let session = try await startedSession(transport)
        defer { Task { await session.stop() } }
        let service = ChannelService(session: session, dataStore: dataStore)

        let syncTask = Task {
            try await service.syncChannels(radioID: radioID, maxChannels: 4, usePipelinedRead: true)
        }

        try await waitUntil("all four reads should be primed") {
            await transport.sentData.count == 5
        }

        // Index 2 is never answered in the pipeline (simulated dropped Write Command).
        for index: UInt8 in [0, 1, 3] {
            await transport.simulateReceive(
                makeChannelInfoPacket(index: index, name: "ch\(index)", secret: Data(repeating: 0xAB, count: 16))
            )
        }

        // After the idle timeout, the service reconciles index 2 with a serial read.
        try await waitUntil("reconcile should issue a serial read for the dropped index") {
            await transport.sentData.count == 6
        }
        await transport.simulateReceive(
            makeChannelInfoPacket(index: 2, name: "ch2", secret: Data(repeating: 0xAB, count: 16))
        )

        let result = try await syncTask.value
        #expect(result.channelsSynced == 4)

        let stored = try await dataStore.fetchChannels(radioID: radioID).sorted { $0.index < $1.index }
        #expect(stored.map(\.index) == [0, 1, 2, 3])
        #expect(stored.first { $0.index == 2 }?.name == "ch2")
    }

    // MARK: - Gate #2a: transport throws mid-pipeline → nothing persisted

    @Test("a transport send failure mid-pipeline throws and persists nothing")
    func transportThrowMidPipelinePersistsNothing() async throws {
        let radioID = UUID()
        let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 4)
        let transport = MockTransport()
        await transport.setSupportsWriteWithoutResponse(true)
        let session = try await startedSession(transport)
        defer { Task { await session.stop() } }
        let service = ChannelService(session: session, dataStore: dataStore)

        // appStart was send #1; fail every send from #2 (the first channel read) onward.
        await transport.failSends(fromSendIndex: 2)

        await #expect(throws: Error.self) {
            _ = try await service.syncChannels(radioID: radioID, maxChannels: 4, usePipelinedRead: true)
        }

        let stored = try await dataStore.fetchChannels(radioID: radioID)
        #expect(stored.isEmpty, "a hard send failure must not persist a partial channel set")
    }

    // MARK: - Gate #2b: partial drain → no mis-index, unread configured slot survives

    @Test("a mid-drain stall persists only read slots and never deletes an unread configured slot")
    func midDrainStallKeepsUnreadConfiguredSlot() async throws {
        let radioID = UUID()
        let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 4)

        // Pre-seed a configured channel at index 2 that the upcoming sync cannot read.
        _ = try await dataStore.batchSaveChannels(
            radioID: radioID,
            configured: [ChannelInfo(index: 2, name: "keep", secret: Data(repeating: 0xCD, count: 16))],
            unconfiguredIndices: [],
            pruneBeyond: nil
        )

        let transport = MockTransport()
        await transport.setSupportsWriteWithoutResponse(true)
        let session = try await startedSession(transport)
        defer { Task { await session.stop() } }
        let service = ChannelService(session: session, dataStore: dataStore)

        let syncTask = Task {
            try await service.syncChannels(radioID: radioID, maxChannels: 4, usePipelinedRead: true)
        }

        try await waitUntil("all four reads should be primed") {
            await transport.sentData.count == 5
        }

        // The reconcile read for the dropped index (send #6, after appStart + 4 primed reads)
        // fails as a transport drop, so index 2 cannot be re-read and stays in neither list.
        await transport.failSends(fromSendIndex: 6)

        // Answer 0, 1, 3 but never 2.
        for index: UInt8 in [0, 1, 3] {
            await transport.simulateReceive(
                makeChannelInfoPacket(index: index, name: "ch\(index)", secret: Data(repeating: 0xAB, count: 16))
            )
        }

        let result = try await syncTask.value
        #expect(result.channelsSynced == 3)

        let stored = try await dataStore.fetchChannels(radioID: radioID).sorted { $0.index < $1.index }
        #expect(stored.map(\.index) == [0, 1, 2, 3], "no mis-indexed rows; the unread index 2 is not deleted")
        #expect(stored.first { $0.index == 2 }?.name == "keep", "the unread configured slot is preserved verbatim")
    }

    // MARK: - Parity: serial path unchanged

    @Test("serial path (usePipelinedRead: false) still syncs via acknowledged reads")
    func serialPathStillSyncs() async throws {
        let radioID = UUID()
        let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 2)
        let transport = MockTransport()   // capability defaults to false
        let session = try await startedSession(transport)
        defer { Task { await session.stop() } }
        let service = ChannelService(session: session, dataStore: dataStore)

        let syncTask = Task {
            try await service.syncChannels(radioID: radioID, maxChannels: 2, usePipelinedRead: false)
        }

        try await waitUntil("first serial read should be sent") {
            await transport.sentData.count == 2
        }
        await transport.simulateReceive(
            makeChannelInfoPacket(index: 0, name: "ch0", secret: Data(repeating: 0xAB, count: 16))
        )
        try await waitUntil("second serial read should be sent after the first responds") {
            await transport.sentData.count == 3
        }
        await transport.simulateReceive(
            makeChannelInfoPacket(index: 1, name: "", secret: Data(repeating: 0, count: 16))
        )

        let result = try await syncTask.value
        #expect(result.channelsSynced == 1)

        let stored = try await dataStore.fetchChannels(radioID: radioID)
        #expect(stored.map(\.index) == [0])
    }
}

// MARK: - Helpers

private func startedSession(_ transport: MockTransport) async throws -> MeshCoreSession {
    // A generous defaultTimeout keeps appStart and acknowledged reads from flaking under
    // heavy parallel test load; no test here relies on a getChannel actually timing out, so
    // it never slows the suite. The pipeline idle timeout stays small but above the gap
    // between back-to-back simulated responses so it never trips mid-stream.
    let session = MeshCoreSession(
        transport: transport,
        configuration: SessionConfiguration(
            defaultTimeout: 5.0,
            clientIdentifier: "MCTst",
            channelPipelineWindow: 8,
            channelPipelineIdleTimeout: 0.5,
            channelPipelineHardTimeout: 10.0,
            channelPipelinePostDrainGrace: 0.05
        )
    )
    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") {
        await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    return session
}

private func makeSelfInfoPacket() -> Data {
    var payload = Data()
    payload.append(1)
    payload.append(22)
    payload.append(22)
    payload.append(Data(repeating: 0x01, count: 32))
    payload.append(withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) })
    payload.append(withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) })
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(withUnsafeBytes(of: UInt32(915_000).littleEndian) { Data($0) })
    payload.append(withUnsafeBytes(of: UInt32(125_000).littleEndian) { Data($0) })
    payload.append(7)
    payload.append(5)
    payload.append(contentsOf: "Test".utf8)

    var packet = Data([ResponseCode.selfInfo.rawValue])
    packet.append(payload)
    return packet
}

private func makeChannelInfoPacket(index: UInt8, name: String, secret: Data) -> Data {
    var packet = Data([ResponseCode.channelInfo.rawValue, index])
    let nameBytes = Array(name.utf8.prefix(31))
    packet.append(contentsOf: nameBytes)
    packet.append(0)
    if nameBytes.count < 31 {
        packet.append(Data(repeating: 0, count: 31 - nameBytes.count))
    }
    packet.append(secret)
    return packet
}
