import Foundation
@testable import MeshCore
import Testing

/// Session-layer validation gates for the windowed channel-read pipeline:
/// #1 correctness with gaps, #3 no orphaned continuations, #4 drop-reconcile, plus the
/// window/refill bound and the capability fallback to serial reads.
@Suite("MeshCoreSession getChannels pipeline")
struct MeshCoreSessionGetChannelsTests {
  // MARK: - Gate #1: correctness with gaps

  @Test
  func `each requested index lands in its own slot and unrequested indexes are ignored`() async throws {
    let transport = MockTransport()
    await transport.setSupportsWriteWithoutResponse(true)
    let session = MeshCoreSession(
      transport: transport,
      configuration: pipelineConfig()
    )
    try await startSession(session, transport: transport)

    let task = Task { try await session.getChannels(indices: [0, 2, 7]) }

    try await waitUntil("all three requests should be primed") {
      await transport.sentData.count == 4 // appStart + 3 channel reads
    }

    // Respond out of order, plus an unrequested index that must be ignored.
    await transport.simulateReceive(makeChannelInfoPacket(index: 7, name: "seven", secret: Data(repeating: 0x77, count: 16)))
    await transport.simulateReceive(makeChannelInfoPacket(index: 2, name: "two", secret: Data(repeating: 0x22, count: 16)))
    await transport.simulateReceive(makeChannelInfoPacket(index: 5, name: "five", secret: Data(repeating: 0x55, count: 16)))
    await transport.simulateReceive(makeChannelInfoPacket(index: 0, name: "zero", secret: Data(repeating: 0x00, count: 16)))

    let result = try await task.value
    #expect(result.missing.isEmpty)
    #expect(result.received.map(\.index) == [0, 2, 7])
    #expect(result.received.first(where: { $0.index == 0 })?.name == "zero")
    #expect(result.received.first(where: { $0.index == 2 })?.name == "two")
    #expect(result.received.first(where: { $0.index == 7 })?.name == "seven")
    #expect(!result.received.contains(where: { $0.index == 5 }))
    await session.stop()
  }

  // MARK: - Gate #4: drop-reconcile (missing detection)

  @Test
  func `dropped writes surface as missing indexes after the idle timeout`() async throws {
    let transport = MockTransport()
    await transport.setSupportsWriteWithoutResponse(true)
    let session = MeshCoreSession(
      transport: transport,
      configuration: pipelineConfig(idleTimeout: 0.1)
    )
    try await startSession(session, transport: transport)

    let task = Task { try await session.getChannels(indices: [0, 1, 2, 3]) }

    try await waitUntil("all four requests should be primed") {
      await transport.sentData.count == 5
    }

    // Index 2 is never answered (simulated dropped write).
    await transport.simulateReceive(makeChannelInfoPacket(index: 0, name: "zero", secret: Data(repeating: 0, count: 16)))
    await transport.simulateReceive(makeChannelInfoPacket(index: 1, name: "one", secret: Data(repeating: 0, count: 16)))
    await transport.simulateReceive(makeChannelInfoPacket(index: 3, name: "three", secret: Data(repeating: 0, count: 16)))

    let result = try await task.value
    #expect(result.received.map(\.index) == [0, 1, 3])
    #expect(result.missing == [2])
    await session.stop()
  }

  // MARK: - Window / refill bound

  @Test
  func `no more than the window is outstanding before the first response, then refills`() async throws {
    let transport = MockTransport()
    await transport.setSupportsWriteWithoutResponse(true)
    let session = MeshCoreSession(
      transport: transport,
      configuration: pipelineConfig(window: 2)
    )
    try await startSession(session, transport: transport)

    let task = Task { try await session.getChannels(indices: [0, 1, 2, 3]) }

    // Only the window (2) should be primed before any response arrives.
    try await waitUntil("window should prime exactly 2 reads") {
      await transport.sentData.count == 3 // appStart + 2
    }
    try? await Task.sleep(for: .milliseconds(50))
    #expect(await transport.sentData.count == 3, "must not exceed the window before a response")

    // Each response refills one more request.
    await transport.simulateReceive(makeChannelInfoPacket(index: 0, name: "zero", secret: Data(repeating: 0, count: 16)))
    try await waitUntil("a response refills the next request") {
      await transport.sentData.count == 4
    }
    await transport.simulateReceive(makeChannelInfoPacket(index: 1, name: "one", secret: Data(repeating: 0, count: 16)))
    try await waitUntil("second response refills the last request") {
      await transport.sentData.count == 5
    }
    await transport.simulateReceive(makeChannelInfoPacket(index: 2, name: "two", secret: Data(repeating: 0, count: 16)))
    await transport.simulateReceive(makeChannelInfoPacket(index: 3, name: "three", secret: Data(repeating: 0, count: 16)))

    let result = try await task.value
    #expect(result.received.map(\.index) == [0, 1, 2, 3])
    #expect(result.missing.isEmpty)
    #expect(await transport.sentData.count == 5, "exactly appStart + 4 channel reads were issued")
    await session.stop()
  }

  // MARK: - Gate #3: no orphaned continuations

  @Test
  func `a cancelled getChannels does not leak a late channelInfo into a same-type successor`() async throws {
    let transport = MockTransport()
    await transport.setSupportsWriteWithoutResponse(true)
    let session = MeshCoreSession(
      transport: transport,
      configuration: pipelineConfig(idleTimeout: 0.1)
    )
    try await startSession(session, transport: transport)

    let channelsTask = Task { try await session.getChannels(indices: [0, 1]) }
    try await waitUntil("getChannels should prime its reads") {
      await transport.sentData.count == 3
    }

    channelsTask.cancel()
    _ = try? await channelsTask.value

    // Orphan frame for the cancelled pipeline. The successor is getChannel(index: 0), whose
    // matcher accepts any channelInfo(index: 0) — the same response type the pipeline reads —
    // so a leaked orphan would surface as the successor's result. getBattery (the weaker prior
    // successor) ignores channelInfo and so could never observe such a leak.
    await transport.simulateReceive(makeChannelInfoPacket(index: 0, name: "late", secret: Data(repeating: 0xAA, count: 16)))

    let nextTask = Task { try await session.getChannel(index: 0) }
    try await waitUntil("getChannel should send only after the pipeline releases the slot") {
      await transport.sentData.count == 4
    }
    await transport.simulateReceive(makeChannelInfoPacket(index: 0, name: "fresh", secret: Data(repeating: 0xBB, count: 16)))

    // The successor must see its own fresh response — the orphaned "late" frame was drained
    // under the pipeline's held serializer slot, not handed to the next command.
    let next = try await nextTask.value
    #expect(next.index == 0)
    #expect(next.name == "fresh")
    await session.stop()
  }

  // MARK: - Capability fallback

  @Test
  func `falls back to serial reads when the transport lacks write-without-response`() async throws {
    let transport = MockTransport() // default: supportsWriteWithoutResponse == false
    let session = MeshCoreSession(
      transport: transport,
      configuration: pipelineConfig()
    )
    try await startSession(session, transport: transport)

    let task = Task { try await session.getChannels(indices: [0, 1]) }

    // Serial path issues one read, waits for its response, then the next.
    try await waitUntil("first serial read should be sent") {
      await transport.sentData.count == 2
    }
    await transport.simulateReceive(makeChannelInfoPacket(index: 0, name: "zero", secret: Data(repeating: 0, count: 16)))
    try await waitUntil("second serial read should be sent after the first responds") {
      await transport.sentData.count == 3
    }
    await transport.simulateReceive(makeChannelInfoPacket(index: 1, name: "one", secret: Data(repeating: 0, count: 16)))

    let result = try await task.value
    #expect(result.received.map(\.index) == [0, 1])
    #expect(result.missing.isEmpty)
    await session.stop()
  }
}

// MARK: - Helpers

private func pipelineConfig(
  window: Int = 8,
  idleTimeout: TimeInterval = 1.5
) -> SessionConfiguration {
  SessionConfiguration(
    defaultTimeout: 10,
    clientIdentifier: "MCTst",
    channelPipelineWindow: window,
    channelPipelineIdleTimeout: idleTimeout,
    channelPipelineHardTimeout: 5.0,
    channelPipelinePostDrainGrace: 0.02
  )
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
  var payload = Data()
  payload.append(1)
  payload.append(UInt8(bitPattern: 22))
  payload.append(UInt8(bitPattern: 22))
  payload.append(Data(repeating: 0x01, count: 32))
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
  payload.append(0)
  payload.append(0)
  payload.append(0)
  payload.append(0)
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
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
