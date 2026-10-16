import Foundation
import MeshCore
@testable import MeshCoreBridgeKit
import Testing

struct BridgeDisconnectedCommandTests {
  @Test
  func `a command before connect reports notConnected without writing a frame`() async throws {
    let recorder = BridgeRecorder()
    let bridge = MeshCoreBridge(
      sendListener: recorder,
      resultListener: recorder,
      eventListener: recorder,
      logListener: recorder
    )
    bridge.getBattery(requestID: 1)
    #expect(await recorder.wait { recorder.failures.contains { $0.id == 1 } })
    let failure = try #require(recorder.failures.first { $0.id == 1 })
    #expect(failure.code == BridgeErrorCode.notConnected.rawValue)
    #expect(recorder.sends.isEmpty)
  }

  @Test
  func `a command after stop reports notConnected without writing a frame`() async throws {
    let recorder = BridgeRecorder()
    let bridge = MeshCoreBridge(
      sendListener: recorder,
      resultListener: recorder,
      eventListener: recorder,
      logListener: recorder
    )
    bridge.startSession(requestID: 1)
    bridge.reportConnected()
    #expect(await recorder.wait { !recorder.sends.isEmpty })
    bridge.receiveBytes(Self.selfInfoPacket())
    #expect(await recorder.wait { recorder.successes.contains { $0.id == 1 } })

    bridge.stopSession(requestID: 2)
    #expect(await recorder.wait { recorder.successes.contains { $0.id == 2 } })
    let sentCount = recorder.sends.count
    bridge.getBattery(requestID: 3)
    #expect(await recorder.wait { recorder.failures.contains { $0.id == 3 } })
    let failure = try #require(recorder.failures.first { $0.id == 3 })
    #expect(failure.code == BridgeErrorCode.notConnected.rawValue)
    #expect(recorder.sends.count == sentCount)
  }

  private static func selfInfoPacket() -> [UInt8] {
    var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    packet.append(Data(repeating: 0x01, count: 32))
    packet.append(Data(repeating: 0, count: 12))
    for value: UInt32 in [915_000, 125_000] {
      packet.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
    }
    packet.append(contentsOf: [7, 5])
    packet.append(contentsOf: "Test".utf8)
    return Array(packet)
  }
}
