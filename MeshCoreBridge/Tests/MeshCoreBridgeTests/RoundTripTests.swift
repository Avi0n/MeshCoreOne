import CryptoKit
import Foundation
import MeshCore
import Testing
@testable import MeshCoreBridgeKit

/// Drives the real `MeshCoreSession` through the bridge's callback surface end to
/// end, mirroring the spike's device-query flow but through the exported API:
/// a fake Kotlin transport reports connect, delivers a canned `selfInfo`, and
/// pushes a distinct event, and every outcome is observed on the registered
/// listeners.
struct RoundTripTests {
  /// Rebuilds the `selfInfo` response packet the session expects after appStart,
  /// matching the session-test wire layout.
  private static func selfInfoPacket(name: String = "Test") -> [UInt8] {
    var payload = Data()
    payload.append(1) // advertisement type
    payload.append(UInt8(bitPattern: 22)) // tx power
    payload.append(UInt8(bitPattern: 22)) // max tx power
    payload.append(Data(repeating: 0x01, count: 32)) // public key
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lat
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lon
    payload.append(contentsOf: [0, 0, 0, 0])
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) }) // freq
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) }) // bw
    payload.append(7) // spreading factor
    payload.append(5) // coding rate
    payload.append(contentsOf: name.utf8)
    var packet = Data([ResponseCode.selfInfo.rawValue])
    packet.append(payload)
    return Array(packet)
  }

  @Test("start handshake and a pushed event flow through the callback surface")
  func startAndPushedEvent() async throws {
    let recorder = BridgeRecorder()
    let bridge = MeshCoreBridge(
      sendListener: recorder,
      resultListener: recorder,
      eventListener: recorder,
      logListener: recorder
    )

    // Kick off the handshake, then report the physical link up so the session's
    // transport.connect() resumes and writes appStart.
    bridge.startSession(requestID: 1)
    bridge.reportConnected()

    // The appStart frame is the outbound "connect" traffic Kotlin would write.
    let sawAppStart = await recorder.wait { !recorder.sends.isEmpty }
    #expect(sawAppStart, "session never wrote the appStart frame")

    // Feed the canned selfInfo response the same way an inbound BLE notification
    // would arrive.
    bridge.receiveBytes(Self.selfInfoPacket())

    let succeeded = await recorder.wait { recorder.successes.contains { $0.id == 1 } }
    #expect(succeeded, "startSession never reported success")

    let success = try #require(recorder.successes.first { $0.id == 1 })
    let envelope = try decodeEnvelope(success.bytes)
    #expect(envelope["kind"] as? String == "selfInfo")
    let payload = try #require(envelope["payload"] as? [String: Any])
    #expect(payload["name"] as? String == "Test")
    #expect(payload["radioSpreadingFactor"] as? Int == 7)

    // The selfInfo also arrives as a pushed event on the event listener.
    let sawSelfInfoEvent = await recorder.wait {
      recorder.events.contains { (try? kind(of: $0)) == "selfInfo" }
    }
    #expect(sawSelfInfoEvent, "selfInfo was not forwarded as an event")

    // Push a distinct event and confirm it is forwarded.
    bridge.receiveBytes([ResponseCode.messagesWaiting.rawValue])
    let sawWaiting = await recorder.wait {
      recorder.events.contains { (try? kind(of: $0)) == "messagesWaiting" }
    }
    #expect(sawWaiting, "messagesWaiting event was not forwarded")

    bridge.stopSession(requestID: 2)
    let stopped = await recorder.wait { recorder.successes.contains { $0.id == 2 } }
    #expect(stopped, "stopSession never reported success")

    #expect(recorder.failures.isEmpty, "unexpected failures: \(recorder.failures)")
  }

  @Test("a second startSession on a live session re-sends appStart")
  func liveStartSessionRefreshesSelfInfo() async throws {
    let recorder = BridgeRecorder()
    let bridge = MeshCoreBridge(
      sendListener: recorder,
      resultListener: recorder,
      eventListener: recorder,
      logListener: recorder
    )

    bridge.startSession(requestID: 1)
    bridge.reportConnected()
    _ = await recorder.wait { !recorder.sends.isEmpty }
    bridge.receiveBytes(Self.selfInfoPacket())
    _ = await recorder.wait { recorder.successes.contains { $0.id == 1 } }
    let sendsAfterHandshake = recorder.sends.count

    bridge.startSession(requestID: 3)
    let sawSecondAppStart = await recorder.wait { recorder.sends.count > sendsAfterHandshake }
    #expect(sawSecondAppStart, "live startSession must write another appStart")

    bridge.receiveBytes(Self.selfInfoPacket(name: "Renamed"))
    let succeeded = await recorder.wait { recorder.successes.contains { $0.id == 3 } }
    #expect(succeeded, "live startSession never reported success")
    let success = try #require(recorder.successes.first { $0.id == 3 })
    let payload = try #require(decodeEnvelope(success.bytes)["payload"] as? [String: Any])
    #expect(payload["name"] as? String == "Renamed")
    #expect(recorder.failures.isEmpty, "unexpected failures: \(recorder.failures)")
  }

  @Test("a command issued before connect fails, not hangs")
  func commandBeforeConnectFails() async throws {
    let recorder = BridgeRecorder()
    let bridge = MeshCoreBridge(
      sendListener: recorder,
      resultListener: recorder,
      eventListener: recorder,
      logListener: recorder
    )

    // No session started: the transport is not connected, so a command must
    // report a failure rather than block forever.
    bridge.getBattery(requestID: 7)
    let failed = await recorder.wait { recorder.failures.contains { $0.id == 7 } }
    #expect(failed, "getBattery before connect should fail")
    let failure = try #require(recorder.failures.first { $0.id == 7 })
    #expect(failure.code == BridgeErrorCode.notConnected.rawValue)
  }

  @Test("setTime writes the 0x06 command frame and reports ok")
  func setTimeSendsCommandAndReportsOk() async throws {
    let recorder = BridgeRecorder()
    let bridge = MeshCoreBridge(
      sendListener: recorder,
      resultListener: recorder,
      eventListener: recorder,
      logListener: recorder
    )

    // Bring the session up: handshake, physical link, canned selfInfo.
    bridge.startSession(requestID: 1)
    bridge.reportConnected()
    _ = await recorder.wait { !recorder.sends.isEmpty }
    bridge.receiveBytes(Self.selfInfoPacket())
    _ = await recorder.wait { recorder.successes.contains { $0.id == 1 } }

    let epoch: Int64 = 1_700_000_000
    bridge.setTime(requestID: 3, timestampEpoch: epoch)

    // The command frame is 0x06 followed by the little-endian UInt32 epoch seconds.
    let expected =
      [CommandCode.setTime.rawValue] + withUnsafeBytes(of: UInt32(epoch).littleEndian) { Array($0) }
    let sawFrame = await recorder.wait { recorder.sends.contains { $0 == expected } }
    #expect(sawFrame, "setTime never wrote the 0x06 command frame")

    // Firmware replies with an OK frame; the command reports success as an `ok` result.
    bridge.receiveBytes([ResponseCode.ok.rawValue])
    let succeeded = await recorder.wait { recorder.successes.contains { $0.id == 3 } }
    #expect(succeeded, "setTime never reported success")
    let success = try #require(recorder.successes.first { $0.id == 3 })
    #expect(try decodeEnvelope(success.bytes)["kind"] as? String == "ok")
    #expect(recorder.failures.isEmpty, "unexpected failures: \(recorder.failures)")
  }

  @Test("setFloodScope region writes the 0x36 0x00 command frame and reports ok")
  func setFloodScopeRegionSendsCommandAndReportsOk() async throws {
    let recorder = BridgeRecorder()
    let bridge = MeshCoreBridge(
      sendListener: recorder,
      resultListener: recorder,
      eventListener: recorder,
      logListener: recorder
    )

    bridge.startSession(requestID: 1)
    bridge.reportConnected()
    _ = await recorder.wait { !recorder.sends.isEmpty }
    bridge.receiveBytes(Self.selfInfoPacket())
    _ = await recorder.wait { recorder.successes.contains { $0.id == 1 } }

    bridge.setFloodScope(requestID: 3, scopeType: "region", scopeText: "Europe", scopeKey: [])

    // Sub-command 0 followed by SHA256("#Europe").prefix(16) — the public-region key convention.
    let regionKey = Array(SHA256.hash(data: Data("#Europe".utf8)).prefix(16))
    let expected = [CommandCode.setFloodScope.rawValue, 0x00] + regionKey
    let sawFrame = await recorder.wait { recorder.sends.contains { $0 == expected } }
    #expect(sawFrame, "setFloodScope never wrote the 0x36 0x00 region frame")

    bridge.receiveBytes([ResponseCode.ok.rawValue])
    let succeeded = await recorder.wait { recorder.successes.contains { $0.id == 3 } }
    #expect(succeeded, "setFloodScope never reported success")
    #expect(try decodeEnvelope(#require(recorder.successes.first { $0.id == 3 }).bytes)["kind"] as? String == "ok")
    #expect(recorder.failures.isEmpty, "unexpected failures: \(recorder.failures)")
  }

  @Test("setFloodScopeUnscoped writes the 0x36 0x01 command frame and reports ok")
  func setFloodScopeUnscopedSendsCommandAndReportsOk() async throws {
    let recorder = BridgeRecorder()
    let bridge = MeshCoreBridge(
      sendListener: recorder,
      resultListener: recorder,
      eventListener: recorder,
      logListener: recorder
    )

    bridge.startSession(requestID: 1)
    bridge.reportConnected()
    _ = await recorder.wait { !recorder.sends.isEmpty }
    bridge.receiveBytes(Self.selfInfoPacket())
    _ = await recorder.wait { recorder.successes.contains { $0.id == 1 } }

    bridge.setFloodScopeUnscoped(requestID: 3)

    // Sub-command 1, no key: forces un-scoped flood broadcasts (firmware v12+).
    let expected = [CommandCode.setFloodScope.rawValue, 0x01]
    let sawFrame = await recorder.wait { recorder.sends.contains { $0 == expected } }
    #expect(sawFrame, "setFloodScopeUnscoped never wrote the 0x36 0x01 frame")

    bridge.receiveBytes([ResponseCode.ok.rawValue])
    let succeeded = await recorder.wait { recorder.successes.contains { $0.id == 3 } }
    #expect(succeeded, "setFloodScopeUnscoped never reported success")
    #expect(recorder.failures.isEmpty, "unexpected failures: \(recorder.failures)")
  }

  @Test("requestStatus with the room node type decodes the room-server counters")
  func requestStatusRoomLayout() async throws {
    let recorder = BridgeRecorder()
    let bridge = Self.startedBridge(recorder)
    await Self.completeHandshake(bridge, recorder)

    let target = [UInt8](repeating: 0x31, count: 32)
    bridge.requestStatus(requestID: 3, publicKey: target, nodeType: Int32(ContactType.room.rawValue))

    let sawFrame = await recorder.wait { recorder.sends.contains { $0.first == CommandCode.sendStatusRequest.rawValue } }
    #expect(sawFrame, "requestStatus never wrote the status-request frame")

    bridge.receiveBytes(Self.messageSentPacket())
    // A room server packs posted (17) and post-push (9) into the field a repeater reads as RX airtime.
    bridge.receiveBytes(Self.statusResponsePacket(publicKeyPrefix: Array(target.prefix(6)), trailing: 17 | (9 << 16)))

    let succeeded = await recorder.wait { recorder.successes.contains { $0.id == 3 } }
    #expect(succeeded, "requestStatus never reported success")
    let success = try #require(recorder.successes.first { $0.id == 3 })
    let payload = try #require(decodeEnvelope(success.bytes)["payload"] as? [String: Any])
    #expect(payload["layout"] as? String == "roomServer")
    #expect(payload["roomServerPostedCount"] as? Int == 17)
    #expect(payload["roomServerPostPushCount"] as? Int == 9)
    #expect(payload["rxAirtime"] as? Int == 0)
  }

  @Test("requestStatus with the repeater node type still decodes RX airtime and receive errors")
  func requestStatusRepeaterLayout() async throws {
    let recorder = BridgeRecorder()
    let bridge = Self.startedBridge(recorder)
    await Self.completeHandshake(bridge, recorder)

    let target = [UInt8](repeating: 0x32, count: 32)
    bridge.requestStatus(requestID: 3, publicKey: target, nodeType: Int32(ContactType.repeater.rawValue))

    let sawFrame = await recorder.wait { recorder.sends.contains { $0.first == CommandCode.sendStatusRequest.rawValue } }
    #expect(sawFrame, "requestStatus never wrote the status-request frame")

    bridge.receiveBytes(Self.messageSentPacket())
    bridge.receiveBytes(
      Self.statusResponsePacket(publicKeyPrefix: Array(target.prefix(6)), trailing: 4321, receiveErrors: 12)
    )

    let succeeded = await recorder.wait { recorder.successes.contains { $0.id == 3 } }
    #expect(succeeded, "requestStatus never reported success")
    let success = try #require(recorder.successes.first { $0.id == 3 })
    let payload = try #require(decodeEnvelope(success.bytes)["payload"] as? [String: Any])
    #expect(payload["layout"] as? String == "repeater")
    #expect(payload["rxAirtime"] as? Int == 4321)
    #expect(payload["receiveErrors"] as? Int == 12)
    #expect(payload["roomServerPostedCount"] == nil || payload["roomServerPostedCount"] is NSNull)
  }

  // MARK: - Helpers

  private static func startedBridge(_ recorder: BridgeRecorder) -> MeshCoreBridge {
    MeshCoreBridge(
      sendListener: recorder,
      resultListener: recorder,
      eventListener: recorder,
      logListener: recorder
    )
  }

  /// Brings the session up: handshake, physical link, canned selfInfo.
  private static func completeHandshake(_ bridge: MeshCoreBridge, _ recorder: BridgeRecorder) async {
    bridge.startSession(requestID: 1)
    bridge.reportConnected()
    _ = await recorder.wait { !recorder.sends.isEmpty }
    bridge.receiveBytes(selfInfoPacket())
    _ = await recorder.wait { recorder.successes.contains { $0.id == 1 } }
  }

  /// The `RES_CODE_SENT` frame the firmware returns before pushing the routed reply.
  private static func messageSentPacket(expectedAck: [UInt8] = [0xAA, 0xBB, 0xCC, 0xDD]) -> [UInt8] {
    var packet = Data([ResponseCode.messageSent.rawValue, 0x00])
    packet.append(contentsOf: expectedAck)
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(5000).littleEndian) { Array($0) })
    return Array(packet)
  }

  /// A pushed `STATUS_RESPONSE` (0x87). `trailing` is the 4-byte field the repeater layout reads as RX
  /// airtime and the room-server layout reads as two packed 16-bit counters; `receiveErrors` is the
  /// repeater-only tail, omitted when nil.
  private static func statusResponsePacket(
    publicKeyPrefix: [UInt8],
    trailing: UInt32,
    receiveErrors: UInt32? = nil
  ) -> [UInt8] {
    var packet = Data([ResponseCode.statusResponse.rawValue, 0x00])
    packet.append(contentsOf: publicKeyPrefix)
    func appendUInt16(_ value: UInt16) { packet.append(contentsOf: withUnsafeBytes(of: value.littleEndian) { Array($0) }) }
    func appendInt16(_ value: Int16) { packet.append(contentsOf: withUnsafeBytes(of: value.littleEndian) { Array($0) }) }
    func appendUInt32(_ value: UInt32) { packet.append(contentsOf: withUnsafeBytes(of: value.littleEndian) { Array($0) }) }
    appendUInt16(1000) // battery
    appendUInt16(0) // tx queue length
    appendInt16(-110) // noise floor
    appendInt16(-85) // last RSSI
    for value in [UInt32(100), 50, 25, 3600, 5, 10, 15, 20] { appendUInt32(value) }
    appendUInt16(0) // full events
    appendInt16(0) // last SNR
    appendUInt16(0) // direct duplicates
    appendUInt16(0) // flood duplicates
    appendUInt32(trailing)
    receiveErrors.map(appendUInt32)
    return Array(packet)
  }

  private func decodeEnvelope(_ bytes: [UInt8]) throws -> [String: Any] {
    let object = try JSONSerialization.jsonObject(with: Data(bytes))
    return try #require(object as? [String: Any])
  }

  private func kind(of bytes: [UInt8]) throws -> String {
    try #require(decodeEnvelope(bytes)["kind"] as? String)
  }
}
