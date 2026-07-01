import Foundation
@testable import MeshCore
import Testing

@Suite("v1.15.0 session — getMessage")
struct V115GetMessageTests {
  @Test
  func `getMessage yields channelDatagram`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 0.3, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    let task = Task {
      try await session.getMessage()
    }

    try await waitUntil("getMessage should be sent") {
      await transport.sentData.count == 2
    }

    // 0x1B + snr/rsv header + channel + pathLen + data_type + data_len + payload
    let payload: [UInt8] = [
      0x1B,
      0x00, 0x00, 0x00,
      0x02, // channel 2
      0xFF, // path_len: 0xFF means direct route
      0xFF, 0xFF, // data_type 0xFFFF
      0x03, // data_len
      0xDE, 0xAD, 0xBE,
    ]
    await transport.simulateReceive(Data(payload))

    let result = try await task.value
    guard case let .channelDatagram(dg) = result else {
      Issue.record("Expected .channelDatagram, got \(result)")
      await session.stop()
      return
    }

    #expect(dg.channelIndex == 2)
    #expect(dg.dataType == 0xFFFF)
    #expect(dg.data == Data([0xDE, 0xAD, 0xBE]))

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

private func makeSelfInfoPacket() -> Data {
  var payload = Data([ResponseCode.selfInfo.rawValue])
  payload.append(1) // adv type
  payload.append(UInt8(bitPattern: 22)) // tx power
  payload.append(UInt8(bitPattern: 22)) // max tx power
  payload.append(Data(repeating: 0x01, count: 32)) // pubkey
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lat
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lon
  payload.append(0) // multi acks
  payload.append(0) // adv loc policy
  payload.append(0) // telemetry mode
  payload.append(0) // manual add
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(869_525).littleEndian) { Array($0) }) // freq
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) }) // bw
  payload.append(11) // sf
  payload.append(5) // cr
  return payload
}
