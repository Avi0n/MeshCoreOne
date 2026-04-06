import Foundation
import Testing
@testable import MeshCore

@Suite("MeshCoreSession auto contact refresh")
struct AutoContactRefreshTests {
    @Test("auto-refresh coalesces bursty contact invalidations")
    func autoRefreshCoalescesBurstyInvalidations() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.2, clientIdentifier: "MeshCore-Tests")
        )
        try await startSession(session, transport: transport)
        await session.setAutoUpdateContacts(true)

        let advertisementPacket = makeAdvertisementPacket(publicKey: Data(repeating: 0x22, count: 32))
        await transport.simulateReceive(advertisementPacket)
        await transport.simulateReceive(advertisementPacket)
        await transport.simulateReceive(advertisementPacket)

        try await waitUntil("first contact refresh should be sent") {
            await transport.sentData.count >= 1
        }

        #expect(await transport.sentData.count == 1)
        #expect(await transport.sentData.first == PacketBuilder.getContacts())

        await simulateEmptyContactsResponse(transport, lastModified: 1)
        try await Task.sleep(for: .milliseconds(50))

        let sentAfterFirstResponse = await transport.sentData.count
        #expect(sentAfterFirstResponse >= 1)
        #expect(sentAfterFirstResponse <= 2)

        if sentAfterFirstResponse == 2 {
            await simulateEmptyContactsResponse(transport, lastModified: 2)
            try await Task.sleep(for: .milliseconds(50))
        }

        #expect(await transport.sentData.count <= 2)
    }

    private func simulateEmptyContactsResponse(_ transport: MockTransport, lastModified: UInt32) async {
        await transport.simulateReceive(makeContactsStartPacket(count: 0))
        await transport.simulateReceive(makeContactsEndPacket(lastModified: lastModified))
    }

    private func startSession(_ session: MeshCoreSession, transport: MockTransport) async throws {
        let startTask = Task { try await session.start() }

        try await waitUntil("appStart command should be sent") {
            await transport.sentData.count >= 1
        }

        await transport.simulateReceive(makeSelfInfoPacket())
        try await startTask.value
        await transport.clearSentData()
    }

    private func waitUntil(
        _ description: String,
        timeout: Duration = .milliseconds(300),
        pollInterval: Duration = .milliseconds(10),
        condition: @escaping @Sendable () async -> Bool
    ) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)

        while clock.now < deadline {
            if await condition() { return }
            try await Task.sleep(for: pollInterval)
        }

        Issue.record("Timed out waiting: \(description)")
        throw MeshCoreError.timeout
    }

    private func makeAdvertisementPacket(publicKey: Data) -> Data {
        var packet = Data([ResponseCode.advertisement.rawValue])
        packet.append(publicKey)
        return packet
    }

    private func makeContactsStartPacket(count: UInt32) -> Data {
        var packet = Data([ResponseCode.contactStart.rawValue])
        packet.append(contentsOf: withUnsafeBytes(of: count.littleEndian) { Array($0) })
        return packet
    }

    private func makeContactsEndPacket(lastModified: UInt32) -> Data {
        var packet = Data([ResponseCode.contactEnd.rawValue])
        packet.append(contentsOf: withUnsafeBytes(of: lastModified.littleEndian) { Array($0) })
        return packet
    }

    private func makeSelfInfoPacket() -> Data {
        var payload = Data([ResponseCode.selfInfo.rawValue])
        payload.append(0x01)
        payload.append(UInt8(bitPattern: Int8(20)))
        payload.append(UInt8(bitPattern: Int8(22)))
        payload.append(Data(repeating: 0xAA, count: 32))
        payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) })
        payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) })
        payload.append(0x00)
        payload.append(0x00)
        payload.append(0x00)
        payload.append(0x01)
        payload.append(contentsOf: withUnsafeBytes(of: UInt32(910_525).littleEndian) { Data($0) })
        payload.append(contentsOf: withUnsafeBytes(of: UInt32(62_500).littleEndian) { Data($0) })
        payload.append(0x07)
        payload.append(0x05)
        payload.append("TestNode".data(using: .utf8)!)
        return payload
    }
}
