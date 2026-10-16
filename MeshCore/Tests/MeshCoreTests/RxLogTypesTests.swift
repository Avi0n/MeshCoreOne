import Foundation
@testable import MeshCore
import Testing

@Suite("RxLogTypes")
struct RxLogTypesTests {
  @Test
  func `RouteType raw values match protocol spec`() {
    #expect(RouteType.tcFlood.rawValue == 0)
    #expect(RouteType.flood.rawValue == 1)
    #expect(RouteType.direct.rawValue == 2)
    #expect(RouteType.tcDirect.rawValue == 3)
  }

  @Test
  func `RouteType hasTransportCode`() {
    #expect(RouteType.tcFlood.hasTransportCode == true)
    #expect(RouteType.flood.hasTransportCode == false)
    #expect(RouteType.direct.hasTransportCode == false)
    #expect(RouteType.tcDirect.hasTransportCode == true)
  }

  @Test
  func `PayloadType raw values match protocol spec`() {
    #expect(PayloadType.request.rawValue == 0)
    #expect(PayloadType.groupText.rawValue == 5)
    #expect(PayloadType.control.rawValue == 11)
    #expect(PayloadType.unknown.rawValue == 255)
  }

  @Test
  func `Reserved PayloadType values 12-14 map to unknown via fromBits`() {
    #expect(PayloadType(fromBits: 12) == .unknown)
    #expect(PayloadType(fromBits: 13) == .unknown)
    #expect(PayloadType(fromBits: 14) == .unknown)
  }

  @Test
  func `PayloadType value 15 maps to rawCustom via fromBits`() {
    #expect(PayloadType(fromBits: 15) == .rawCustom)
  }

  @Test
  func `PayloadType rawValue initializer returns nil for undefined values`() {
    #expect(PayloadType(rawValue: 12) == nil)
    #expect(PayloadType(rawValue: 255) == .unknown)
  }

  @Test
  func `PayloadType fromBits with valid values`() {
    #expect(PayloadType(fromBits: 0) == .request)
    #expect(PayloadType(fromBits: 5) == .groupText)
    #expect(PayloadType(fromBits: 11) == .control)
  }

  @Test
  func `RouteType displayName`() {
    #expect(RouteType.tcFlood.displayName == "TC_FLOOD")
    #expect(RouteType.flood.displayName == "FLOOD")
    #expect(RouteType.direct.displayName == "DIRECT")
    #expect(RouteType.tcDirect.displayName == "TC_DIRECT")
  }

  @Test
  func `PayloadType displayName`() {
    #expect(PayloadType.request.displayName == "REQUEST")
    #expect(PayloadType.groupText.displayName == "GROUP_TEXT")
    #expect(PayloadType.unknown.displayName == "UNKNOWN")
  }

  @Test
  func `ParsedRxLogData initializes with all fields`() {
    let data = ParsedRxLogData(
      snr: 8.5,
      rssi: -85,
      rawPayload: Data([0x01, 0x02, 0x03]),
      routeType: .flood,
      payloadType: .groupText,
      payloadVersion: 1,
      payloadTypeBits: 5,
      transportCode: nil,
      pathLength: 2,
      pathNodes: [0x3A, 0x7F],
      packetPayload: Data([0xAA, 0xBB])
    )

    #expect(data.snr == 8.5)
    #expect(data.rssi == -85)
    #expect(data.routeType == .flood)
    #expect(data.payloadType == .groupText)
    #expect(data.payloadVersion == 1)
    #expect(data.transportCode == nil)
    #expect(data.pathLength == 2)
    #expect(data.pathNodes == [0x3A, 0x7F])
    #expect(data.packetHash.count == 16) // 8 bytes as hex
  }

  @Test
  func `ParsedRxLogData packetHash is stable`() {
    let payload = Data([0xAA, 0xBB, 0xCC])
    let data1 = ParsedRxLogData(
      snr: nil, rssi: nil, rawPayload: Data(),
      routeType: .flood, payloadType: .groupText, payloadVersion: 0,
      payloadTypeBits: 5,
      transportCode: nil, pathLength: 0, pathNodes: [],
      packetPayload: payload
    )
    let data2 = ParsedRxLogData(
      snr: 5.0, rssi: -90, rawPayload: Data([0xFF]),
      routeType: .direct, payloadType: .ack, payloadVersion: 2,
      payloadTypeBits: 3,
      transportCode: Data([0x01, 0x02, 0x03, 0x04]), pathLength: 3, pathNodes: [0x11, 0x22, 0x33],
      packetPayload: payload // Same payload
    )

    // Same packetPayload should produce same hash regardless of other fields
    #expect(data1.packetHash == data2.packetHash)
  }
}
