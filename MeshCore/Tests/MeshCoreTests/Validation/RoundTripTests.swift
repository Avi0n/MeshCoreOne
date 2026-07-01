import Foundation
@testable import MeshCore
import Testing

/// Tests that verify Build -> Parse -> fields match (round-trip consistency)
///
/// These tests construct binary payloads, parse them using the Swift parsers,
/// and verify the parsed values match the original inputs.
@Suite("Round-Trip")
struct RoundTripTests {
  // MARK: - Contact Round-Trip

  @Test
  func `Contact round trip`() {
    // Build a 147-byte contact response packet
    var data = Data()
    let publicKey = Data(repeating: 0xAA, count: 32)
    let type: UInt8 = 1
    let flags: UInt8 = 0x02
    let pathLen: Int8 = 3
    let pathBytes = Data([0x11, 0x22, 0x33]) + Data(repeating: 0, count: 61) // 64 bytes total
    let nameBytes = "TestContact".data(using: .utf8)!.prefix(32)
    let namePadded = nameBytes + Data(repeating: 0, count: 32 - nameBytes.count)
    let lastAdvert: UInt32 = 1_704_067_200
    let lat: Int32 = 37_774_900 // 37.7749 * 1e6
    let lon: Int32 = -122_419_400 // -122.4194 * 1e6
    let lastMod: UInt32 = 1_704_067_200

    data.append(publicKey)
    data.append(type)
    data.append(flags)
    data.append(UInt8(bitPattern: pathLen))
    data.append(pathBytes)
    data.append(namePadded)
    data.append(contentsOf: withUnsafeBytes(of: lastAdvert.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: lat.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: lon.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: lastMod.littleEndian) { Data($0) })

    #expect(data.count == 147, "Contact payload should be 147 bytes")

    // Parse using Parsers.Contact
    let event = Parsers.Contact.parse(data)

    guard case let .contact(contact) = event else {
      Issue.record("Expected .contact event, got \(event)")
      return
    }

    // Verify round-trip
    #expect(contact.publicKey == publicKey)
    #expect(contact.type == ContactType(rawValue: type))
    #expect(contact.flags == ContactFlags(rawValue: flags))
    #expect(contact.outPathLength == pathLen)
    #expect(contact.outPath == Data([0x11, 0x22, 0x33]))
    #expect(contact.advertisedName == "TestContact")
    #expect(abs(contact.latitude - 37.7749) <= 0.0001)
    #expect(abs(contact.longitude - -122.4194) <= 0.0001)
  }

  @Test
  func `Contact rejects reserved path length encoding`() {
    var data = Data(repeating: 0, count: 147)
    data.replaceSubrange(0..<32, with: Data(repeating: 0xAA, count: 32))
    data[32] = 1
    data[33] = 0
    data[34] = 0xC1 // mode 3 (reserved), hop count 1

    let event = Parsers.Contact.parse(data)

    guard case let .parseFailure(_, reason) = event else {
      Issue.record("Expected .parseFailure event, got \(event)")
      return
    }

    #expect(reason.contains("reserved path length encoding"))
  }

  // MARK: - SelfInfo Round-Trip

  @Test
  func `SelfInfo round trip`() throws {
    var data = Data()
    let advType: UInt8 = 1
    let txPower: Int8 = 20
    let maxTxPower: Int8 = 30
    let publicKey = Data(repeating: 0xBB, count: 32)
    let lat: Int32 = 37_774_900
    let lon: Int32 = -122_419_400
    let multiAcks: UInt8 = 1
    let advLocPolicy: UInt8 = 2
    // Telemetry mode: env=0 (bits 5-4), loc=1 (bits 3-2), base=2 (bits 1-0)
    let telemetryMode: UInt8 = ((0 & 0b11) << 4) | ((1 & 0b11) << 2) | (2 & 0b11)
    let manualAdd: UInt8 = 1
    let radioFreq: UInt32 = 906_875 // 906.875 MHz * 1000
    let radioBW: UInt32 = 250_000 // 250 kHz * 1000
    let radioSF: UInt8 = 11
    let radioCR: UInt8 = 8
    let name = "MyNode"

    data.append(advType)
    data.append(UInt8(bitPattern: txPower))
    data.append(UInt8(bitPattern: maxTxPower))
    data.append(publicKey)
    data.append(contentsOf: withUnsafeBytes(of: lat.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: lon.littleEndian) { Data($0) })
    data.append(multiAcks)
    data.append(advLocPolicy)
    data.append(telemetryMode)
    data.append(manualAdd)
    data.append(contentsOf: withUnsafeBytes(of: radioFreq.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: radioBW.littleEndian) { Data($0) })
    data.append(radioSF)
    data.append(radioCR)
    try data.append(#require(name.data(using: .utf8)))

    let event = Parsers.SelfInfo.parse(data)

    guard case let .selfInfo(info) = event else {
      Issue.record("Expected .selfInfo event, got \(event)")
      return
    }

    #expect(info.advertisementType == advType)
    #expect(info.txPower == txPower)
    #expect(info.maxTxPower == maxTxPower)
    #expect(info.publicKey == publicKey)
    #expect(abs(info.latitude - 37.7749) <= 0.0001)
    #expect(abs(info.longitude - -122.4194) <= 0.0001)
    #expect(info.multiAcks == multiAcks)
    #expect(info.advertisementLocationPolicy == advLocPolicy)
    #expect(info.telemetryModeEnvironment == 0)
    #expect(info.telemetryModeLocation == 1)
    #expect(info.telemetryModeBase == 2)
    #expect(info.manualAddContacts == true)
    #expect(abs(info.radioFrequency - 906.875) <= 0.001)
    #expect(abs(info.radioBandwidth - 250.0) <= 0.001)
    #expect(info.radioSpreadingFactor == radioSF)
    #expect(info.radioCodingRate == radioCR)
    #expect(info.name == "MyNode")
  }

  @Test
  func `SelfInfo negative TX power round trip`() throws {
    var data = Data()
    let advType: UInt8 = 1
    let txPower: Int8 = -5
    let maxTxPower: Int8 = 30
    let publicKey = Data(repeating: 0xCC, count: 32)
    let lat: Int32 = 0
    let lon: Int32 = 0
    let multiAcks: UInt8 = 0
    let advLocPolicy: UInt8 = 0
    let telemetryMode: UInt8 = 0
    let manualAdd: UInt8 = 0
    let radioFreq: UInt32 = 915_000
    let radioBW: UInt32 = 250_000
    let radioSF: UInt8 = 10
    let radioCR: UInt8 = 5
    let name = "NegPwr"

    data.append(advType)
    data.append(UInt8(bitPattern: txPower))
    data.append(UInt8(bitPattern: maxTxPower))
    data.append(publicKey)
    data.append(contentsOf: withUnsafeBytes(of: lat.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: lon.littleEndian) { Data($0) })
    data.append(multiAcks)
    data.append(advLocPolicy)
    data.append(telemetryMode)
    data.append(manualAdd)
    data.append(contentsOf: withUnsafeBytes(of: radioFreq.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: radioBW.littleEndian) { Data($0) })
    data.append(radioSF)
    data.append(radioCR)
    try data.append(#require(name.data(using: .utf8)))

    let event = Parsers.SelfInfo.parse(data)

    guard case let .selfInfo(info) = event else {
      Issue.record("Expected .selfInfo event, got \(event)")
      return
    }

    #expect(info.txPower == -5, "Negative TX power should be preserved")
    #expect(info.maxTxPower == 30)
    #expect(info.name == "NegPwr")
  }

  @Test
  func `SelfInfo parses minimum-length payload without device name`() {
    var data = Data()
    data.append(0x01) // advType
    data.append(UInt8(bitPattern: Int8(20))) // txPower
    data.append(UInt8(bitPattern: Int8(30))) // maxTxPower
    data.append(Data(repeating: 0xAA, count: 32)) // publicKey
    data.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) }) // lat
    data.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) }) // lon
    data.append(0x02) // multiAcks
    data.append(0x01) // advLocPolicy
    data.append(0x00) // telemetryMode
    data.append(0x01) // manualAdd
    data.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Data($0) }) // radioFreq
    data.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Data($0) }) // radioBW
    data.append(0x0A) // radioSF
    data.append(0x05) // radioCR

    #expect(data.count == 57)

    let event = Parsers.SelfInfo.parse(data)

    guard case let .selfInfo(info) = event else {
      Issue.record("Expected .selfInfo event, got \(event)")
      return
    }

    #expect(info.radioSpreadingFactor == 0x0A)
    #expect(info.radioCodingRate == 0x05)
    #expect(info.name.isEmpty)
  }

  @Test
  func `SelfInfo rejects truncated fixed-width payload`() {
    var data = Data()
    data.append(0x01) // advType
    data.append(UInt8(bitPattern: Int8(20))) // txPower
    data.append(UInt8(bitPattern: Int8(30))) // maxTxPower
    data.append(Data(repeating: 0xAA, count: 32)) // publicKey
    data.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) }) // lat
    data.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) }) // lon
    data.append(0x02) // multiAcks
    data.append(0x01) // advLocPolicy
    data.append(0x00) // telemetryMode
    data.append(0x01) // manualAdd
    data.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Data($0) }) // radioFreq
    data.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Data($0) }) // radioBW

    #expect(data.count == 55)

    let event = Parsers.SelfInfo.parse(data)

    guard case let .parseFailure(_, reason) = event else {
      Issue.record("Expected .parseFailure event, got \(event)")
      return
    }

    #expect(reason.contains("SelfInfo response too short"))
  }

  // MARK: - Message Round-Trip

  @Test
  func `ContactMessage v3 round trip`() throws {
    var data = Data()
    let snrRaw: Int8 = 24 // 6.0 dB * 4
    let reserved: UInt16 = 0
    let pubkeyPrefix = Data([0x01, 0x23, 0x45, 0x67, 0x89, 0xAB])
    let pathLen: UInt8 = 2
    let txtType: UInt8 = 0 // plain text
    let timestamp: UInt32 = 1_704_067_200
    let text = "Hello World"

    data.append(UInt8(bitPattern: snrRaw))
    data.append(contentsOf: withUnsafeBytes(of: reserved.littleEndian) { Data($0) })
    data.append(pubkeyPrefix)
    data.append(pathLen)
    data.append(txtType)
    data.append(contentsOf: withUnsafeBytes(of: timestamp.littleEndian) { Data($0) })
    try data.append(#require(text.data(using: .utf8)))

    let event = Parsers.ContactMessage.parse(data, version: .v3)

    guard case let .contactMessageReceived(msg) = event else {
      Issue.record("Expected .contactMessageReceived event, got \(event)")
      return
    }

    #expect(abs((msg.snr ?? 0) - 6.0) <= 0.01)
    #expect(msg.senderPublicKeyPrefix == pubkeyPrefix)
    #expect(msg.pathLength == pathLen)
    #expect(msg.textType == txtType)
    #expect(msg.text == "Hello World")
  }

  @Test
  func `ContactMessage rejects truncated signature payload`() {
    var data = Data()
    data.append(UInt8(bitPattern: Int8(0)))
    data.append(contentsOf: withUnsafeBytes(of: UInt16(0).littleEndian) { Data($0) })
    data.append(Data([0x01, 0x23, 0x45, 0x67, 0x89, 0xAB]))
    data.append(0x00)
    data.append(0x02) // signed text
    data.append(contentsOf: withUnsafeBytes(of: UInt32(1_704_067_200).littleEndian) { Data($0) })
    data.append(Data([0xDE, 0xAD, 0xBE])) // only 3 signature bytes, no text payload

    let event = Parsers.ContactMessage.parse(data, version: .v3)

    guard case let .parseFailure(_, reason) = event else {
      Issue.record("Expected .parseFailure event, got \(event)")
      return
    }

    #expect(reason.contains("signature truncated"))
  }

  @Test
  func `ChannelMessage v3 round trip`() throws {
    var data = Data()
    let snrRaw: Int8 = -20 // -5.0 dB * 4
    let reserved: UInt16 = 0
    let channel: UInt8 = 2
    let pathLen: UInt8 = 0
    let txtType: UInt8 = 0
    let timestamp: UInt32 = 1_704_067_200
    let text = "Broadcast message"

    data.append(UInt8(bitPattern: snrRaw))
    data.append(contentsOf: withUnsafeBytes(of: reserved.littleEndian) { Data($0) })
    data.append(channel)
    data.append(pathLen)
    data.append(txtType)
    data.append(contentsOf: withUnsafeBytes(of: timestamp.littleEndian) { Data($0) })
    try data.append(#require(text.data(using: .utf8)))

    let event = Parsers.ChannelMessage.parse(data, version: .v3)

    guard case let .channelMessageReceived(msg) = event else {
      Issue.record("Expected .channelMessageReceived event, got \(event)")
      return
    }

    #expect(abs((msg.snr ?? 0) - -5.0) <= 0.01)
    #expect(msg.channelIndex == channel)
    #expect(msg.pathLength == pathLen)
    #expect(msg.text == "Broadcast message")
  }

  // MARK: - StatusResponse Round-Trip

  @Test
  func `StatusResponse round trip`() {
    var data = Data()
    let pubkeyPrefix = Data([0x01, 0x23, 0x45, 0x67, 0x89, 0xAB])
    let battery: UInt16 = 3800
    let txQueue: UInt16 = 5
    let noiseFloor: Int16 = -110
    let lastRSSI: Int16 = -85
    let packetsRecv: UInt32 = 1000
    let packetsSent: UInt32 = 500
    let airtime: UInt32 = 3600
    let uptime: UInt32 = 86400
    let sentFlood: UInt32 = 100
    let sentDirect: UInt32 = 400
    let recvFlood: UInt32 = 200
    let recvDirect: UInt32 = 800
    let fullEvents: UInt16 = 10
    let lastSNRRaw: Int16 = 24 // 6.0 * 4
    let directDups: UInt16 = 5
    let floodDups: UInt16 = 15
    let rxAirtime: UInt32 = 1800

    data.append(0x00) // Reserved byte (per firmware format)
    data.append(pubkeyPrefix)
    data.append(contentsOf: withUnsafeBytes(of: battery.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: txQueue.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: noiseFloor.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: lastRSSI.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: packetsRecv.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: packetsSent.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: airtime.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: uptime.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: sentFlood.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: sentDirect.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: recvFlood.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: recvDirect.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: fullEvents.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: lastSNRRaw.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: directDups.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: floodDups.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: rxAirtime.littleEndian) { Data($0) })

    let event = Parsers.StatusResponse.parse(data)

    guard case let .statusResponse(status) = event else {
      Issue.record("Expected .statusResponse event, got \(event)")
      return
    }

    #expect(status.publicKeyPrefix == pubkeyPrefix)
    #expect(status.battery == Int(battery))
    #expect(status.txQueueLength == Int(txQueue))
    #expect(status.noiseFloor == Int(noiseFloor))
    #expect(status.lastRSSI == Int(lastRSSI))
    #expect(status.packetsReceived == packetsRecv)
    #expect(status.packetsSent == packetsSent)
    #expect(status.airtime == airtime)
    #expect(status.uptime == uptime)
    #expect(status.sentFlood == sentFlood)
    #expect(status.sentDirect == sentDirect)
    #expect(status.receivedFlood == recvFlood)
    #expect(status.receivedDirect == recvDirect)
    #expect(status.fullEvents == Int(fullEvents))
    #expect(abs(status.lastSNR - 6.0) <= 0.01)
    #expect(status.directDuplicates == Int(directDups))
    #expect(status.floodDuplicates == Int(floodDups))
    #expect(status.rxAirtime == rxAirtime)
    #expect(status.receiveErrors == 0, "receiveErrors should default to 0 for legacy payload")
  }

  // MARK: - Stats Round-Trip

  @Test
  func `CoreStats round trip`() {
    var data = Data()
    let batteryMV: UInt16 = 3750
    let uptime: UInt32 = 86400
    let errors: UInt16 = 3
    let queueLen: UInt8 = 5

    data.append(contentsOf: withUnsafeBytes(of: batteryMV.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: uptime.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: errors.littleEndian) { Data($0) })
    data.append(queueLen)

    let event = Parsers.CoreStats.parse(data)

    guard case let .statsCore(stats) = event else {
      Issue.record("Expected .statsCore event, got \(event)")
      return
    }

    #expect(stats.batteryMV == batteryMV)
    #expect(stats.uptimeSeconds == uptime)
    #expect(stats.errors == errors)
    #expect(stats.queueLength == queueLen)
  }

  @Test
  func `RadioStats round trip`() {
    var data = Data()
    let noiseFloor: Int16 = -115
    let lastRSSI: Int8 = -90
    let lastSNRRaw: Int8 = 28 // 7.0 * 4
    let txAir: UInt32 = 1000
    let rxAir: UInt32 = 2000

    data.append(contentsOf: withUnsafeBytes(of: noiseFloor.littleEndian) { Data($0) })
    data.append(UInt8(bitPattern: lastRSSI))
    data.append(UInt8(bitPattern: lastSNRRaw))
    data.append(contentsOf: withUnsafeBytes(of: txAir.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: rxAir.littleEndian) { Data($0) })

    let event = Parsers.RadioStats.parse(data)

    guard case let .statsRadio(stats) = event else {
      Issue.record("Expected .statsRadio event, got \(event)")
      return
    }

    #expect(stats.noiseFloor == noiseFloor)
    #expect(stats.lastRSSI == lastRSSI)
    #expect(abs(stats.lastSNR - 7.0) <= 0.01)
    #expect(stats.txAirtimeSeconds == txAir)
    #expect(stats.rxAirtimeSeconds == rxAir)
  }

  @Test
  func `PacketStats round trip (legacy 24-byte format)`() {
    var data = Data()
    let received: UInt32 = 1000
    let sent: UInt32 = 500
    let floodTx: UInt32 = 100
    let directTx: UInt32 = 400
    let floodRx: UInt32 = 200
    let directRx: UInt32 = 800

    data.append(contentsOf: withUnsafeBytes(of: received.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: sent.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: floodTx.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: directTx.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: floodRx.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: directRx.littleEndian) { Data($0) })

    let event = Parsers.PacketStats.parse(data)

    guard case let .statsPackets(stats) = event else {
      Issue.record("Expected .statsPackets event, got \(event)")
      return
    }

    #expect(stats.received == received)
    #expect(stats.sent == sent)
    #expect(stats.floodTx == floodTx)
    #expect(stats.directTx == directTx)
    #expect(stats.floodRx == floodRx)
    #expect(stats.directRx == directRx)
    #expect(stats.receiveErrors == 0)
  }

  @Test
  func `PacketStats round trip (28-byte format with receiveErrors)`() {
    var data = Data()
    let received: UInt32 = 1000
    let sent: UInt32 = 500
    let floodTx: UInt32 = 100
    let directTx: UInt32 = 400
    let floodRx: UInt32 = 200
    let directRx: UInt32 = 800
    let receiveErrors: UInt32 = 42

    data.append(contentsOf: withUnsafeBytes(of: received.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: sent.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: floodTx.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: directTx.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: floodRx.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: directRx.littleEndian) { Data($0) })
    data.append(contentsOf: withUnsafeBytes(of: receiveErrors.littleEndian) { Data($0) })

    let event = Parsers.PacketStats.parse(data)

    guard case let .statsPackets(stats) = event else {
      Issue.record("Expected .statsPackets event, got \(event)")
      return
    }

    #expect(stats.received == received)
    #expect(stats.sent == sent)
    #expect(stats.floodTx == floodTx)
    #expect(stats.directTx == directTx)
    #expect(stats.floodRx == floodRx)
    #expect(stats.directRx == directRx)
    #expect(stats.receiveErrors == receiveErrors)
  }

  // MARK: - ChannelInfo Round-Trip

  @Test
  func `ChannelInfo round trip`() throws {
    var data = Data()
    let index: UInt8 = 1
    let name = "TestChannel"
    let nameBytes = try #require(name.data(using: .utf8)?.prefix(32))
    let namePadded = nameBytes + Data(repeating: 0, count: 32 - nameBytes.count)
    let secret = Data(0..<16)

    data.append(index)
    data.append(namePadded)
    data.append(secret)

    let event = Parsers.ChannelInfo.parse(data)

    guard case let .channelInfo(info) = event else {
      Issue.record("Expected .channelInfo event, got \(event)")
      return
    }

    #expect(info.index == index)
    #expect(info.name == "TestChannel")
    #expect(info.secret == secret)
  }

  @Test
  func `ChannelInfo handles garbage bytes after null`() throws {
    // Firmware uses strcpy which leaves uninitialized garbage after the null terminator.
    var data = Data()
    let index: UInt8 = 2
    let name = "Primary"
    let nameBytes = try #require(name.data(using: .utf8))
    var namePadded = nameBytes
    namePadded.append(0) // Null terminator
    // Append garbage bytes (invalid UTF-8 sequences) to simulate uninitialized memory
    let garbageBytes = Data([0xFF, 0xFE, 0x80, 0x81, 0xC0, 0xC1])
    namePadded.append(garbageBytes)
    // Pad to 32 bytes total
    namePadded.append(Data(repeating: 0xAB, count: 32 - namePadded.count))
    let secret = Data(repeating: 0xCC, count: 16)

    data.append(index)
    data.append(namePadded)
    data.append(secret)

    let event = Parsers.ChannelInfo.parse(data)

    guard case let .channelInfo(info) = event else {
      Issue.record("Expected .channelInfo event, got \(event)")
      return
    }

    #expect(info.index == index)
    #expect(info.name == "Primary", "Name should be parsed up to null terminator, ignoring garbage bytes")
    #expect(info.secret == secret)
  }

  @Test
  func `ChannelInfo lossy decodes invalid UTF-8 before null`() {
    var data = Data()
    let index: UInt8 = 3
    var namePadded = Data([0x50, 0x72, 0x69, 0xFF, 0x6D, 0x61, 0x72, 0x79])
    namePadded.append(0)
    namePadded.append(Data(repeating: 0, count: 32 - namePadded.count))
    let secret = Data(repeating: 0x55, count: 16)

    data.append(index)
    data.append(namePadded)
    data.append(secret)

    let event = Parsers.ChannelInfo.parse(data)

    guard case let .channelInfo(info) = event else {
      Issue.record("Expected .channelInfo event, got \(event)")
      return
    }

    let expectedName = String(decoding: Data([0x50, 0x72, 0x69, 0xFF, 0x6D, 0x61, 0x72, 0x79]), as: UTF8.self)
    #expect(info.index == index)
    #expect(info.name == expectedName)
    #expect(!info.name.isEmpty)
    #expect(info.secret == secret)
  }

  // MARK: - DeviceInfo Round-Trip

  @Test
  func `DeviceInfo v9 client repeat round trip`() throws {
    // Build a v9 device info response (80 bytes: 79 v3 bytes + 1 client_repeat)
    var data = Data()
    let fwVer: UInt8 = 9
    let maxContacts: UInt8 = 50 // stored as count/2
    let maxChannels: UInt8 = 8
    let blePin: UInt32 = 123_456
    let fwBuild = "15 Feb 2026"
    let model = "T-Deck"
    let version = "1.13.0"

    data.append(fwVer)
    data.append(maxContacts)
    data.append(maxChannels)
    data.append(contentsOf: withUnsafeBytes(of: blePin.littleEndian) { Data($0) })

    let fwBuildPadded = try #require(fwBuild.data(using: .utf8)?.prefix(12))
    data.append(fwBuildPadded)
    data.append(Data(repeating: 0, count: 12 - fwBuildPadded.count))

    let modelPadded = try #require(model.data(using: .utf8)?.prefix(40))
    data.append(modelPadded)
    data.append(Data(repeating: 0, count: 40 - modelPadded.count))

    let versionPadded = try #require(version.data(using: .utf8)?.prefix(20))
    data.append(versionPadded)
    data.append(Data(repeating: 0, count: 20 - versionPadded.count))

    data.append(1) // client_repeat = enabled

    #expect(data.count == 80, "v9 DeviceInfo should be 80 bytes")

    let event = Parsers.DeviceInfo.parse(data)

    guard case let .deviceInfo(caps) = event else {
      Issue.record("Expected .deviceInfo event, got \(event)")
      return
    }

    #expect(caps.firmwareVersion == 9)
    #expect(caps.maxContacts == 100) // 50 * 2
    #expect(caps.maxChannels == 8)
    #expect(caps.blePin == blePin)
    #expect(caps.clientRepeat, "client_repeat should be true for v9 with byte=1")
  }

  @Test
  func `DeviceInfo v10 pathHashMode round trip`() throws {
    // Build a v10 device info response (81 bytes: 79 base + 1 client_repeat + 1 pathHashMode)
    var data = Data()
    let fwVer: UInt8 = 10
    let maxContacts: UInt8 = 50
    let maxChannels: UInt8 = 8
    let blePin: UInt32 = 654_321
    let fwBuild = "20 Feb 2026"
    let model = "T-Deck"
    let version = "1.14.0"
    let clientRepeat: UInt8 = 1
    let pathHashMode: UInt8 = 2 // 3-byte hashes

    data.append(fwVer)
    data.append(maxContacts)
    data.append(maxChannels)
    data.append(contentsOf: withUnsafeBytes(of: blePin.littleEndian) { Data($0) })

    let fwBuildPadded = try #require(fwBuild.data(using: .utf8)?.prefix(12))
    data.append(fwBuildPadded)
    data.append(Data(repeating: 0, count: 12 - fwBuildPadded.count))

    let modelPadded = try #require(model.data(using: .utf8)?.prefix(40))
    data.append(modelPadded)
    data.append(Data(repeating: 0, count: 40 - modelPadded.count))

    let versionPadded = try #require(version.data(using: .utf8)?.prefix(20))
    data.append(versionPadded)
    data.append(Data(repeating: 0, count: 20 - versionPadded.count))

    data.append(clientRepeat)
    data.append(pathHashMode)

    #expect(data.count == 81, "v10 DeviceInfo should be 81 bytes (79 + client_repeat + pathHashMode)")

    let event = Parsers.DeviceInfo.parse(data)

    guard case let .deviceInfo(caps) = event else {
      Issue.record("Expected .deviceInfo event, got \(event)")
      return
    }

    #expect(caps.firmwareVersion == 10)
    #expect(caps.maxContacts == 100)
    #expect(caps.maxChannels == 8)
    #expect(caps.blePin == blePin)
    #expect(caps.clientRepeat, "client_repeat should be true")
    #expect(caps.pathHashMode == 2, "pathHashMode should be 2 (3-byte hashes)")
  }

  @Test
  func `DeviceInfo v10 tolerates missing pathHashMode byte`() {
    var data = Data()
    data.append(10) // fwVer
    data.append(50) // maxContacts
    data.append(8) // maxChannels
    let blePin: UInt32 = 654_321
    data.append(contentsOf: withUnsafeBytes(of: blePin.littleEndian) { Data($0) })
    data.append(Data(repeating: 0, count: 12 + 40 + 20))
    data.append(1) // client_repeat present
    #expect(data.count == 80, "v10 payload missing pathHashMode byte")

    let event = Parsers.DeviceInfo.parse(data)

    guard case let .deviceInfo(caps) = event else {
      Issue.record("Expected .deviceInfo event, got \(event)")
      return
    }

    #expect(caps.firmwareVersion == 10)
    #expect(caps.maxContacts == 100)
    #expect(caps.clientRepeat == true, "client_repeat should be parsed from base block")
    #expect(caps.pathHashMode == 0, "Missing pathHashMode should default to 0")
  }

  @Test
  func `DeviceInfo v9 defaults pathHashMode to 0`() {
    // v9 firmware doesn't include pathHashMode — it should default to 0
    var data = Data()
    data.append(9) // fwVer
    data.append(50) // maxContacts
    data.append(8) // maxChannels
    let blePin: UInt32 = 0
    data.append(contentsOf: withUnsafeBytes(of: blePin.littleEndian) { Data($0) })
    data.append(Data(repeating: 0, count: 12)) // fwBuild
    data.append(Data(repeating: 0, count: 40)) // model
    data.append(Data(repeating: 0, count: 20)) // version
    data.append(0) // client_repeat = disabled

    #expect(data.count == 80, "v9 DeviceInfo should be 80 bytes")

    let event = Parsers.DeviceInfo.parse(data)

    guard case let .deviceInfo(caps) = event else {
      Issue.record("Expected .deviceInfo event, got \(event)")
      return
    }

    #expect(caps.firmwareVersion == 9)
    #expect(caps.pathHashMode == 0, "v9 firmware should default pathHashMode to 0")
  }

  @Test
  func `DeviceInfo v8 no client repeat round trip`() {
    // Build a v8 device info response (79 bytes, no client_repeat)
    var data = Data()
    data.append(8) // fwVer
    data.append(25) // maxContacts (count/2)
    data.append(4) // maxChannels
    let blePin: UInt32 = 0
    data.append(contentsOf: withUnsafeBytes(of: blePin.littleEndian) { Data($0) })
    data.append(Data(repeating: 0, count: 12)) // fwBuild
    data.append(Data(repeating: 0, count: 40)) // model
    data.append(Data(repeating: 0, count: 20)) // version

    #expect(data.count == 79, "v8 DeviceInfo should be 79 bytes")

    let event = Parsers.DeviceInfo.parse(data)

    guard case let .deviceInfo(caps) = event else {
      Issue.record("Expected .deviceInfo event, got \(event)")
      return
    }

    #expect(caps.firmwareVersion == 8)
    #expect(!caps.clientRepeat, "client_repeat should be false for v8 firmware")
  }

  // MARK: - AllowedRepeatFreq Round-Trip

  @Test
  func `AllowedRepeatFreq round trip`() {
    var data = Data()
    let ranges: [(UInt32, UInt32)] = [
      (433_000, 433_000),
      (869_000, 869_000),
      (918_000, 918_000),
    ]
    for (lower, upper) in ranges {
      data.append(contentsOf: withUnsafeBytes(of: lower.littleEndian) { Data($0) })
      data.append(contentsOf: withUnsafeBytes(of: upper.littleEndian) { Data($0) })
    }

    let event = Parsers.AllowedRepeatFreq.parse(data)

    guard case let .allowedRepeatFreq(parsed) = event else {
      Issue.record("Expected .allowedRepeatFreq event, got \(event)")
      return
    }

    #expect(parsed.count == 3)
    #expect(parsed[0].lowerKHz == 433_000)
    #expect(parsed[0].upperKHz == 433_000)
    #expect(parsed[1].lowerKHz == 869_000)
    #expect(parsed[2].lowerKHz == 918_000)
  }

  // MARK: - SetRadio Client Repeat

  @Test
  func `setRadio with repeat appends byte`() {
    let packet = PacketBuilder.setRadio(
      frequency: 915.0,
      bandwidth: 250.0,
      spreadingFactor: 11,
      codingRate: 8,
      clientRepeat: true
    )
    // cmd(1) + freq(4) + bw(4) + sf(1) + cr(1) + repeat(1) = 12
    #expect(packet.count == 12)
    #expect(packet.last == 1, "Last byte should be repeat=1")
  }

  @Test
  func `setRadio without repeat no extra byte`() {
    let packet = PacketBuilder.setRadio(
      frequency: 915.0,
      bandwidth: 250.0,
      spreadingFactor: 11,
      codingRate: 8
    )
    // cmd(1) + freq(4) + bw(4) + sf(1) + cr(1) = 11
    #expect(packet.count == 11)
  }

  @Test
  func `setRadio rounds frequency to the nearest kHz`() {
    let packet = PacketBuilder.setRadio(
      frequency: 512.002,
      bandwidth: 250.0,
      spreadingFactor: 11,
      codingRate: 8
    )
    let freqKHz = packet.readUInt32LE(at: 1)
    // Truncation would yield 512001; rounding restores the representable 512002.
    #expect(freqKHz == 512_002)
  }

  @Test
  func `setRadio clamps out-of-range frequency and bandwidth instead of trapping`() {
    let high = PacketBuilder.setRadio(
      frequency: 9_999_999.0,
      bandwidth: 9_999_999.0,
      spreadingFactor: 11,
      codingRate: 8
    )
    #expect(high.readUInt32LE(at: 1) == PacketBuilder.frequencyRangeKHz.upperBound)
    #expect(high.readUInt32LE(at: 5) == PacketBuilder.bandwidthRangeHz.upperBound)

    let low = PacketBuilder.setRadio(
      frequency: -1.0,
      bandwidth: Double.nan,
      spreadingFactor: 11,
      codingRate: 8
    )
    #expect(low.readUInt32LE(at: 1) == PacketBuilder.frequencyRangeKHz.lowerBound)
    #expect(low.readUInt32LE(at: 5) == PacketBuilder.bandwidthRangeHz.lowerBound)
  }

  @Test
  func `setTime saturates out-of-range dates instead of trapping`() {
    let preEpoch = PacketBuilder.setTime(Date(timeIntervalSince1970: -1000))
    #expect(preEpoch.readUInt32LE(at: 1) == 0)

    let postRange = PacketBuilder.setTime(Date(timeIntervalSince1970: Double(UInt32.max) + 1000))
    #expect(postRange.readUInt32LE(at: 1) == UInt32.max)
  }

  @Test
  func `setTxPower positive power packet format`() {
    let packet = PacketBuilder.setTxPower(20)
    #expect(packet == Data([0x0C, 0x14]))
  }

  @Test
  func `setTxPower negative power packet format`() {
    let packet = PacketBuilder.setTxPower(-5)
    #expect(packet == Data([0x0C, 0xFB]))
  }

  @Test
  func `getRepeatFreq packet format`() {
    let packet = PacketBuilder.getRepeatFreq()
    #expect(packet == Data([0x3C]))
  }

  // MARK: - CustomVars Round-Trip

  @Test
  func `CustomVars round trip`() throws {
    let varString = "key1:value1,key2:value2,mode:auto"
    let data = try #require(varString.data(using: .utf8))

    let event = Parsers.CustomVars.parse(data)

    guard case let .customVars(vars) = event else {
      Issue.record("Expected .customVars event, got \(event)")
      return
    }

    #expect(vars["key1"] == "value1")
    #expect(vars["key2"] == "value2")
    #expect(vars["mode"] == "auto")
  }

  // MARK: - LPP Round-Trip

  @Test
  func `LPP encoder/decoder temperature round trip`() {
    var encoder = LPPEncoder()
    encoder.addTemperature(channel: 1, celsius: 22.5)
    let encoded = encoder.encode()

    let decoded = LPPDecoder.decode(encoded)
    #expect(decoded.count == 1)
    #expect(decoded[0].channel == 1)
    #expect(decoded[0].type == .temperature)

    if case let .float(value) = decoded[0].value {
      #expect(abs(value - 22.5) <= 0.1)
    } else {
      Issue.record("Expected float value")
    }
  }

  @Test
  func `LPP encoder/decoder GPS round trip`() {
    var encoder = LPPEncoder()
    encoder.addGPS(channel: 3, latitude: 37.7749, longitude: -122.4194, altitude: 50.0)
    let encoded = encoder.encode()

    let decoded = LPPDecoder.decode(encoded)
    #expect(decoded.count == 1)
    #expect(decoded[0].channel == 3)
    #expect(decoded[0].type == .gps)

    if case let .gps(lat, lon, alt) = decoded[0].value {
      #expect(abs(lat - 37.7749) <= 0.0001)
      #expect(abs(lon - -122.4194) <= 0.0001)
      #expect(abs(alt - 50.0) <= 0.01)
    } else {
      Issue.record("Expected GPS value")
    }
  }

  @Test
  func `LPP encoder/decoder multi-sensor round trip`() {
    var encoder = LPPEncoder()
    encoder.addTemperature(channel: 1, celsius: 25.0)
    encoder.addHumidity(channel: 2, percent: 60.0)
    encoder.addVoltage(channel: 3, volts: 3.7)
    let encoded = encoder.encode()

    let decoded = LPPDecoder.decode(encoded)
    #expect(decoded.count == 3)

    #expect(decoded[0].channel == 1)
    #expect(decoded[0].type == .temperature)
    if case let .float(temp) = decoded[0].value {
      #expect(abs(temp - 25.0) <= 0.1)
    }

    #expect(decoded[1].channel == 2)
    #expect(decoded[1].type == .humidity)
    if case let .float(humidity) = decoded[1].value {
      #expect(abs(humidity - 60.0) <= 0.5)
    }

    #expect(decoded[2].channel == 3)
    #expect(decoded[2].type == .voltage)
    if case let .float(volts) = decoded[2].value {
      #expect(abs(volts - 3.7) <= 0.01)
    }
  }

  @Test
  func `LPP encoder/decoder negative temperature round trip`() {
    var encoder = LPPEncoder()
    encoder.addTemperature(channel: 1, celsius: -15.5)
    let encoded = encoder.encode()

    let decoded = LPPDecoder.decode(encoded)
    #expect(decoded.count == 1)

    if case let .float(temp) = decoded[0].value {
      #expect(abs(temp - -15.5) <= 0.1)
    } else {
      Issue.record("Expected float value for temperature")
    }
  }

  @Test
  func `LPP encoder/decoder accelerometer round trip`() {
    var encoder = LPPEncoder()
    encoder.addAccelerometer(channel: 5, x: -0.5, y: 0.25, z: 1.0)
    let encoded = encoder.encode()

    let decoded = LPPDecoder.decode(encoded)
    #expect(decoded.count == 1)
    #expect(decoded[0].type == .accelerometer)

    if case let .vector3(x, y, z) = decoded[0].value {
      #expect(abs(x - -0.5) <= 0.001)
      #expect(abs(y - 0.25) <= 0.001)
      #expect(abs(z - 1.0) <= 0.001)
    } else {
      Issue.record("Expected vector3 value for accelerometer")
    }
  }

  @Test
  func `ChannelMessage v3 empty-text broadcast parses at firmware-minimum size`() {
    var data = Data()
    let snrRaw: Int8 = -20 // -5.0 dB * 4
    let reserved: UInt16 = 0
    let channel: UInt8 = 2
    let pathLen: UInt8 = 0
    let txtType: UInt8 = 0
    let timestamp: UInt32 = 1_704_067_200

    data.append(UInt8(bitPattern: snrRaw))
    data.append(contentsOf: withUnsafeBytes(of: reserved.littleEndian) { Data($0) })
    data.append(channel)
    data.append(pathLen)
    data.append(txtType)
    data.append(contentsOf: withUnsafeBytes(of: timestamp.littleEndian) { Data($0) })
    // No text bytes appended: firmware emits this when strlen(text) == 0.

    #expect(data.count == 10)

    let event = Parsers.ChannelMessage.parse(data, version: .v3)

    guard case let .channelMessageReceived(msg) = event else {
      Issue.record("Expected .channelMessageReceived event for empty-text broadcast, got \(event)")
      return
    }

    #expect(msg.channelIndex == channel)
    #expect(msg.pathLength == pathLen)
    #expect(msg.text.isEmpty)
  }

  @Test
  func `ChannelMessage v1 empty-text broadcast parses at firmware-minimum size`() {
    var data = Data()
    let channel: UInt8 = 3
    let pathLen: UInt8 = 0
    let txtType: UInt8 = 0
    let timestamp: UInt32 = 1_704_067_200

    data.append(channel)
    data.append(pathLen)
    data.append(txtType)
    data.append(contentsOf: withUnsafeBytes(of: timestamp.littleEndian) { Data($0) })
    // No text bytes appended: firmware emits this when strlen(text) == 0.

    #expect(data.count == 7)

    let event = Parsers.ChannelMessage.parse(data, version: .v1)

    guard case let .channelMessageReceived(msg) = event else {
      Issue.record("Expected .channelMessageReceived event for empty-text broadcast, got \(event)")
      return
    }

    #expect(msg.channelIndex == channel)
    #expect(msg.pathLength == pathLen)
    #expect(msg.text.isEmpty)
  }
}
