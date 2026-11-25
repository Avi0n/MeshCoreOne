import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("RxLogPathFraming")
struct RxLogPathFramingTests {
  @Test(arguments: [RouteType.direct, .tcDirect])
  func `direct text messages are remaining`(route: RouteType) {
    #expect(RxLogPathFraming(entry: makeDTO(routeType: route, payloadType: .textMessage)) == .remaining)
  }

  @Test
  func `a zero-hop direct advert is traversed`() {
    #expect(RxLogPathFraming(entry: makeDTO(routeType: .direct, payloadType: .advert)) == .traversed)
  }

  @Test
  func `trace never copies its SNR path bytes`() {
    let entry = makeDTO(
      routeType: .direct,
      payloadType: .trace,
      pathLength: 3,
      pathNodes: [0x1C, 0xF4, 0x08],
      packetPayload: Data(repeating: 0, count: 9)
    )
    #expect(RxLogPathFraming.trace.clipboardPath(for: entry) == nil)
  }

  @Test
  func `trace copies its target hashes`() {
    var payload = Data(repeating: 0, count: 9)
    payload.append(contentsOf: [0xAA, 0xBB])
    let entry = makeDTO(
      routeType: .direct,
      payloadType: .trace,
      pathLength: 1,
      pathNodes: [0x1C],
      packetPayload: payload
    )
    #expect(RxLogPathFraming.trace.clipboardPath(for: entry) == "AA,BB")
  }

  @Test
  func `traversed copies its path hops`() {
    let withHops = makeDTO(
      routeType: .flood,
      payloadType: .groupText,
      pathLength: 2,
      pathNodes: [0xAA, 0xBB]
    )
    #expect(RxLogPathFraming.traversed.clipboardPath(for: withHops) == "AA,BB")
  }

  private func makeDTO(
    routeType: RouteType,
    payloadType: PayloadType,
    pathLength: UInt8 = 0,
    pathNodes: [UInt8] = [],
    packetPayload: Data = Data()
  ) -> RxLogEntryDTO {
    let parsed = ParsedRxLogData(
      snr: nil,
      rssi: nil,
      rawPayload: packetPayload,
      routeType: routeType,
      payloadType: payloadType,
      payloadVersion: 0,
      payloadTypeBits: payloadType.rawValue & 0x0F,
      transportCode: nil,
      pathLength: pathLength,
      pathNodes: pathNodes,
      packetPayload: packetPayload
    )
    return RxLogEntryDTO(radioID: UUID(), from: parsed)
  }
}
