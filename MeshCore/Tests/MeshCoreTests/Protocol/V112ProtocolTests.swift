import Foundation
@testable import MeshCore
import Testing

@Suite("V112 Protocol")
struct V112ProtocolTests {
  // MARK: - ResponseCode Tests

  @Test
  func `contactDeleted response code exists`() {
    let code = ResponseCode(rawValue: 0x8F)
    #expect(code != nil)
    #expect(code == .contactDeleted)
  }

  @Test
  func `contactsFull response code exists`() {
    let code = ResponseCode(rawValue: 0x90)
    #expect(code != nil)
    #expect(code == .contactsFull)
  }

  @Test
  func `contactDeleted category is push`() {
    #expect(ResponseCode.contactDeleted.category == .push)
  }

  @Test
  func `contactsFull category is push`() {
    #expect(ResponseCode.contactsFull.category == .push)
  }

  // MARK: - ContactDeleted Parser Tests

  @Test
  func `contactDeleted parses valid payload`() {
    let publicKey = Data(repeating: 0xAB, count: 32)

    let event = Parsers.ContactDeleted.parse(publicKey)

    if case let .contactDeleted(parsedKey) = event {
      #expect(parsedKey == publicKey)
    } else {
      Issue.record("Expected .contactDeleted event, got \(event)")
    }
  }

  @Test
  func `contactDeleted parse failure for short payload`() {
    let shortData = Data(repeating: 0xAB, count: 31)

    let event = Parsers.ContactDeleted.parse(shortData)

    if case let .parseFailure(_, reason) = event {
      #expect(reason.contains("ContactDeleted too short"))
    } else {
      Issue.record("Expected .parseFailure event, got \(event)")
    }
  }

  @Test
  func `contactDeleted ignores extra bytes`() {
    var data = Data(repeating: 0xCD, count: 32)
    data.append(contentsOf: [0xFF, 0xFF, 0xFF])

    let event = Parsers.ContactDeleted.parse(data)

    if case let .contactDeleted(parsedKey) = event {
      #expect(parsedKey.count == 32)
      #expect(parsedKey == Data(repeating: 0xCD, count: 32))
    } else {
      Issue.record("Expected .contactDeleted event, got \(event)")
    }
  }

  // MARK: - ContactsFull Parser Tests

  @Test
  func `contactsFull parses empty payload`() {
    let event = Parsers.ContactsFull.parse(Data())

    if case .contactsFull = event {
      // Success
    } else {
      Issue.record("Expected .contactsFull event, got \(event)")
    }
  }

  @Test
  func `contactsFull parses any payload`() {
    let data = Data([0x01, 0x02, 0x03])

    let event = Parsers.ContactsFull.parse(data)

    if case .contactsFull = event {
      // Success - payload is ignored
    } else {
      Issue.record("Expected .contactsFull event, got \(event)")
    }
  }

  // MARK: - PacketParser Integration Tests

  @Test
  func `packetParser routes contactDeleted`() {
    var packet = Data([0x8F])
    packet.append(Data(repeating: 0xEF, count: 32))

    let event = PacketParser.parse(packet)

    if case let .contactDeleted(publicKey) = event {
      #expect(publicKey == Data(repeating: 0xEF, count: 32))
    } else {
      Issue.record("Expected .contactDeleted event, got \(event)")
    }
  }

  @Test
  func `packetParser routes contactsFull`() {
    let packet = Data([0x90])

    let event = PacketParser.parse(packet)

    if case .contactsFull = event {
      // Success
    } else {
      Issue.record("Expected .contactsFull event, got \(event)")
    }
  }

  @Test
  func `packetParser contactDeleted parse failure for short payload`() {
    var packet = Data([0x8F])
    packet.append(Data(repeating: 0xAB, count: 20))

    let event = PacketParser.parse(packet)

    if case let .parseFailure(_, reason) = event {
      #expect(reason.contains("ContactDeleted too short"))
    } else {
      Issue.record("Expected .parseFailure event, got \(event)")
    }
  }

  // MARK: - ContactManager Tests

  @Test
  func `contactManager tracks contactDeleted`() {
    var manager = ContactManager()
    let publicKey = Data(repeating: 0x11, count: 32)
    let contactId = publicKey.hexString

    let contact = MeshContact(
      id: contactId,
      publicKey: publicKey,
      type: .chat,
      flags: [],
      outPathLength: 0,
      outPath: Data(),
      advertisedName: "Test",
      lastAdvertisement: Date(),
      latitude: 0,
      longitude: 0,
      lastModified: Date()
    )
    manager.store(contact)

    #expect(manager.getByPublicKey(publicKey) != nil)

    manager.trackChanges(from: .contactDeleted(publicKey: publicKey))

    #expect(manager.getByPublicKey(publicKey) == nil)
    #expect(manager.needsRefresh)
  }

  @Test
  func `contactManager tracks contactsFull`() {
    var manager = ContactManager()

    manager.trackChanges(from: .contactsFull)

    #expect(manager.needsRefresh)
  }

  // MARK: - Auto-Add Config PacketBuilder Tests

  @Test
  func `getAutoAddConfig packet builder`() {
    let packet = PacketBuilder.getAutoAddConfig()

    #expect(packet == Data([0x3B]))
  }

  @Test
  func `setAutoAddConfig packet builder`() {
    let packet = PacketBuilder.setAutoAddConfig(AutoAddConfig(bitmask: 0x0F))

    #expect(packet == Data([0x3A, 0x0F, 0x00]))
  }

  @Test
  func `setAutoAddConfig all bits set`() {
    let packet = PacketBuilder.setAutoAddConfig(AutoAddConfig(bitmask: 0xFF))

    #expect(packet == Data([0x3A, 0xFF, 0x00]))
  }

  @Test
  func `setAutoAddConfig zero bits`() {
    let packet = PacketBuilder.setAutoAddConfig(AutoAddConfig(bitmask: 0x00))

    #expect(packet == Data([0x3A, 0x00, 0x00]))
  }

  @Test
  func `setAutoAddConfig with maxHops`() {
    let packet = PacketBuilder.setAutoAddConfig(AutoAddConfig(bitmask: 0x0F, maxHops: 3))

    #expect(packet == Data([0x3A, 0x0F, 0x03]))
  }

  // MARK: - Auto-Add Config Response Parser Tests

  @Test
  func `autoAddConfig response code exists`() {
    let code = ResponseCode(rawValue: 0x19)
    #expect(code != nil)
    #expect(code == .autoAddConfig)
  }

  @Test
  func `autoAddConfig category is device`() {
    #expect(ResponseCode.autoAddConfig.category == .device)
  }

  @Test
  func `autoAddConfig parses single-byte payload with default maxHops`() {
    let packet = Data([0x19, 0x0F])

    let event = PacketParser.parse(packet)

    if case let .autoAddConfig(config) = event {
      #expect(config.bitmask == 0x0F)
      #expect(config.maxHops == 0)
    } else {
      Issue.record("Expected .autoAddConfig event, got \(event)")
    }
  }

  @Test
  func `autoAddConfig parses two-byte payload with maxHops`() {
    let packet = Data([0x19, 0x0F, 0x05])

    let event = PacketParser.parse(packet)

    if case let .autoAddConfig(config) = event {
      #expect(config.bitmask == 0x0F)
      #expect(config.maxHops == 5)
    } else {
      Issue.record("Expected .autoAddConfig event, got \(event)")
    }
  }

  @Test
  func `autoAddConfig parse failure for empty payload`() {
    let packet = Data([0x19])

    let event = PacketParser.parse(packet)

    if case let .parseFailure(_, reason) = event {
      #expect(reason.contains("AutoAddConfig response too short"))
    } else {
      Issue.record("Expected .parseFailure event, got \(event)")
    }
  }

  @Test
  func `autoAddConfig ignores extra bytes beyond maxHops`() {
    let packet = Data([0x19, 0x0E, 0x03, 0xFF, 0xFF])

    let event = PacketParser.parse(packet)

    if case let .autoAddConfig(config) = event {
      #expect(config.bitmask == 0x0E)
      #expect(config.maxHops == 3)
    } else {
      Issue.record("Expected .autoAddConfig event, got \(event)")
    }
  }
}
