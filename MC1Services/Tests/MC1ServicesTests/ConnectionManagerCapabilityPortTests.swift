import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Capability result retirement ownership")
@MainActor
struct ConnectionManagerCapabilityPortTests {
  @Test(.timeLimit(.minutes(1)))
  func `late capabilities drain before the same-radio successor sets its platform and pacing`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let saved = DeviceDTO.testDevice(id: env.deviceID)
    try await env.manager.persistenceStore.saveDevice(saved)
    await env.ble.setResponsePackets(for: .deviceQuery, packets: [capabilities(model: "T1000-E")])
    await env.ble.holdReplies(for: .deviceQuery)
    let old = Task { try await env.connect(.bluetooth) }
    var replacement: Task<ConnectionResult, Error>?
    do {
      try await waitUntil("the old capability reply must be held after the actual query") {
        await env.ble.heldReplyCount(for: .deviceQuery) == 1
      }
      let request = try #require(env.manager.desiredRequest)
      let resource = try #require(request.attempt?.resource)
      await env.ble.setResponsePackets(for: .deviceQuery, packets: [capabilities(model: "Heltec V3")])
      replacement = Task { try await env.connect(.bluetooth) }
      try await waitUntil("the same-radio successor must revoke the old attempt") {
        env.manager.connectionRequestID != request.id
      }
      #expect(!resource.binding.isActive)
      #expect(env.manager.currentConnection == nil)
      #expect(env.manager.detectedPlatform == .unknown)
      #expect(await env.ble.connectCount == 1)
      await env.ble.releaseReplies(for: .deviceQuery)
      _ = await old.result
      let result = try await #require(replacement).value
      #expect(env.manager.isCurrent(result))
      let current = try #require(env.manager.currentConnection)
      #expect(current.device.id == env.deviceID)
      #expect(current.device.radioID == saved.radioID)
      #expect(current.device.manufacturerName == "Heltec V3")
      #expect(env.manager.detectedPlatform == .esp32)
      #expect(await env.stateMachine.writePacingDelay == DevicePlatform.esp32.recommendedWritePacing)
      #expect(await env.ble.connectCount == 2)
      #expect(await resource.session.subscriberCountForTest == 0)
      #expect(try await current.services.session.getTime().timeIntervalSince1970 > 0)
    } catch {
      old.cancel()
      replacement?.cancel()
      await env.ble.releaseAllGates()
      _ = await old.result
      _ = await replacement?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  private func capabilities(model: String) -> Data {
    let modelOffset = 20
    var packet = Data(repeating: 0, count: 80)
    packet[0] = ResponseCode.deviceInfo.rawValue
    packet[1] = 14
    packet[2] = 1
    packet[3] = 1
    let bytes = Data(model.utf8.prefix(40))
    packet.replaceSubrange(modelOffset..<(modelOffset + bytes.count), with: bytes)
    return packet
  }
}
