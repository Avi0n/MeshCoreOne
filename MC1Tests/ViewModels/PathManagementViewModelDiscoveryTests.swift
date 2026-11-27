import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("PathManagementViewModel Discovery", .timeLimit(.minutes(1)))
struct PathManagementViewModelDiscoveryTests {
  @Test
  @MainActor
  func `Response with hops presents a success result and refreshes contact`() async throws {
    let fixture = try await DiscoveryFixture()
    defer { fixture.stop() }

    let key = Data(repeating: 0x11, count: 32)
    let contact = try await fixture.seedContact(publicKey: key)
    var refreshed = false
    fixture.vm.onContactNeedsRefresh = { refreshed = true }

    let task = Task { @MainActor in
      await fixture.vm.discoverPath(for: contact)
    }

    try await waitUntil("discovery frame sent") {
      await fixture.transport.sentData.contains { $0.first == CommandCode.pathDiscovery.rawValue }
    }
    await fixture.transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 5000))
    try await waitUntil("discovery countdown set") { fixture.vm.discoverySecondsRemaining != nil }
    await fixture.transport.simulateReceive(makePathDiscoveryPacket(
      publicKeyPrefix: Data(key.prefix(6)),
      outPathLength: 2,
      outPath: Data([0xAA, 0xBB])
    ))
    try await waitUntil("setPath fetches live contact") {
      await fixture.transport.sentData.contains { $0.first == CommandCode.getContactByKey.rawValue }
    }
    await fixture.transport.simulateReceive(makeContactPacket(
      publicKey: key,
      outPathLength: PacketBuilder.floodPathSentinel,
      outPath: Data(),
      name: "Node"
    ))
    try await waitUntil("discovery writes updateContact") {
      await fixture.transport.sentData.contains { $0.first == CommandCode.updateContact.rawValue }
    }
    await fixture.transport.simulateReceive(Data([ResponseCode.ok.rawValue]))
    await task.value

    #expect(fixture.vm.discoveryResult == .success(hopCount: 2))
    #expect(fixture.vm.showDiscoveryResult == true)
    #expect(fixture.vm.isDiscovering == false)
    #expect(refreshed)
  }

  @Test
  @MainActor
  func `Response without a decodable path presents no-path-found`() async throws {
    let fixture = try await DiscoveryFixture()
    defer { fixture.stop() }

    let key = Data(repeating: 0x22, count: 32)
    let contact = try await fixture.seedContact(publicKey: key)
    var refreshed = false
    fixture.vm.onContactNeedsRefresh = { refreshed = true }

    let task = Task { @MainActor in
      await fixture.vm.discoverPath(for: contact)
    }

    try await waitUntil("discovery frame sent") {
      await fixture.transport.sentData.contains { $0.first == CommandCode.pathDiscovery.rawValue }
    }
    await fixture.transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0x11, 0x22, 0x33, 0x44]), suggestedTimeoutMs: 5000))
    try await waitUntil("discovery countdown set") { fixture.vm.discoverySecondsRemaining != nil }
    // 0xC1 uses reserved hash-size mode 3, which decodes with outHopCount == nil.
    await fixture.transport.simulateReceive(makePathDiscoveryPacket(
      publicKeyPrefix: Data(key.prefix(6)),
      outPathLength: 0xC1,
      outPath: Data()
    ))
    await task.value

    #expect(fixture.vm.discoveryResult == .noPathFound)
    #expect(fixture.vm.showDiscoveryResult == true)
    #expect(fixture.vm.isDiscovering == false)
    #expect(!refreshed)
  }

  @Test
  @MainActor
  func `Discovery timeout presents no-path-found`() async throws {
    let fixture = try await DiscoveryFixture()
    defer { fixture.stop() }

    let key = Data(repeating: 0x33, count: 32)
    let contact = try await fixture.seedContact(publicKey: key)
    var refreshed = false
    fixture.vm.onContactNeedsRefresh = { refreshed = true }

    let task = Task { @MainActor in
      await fixture.vm.discoverPath(for: contact)
    }

    try await waitUntil("discovery frame sent") {
      await fixture.transport.sentData.contains { $0.first == CommandCode.pathDiscovery.rawValue }
    }
    await fixture.transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0x21, 0x22, 0x23, 0x24]), suggestedTimeoutMs: 5000))
    try await waitUntil("discovery timer parked") {
      fixture.vm.discoverySecondsRemaining != nil && fixture.clock.nextSleepDuration == .seconds(20)
    }
    fixture.clock.advance(by: .seconds(60))
    await task.value

    #expect(fixture.vm.discoveryResult == .noPathFound)
    #expect(fixture.vm.showDiscoveryResult == true)
    #expect(fixture.vm.isDiscovering == false)
    #expect(!refreshed)
  }

  @Test
  @MainActor
  func `Cancel stops in-flight discovery without showing a result`() async throws {
    let fixture = try await DiscoveryFixture()
    defer { fixture.stop() }

    let key = Data(repeating: 0x44, count: 32)
    let contact = try await fixture.seedContact(publicKey: key)
    var refreshed = false
    fixture.vm.onContactNeedsRefresh = { refreshed = true }

    let task = Task { @MainActor in
      await fixture.vm.discoverPath(for: contact)
    }

    try await waitUntil("discovery frame sent") {
      await fixture.transport.sentData.contains { $0.first == CommandCode.pathDiscovery.rawValue }
    }
    await fixture.transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0x41, 0x42, 0x43, 0x44]), suggestedTimeoutMs: 5000))
    try await waitUntil("discovery countdown set") { fixture.vm.discoverySecondsRemaining != nil }
    fixture.vm.cancelDiscovery()
    await task.value

    #expect(fixture.vm.isDiscovering == false)
    #expect(fixture.vm.discoveryResult == nil)
    #expect(fixture.vm.showDiscoveryResult == false)
    #expect(!refreshed)
  }

  @Test
  @MainActor
  func `Radio write failure presents the discovery alert and leaves errorMessage nil`() async throws {
    let fixture = try await DiscoveryFixture()
    defer { fixture.stop() }

    let key = Data(repeating: 0x55, count: 32)
    let unseededContact = ContactDTO(from: Contact(
      radioID: fixture.radioID,
      publicKey: key,
      name: "Missing",
      typeRawValue: ContactType.repeater.rawValue,
      lastHeardTimestamp: 0
    ))
    var refreshed = false
    fixture.vm.onContactNeedsRefresh = { refreshed = true }

    let task = Task { @MainActor in
      await fixture.vm.discoverPath(for: unseededContact)
    }

    try await waitUntil("discovery frame sent") {
      await fixture.transport.sentData.contains { $0.first == CommandCode.pathDiscovery.rawValue }
    }
    await fixture.transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0x31, 0x32, 0x33, 0x34]), suggestedTimeoutMs: 5000))
    try await waitUntil("discovery countdown set") { fixture.vm.discoverySecondsRemaining != nil }
    await fixture.transport.simulateReceive(makePathDiscoveryPacket(
      publicKeyPrefix: Data(key.prefix(6)),
      outPathLength: 1,
      outPath: Data([0xAA])
    ))
    await task.value

    #expect(fixture.vm.discoveryResult == .failed(ContactServiceError.contactNotFound.userFacingMessage))
    #expect(fixture.vm.showDiscoveryResult == true)
    #expect(fixture.vm.isDiscovering == false)
    #expect(fixture.vm.errorMessage == nil)
    #expect(!refreshed)
  }
}

@MainActor
private final class DiscoveryFixture {
  let radioID = UUID()
  let transport = MockTransport()
  let clock = TestClock()
  let session: MeshCoreSession
  let store: PersistenceStore
  let services: ServiceContainer
  let vm: PathManagementViewModel
  private let suiteName: String

  init() async throws {
    suiteName = "PathDiscoveryVMTests.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defaults.removePersistentDomain(forName: suiteName)

    session = MeshCoreSession(transport: transport, clock: clock)
    store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    services = ServiceContainer(session: session, dataStore: store, radioID: radioID)
    vm = PathManagementViewModel(defaults: defaults)
    vm.configure(
      dataStore: { [store] in store },
      contactService: { [services] in services.contactService },
      connectedDevice: { nil },
      onContactNeedsRefresh: {}
    )

    let startTask = Task { try await session.start() }
    try await waitUntil("appStart sent") { await self.transport.sentData.contains { $0.first == CommandCode.appStart.rawValue } }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    await services.startEventMonitoring(radioID: radioID)
  }

  func seedContact(publicKey: Data) async throws -> ContactDTO {
    let contact = ContactDTO(from: Contact(
      radioID: radioID,
      publicKey: publicKey,
      name: "Node",
      typeRawValue: ContactType.repeater.rawValue,
      lastHeardTimestamp: 0
    ))
    try await store.saveContact(contact)
    return contact
  }

  func stop() {
    UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName)
    Task { await session.stop() }
  }
}

private func makeSelfInfoPacket() -> Data {
  var payload = Data([1, UInt8(bitPattern: 22), UInt8(bitPattern: 22)])
  payload.append(Data(repeating: 0x01, count: 32))
  payload.append(contentsOf: [0, 0, 0, 0, 0, 0, 0, 0])
  payload.append(contentsOf: [0, 0, 0, 0])
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
  payload.append(contentsOf: [7, 5])
  payload.append(contentsOf: "Test".utf8)
  return Data([ResponseCode.selfInfo.rawValue]) + payload
}

private func makeMessageSentPacket(expectedAck: Data, suggestedTimeoutMs: UInt32) -> Data {
  var packet = Data([ResponseCode.messageSent.rawValue, 0])
  packet.append(expectedAck)
  packet.append(contentsOf: withUnsafeBytes(of: suggestedTimeoutMs.littleEndian) { Array($0) })
  return packet
}

private func makePathDiscoveryPacket(publicKeyPrefix: Data, outPathLength: UInt8, outPath: Data) -> Data {
  var packet = Data([ResponseCode.pathDiscoveryResponse.rawValue, 0x00])
  packet.append(publicKeyPrefix)
  packet.append(outPathLength)
  packet.append(outPath)
  packet.append(0x00)
  return packet
}

private func makeContactPacket(publicKey: Data, outPathLength: UInt8, outPath: Data, name: String) -> Data {
  var packet = Data([ResponseCode.contact.rawValue])
  packet.append(publicKey)
  packet.append(ContactType.repeater.rawValue)
  packet.append(0)
  packet.append(outPathLength)
  let paddedPath = outPath.prefix(PathEncoding.maxPathBytes)
  packet.append(paddedPath)
  if paddedPath.count < PathEncoding.maxPathBytes {
    packet.append(Data(repeating: 0, count: PathEncoding.maxPathBytes - paddedPath.count))
  }
  let nameBytes = Array(name.utf8.prefix(31))
  packet.append(contentsOf: nameBytes)
  packet.append(0)
  if nameBytes.count < 31 {
    packet.append(Data(repeating: 0, count: 31 - nameBytes.count))
  }
  packet.append(contentsOf: [0, 0, 0, 0])
  packet.append(contentsOf: [0, 0, 0, 0])
  packet.append(contentsOf: [0, 0, 0, 0])
  packet.append(contentsOf: [0, 0, 0, 0])
  return packet
}
