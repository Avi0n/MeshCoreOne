import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Region discovery owner", .serialized)
@MainActor
struct RegionDiscoveryStateTests {
  @Test
  func `advanced and regions share one run and live regions`() async throws {
    let fixture = try DiscoveryFixture()
    defer { fixture.tearDown() }
    let owner = fixture.owner
    fixture.install(device: fixture.device(regions: ["South"]))
    owner.setWorkflowActive(true)
    #expect(owner.knownRegions == ["South"])

    owner.discover = { .noRepeatersResponded }
    owner.start()
    await fixture.flush()
    #expect(owner.isDiscovering == false)
    #expect(owner.discoveryMessage == L10n.Chats.Chats.ChannelInfo.Region.noRepeatersResponded)

    fixture.install(device: fixture.device(regions: ["North", "South"]))
    owner.noteRegionsChanged()
    #expect(owner.knownRegions == ["North", "South"])
    #expect(owner.discoveryMessage == L10n.Chats.Chats.ChannelInfo.Region.noRepeatersResponded)
  }

  @Test
  func `leaving retains completed feedback`() async throws {
    let fixture = try DiscoveryFixture()
    defer { fixture.tearDown() }
    let calls = CallCount()
    fixture.owner.discover = {
      calls.count += 1
      return .errorLoadingRepeaters
    }
    fixture.owner.setWorkflowActive(true)
    fixture.owner.start()
    await fixture.flush()
    let message = fixture.owner.discoveryMessage
    fixture.owner.setWorkflowActive(false)
    fixture.owner.setWorkflowActive(true)
    await fixture.flush()
    #expect(calls.count == 1)
    #expect(fixture.owner.discoveryMessage == message)
    #expect(message == L10n.Chats.Chats.ChannelInfo.Region.errLoadingRepeaters)
    #expect(fixture.owner.isDiscovering == false)
  }

  @Test
  func `newer run keeps busy when the older result arrives first`() async throws {
    let fixture = try DiscoveryFixture()
    defer { fixture.tearDown() }
    let gate = DiscoveryGate()
    defer { gate.resumeAll(.sendFailed) }
    fixture.owner.discover = { await gate.wait() }
    fixture.owner.setWorkflowActive(true)
    fixture.owner.start()
    #expect(await gate.untilWaiting(1))
    fixture.owner.start()
    #expect(await gate.untilWaiting(2))

    gate.resumeFirst(.completed(newRegions: ["Old"], allRepeatersTableFull: false))
    await fixture.flush()
    #expect(fixture.owner.isDiscovering)
    #expect(fixture.owner.knownRegions.contains("Old") == false)

    gate.resumeFirst(.noRepeatersResponded)
    await fixture.flush()
    #expect(fixture.owner.isDiscovering == false)
    #expect(fixture.owner.discoveryMessage == L10n.Chats.Chats.ChannelInfo.Region.noRepeatersResponded)
    #expect(fixture.owner.knownRegions.contains("Old") == false)
  }

  @Test
  func `older result does not replace A finished newer run`() async throws {
    let fixture = try DiscoveryFixture()
    defer { fixture.tearDown() }
    let gate = DiscoveryGate()
    defer { gate.resumeAll(.sendFailed) }
    fixture.owner.discover = { await gate.wait() }
    fixture.owner.setWorkflowActive(true)
    fixture.owner.start()
    #expect(await gate.untilWaiting(1))
    fixture.owner.start()
    #expect(await gate.untilWaiting(2))

    gate.resumeLast(.completed(newRegions: ["New"], allRepeatersTableFull: false))
    await fixture.flush()
    #expect(fixture.owner.knownRegions == ["New"])
    #expect(fixture.owner.isDiscovering == false)

    gate.resumeFirst(.completed(newRegions: ["Old"], allRepeatersTableFull: false))
    await fixture.flush()
    #expect(fixture.owner.knownRegions == ["New"])
    #expect(fixture.owner.discoveryMessage == nil)
  }

  @Test
  func `cancelled run does not publish or mutate`() async throws {
    let fixture = try DiscoveryFixture()
    defer { fixture.tearDown() }
    let gate = DiscoveryGate()
    defer { gate.resumeAll(.sendFailed) }
    fixture.owner.discover = { await gate.wait() }
    fixture.owner.setWorkflowActive(true)
    fixture.owner.start()
    #expect(await gate.untilWaiting(1))
    fixture.owner.setWorkflowActive(false)
    #expect(fixture.owner.isDiscovering == false)
    gate.resumeFirst(.completed(newRegions: ["Late"], allRepeatersTableFull: false))
    await fixture.flush()
    #expect(fixture.owner.knownRegions.isEmpty)
    #expect(fixture.owner.discoveryMessage == nil)
    fixture.owner.addKnownRegion("Manual")
    #expect(fixture.owner.knownRegions.contains("Manual") == false)
  }

  @Test
  func `radio or session replacement drops the run`() async throws {
    let fixture = try DiscoveryFixture()
    defer { fixture.tearDown() }
    let gate = DiscoveryGate()
    defer { gate.resumeAll(.sendFailed) }
    fixture.owner.discover = { await gate.wait() }
    fixture.owner.setWorkflowActive(true)
    fixture.owner.start()
    #expect(await gate.untilWaiting(1))

    fixture.install(device: fixture.device(radioID: UUID(), regions: []))
    fixture.owner.noteConnectionChanged()
    #expect(fixture.owner.isDiscovering == false)
    gate.resumeAll(.completed(newRegions: ["Moved"], allRepeatersTableFull: false))
    await fixture.flush()
    #expect(fixture.owner.knownRegions.contains("Moved") == false)

    fixture.replaceSession()
    fixture.owner.noteConnectionChanged()
    fixture.owner.start()
    #expect(await gate.untilWaiting(1))
    fixture.replaceSession()
    fixture.owner.noteConnectionChanged()
    #expect(fixture.owner.isDiscovering == false)
    gate.resumeFirst(.completed(newRegions: ["Stale"], allRepeatersTableFull: false))
    await fixture.flush()
    #expect(fixture.owner.knownRegions.contains("Stale") == false)
    #expect(fixture.owner.isDiscovering == false)
  }

  @Test
  func `same radio snapshot keeps the run`() async throws {
    let fixture = try DiscoveryFixture()
    defer { fixture.tearDown() }
    let gate = DiscoveryGate()
    defer { gate.resumeAll(.sendFailed) }
    fixture.owner.discover = { await gate.wait() }
    fixture.owner.setWorkflowActive(true)
    fixture.owner.start()
    #expect(await gate.untilWaiting(1))
    fixture.install(device: fixture.device(name: "Field", regions: []))
    fixture.owner.noteConnectionChanged()
    #expect(fixture.owner.isDiscovering)
    gate.resumeFirst(.completed(newRegions: ["North"], allRepeatersTableFull: false))
    await fixture.flush()
    #expect(fixture.owner.knownRegions == ["North"])
    #expect(fixture.owner.isDiscovering == false)
  }

  @Test
  func `admitted callback can add and A closed workflow cannot`() throws {
    let fixture = try DiscoveryFixture()
    defer { fixture.tearDown() }
    fixture.owner.setWorkflowActive(true)
    fixture.owner.addKnownRegion("North")
    #expect(fixture.owner.knownRegions == ["North"])
    fixture.owner.removeKnownRegion("North")
    #expect(fixture.owner.knownRegions.isEmpty)
    fixture.owner.setWorkflowActive(false)
    fixture.owner.addKnownRegion("South")
    #expect(fixture.owner.knownRegions.isEmpty)
  }

  @Test
  func `suspended production discovery does not retain its owner`() async throws {
    let fixture = try DiscoveryFixture()
    defer { fixture.tearDown() }
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    defer { Task { await session.stop() } }
    let startup = Task { try await session.start() }
    defer { startup.cancel() }
    try await waitUntil(timeout: .seconds(1), "app start should send") {
      await transport.sentData.count == 1
    }
    var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    packet.append(Data(repeating: 1, count: ProtocolLimits.publicKeySize))
    packet.append(Data(repeating: 0, count: 12))
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
    packet.append(contentsOf: [7, 5])
    await transport.simulateReceive(packet)
    try await startup.value

    let dataStore = fixture.appState.connectionManager.persistenceStore
    let contactService = ContactService(
      session: session,
      dataStore: dataStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )
    let state = fixture.appState
    var owner: RegionDiscoveryState? = RegionDiscoveryState()
    owner?.configure(
      session: { session },
      contactService: { contactService },
      connectedDevice: { state.connectedDevice },
      connectionState: { state.connectionState },
      connectionManager: state.connectionManager,
      dataStore: dataStore
    )
    owner?.setWorkflowActive(true)
    weak let releasedOwner = owner
    owner?.start()
    try await waitUntil(timeout: .seconds(1), "production discovery should send its probe") {
      await transport.sentData.count == 2
    }

    owner = nil
    await fixture.flush()

    #expect(releasedOwner == nil)
    await session.stop()
  }
}
