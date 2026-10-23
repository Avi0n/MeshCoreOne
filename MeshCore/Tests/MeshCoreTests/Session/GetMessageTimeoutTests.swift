import Foundation
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("MeshCoreSession getMessage timeout", .timeLimit(.minutes(1)))
struct GetMessageTimeoutTests {
  @Test(arguments: [
    (defaultTimeout: 2.0, override: nil as Double?),
    (defaultTimeout: 20.0, override: 5.0),
    (defaultTimeout: 2.0, override: 6.0)
  ])
  func `getMessage uses the default timeout or its override`(
    _ timeouts: (defaultTimeout: Double, override: Double?)
  ) async throws {
    let transport = MockTransport()
    let clock = TestClock()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: timeouts.defaultTimeout, clientIdentifier: "MeshCore-Tests"),
      clock: clock
    )
    do {
      let startTask = Task { try await session.start() }
      try await waitUntil("transport should send appStart") {
        await transport.sentData.count == 1
      }
      await transport.simulateReceive(makeSelfInfoPacket())
      try await startTask.value
      try #require(clock.sleeperCount == 0)

      let request = Task { try await session.getMessage(timeout: timeouts.override) }
      try await waitUntil("getMessage timeout should park on the injected clock") {
        clock.sleeperCount == 1
      }
      let expected = Duration.seconds(timeouts.override ?? timeouts.defaultTimeout)
      let boundaryStep = Duration.milliseconds(1)
      clock.advance(by: expected - boundaryStep)
      try #require(clock.sleeperCount == 1, "getMessage must not time out before its deadline")
      clock.advance(by: boundaryStep)
      try #require(clock.sleeperCount == 0, "getMessage must time out at its deadline")

      await #expect {
        _ = try await request.value
      } throws: { error in
        guard let error = error as? MeshCoreError, case .timeout = error else { return false }
        return true
      }
    } catch {
      await session.stop()
      throw error
    }
    await session.stop()
  }
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
