import Foundation
@testable import MC1Services
import Testing

@Suite("ConnectionMethod Tests")
struct ConnectionMethodTests {
  @Test
  func `ConnectionMethod decodes the frozen synthesized wire shape (guards against silent rename)`() throws {
    let decoder = JSONDecoder()

    let wifiJSON = #"{"wifi":{"displayName":"Field Radio","host":"192.168.1.50","port":5000}}"#
    let wifi = try decoder.decode(ConnectionMethod.self, from: Data(wifiJSON.utf8))
    guard case let .wifi(host, port, displayName) = wifi else {
      Issue.record("expected .wifi, got \(wifi)"); return
    }
    #expect(host == "192.168.1.50")
    #expect(port == 5000)
    #expect(displayName == "Field Radio")

    let bleJSON = #"{"bluetooth":{"displayName":null,"peripheralUUID":"E621E1F8-C36C-495A-93FC-0C247A3E6E5F"}}"#
    let ble = try decoder.decode(ConnectionMethod.self, from: Data(bleJSON.utf8))
    guard case let .bluetooth(uuid, name) = ble else {
      Issue.record("expected .bluetooth, got \(ble)"); return
    }
    #expect(uuid == UUID(uuidString: "E621E1F8-C36C-495A-93FC-0C247A3E6E5F"))
    #expect(name == nil)
  }

  @Test
  func `ConnectionMethod round-trips through the backup encoder`() throws {
    let methods: [ConnectionMethod] = [
      .wifi(host: "10.0.0.2", port: 4403, displayName: nil),
      .bluetooth(peripheralUUID: UUID(), displayName: "Pocket")
    ]
    let data = try makeBackupJSONEncoder().encode(methods)
    let decoded = try makeBackupJSONDecoder().decode([ConnectionMethod].self, from: data)
    #expect(decoded == methods)
  }

  @Test
  func `ConnectionMethod ENCODES to the frozen synthesized wire shape (pins the write shape)`() throws {
    // Decode-of-frozen-input and the symmetric round-trip do not pin the encoder's output:
    // a synthesized-Codable change that altered encode and decode in lockstep would pass
    // both while still bricking files written by the current app. This asserts the produced
    // JSON. makeBackupJSONEncoder() uses .sortedKeys, so the literal is key-stable.
    let wifi: ConnectionMethod = .wifi(host: "192.168.1.50", port: 5000, displayName: "Field Radio")
    let json = try String(decoding: makeBackupJSONEncoder().encode(wifi), as: UTF8.self)
    #expect(json == #"{"wifi":{"displayName":"Field Radio","host":"192.168.1.50","port":5000}}"#)
  }

  @Test
  func `Bluetooth method has correct identifier`() {
    let uuid = UUID()
    let method = ConnectionMethod.bluetooth(peripheralUUID: uuid, displayName: "My Device")

    #expect(method.id == "ble:\(uuid.uuidString)")
  }

  @Test
  func `WiFi method has correct identifier`() {
    let method = ConnectionMethod.wifi(host: "192.168.1.50", port: 5000, displayName: "Home")

    #expect(method.id == "wifi:192.168.1.50:5000")
  }

  @Test
  func `Codable round-trip for Bluetooth`() throws {
    let uuid = UUID()
    let original = ConnectionMethod.bluetooth(peripheralUUID: uuid, displayName: "Test")

    let encoded = try JSONEncoder().encode(original)
    let decoded = try JSONDecoder().decode(ConnectionMethod.self, from: encoded)

    #expect(decoded.id == original.id)
  }

  @Test
  func `Codable round-trip for WiFi`() throws {
    let original = ConnectionMethod.wifi(host: "10.0.0.1", port: 8080, displayName: nil)

    let encoded = try JSONEncoder().encode(original)
    let decoded = try JSONDecoder().decode(ConnectionMethod.self, from: encoded)

    #expect(decoded.id == original.id)
  }

  @Test
  func `Display name returns custom name when set`() {
    let method = ConnectionMethod.wifi(host: "192.168.1.1", port: 5000, displayName: "Office Router")
    #expect(method.displayName == "Office Router")
  }

  @Test
  func `Display name returns nil when not set`() {
    let method = ConnectionMethod.wifi(host: "192.168.1.1", port: 5000, displayName: nil)
    #expect(method.displayName == nil)
  }
}
