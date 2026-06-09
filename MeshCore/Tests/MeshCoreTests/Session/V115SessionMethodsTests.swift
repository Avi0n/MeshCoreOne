import Foundation
import Testing
@testable import MeshCore

@Suite("v1.15.0 session methods")
struct V115SessionMethodsTests {

    @Test("sendChannelData emits correct frame and awaits OK")
    func sendChannelDataEmitsFrameAndAwaitsOK() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.3, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let task = Task {
            try await session.sendChannelData(
                channelIndex: 1,
                dataType: 0xFFFF,
                payload: Data([0x01, 0x02, 0x03])
            )
        }

        try await waitUntil("command should be sent") {
            await transport.sentData.count == 2
        }

        let sent = await transport.sentData[1]
        #expect(sent[0] == 0x3E)
        #expect(sent[1] == 0x01)
        #expect(sent[2] == 0xFF)
        #expect(sent[3] == 0xFF && sent[4] == 0xFF, "data_type LE")
        #expect(Data(sent[5...]) == Data([0x01, 0x02, 0x03]))

        await transport.simulateOK()
        try await task.value
        await session.stop()
    }

    @Test("sendChannelData throws deviceError on error response")
    func sendChannelDataDeviceError() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.3, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let task = Task {
            try await session.sendChannelData(
                channelIndex: 0,
                dataType: 0x0001,
                payload: Data([0x42])
            )
        }
        try await waitUntil("command should be sent") {
            await transport.sentData.count == 2
        }
        await transport.simulateError(code: 2)

        let err = await #expect(throws: MeshCoreError.self) {
            try await task.value
        }
        guard case .deviceError(let code)? = err else {
            Issue.record("Expected deviceError, got \(String(describing: err))")
            await session.stop()
            return
        }
        #expect(code == 2)
        await session.stop()
    }

    @Test("sendChannelData forwards pathLength and pathBytes to the builder")
    func sendChannelDataDirectPath() async throws {
        // Exercises the non-flood default arguments added in Rev 4. The session layer
        // is a thin passthrough, so we assert the wire bytes end up in the builder's
        // 1-byte-hash direct-path shape: [0x3E][ch][pathLen][pathBytes…][dataType LE][payload].
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.3, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let task = Task {
            try await session.sendChannelData(
                channelIndex: 0,
                dataType: 0xFFFF,
                payload: Data([0x42]),
                pathLength: 0x03,                           // hash_size=1, hash_count=3
                pathBytes: Data([0x11, 0x22, 0x33])
            )
        }
        try await waitUntil("command should be sent") {
            await transport.sentData.count == 2
        }

        let sent = await transport.sentData[1]
        #expect(sent[0] == 0x3E)
        #expect(sent[1] == 0x00, "channel 0")
        #expect(sent[2] == 0x03, "pathLength verbatim")
        #expect(Data(sent[3..<6]) == Data([0x11, 0x22, 0x33]), "pathBytes follow pathLength")
        #expect(sent[6] == 0xFF && sent[7] == 0xFF, "data_type LE 0xFFFF")
        #expect(Data(sent[8...]) == Data([0x42]))

        await transport.simulateOK()
        try await task.value
        await session.stop()
    }

    @Test("setDefaultFloodScope with name and key")
    func setDefaultFloodScopeSets() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.3, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let key = Data(repeating: 0x5A, count: 16)
        let task = Task {
            try await session.setDefaultFloodScope(name: "Europe", scopeKey: key)
        }
        try await waitUntil("command should be sent") {
            await transport.sentData.count == 2
        }

        let sent = await transport.sentData[1]
        #expect(sent.count == 48)
        #expect(sent[0] == 0x3F)
        #expect(Data(sent[1..<7]) == Data("Europe".utf8))
        #expect(Data(sent[32..<48]) == key)

        await transport.simulateOK()
        try await task.value
        await session.stop()
    }

    @Test("setDefaultFloodScope clears with empty args")
    func setDefaultFloodScopeClears() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.3, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let task = Task {
            try await session.setDefaultFloodScope(name: "", scopeKey: Data())
        }
        try await waitUntil("command should be sent") {
            await transport.sentData.count == 2
        }
        let sent = await transport.sentData[1]
        #expect(sent == Data([0x3F]), "Clear command is single byte")

        await transport.simulateOK()
        try await task.value
        await session.stop()
    }

    @Test("setDefaultFloodScope FloodScope overload — disabled clears")
    func setDefaultFloodScopeFloodScopeDisabled() async throws {
        // Exercises the (name:scope:) overload's `.disabled` short-circuit, which
        // the raw-key test can't reach. Regardless of the `name` argument, passing
        // `.disabled` must emit the single-byte clear frame.
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.3, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let task = Task {
            try await session.setDefaultFloodScope(name: "ignored", scope: .disabled)
        }
        try await waitUntil("command should be sent") {
            await transport.sentData.count == 2
        }
        let sent = await transport.sentData[1]
        #expect(sent == Data([0x3F]), "`.disabled` scope must short-circuit to clear frame")

        await transport.simulateOK()
        try await task.value
        await session.stop()
    }

    @Test("setDefaultFloodScope FloodScope overload — channelName derives key")
    func setDefaultFloodScopeFloodScopeChannelName() async throws {
        // Verifies the overload derives a 16-byte key from a FloodScope case and
        // emits the 48-byte set frame. We don't assert the exact derived key here
        // (that's covered by FloodScope.scopeKey() tests); we assert the wire
        // format is the 48-byte set form.
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.3, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let scope = FloodScope.channelName("public")
        let task = Task {
            try await session.setDefaultFloodScope(name: "pub", scope: scope)
        }
        try await waitUntil("command should be sent") {
            await transport.sentData.count == 2
        }
        let sent = await transport.sentData[1]
        #expect(sent.count == 48)
        #expect(sent[0] == 0x3F)
        #expect(Data(sent[1..<4]) == Data("pub".utf8))
        #expect(Data(sent[32..<48]) == scope.scopeKey(),
                "Overload must derive key via FloodScope.scopeKey()")

        await transport.simulateOK()
        try await task.value
        await session.stop()
    }

    @Test("getDefaultFloodScope returns populated scope")
    func getDefaultFloodScopePopulated() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.3, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let task = Task {
            try await session.getDefaultFloodScope()
        }
        try await waitUntil("command should be sent") {
            await transport.sentData.count == 2
        }

        var wire = Data([0x1C])
        var nameBytes = Array("NA".utf8)
        while nameBytes.count < 31 { nameBytes.append(0) }
        wire.append(contentsOf: nameBytes)
        wire.append(Data(repeating: 0xC3, count: 16))
        await transport.simulateReceive(wire)

        let result = try await task.value
        #expect(result?.name == "NA")
        #expect(result?.scopeKey == Data(repeating: 0xC3, count: 16))

        await session.stop()
    }

    @Test("getDefaultFloodScope returns nil when empty")
    func getDefaultFloodScopeEmpty() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.3, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let task = Task {
            try await session.getDefaultFloodScope()
        }
        try await waitUntil("command should be sent") {
            await transport.sentData.count == 2
        }

        await transport.simulateReceive(Data([0x1C]))

        let result = try await task.value
        #expect(result == nil)

        await session.stop()
    }

    @Test("getDefaultFloodScope propagates device errors")
    func getDefaultFloodScopePropagatesErrors() async throws {
        // Firmware <11 doesn't recognise opcode 0x40 and falls through to the catch-all
        // branch in MyMesh.cpp, which returns ERR_CODE_UNSUPPORTED_CMD = 1 (MyMesh.cpp:129).
        // The session method must surface this as a deviceError, not silently return nil —
        // otherwise callers can't distinguish "no scope configured" from "firmware too old".
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.3, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let task = Task { try await session.getDefaultFloodScope() }
        try await waitUntil("command should be sent") {
            await transport.sentData.count == 2
        }

        await transport.simulateError(code: 1)  // ERR_CODE_UNSUPPORTED_CMD

        let err = await #expect(throws: MeshCoreError.self) { try await task.value }
        guard case .deviceError(let code)? = err else {
            Issue.record("Expected deviceError, got \(String(describing: err))")
            await session.stop()
            return
        }
        #expect(code == 1)
        await session.stop()
    }

    @Test("sendTrace throws invalidInput when path is nil")
    func sendTraceRejectsNilPath() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.3, clientIdentifier: "MCTst")
        )

        let err = await #expect(throws: MeshCoreError.self) {
            try await session.sendTrace()
        }
        guard case .invalidInput? = err else {
            Issue.record("Expected invalidInput, got \(String(describing: err))")
            return
        }
        let sentCount = await transport.sentData.count
        #expect(sentCount == 0, "Guard must fail before any frame is sent")
    }

    @Test("sendTrace throws invalidInput when path is empty")
    func sendTraceRejectsEmptyPath() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.3, clientIdentifier: "MCTst")
        )

        let err = await #expect(throws: MeshCoreError.self) {
            try await session.sendTrace(path: Data())
        }
        guard case .invalidInput? = err else {
            Issue.record("Expected invalidInput, got \(String(describing: err))")
            return
        }
        let sentCount = await transport.sentData.count
        #expect(sentCount == 0, "Guard must fail before any frame is sent")
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
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(250).littleEndian) { Array($0) })     // bw
    payload.append(11)                                         // sf
    payload.append(5)                                          // cr
    return payload
}
