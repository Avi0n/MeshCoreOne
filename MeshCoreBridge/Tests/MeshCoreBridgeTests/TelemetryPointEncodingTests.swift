import Foundation
import MeshCore
import Testing
@testable import MeshCoreBridgeKit

/// The decoded LPP readings the bridge puts on the boundary.
///
/// MeshCore's `LPPDecoder` is the only Cayenne LPP parser in the product, so the
/// consumer receives values rather than bytes. These expectations are written
/// from the LPP specification and the sensor scale factors, not from any
/// consumer, so they fail if the encoder ever ships a value the wire does not
/// mean.
struct TelemetryPointEncodingTests {
  @Test("every sensor type in the fixture frame encodes to its specified value")
  func everySensorTypeEncodes() {
    let encoded = LPPDecoder.decode(BridgeFixtures.telemetryFrame)
      .map { PayloadEncoder.lppDataPoint($0).serializedString() }
    #expect(encoded.count == LPPSensorType.allCases.count)
    #expect(encoded == Self.expectedPoints)
  }

  @Test("the telemetry payload carries decoded points alongside the raw frame")
  func payloadCarriesPoints() throws {
    let payload = PayloadEncoder.telemetryResponse(
      TelemetryResponse(publicKeyPrefix: Data([0xA0]), tag: nil, rawData: BridgeFixtures.telemetryFrame)
    )
    let object = try #require(
      JSONSerialization.jsonObject(with: Data(payload.serialized())) as? [String: Any]
    )
    let points = try #require(object["points"] as? [[String: Any]])
    #expect(points.count == LPPSensorType.allCases.count)
    #expect(object["rawData"] as? String == BridgeFixtures.telemetryFrame.base64EncodedString())
  }

  /// The four-byte LPP time is unsigned. A signed 32-bit reader reports the
  /// first of these as December 1901 and the second as December 1969.
  @Test(
    "a unix time past January 2038 keeps its unsigned value",
    arguments: [
      (Data([0x80, 0x00, 0x00, 0x00]), Int64(2_147_483_648)), // 19 Jan 2038 03:14:08 UTC
      (Data([0xFF, 0xFF, 0xFF, 0xFF]), Int64(4_294_967_295)) // 7 Feb 2106 06:28:15 UTC
    ]
  )
  func unixTimeStaysUnsigned(bytes: Data, expected: Int64) throws {
    let frame = Data([0x01, 133]) + bytes
    let point = try #require(LPPDecoder.decode(frame).first)
    #expect(
      PayloadEncoder.lppDataPoint(point).serializedString() ==
        #"{"channel":1,"typeRawValue":133,"value":{"kind":"timestamp","value":\#(expected)}}"#
    )
  }

  /// One expected encoding per reading in `BridgeFixtures.telemetryFrame`, in
  /// frame order. Object keys are lexicographically sorted, so `gps` reads
  /// altitude-first and `rgb` blue-first.
  private static let expectedPoints = [
    #"{"channel":1,"typeRawValue":0,"value":{"kind":"digital","value":true}}"#,
    #"{"channel":1,"typeRawValue":1,"value":{"kind":"digital","value":false}}"#,
    #"{"channel":1,"typeRawValue":2,"value":{"kind":"float","value":-4.0}}"#,
    #"{"channel":1,"typeRawValue":3,"value":{"kind":"float","value":4.0}}"#,
    #"{"channel":1,"typeRawValue":100,"value":{"kind":"integer","value":4294967295}}"#,
    #"{"channel":1,"typeRawValue":101,"value":{"kind":"integer","value":1000}}"#,
    #"{"channel":1,"typeRawValue":102,"value":{"kind":"digital","value":true}}"#,
    #"{"channel":1,"typeRawValue":103,"value":{"kind":"float","value":-20.0}}"#,
    #"{"channel":1,"typeRawValue":104,"value":{"kind":"float","value":50.0}}"#,
    #"{"channel":1,"typeRawValue":113,"value":{"kind":"vector3","x":1.0,"y":-1.0,"z":0.0}}"#,
    #"{"channel":1,"typeRawValue":115,"value":{"kind":"float","value":1000.0}}"#,
    #"{"channel":1,"typeRawValue":116,"value":{"kind":"float","value":3.99}}"#,
    #"{"channel":1,"typeRawValue":117,"value":{"kind":"float","value":-0.1}}"#,
    #"{"channel":1,"typeRawValue":118,"value":{"kind":"integer","value":921600000}}"#,
    #"{"channel":1,"typeRawValue":120,"value":{"kind":"integer","value":85}}"#,
    #"{"channel":1,"typeRawValue":121,"value":{"kind":"float","value":-60.0}}"#,
    #"{"channel":1,"typeRawValue":122,"value":{"kind":"float","value":-3.907}}"#,
    #"{"channel":1,"typeRawValue":125,"value":{"kind":"integer","value":500}}"#,
    #"{"channel":1,"typeRawValue":128,"value":{"kind":"integer","value":100}}"#,
    #"{"channel":1,"typeRawValue":130,"value":{"kind":"float","value":100.0}}"#,
    #"{"channel":1,"typeRawValue":131,"value":{"kind":"float","value":10.0}}"#,
    #"{"channel":1,"typeRawValue":132,"value":{"kind":"integer","value":180}}"#,
    #"{"channel":1,"typeRawValue":133,"value":{"kind":"timestamp","value":2147483648}}"#,
    #"{"channel":1,"typeRawValue":134,"value":{"kind":"vector3","x":1.0,"y":-1.0,"z":0.0}}"#,
    #"{"channel":1,"typeRawValue":135,"value":{"blue":0,"green":128,"kind":"rgb","red":255}}"#,
    #"{"channel":1,"typeRawValue":136,"value":{"altitude":123.45,"kind":"gps","latitude":52.3456,"longitude":-4.5678}}"#,
    #"{"channel":1,"typeRawValue":142,"value":{"kind":"digital","value":true}}"#
  ]
}
