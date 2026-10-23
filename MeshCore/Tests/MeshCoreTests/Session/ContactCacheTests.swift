import Foundation
@testable import MeshCore
import Testing

@Suite("Contact cache")
struct ContactCacheTests {
  @Test
  func `ensureContacts returns the full cache after unchanged and changed deltas`() async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport)
    let first = makeContact(key: 1, name: "First", modified: 10)
    let second = makeContact(key: 2, name: "Second", modified: 10)
    let initial = Task { try await session.ensureContacts() }
    try await respond(transport, contacts: [first, second], total: 2, modified: 10)
    #expect(try await ordered(initial.value) == [first, second])

    let unchanged = Task { try await session.ensureContacts(force: true) }
    try await respond(transport, contacts: [], total: 2, modified: 10, since: 10)
    #expect(try await ordered(unchanged.value) == [first, second])

    let updated = makeContact(key: 2, name: "Updated", modified: 20)
    let changed = Task { try await session.ensureContacts(force: true) }
    try await respond(transport, contacts: [updated], total: 2, modified: 20, since: 10)
    #expect(try await ordered(changed.value) == [first, updated])
    #expect(try await ordered(session.ensureContacts()) == [first, updated])
    #expect(await transport.sentData.isEmpty)

    let delta = Task { try await session.getContacts(since: Date(timeIntervalSince1970: 20)) }
    try await respond(transport, contacts: [], total: 2, modified: 20, since: 20)
    #expect(try await delta.value.isEmpty)
    #expect(await ordered(session.cachedContacts) == [first, updated])
    await session.stop()
  }

  @Test
  func `successful removal clears cached lookups and failed removal preserves them`() async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport)
    let contact = makeContact(key: 1, name: "First", modified: 10)
    let initial = Task { try await session.getContacts() }
    try await respond(transport, contacts: [contact], total: 1, modified: 10)
    _ = try await initial.value

    let failed = Task { try await session.removeContact(publicKey: contact.publicKey) }
    try await waitForCommand(transport, expected: PacketBuilder.removeContact(publicKey: contact.publicKey))
    await transport.simulateError(code: 2)
    let removalError = await #expect(throws: MeshCoreError.self) { try await failed.value }
    guard case .deviceError(code: 2)? = removalError else {
      Issue.record("Expected failed removal to return the device error")
      await session.stop()
      return
    }
    #expect(await session.getContactByKeyPrefix(contact.publicKey) == contact)

    let removed = Task { try await session.removeContact(publicKey: contact.publicKey) }
    try await waitForCommand(transport, expected: PacketBuilder.removeContact(publicKey: contact.publicKey))
    await transport.simulateOK()
    try await removed.value
    #expect(await session.cachedContacts.isEmpty)
    #expect(await session.getContactByKeyPrefix(contact.publicKey) == nil)
    #expect(await session.getContactByName(contact.advertisedName) == nil)
    await session.stop()
  }

  @Test
  func `complete full fetch replaces missing contacts without clearing pending contacts`() async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport)
    let first = makeContact(key: 1, name: "First", modified: 10)
    let second = makeContact(key: 2, name: "Second", modified: 10)
    let initial = Task { try await session.getContacts() }
    try await respond(transport, contacts: [first, second], total: 2, modified: 10)
    _ = try await initial.value

    let refresh = Task { try await session.getContacts() }
    try await respond(transport, contacts: [second], total: 1, modified: 10, pending: first)
    #expect(try await refresh.value == [second])
    #expect(await session.cachedContacts == [second])
    #expect(await session.cachedPendingContacts == [first])
    #expect(await session.getContactByKeyPrefix(first.publicKey) == nil)

    let empty = Task { try await session.getContacts() }
    try await respond(transport, contacts: [], total: 0, modified: 10)
    #expect(try await empty.value.isEmpty)
    #expect(await session.cachedContacts.isEmpty)
    await session.stop()
  }

  @Test(arguments: [0, 1, 2])
  func `incomplete full fetch cannot prune cached contacts`(completion: Int) async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport)
    let first = makeContact(key: 1, name: "First", modified: 10)
    let second = makeContact(key: 2, name: "Second", modified: 10)
    let initial = Task { try await session.getContacts() }
    try await respond(transport, contacts: [first, second], total: 2, modified: 10)
    _ = try await initial.value

    let refresh = Task { try await session.getContacts() }
    try await waitForCommand(transport, expected: PacketBuilder.getContacts())
    if completion != 2 {
      await transport.simulateReceive(startPacket(total: 2))
    }
    await transport.simulateReceive(contactPacket(second))
    if completion == 0 {
      await transport.simulateError(code: 3)
      let fetchError = await #expect(throws: MeshCoreError.self) { try await refresh.value }
      guard case .deviceError(code: 3)? = fetchError else {
        Issue.record("Expected failed fetch to return the device error")
        await session.stop()
        return
      }
    } else {
      await transport.simulateReceive(endPacket(modified: 10))
      #expect(try await refresh.value == [second])
      #expect(await session.isContactsDirty)
    }
    #expect(await ordered(session.cachedContacts) == [first, second])
    if completion != 0 {
      let retry = Task { try await session.ensureContacts() }
      try await respond(transport, contacts: [first, second], total: 2, modified: 10)
      #expect(try await ordered(retry.value) == [first, second])
    }
    await session.stop()
  }

  private func respond(
    _ transport: MockTransport,
    contacts: [MeshContact],
    total: UInt32,
    modified: UInt32,
    since: UInt32? = nil,
    pending: MeshContact? = nil
  ) async throws {
    try await waitForCommand(transport, expected: PacketBuilder.getContacts(since: since.map { Date(timeIntervalSince1970: TimeInterval($0)) }))
    await transport.simulateReceive(startPacket(total: total))
    for contact in contacts {
      await transport.simulateReceive(contactPacket(contact))
    }
    if let pending {
      var packet = contactPacket(pending)
      packet[0] = ResponseCode.newAdvertisement.rawValue
      await transport.simulateReceive(packet)
    }
    await transport.simulateReceive(endPacket(modified: modified))
  }

  private func makeContact(key: UInt8, name: String, modified: UInt32) -> MeshContact {
    let publicKey = Data(repeating: key, count: 32)
    return MeshContact(
      id: publicKey.hexString,
      publicKey: publicKey,
      type: .chat,
      flags: ContactFlags(),
      outPathLength: 0xFF,
      outPath: Data(),
      advertisedName: name,
      lastAdvertisement: Date(timeIntervalSince1970: 0),
      latitude: 0,
      longitude: 0,
      lastModified: Date(timeIntervalSince1970: TimeInterval(modified))
    )
  }

  private func ordered(_ contacts: [MeshContact]) -> [MeshContact] {
    contacts.sorted { $0.id < $1.id }
  }

  private func contactPacket(_ contact: MeshContact) -> Data {
    var packet = PacketBuilder.updateContact(contact)
    packet[0] = ResponseCode.contact.rawValue
    packet.appendLittleEndian(UInt32(contact.lastModified.timeIntervalSince1970))
    return packet
  }

  private func startPacket(total: UInt32) -> Data {
    var packet = Data([ResponseCode.contactStart.rawValue])
    packet.appendLittleEndian(total)
    return packet
  }

  private func endPacket(modified: UInt32) -> Data {
    var packet = Data([ResponseCode.contactEnd.rawValue])
    packet.appendLittleEndian(modified)
    return packet
  }

  private func startedSession(_ transport: MockTransport) async throws -> MeshCoreSession {
    let session = MeshCoreSession(transport: transport)
    let start = Task { try await session.start() }
    try await waitForCommand(transport, expected: PacketBuilder.appStart(clientId: SessionConfiguration().clientIdentifier))
    await transport.simulateReceive(Data([ResponseCode.selfInfo.rawValue, 1, 22, 22]) + Data(repeating: 0, count: 54))
    try await start.value
    return session
  }

  private func waitForCommand(_ transport: MockTransport, expected: Data) async throws {
    let deadline = ContinuousClock.now.advanced(by: .seconds(2))
    while ContinuousClock.now < deadline {
      if let sent = await transport.sentData.first {
        #expect(sent == expected)
        await transport.clearSentData()
        return
      }
      try await Task.sleep(for: .milliseconds(5))
    }
    throw MeshCoreError.timeout
  }
}
