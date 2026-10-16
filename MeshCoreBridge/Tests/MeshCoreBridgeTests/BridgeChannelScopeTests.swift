import Foundation
import MeshCore
@testable import MeshCoreBridgeKit
import Testing

struct BridgeChannelScopeTests {
  @Test(arguments: ["region", "disabled", "unscoped"])
  func `scope and channel send share a response slot`(selector: String) async throws {
    let (bridge, recorder) = try await connectedBridge()
    bridge.sendChannelMessageWithScope(
      requestID: 2, channel: 1, text: "Scoped", timestampEpoch: 1_700_000_000,
      scopeType: selector, scopeText: "Europe"
    )
    #expect(await recorder.wait { recorder.sends.count == 2 })
    let expected = switch selector {
    case "region": PacketBuilder.setFloodScope(FloodScope.region("Europe").scopeKey())
    case "disabled": PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey())
    default: PacketBuilder.setFloodScopeUnscoped()
    }
    #expect(recorder.sends[1] == Array(expected))
    bridge.setTime(requestID: 3, timestampEpoch: 1_700_000_001)
    bridge.receiveBytes([ResponseCode.ok.rawValue])
    #expect(await recorder.wait { recorder.sends.count == 3 })
    #expect(recorder.sends[2] == Array(PacketBuilder.sendChannelMessage(
      channel: 1, text: "Scoped", timestamp: Date(timeIntervalSince1970: 1_700_000_000)
    )))
    bridge.receiveBytes([ResponseCode.ok.rawValue])
    #expect(await recorder.wait { recorder.successes.contains { $0.id == 2 } && recorder.sends.count == 4 })
    let result = try #require(recorder.successes.first { $0.id == 2 })
    try GoldenFixtureTests.compareOrRegenerate(
      result.bytes, file: GoldenFixtureTests.fixturesDir.appendingPathComponent("results/ok.json")
    )
    #expect(recorder.sends[3].first == CommandCode.setTime.rawValue)
    bridge.receiveBytes([ResponseCode.ok.rawValue])
    #expect(await recorder.wait { recorder.successes.contains { $0.id == 3 } })
  }

  @Test
  func `rejected scope prevents the channel write`() async throws {
    let (bridge, recorder) = try await connectedBridge()
    bridge.sendChannelMessageWithScope(
      requestID: 2, channel: 1, text: "Scoped", timestampEpoch: 1_700_000_000,
      scopeType: "region", scopeText: "Europe"
    )
    #expect(await recorder.wait { recorder.sends.count == 2 })
    bridge.receiveBytes([ResponseCode.error.rawValue, ErrorCode.unsupportedCommand.rawValue])
    #expect(await recorder.wait { recorder.failures.contains { $0.id == 2 } })
    #expect(recorder.failures.first { $0.id == 2 }?.code == BridgeErrorCode.deviceError.rawValue)
    #expect(recorder.sends.count == 2)
  }

  @Test
  func `unknown resolved scope fails without a write`() async throws {
    let (bridge, recorder) = try await connectedBridge()
    bridge.sendChannelMessageWithScope(
      requestID: 2, channel: 1, text: "Scoped", timestampEpoch: 1_700_000_000,
      scopeType: "unknown", scopeText: "Europe"
    )
    #expect(await recorder.wait { recorder.failures.contains { $0.id == 2 } })
    #expect(recorder.failures.first { $0.id == 2 }?.code == BridgeErrorCode.invalidInput.rawValue)
    #expect(recorder.sends.count == 1)
  }

  @Test(arguments: [true, false])
  func `cancelled sends drain the active response without writing the message`(scopeWasSent: Bool) async throws {
    let (bridge, recorder) = try await connectedBridge()
    if !scopeWasSent {
      bridge.setTime(requestID: 2, timestampEpoch: 1_700_000_000)
      #expect(await recorder.wait { recorder.sends.count == 2 })
    }
    bridge.sendChannelMessageWithScope(
      requestID: 3, channel: 1, text: "Cancelled", timestampEpoch: 1_700_000_000,
      scopeType: "region", scopeText: "Europe"
    )
    if scopeWasSent { #expect(await recorder.wait { recorder.sends.count == 2 }) }
    bridge.cancelRequest(requestID: 3)
    bridge.setTime(requestID: 4, timestampEpoch: 1_700_000_001)
    bridge.receiveBytes([ResponseCode.ok.rawValue])
    #expect(await recorder.wait { recorder.sends.count == 3 })
    #expect(recorder.sends[2].first == CommandCode.setTime.rawValue)
    bridge.receiveBytes([ResponseCode.ok.rawValue])
    #expect(await recorder.wait { recorder.successes.contains { $0.id == 4 } })
    #expect(await recorder.wait { recorder.failures.contains { $0.id == 3 } })
    #expect(recorder.sends.count == 3)
  }

  private func connectedBridge() async throws -> (MeshCoreBridge, BridgeRecorder) {
    let recorder = BridgeRecorder()
    let bridge = MeshCoreBridge(
      sendListener: recorder, resultListener: recorder, eventListener: recorder, logListener: recorder
    )
    bridge.startSession(requestID: 1)
    bridge.reportConnected()
    #expect(await recorder.wait { recorder.sends.count == 1 })
    var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    packet.append(Data(repeating: 0x01, count: 32))
    packet.append(Data(repeating: 0, count: 12))
    for value: UInt32 in [915_000, 125_000] {
      packet.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
    }
    packet.append(contentsOf: [7, 5])
    packet.append(contentsOf: "Test".utf8)
    bridge.receiveBytes(Array(packet))
    #expect(await recorder.wait { recorder.successes.contains { $0.id == 1 } })
    return (bridge, recorder)
  }
}
