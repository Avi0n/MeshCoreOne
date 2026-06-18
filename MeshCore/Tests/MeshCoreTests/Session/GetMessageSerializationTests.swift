import Foundation
import Testing
@testable import MeshCore

@Suite("MeshCoreSession getMessage serialization")
struct GetMessageSerializationTests {

    /// getMessage runs inside the request/response serializer, so a command running
    /// concurrently with it owns the serializer first and any `.error` that arrives
    /// while that command is in flight is the command's own. getMessage must not consume
    /// that error and report a spurious device error for the message-fetch path; it sees
    /// only its own response once the unrelated command releases the serializer.
    @Test("an unrelated error does not fail an in-flight getMessage")
    func unrelatedErrorDoesNotFailGetMessage() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.5, clientIdentifier: "MCTst")
        )

        try await startSession(session, transport: transport)

        // A simple command acquires the serializer first.
        let resetTask = Task { try await session.factoryReset() }
        try await waitUntil("factoryReset should be sent") {
            await transport.sentData.count == 2
        }

        // getMessage is issued while the reset is outstanding; it must park behind it.
        let messageTask = Task { try await session.getMessage() }
        try? await Task.sleep(for: .milliseconds(50))
        #expect(await transport.sentData.count == 2, "getMessage must wait behind the in-flight command")

        // The error belongs to the reset command, which owns the serializer.
        await transport.simulateError(code: 17)

        let resetError = await #expect(throws: MeshCoreError.self) {
            try await resetTask.value
        }
        guard case .deviceError(let code)? = resetError else {
            Issue.record("Expected reset to fail with deviceError, got \(String(describing: resetError))")
            await session.stop()
            return
        }
        #expect(code == 17)

        // Only after the reset releases the serializer does getMessage send its frame.
        try await waitUntil("getMessage should send after the command completes") {
            await transport.sentData.count == 3
        }

        // getMessage receives its own response and is unaffected by the earlier error.
        await transport.simulateReceive(makeNoMoreMessagesPacket())

        let result = try await messageTask.value
        guard case .noMoreMessages = result else {
            Issue.record("Expected getMessage to return .noMoreMessages, got \(result)")
            await session.stop()
            return
        }

        await session.stop()
    }
}

private func startSession(
    _ session: MeshCoreSession,
    transport: MockTransport
) async throws {
    let startTask = Task { try await session.start() }
    try await waitUntil("transport should send appStart before session starts") {
        await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
}

private func makeNoMoreMessagesPacket() -> Data {
    Data([ResponseCode.noMoreMessages.rawValue])
}

private func makeSelfInfoPacket() -> Data {
    var payload = Data([ResponseCode.selfInfo.rawValue])
    payload.append(1)                                         // adv type
    payload.append(UInt8(bitPattern: 22))                     // tx power
    payload.append(UInt8(bitPattern: 22))                     // max tx power
    payload.append(Data(repeating: 0x01, count: 32))          // pubkey
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })  // lat
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })  // lon
    payload.append(0)                                          // multi acks
    payload.append(0)                                          // adv loc policy
    payload.append(0)                                          // telemetry mode
    payload.append(0)                                          // manual add
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(869525).littleEndian) { Array($0) })  // freq
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) }) // bw
    payload.append(11)                                         // sf
    payload.append(5)                                          // cr
    return payload
}
