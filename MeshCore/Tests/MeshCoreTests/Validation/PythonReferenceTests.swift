import Foundation
@testable import MeshCore
import Testing

/// Tests that verify Swift PacketBuilder produces identical bytes to Python meshcore_py.
///
/// These tests compare Swift-generated packets against reference bytes extracted from
/// the Python meshcore_py library, ensuring byte-level protocol compatibility.
@Suite("Python Reference")
struct PythonReferenceTests {
  // MARK: - Device Commands

  @Test
  func `appStart matches Python`() {
    // Python: b"\x01\x03" + 6 spaces + "MCore" (per firmware, name at byte 8)
    let packet = PacketBuilder.appStart(clientId: "MCore")
    #expect(packet == PythonReferenceBytes.appStart,
            "appStart mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.appStart.hexString)")
  }

  @Test
  func `deviceQuery matches Python`() {
    // Python: b"\x16\x03"
    let packet = PacketBuilder.deviceQuery()
    #expect(packet == PythonReferenceBytes.deviceQuery)
  }

  @Test
  func `getBattery matches Python`() {
    // Python: b"\x14"
    let packet = PacketBuilder.getBattery()
    #expect(packet == PythonReferenceBytes.getBattery)
  }

  @Test
  func `getTime matches Python`() {
    // Python: b"\x05"
    let packet = PacketBuilder.getTime()
    #expect(packet == PythonReferenceBytes.getTime)
  }

  @Test
  func `setTime matches Python`() {
    // Python: b"\x06" + timestamp.to_bytes(4, "little")
    let date = Date(timeIntervalSince1970: 1_704_067_200)
    let packet = PacketBuilder.setTime(date)
    #expect(packet == PythonReferenceBytes.setTime_1704067200,
            "setTime mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.setTime_1704067200.hexString)")
  }

  @Test
  func `setName matches Python`() {
    // Python: b"\x08" + name.encode("utf-8")
    let packet = PacketBuilder.setName("TestNode")
    #expect(packet == PythonReferenceBytes.setName_TestNode)
  }

  @Test
  func `setCoordinates matches Python`() {
    // Python: lat/lon * 1e6 as signed little-endian int32
    let packet = PacketBuilder.setCoordinates(latitude: 37.7749, longitude: -122.4194)
    #expect(packet == PythonReferenceBytes.setCoords_SF,
            "setCoords mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.setCoords_SF.hexString)")
  }

  @Test
  func `setTxPower matches Python`() {
    // Python: b"\x0c" + power.to_bytes(4, "little")
    let packet = PacketBuilder.setTxPower(20)
    #expect(packet == PythonReferenceBytes.setTxPower_20)
  }

  @Test
  func `setRadio matches Python`() {
    // Python: freq/bw * 1000 as uint32 LE, sf/cr as uint8
    let packet = PacketBuilder.setRadio(
      frequency: 906.875,
      bandwidth: 250.0,
      spreadingFactor: 11,
      codingRate: 8
    )
    #expect(packet == PythonReferenceBytes.setRadio_default,
            "setRadio mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.setRadio_default.hexString)")
  }

  @Test
  func `sendAdvertisement matches Python`() {
    // Python: b"\x07" or b"\x07\x01" for flood
    #expect(PacketBuilder.sendAdvertisement(flood: false) == PythonReferenceBytes.sendAdvertisement)
    #expect(PacketBuilder.sendAdvertisement(flood: true) == PythonReferenceBytes.sendAdvertisement_flood)
  }

  @Test
  func `reboot matches Python`() {
    // Python: b"\x13reboot"
    let packet = PacketBuilder.reboot()
    #expect(packet == PythonReferenceBytes.reboot)
  }

  // MARK: - Contact Commands

  @Test
  func `getContacts matches Python`() {
    let packet = PacketBuilder.getContacts()
    #expect(packet == PythonReferenceBytes.getContacts)
  }

  // MARK: - Messaging Commands

  @Test
  func `getMessage matches Python`() {
    let packet = PacketBuilder.getMessage()
    #expect(packet == PythonReferenceBytes.getMessage)
  }

  @Test
  func `sendMessage matches Python`() {
    // Python: b"\x02\x00" + attempt + timestamp(4LE) + dst(6) + msg
    let dst = Data([0x01, 0x23, 0x45, 0x67, 0x89, 0xAB])
    let timestamp = Date(timeIntervalSince1970: 1_704_067_200)
    let packet = PacketBuilder.sendMessage(
      to: dst,
      text: "Hello",
      timestamp: timestamp,
      attempt: 0
    )
    #expect(packet == PythonReferenceBytes.sendMessage_Hello,
            "sendMessage mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.sendMessage_Hello.hexString)")
  }

  @Test
  func `sendCommand matches Python`() {
    let dst = Data([0x01, 0x23, 0x45, 0x67, 0x89, 0xAB])
    let timestamp = Date(timeIntervalSince1970: 1_704_067_200)
    let packet = PacketBuilder.sendCommand(
      to: dst,
      command: "status",
      timestamp: timestamp
    )
    #expect(packet == PythonReferenceBytes.sendCommand_status,
            "sendCommand mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.sendCommand_status.hexString)")
  }

  @Test
  func `sendChannelMessage matches Python`() {
    let timestamp = Date(timeIntervalSince1970: 1_704_067_200)
    let packet = PacketBuilder.sendChannelMessage(
      channel: 0,
      text: "Hi",
      timestamp: timestamp
    )
    #expect(packet == PythonReferenceBytes.sendChannelMessage_0_Hi,
            "sendChannelMessage mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.sendChannelMessage_0_Hi.hexString)")
  }

  @Test
  func `sendLogin matches Python`() {
    // Python: b"\x1A" + dst(32) + password
    let dst = Data([0x01, 0x23, 0x45, 0x67, 0x89, 0xAB]) + Data(repeating: 0, count: 26)
    let packet = PacketBuilder.sendLogin(to: dst, password: "secret")
    #expect(packet == PythonReferenceBytes.sendLogin,
            "sendLogin mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.sendLogin.hexString)")
  }

  @Test
  func `sendLogout matches Python`() {
    // Python: b"\x1D" + dst(32)
    let dst = Data([0x01, 0x23, 0x45, 0x67, 0x89, 0xAB]) + Data(repeating: 0, count: 26)
    let packet = PacketBuilder.sendLogout(to: dst)
    #expect(packet == PythonReferenceBytes.sendLogout,
            "sendLogout mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.sendLogout.hexString)")
  }

  @Test
  func `sendStatusRequest matches Python`() {
    // Python: b"\x1B" + dst(32)
    let dst = Data([0x01, 0x23, 0x45, 0x67, 0x89, 0xAB]) + Data(repeating: 0, count: 26)
    let packet = PacketBuilder.sendStatusRequest(to: dst)
    #expect(packet == PythonReferenceBytes.sendStatusRequest,
            "sendStatusRequest mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.sendStatusRequest.hexString)")
  }

  // MARK: - Channel Commands

  @Test
  func `getChannel matches Python`() {
    let packet = PacketBuilder.getChannel(index: 0)
    #expect(packet == PythonReferenceBytes.getChannel_0)
  }

  @Test
  func `setChannel matches Python`() {
    let secret = Data(0..<16)
    let packet = PacketBuilder.setChannel(index: 0, name: "General", secret: secret)
    #expect(packet == PythonReferenceBytes.setChannel_0_General,
            "setChannel mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.setChannel_0_General.hexString)")
  }

  // MARK: - Stats Commands

  @Test
  func `getStats matches Python`() {
    #expect(PacketBuilder.getStatsCore() == PythonReferenceBytes.getStatsCore)
    #expect(PacketBuilder.getStatsRadio() == PythonReferenceBytes.getStatsRadio)
    #expect(PacketBuilder.getStatsPackets() == PythonReferenceBytes.getStatsPackets)
  }

  @Test
  func `getSelfTelemetry matches Python`() {
    let packet = PacketBuilder.getSelfTelemetry()
    #expect(packet == PythonReferenceBytes.getSelfTelemetry,
            "getSelfTelemetry mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.getSelfTelemetry.hexString)")
  }

  // MARK: - Security Commands

  @Test
  func `exportPrivateKey matches Python`() {
    let packet = PacketBuilder.exportPrivateKey()
    #expect(packet == PythonReferenceBytes.exportPrivateKey)
  }

  @Test
  func `signStart matches Python`() {
    let packet = PacketBuilder.signStart()
    #expect(packet == PythonReferenceBytes.signStart)
  }

  @Test
  func `signFinish matches Python`() {
    let packet = PacketBuilder.signFinish()
    #expect(packet == PythonReferenceBytes.signFinish)
  }

  // MARK: - Path Discovery Commands

  @Test
  func `pathDiscovery matches Python`() {
    let dst = Data([0x01, 0x23, 0x45, 0x67, 0x89, 0xAB]) + Data(repeating: 0, count: 26)
    let packet = PacketBuilder.sendPathDiscovery(to: dst)
    #expect(packet == PythonReferenceBytes.pathDiscovery,
            "pathDiscovery mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.pathDiscovery.hexString)")
  }

  @Test
  func `sendTrace matches Python`() {
    let packet = PacketBuilder.sendTrace(
      tag: 12345,
      authCode: 67890,
      flags: 0
    )
    #expect(packet == PythonReferenceBytes.sendTrace,
            "sendTrace mismatch - Swift: \(packet.hexString), Python: \(PythonReferenceBytes.sendTrace.hexString)")
  }
}
