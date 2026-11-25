import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@MainActor
struct RxLogViewModelTests {
  // MARK: - RxLogEntryDTO Computed Properties

  @Test
  func `traceTargetHashes extracts 1-byte hashes when path_sz=0`() {
    // flags byte: path_sz=0 → hashSize = 1<<0 = 1
    var payload = Data(repeating: 0, count: 9) // [tag:4][auth:4][flags:1]
    payload[8] = 0x00 // path_sz = 0
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC])
    let dto = makeDTO(payloadType: .trace, packetPayload: payload)

    let hashes = dto.traceTargetHashes
    #expect(hashes?.count == 3)
    #expect(hashes?[0] == Data([0xAA]))
    #expect(hashes?[1] == Data([0xBB]))
    #expect(hashes?[2] == Data([0xCC]))
  }

  @Test
  func `traceTargetHashes extracts 2-byte hashes when path_sz=1`() {
    // flags byte: path_sz=1 → hashSize = 1<<1 = 2
    var payload = Data(repeating: 0, count: 9)
    payload[8] = 0x01
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC, 0xDD])
    let dto = makeDTO(payloadType: .trace, packetPayload: payload)

    let hashes = dto.traceTargetHashes
    #expect(hashes?.count == 2)
    #expect(hashes?[0] == Data([0xAA, 0xBB]))
    #expect(hashes?[1] == Data([0xCC, 0xDD]))
  }

  @Test
  func `traceTargetHashes returns nil for non-TRACE payload type`() {
    var payload = Data(repeating: 0, count: 12)
    payload[8] = 0x00
    let dto = makeDTO(payloadType: .textMessage, packetPayload: payload)
    #expect(dto.traceTargetHashes == nil)
  }

  @Test
  func `traceTargetHashes returns nil when payload is too short`() {
    let payload = Data(repeating: 0, count: 8) // needs > 9
    let dto = makeDTO(payloadType: .trace, packetPayload: payload)
    #expect(dto.traceTargetHashes == nil)
  }

  @Test
  func `traceTargetHashes returns nil when hash bytes don't divide evenly`() {
    // path_sz=1 → hashSize=2, but 3 remaining bytes don't divide evenly
    var payload = Data(repeating: 0, count: 9)
    payload[8] = 0x01
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC])
    let dto = makeDTO(payloadType: .trace, packetPayload: payload)
    #expect(dto.traceTargetHashes == nil)
  }

  @Test
  func `senderPrefix extracts correct bytes for hashSize=1`() {
    // pathLength encodes hashSize=1: mode=0, hops=1 → 0x01
    let payload = Data([0xDD, 0xAA, 0xFF, 0xFF]) // [dest:1][src:1][rest]
    let dto = makeDTO(routeType: .direct, payloadType: .textMessage, pathLength: 0x01, packetPayload: payload)

    #expect(dto.senderPrefix == Data([0xAA]))
    #expect(dto.recipientPrefix == Data([0xDD]))
  }

  @Test
  func `senderPrefix uses fixed 1-byte payload hashes when path hashSize=2`() {
    // pathLength encodes hashSize=2: mode=1 → (1<<6)|hops = 0x41
    // DM payload hashes remain 1 byte even when routed path hashes are 2 bytes.
    let payload = Data([0xDD, 0xAA, 0xFF, 0xFF])
    let dto = makeDTO(routeType: .direct, payloadType: .textMessage, pathLength: 0x41, packetPayload: payload)

    #expect(dto.senderPrefix == Data([0xAA]))
    #expect(dto.recipientPrefix == Data([0xDD]))
  }

  @Test
  func `senderPrefix uses fixed 1-byte payload hashes when path hashSize=3`() {
    // pathLength encodes hashSize=3: mode=2 → (2<<6)|hops = 0x81
    // DM payload hashes remain 1 byte even when routed path hashes are 3 bytes.
    let payload = Data([0xDD, 0xAA, 0xFF, 0xFF])
    let dto = makeDTO(routeType: .direct, payloadType: .textMessage, pathLength: 0x81, packetPayload: payload)

    #expect(dto.senderPrefix == Data([0xAA]))
    #expect(dto.recipientPrefix == Data([0xDD]))
  }

  @Test
  func `senderPrefix extracts the same offsets on a flood route`() {
    let payload = Data([0xDD, 0xAA, 0xFF, 0xFF])
    let dto = makeDTO(routeType: .flood, payloadType: .textMessage, pathLength: 0x01, packetPayload: payload)
    #expect(dto.senderPrefix == Data([0xAA]))
    #expect(dto.recipientPrefix == Data([0xDD]))
  }

  @Test
  func `senderPrefix returns nil for non-text payload`() {
    let payload = Data([0xDD, 0xAA, 0xFF, 0xFF])
    let dto = makeDTO(routeType: .direct, payloadType: .trace, pathLength: 0x01, packetPayload: payload)
    #expect(dto.senderPrefix == nil)
  }

  @Test
  func `pathHashSize decodes TRACE using standard pathLength encoding`() {
    // pathLength=0x41 → mode=1, hashSize=2
    let dto = makeDTO(payloadType: .trace, pathLength: 0x41)
    #expect(dto.pathHashSize == 2)
  }

  @Test
  func `hopCount decodes TRACE using standard pathLength encoding`() {
    // pathLength=0x43 → mode=1, hashSize=2, hopCount=3
    let dto = makeDTO(payloadType: .trace, pathLength: 0x43)
    #expect(dto.hopCount == 3)
  }

  @Test
  func `hopCount uses decodePathLen for non-TRACE`() {
    // pathLength=0x43 → mode=1, hashSize=2, hopCount=3
    let dto = makeDTO(payloadType: .textMessage, pathLength: 0x43)
    #expect(dto.hopCount == 3)
    #expect(dto.pathHashSize == 2)
  }

  @Test
  func `pathHopHashes chunks path nodes by hash size`() {
    let oneByte = makeDTO(pathLength: 0x02, pathNodes: [0xAA, 0xBB])
    #expect(oneByte.pathHopHashes == [Data([0xAA]), Data([0xBB])])

    let twoByte = makeDTO(pathLength: 0x42, pathNodes: [0xAA, 0x11, 0xBB, 0x22])
    #expect(twoByte.pathHopHashes == [Data([0xAA, 0x11]), Data([0xBB, 0x22])])

    let threeByte = makeDTO(pathLength: 0x81, pathNodes: [0xAA, 0x11, 0x22])
    #expect(threeByte.pathHopHashes == [Data([0xAA, 0x11, 0x22])])
  }

  // MARK: - buildNodeNameMap

  @Test
  func `Empty contacts produces empty map`() {
    let map = RxLogViewModel.buildNodeNameMap(from: [])
    #expect(map.isEmpty)
  }

  @Test
  func `Single contact generates entries for 1, 2, and 3-byte prefixes`() {
    let key = Data([0xAA, 0xBB, 0xCC, 0xDD])
    let contact = makeContact(name: "Alice", publicKey: key)
    let map = RxLogViewModel.buildNodeNameMap(from: [contact])

    #expect(map[Data([0xAA])] == "Alice")
    #expect(map[Data([0xAA, 0xBB])] == "Alice")
    #expect(map[Data([0xAA, 0xBB, 0xCC])] == "Alice")
  }

  @Test
  func `Two contacts with different first bytes resolve at all prefix lengths`() {
    let contacts = [
      makeContact(name: "Alice", publicKey: Data([0xAA, 0xBB, 0xCC, 0xDD])),
      makeContact(name: "Bob", publicKey: Data([0x11, 0x22, 0x33, 0x44]))
    ]
    let map = RxLogViewModel.buildNodeNameMap(from: contacts)

    #expect(map[Data([0xAA])] == "Alice")
    #expect(map[Data([0x11])] == "Bob")
    #expect(map[Data([0xAA, 0xBB])] == "Alice")
    #expect(map[Data([0x11, 0x22])] == "Bob")
    #expect(map[Data([0xAA, 0xBB, 0xCC])] == "Alice")
    #expect(map[Data([0x11, 0x22, 0x33])] == "Bob")
  }

  @Test
  func `Two contacts sharing first byte omit 1-byte entry but resolve at 2 and 3 bytes`() {
    let contacts = [
      makeContact(name: "Alice", publicKey: Data([0xAA, 0xBB, 0xCC, 0xDD])),
      makeContact(name: "Bob", publicKey: Data([0xAA, 0x22, 0x33, 0x44]))
    ]
    let map = RxLogViewModel.buildNodeNameMap(from: contacts)

    // 1-byte prefix is ambiguous — should not be in the map
    #expect(map[Data([0xAA])] == nil)

    // 2-byte prefixes are unique
    #expect(map[Data([0xAA, 0xBB])] == "Alice")
    #expect(map[Data([0xAA, 0x22])] == "Bob")

    // 3-byte prefixes are unique
    #expect(map[Data([0xAA, 0xBB, 0xCC])] == "Alice")
    #expect(map[Data([0xAA, 0x22, 0x33])] == "Bob")
  }

  @Test
  func `Two contacts sharing first two bytes omit 1 and 2-byte entries but resolve at 3 bytes`() {
    let contacts = [
      makeContact(name: "Alice", publicKey: Data([0xAA, 0xBB, 0xCC, 0xDD])),
      makeContact(name: "Bob", publicKey: Data([0xAA, 0xBB, 0x33, 0x44]))
    ]
    let map = RxLogViewModel.buildNodeNameMap(from: contacts)

    #expect(map[Data([0xAA])] == nil)
    #expect(map[Data([0xAA, 0xBB])] == nil)
    #expect(map[Data([0xAA, 0xBB, 0xCC])] == "Alice")
    #expect(map[Data([0xAA, 0xBB, 0x33])] == "Bob")
  }

  @Test
  func `Contact with short public key only generates entries for available lengths`() {
    let contacts = [
      makeContact(name: "Short", publicKey: Data([0xAA, 0xBB]))
    ]
    let map = RxLogViewModel.buildNodeNameMap(from: contacts)

    #expect(map[Data([0xAA])] == "Short")
    #expect(map[Data([0xAA, 0xBB])] == "Short")
    // 3-byte prefix not generated since key only has 2 bytes
    #expect(map[Data([0xAA, 0xBB])] == "Short")
    #expect(map.count == 2)
  }

  @Test
  func `Nickname takes precedence over name via displayName`() {
    let contact = makeContact(name: "Alice Jones", publicKey: Data([0xAA, 0xBB, 0xCC, 0xDD]), nickname: "AJ")
    let map = RxLogViewModel.buildNodeNameMap(from: [contact])

    #expect(map[Data([0xAA])] == "AJ")
  }

  @Test
  func `subscribe stores stream entries and unsubscribe drops later ones`() async throws {
    let services = try await ServiceContainer.forTesting(
      session: MeshCoreSession(transport: MockTransport())
    )
    let service = services.rxLogService
    await service.startEventMonitoring(radioID: UUID())
    defer { Task { await service.stopEventMonitoring() } }

    let viewModel = RxLogViewModel()
    viewModel.configure(
      rxLogService: { service },
      dataStore: { nil },
      radioID: { nil }
    )

    await viewModel.subscribe()
    #expect(viewModel.streamTaskForTesting != nil)

    let first = parsedPacket(raw: 0x11)
    await service.process(first)
    try await waitUntil(timeout: .seconds(1), "first stream entry should append") {
      viewModel.entries.contains { $0.rawPayload == first.rawPayload }
    }

    viewModel.unsubscribe()
    #expect(viewModel.streamTaskForTesting == nil)

    let dropped = parsedPacket(raw: 0x22)
    await service.process(dropped)
    try await Task.sleep(for: .milliseconds(100))
    #expect(viewModel.entries.contains { $0.rawPayload == dropped.rawPayload } == false)

    await viewModel.subscribe()
    let later = parsedPacket(raw: 0x33)
    await service.process(later)
    try await waitUntil(timeout: .seconds(1), "later stream entry should append") {
      viewModel.entries.contains { $0.rawPayload == later.rawPayload }
    }
    viewModel.unsubscribe()
  }

  @Test
  func `unsubscribe during loadExistingEntries does not install a stream`() async throws {
    let services = try await ServiceContainer.forTesting(
      session: MeshCoreSession(transport: MockTransport())
    )
    let service = services.rxLogService
    await service.startEventMonitoring(radioID: UUID())
    defer { Task { await service.stopEventMonitoring() } }

    let hang = HangingEntryLoad()
    let viewModel = RxLogViewModel()
    viewModel.configure(
      rxLogService: { service },
      dataStore: { nil },
      radioID: { nil }
    )
    viewModel.loadExistingEntriesForTesting = { await hang.load() }

    let subscribeTask = Task { await viewModel.subscribe() }
    try await waitUntil(timeout: .seconds(1), "load should start") { hang.started }

    viewModel.unsubscribe()
    #expect(viewModel.streamTaskForTesting == nil)

    hang.complete()
    await subscribeTask.value
    #expect(viewModel.streamTaskForTesting == nil)

    let dropped = parsedPacket(raw: 0x44)
    await service.process(dropped)
    try await Task.sleep(for: .milliseconds(100))
    #expect(viewModel.entries.contains { $0.rawPayload == dropped.rawPayload } == false)
  }

  @Test
  func `a second subscribe does not append twice`() async throws {
    let services = try await ServiceContainer.forTesting(
      session: MeshCoreSession(transport: MockTransport())
    )
    let service = services.rxLogService
    await service.startEventMonitoring(radioID: UUID())
    defer { Task { await service.stopEventMonitoring() } }

    let viewModel = RxLogViewModel()
    viewModel.configure(
      rxLogService: { service },
      dataStore: { nil },
      radioID: { nil }
    )

    await viewModel.subscribe()
    await viewModel.subscribe()

    let packet = parsedPacket(raw: 0x66)
    await service.process(packet)
    try await waitUntil(timeout: .seconds(1), "one subscriber should append") {
      viewModel.entries.contains { $0.rawPayload == packet.rawPayload }
    }
    #expect(viewModel.entries.filter { $0.rawPayload == packet.rawPayload }.count == 1)
    viewModel.unsubscribe()
  }

  // MARK: - Pause buffer

  @Test
  func `scrolled away arrivals stay pending and flush newest first`() async throws {
    let (viewModel, service) = try await listeningViewModel()
    defer { Task { await service.stopEventMonitoring() } }
    await viewModel.subscribe()
    viewModel.setScrolledAway(true)

    let older = parsedPacket(raw: 0x31)
    let newer = parsedPacket(raw: 0x32)
    await service.process(older)
    await service.process(newer)
    try await waitUntil(timeout: .seconds(1), "both arrivals should be held") {
      viewModel.pendingEntries.count == 2
    }

    #expect(viewModel.entries.isEmpty)
    #expect(viewModel.groupCounts.isEmpty)

    viewModel.setScrolledAway(false)
    #expect(viewModel.pendingEntries.isEmpty)
    #expect(viewModel.entries.map(\.rawPayload) == [newer.rawPayload, older.rawPayload])
    #expect(viewModel.groupCounts == rebuiltGroupCounts(viewModel.entries))
    viewModel.unsubscribe()
  }

  @Test
  func `user pause holds arrivals at the top and resume flushes them`() async throws {
    let (viewModel, service) = try await listeningViewModel()
    defer { Task { await service.stopEventMonitoring() } }
    await viewModel.subscribe()
    viewModel.toggleUserPause()
    #expect(viewModel.isPaused)
    #expect(!viewModel.isScrolledAway)

    let packet = parsedPacket(raw: 0x21)
    await service.process(packet)
    try await waitUntil(timeout: .seconds(1), "paused arrival should be held") {
      viewModel.pendingEntries.contains { $0.rawPayload == packet.rawPayload }
    }
    #expect(viewModel.entries.isEmpty)

    viewModel.toggleUserPause()
    #expect(viewModel.entries.first?.rawPayload == packet.rawPayload)
    #expect(viewModel.pendingEntries.isEmpty)
    viewModel.unsubscribe()
  }

  @Test
  func `resume after the cap keeps maxEntries and rebuilt group counts`() async throws {
    let (viewModel, service) = try await listeningViewModel()
    defer { Task { await service.stopEventMonitoring() } }
    let seed = (0..<RxLogViewModel.maxEntries).map { index in
      makeDTO(packetPayload: Data([UInt8(index & 0xFF), UInt8((index >> 8) & 0xFF)]))
    }
    viewModel.loadExistingEntriesForTesting = { seed }
    await viewModel.subscribe()
    #expect(viewModel.entries.count == RxLogViewModel.maxEntries)

    viewModel.setScrolledAway(true)
    let countsWhilePaused = viewModel.groupCounts
    for raw in [UInt8(0xA1), 0xA2, 0xA3] {
      await service.process(parsedPacket(raw: raw))
    }
    try await waitUntil(timeout: .seconds(1), "paused arrivals should be held") {
      viewModel.pendingEntries.count == 3
    }
    #expect(viewModel.entries.count == RxLogViewModel.maxEntries)
    #expect(viewModel.groupCounts == countsWhilePaused)

    viewModel.setScrolledAway(false)
    #expect(viewModel.entries.count == RxLogViewModel.maxEntries)
    #expect(viewModel.pendingEntries.isEmpty)
    #expect(viewModel.groupCounts == rebuiltGroupCounts(viewModel.entries))
    #expect(viewModel.entries.first?.rawPayload == Data([0xA3]))
    viewModel.unsubscribe()
  }

  @Test
  func `clearLog drops a pause and lets the next packet into entries`() async throws {
    let (viewModel, service) = try await listeningViewModel()
    defer { Task { await service.stopEventMonitoring() } }
    await viewModel.subscribe()
    viewModel.toggleUserPause()
    viewModel.setScrolledAway(true)

    let held = parsedPacket(raw: 0x40)
    await service.process(held)
    try await waitUntil(timeout: .seconds(1), "paused arrival should be held") {
      viewModel.pendingEntries.contains { $0.rawPayload == held.rawPayload }
    }

    await viewModel.clearLog()
    #expect(!viewModel.isPaused)
    #expect(viewModel.pendingEntries.isEmpty)
    #expect(viewModel.entries.isEmpty)

    let packet = parsedPacket(raw: 0x41)
    await service.process(packet)
    try await waitUntil(timeout: .seconds(1), "packet after clearLog should append") {
      viewModel.entries.contains { $0.rawPayload == packet.rawPayload }
    }
    viewModel.unsubscribe()
  }

  @Test
  func `a cancelled load after a service swap leaves the pause buffer empty`() async throws {
    let servicesA = try await ServiceContainer.forTesting(session: MeshCoreSession(transport: MockTransport()))
    let servicesB = try await ServiceContainer.forTesting(session: MeshCoreSession(transport: MockTransport()))
    let serviceA = servicesA.rxLogService
    await serviceA.startEventMonitoring(radioID: UUID())
    defer { Task { await serviceA.stopEventMonitoring() } }

    let (defaults, suiteName) = isolatedDefaults()
    defer { defaults.removePersistentDomain(forName: suiteName) }
    var current = serviceA
    let viewModel = RxLogViewModel(defaults: defaults)
    viewModel.configure(
      rxLogService: { current },
      dataStore: { nil },
      radioID: { nil }
    )
    await viewModel.subscribe()
    viewModel.setScrolledAway(true)
    let packet = parsedPacket(raw: 0x71)
    await serviceA.process(packet)
    try await waitUntil(timeout: .seconds(1), "arrival should be held before the swap") {
      !viewModel.pendingEntries.isEmpty
    }
    viewModel.selectedEntry = viewModel.pendingEntries[0]

    current = servicesB.rxLogService
    let hang = HangingEntryLoad()
    viewModel.loadExistingEntriesForTesting = { await hang.load() }
    let subscribeTask = Task { await viewModel.subscribe() }
    try await waitUntil(timeout: .seconds(1), "replacement load should start") { hang.started }
    #expect(viewModel.pendingEntries.isEmpty)
    #expect(viewModel.selectedEntry == nil)

    viewModel.unsubscribe()
    let cancelled = makeDTO(packetPayload: Data([0x99]))
    hang.complete(with: [cancelled])
    await subscribeTask.value
    #expect(viewModel.pendingEntries.isEmpty)
    #expect(viewModel.selectedEntry == nil)
    #expect(viewModel.entries.contains { $0.id == cancelled.id } == false)
  }

  @Test
  func `clearLog clears the selected packet and resubscribing keeps it`() async throws {
    let (viewModel, service) = try await listeningViewModel()
    defer { Task { await service.stopEventMonitoring() } }
    await viewModel.subscribe()
    viewModel.selectedEntry = makeDTO(packetPayload: Data([0x55]))
    await viewModel.clearLog()
    #expect(viewModel.selectedEntry == nil)

    let kept = makeDTO(packetPayload: Data([0x56]))
    viewModel.selectedEntry = kept
    await viewModel.subscribe()
    #expect(viewModel.selectedEntry == kept)
    viewModel.unsubscribe()
  }

  @Test
  func `paused resubscribe keeps entries and holds newly loaded rows`() async throws {
    let (viewModel, service) = try await listeningViewModel()
    defer { Task { await service.stopEventMonitoring() } }
    let seed = [
      makeDTO(packetPayload: Data([0x01])),
      makeDTO(packetPayload: Data([0x02]))
    ]
    var loaded = seed
    viewModel.loadExistingEntriesForTesting = { loaded }
    await viewModel.subscribe()
    let seededIDs = viewModel.entries.map(\.id)
    viewModel.setScrolledAway(true)
    viewModel.unsubscribe()

    let extra = [
      makeDTO(packetPayload: Data([0x03])),
      makeDTO(packetPayload: Data([0x04])),
      makeDTO(packetPayload: Data([0x05]))
    ]
    loaded = extra + seed
    await viewModel.subscribe()
    #expect(viewModel.entries.map(\.id) == seededIDs)
    #expect(viewModel.pendingEntries.count == extra.count)
    viewModel.unsubscribe()
  }

  // MARK: - Filters and search

  @Test
  func `hidden types grouping and clearFilters`() async throws {
    let (viewModel, service) = try await listeningViewModel()
    defer { Task { await service.stopEventMonitoring() } }
    let advert = makeDTO(payloadType: .advert, packetPayload: Data([0x11]))
    let ack = makeDTO(payloadType: .ack, packetPayload: Data([0x12]))
    viewModel.loadExistingEntriesForTesting = { [advert, ack] }
    await viewModel.subscribe()

    viewModel.filter.setHidden(true, for: .advert)
    viewModel.filter.groupsArrivals = true
    #expect(viewModel.hasActiveFilters)
    #expect(viewModel.visibleEntries.map(\.id) == [ack.id])

    viewModel.clearFilters()
    #expect(!viewModel.hasActiveFilters)
    #expect(viewModel.filter.groupsArrivals)
    #expect(viewModel.visibleEntries.map(\.id) == [advert.id, ack.id])
    viewModel.unsubscribe()
  }

  @Test
  func `pendingVisibleCount respects filters while the list stays put`() async throws {
    let (viewModel, service) = try await listeningViewModel()
    defer { Task { await service.stopEventMonitoring() } }
    var loaded: [RxLogEntryDTO] = []
    viewModel.loadExistingEntriesForTesting = { loaded }
    await viewModel.subscribe()
    viewModel.setScrolledAway(true)

    let advert = makeDTO(payloadType: .advert, packetPayload: Data([0x21]), decodedText: "alpha beacon")
    let ack = makeDTO(payloadType: .ack, packetPayload: Data([0x22]), decodedText: "bravo")
    loaded = [advert, ack]
    await viewModel.subscribe()
    viewModel.filter.setHidden(true, for: .advert)
    viewModel.searchText = "bravo"

    #expect(viewModel.pendingVisibleCount == 1)
    #expect(viewModel.entries.isEmpty)
    #expect(viewModel.visibleEntries.isEmpty)
    viewModel.unsubscribe()
  }

  @Test
  func `search matches text channel hash and contact name`() async throws {
    let (viewModel, service) = try await listeningViewModel()
    defer { Task { await service.stopEventMonitoring() } }
    let text = makeDTO(packetPayload: Data([0x31]), decodedText: "weather report")
    let channel = makeDTO(packetPayload: Data([0x32]), channelName: "Ops")
    let named = makeDTO(packetPayload: Data([0x33]), fromContactName: "Casey")
    let hashed = makeDTO(packetPayload: Data([0x34, 0x56]))
    let other = makeDTO(packetPayload: Data([0x35]), decodedText: "unrelated")
    viewModel.loadExistingEntriesForTesting = { [text, channel, named, hashed, other] }
    await viewModel.subscribe()

    viewModel.searchText = "weather"
    #expect(viewModel.visibleEntries.map(\.id) == [text.id])

    viewModel.searchText = "ops"
    #expect(viewModel.visibleEntries.map(\.id) == [channel.id])

    viewModel.searchText = "casey"
    #expect(viewModel.visibleEntries.map(\.id) == [named.id])

    let prefix = String(hashed.packetHash.prefix(6))
    #expect(text.packetHash.hasPrefix(prefix) == false)
    #expect(channel.packetHash.hasPrefix(prefix) == false)
    #expect(named.packetHash.hasPrefix(prefix) == false)
    #expect(other.packetHash.hasPrefix(prefix) == false)
    viewModel.searchText = prefix
    #expect(viewModel.visibleEntries.map(\.id) == [hashed.id])
    viewModel.unsubscribe()
  }

  @Test
  func `search matches hop and direct-message names from saved contacts`() async throws {
    let radioID = UUID()
    let services = try await ServiceContainer.forTesting(
      session: MeshCoreSession(transport: MockTransport()),
      radioID: radioID
    )
    let alphaKey = Data([0xAA, 0x11]) + Data(repeating: 0x01, count: 30)
    let bravoKey = Data([0xBB, 0x22]) + Data(repeating: 0x02, count: 30)
    let alpha = makeContact(name: "Alpha", publicKey: alphaKey, radioID: radioID)
    let bravo = makeContact(name: "Bravo", publicKey: bravoKey, radioID: radioID)
    try await services.dataStore.saveContact(alpha)
    try await services.dataStore.saveContact(bravo)

    let (defaults, suiteName) = isolatedDefaults()
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let viewModel = RxLogViewModel(defaults: defaults)
    viewModel.configure(
      rxLogService: { services.rxLogService },
      dataStore: { services.dataStore },
      radioID: { radioID }
    )
    let hopAA = makeDTO(pathLength: 0x01, pathNodes: [0xAA], packetPayload: Data([0x01]))
    let hopBB = makeDTO(pathLength: 0x41, pathNodes: [0xBB, 0x22], packetPayload: Data([0x02]))
    let hopCC = makeDTO(pathLength: 0x01, pathNodes: [0xCC], packetPayload: Data([0x03]))
    let direct = makeDTO(
      routeType: .direct,
      payloadType: .textMessage,
      packetPayload: Data([0xAA, 0x99, 0x00, 0x00])
    )
    viewModel.loadExistingEntriesForTesting = { [hopAA, hopBB, hopCC, direct] }
    await viewModel.subscribe()
    await viewModel.loadNodeNames()

    viewModel.searchText = "alp"
    #expect(Set(viewModel.visibleEntries.map(\.id)) == Set([hopAA.id, direct.id]))

    viewModel.searchText = "bra"
    #expect(viewModel.visibleEntries.map(\.id) == [hopBB.id])

    try await services.dataStore.deleteContact(id: alpha.id)
    await viewModel.loadNodeNames()
    viewModel.searchText = "alp"
    #expect(viewModel.visibleEntries.isEmpty)
    viewModel.unsubscribe()
  }

  @Test
  func `arrivals lists every reception of a repeated packet`() async throws {
    let (viewModel, service) = try await listeningViewModel()
    defer { Task { await service.stopEventMonitoring() } }
    let shared = Data([0xB0])
    let first = makeDTO(packetPayload: shared)
    let second = makeDTO(packetPayload: shared)
    let third = makeDTO(packetPayload: shared)
    let other = makeDTO(packetPayload: Data([0xB1]))
    viewModel.loadExistingEntriesForTesting = { [first, second, third, other] }
    await viewModel.subscribe()

    #expect(viewModel.arrivals(of: first).map(\.id) == [first.id, second.id, third.id])
    #expect(viewModel.arrivals(of: other).isEmpty)

    viewModel.filter.groupsArrivals = true
    #expect(viewModel.visibleEntries.count == 2)
    viewModel.unsubscribe()
  }

  @Test
  func `RxLogFilter round-trips and falls back when storage is unusable`() {
    let (defaults, suiteName) = isolatedDefaults()
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let viewModel = RxLogViewModel(defaults: defaults)
    viewModel.filter.route = .floodOnly
    viewModel.filter.decrypt = .failed
    viewModel.filter.setHidden(true, for: .ack)
    viewModel.filter.groupsArrivals = true

    let reloaded = RxLogViewModel(defaults: defaults)
    #expect(reloaded.filter.route == .floodOnly)
    #expect(reloaded.filter.decrypt == .failed)
    #expect(reloaded.filter.isHidden(.ack))
    #expect(reloaded.filter.groupsArrivals)

    defaults.set("", forKey: AppStorageKey.rxLogFilter.rawValue)
    #expect(RxLogViewModel(defaults: defaults).filter == RxLogFilter())

    defaults.set("{not-json", forKey: AppStorageKey.rxLogFilter.rawValue)
    #expect(RxLogViewModel(defaults: defaults).filter == RxLogFilter())

    let partial = #"{"route":"floodOnly"}"#
    let decoded = RxLogFilter(storageString: partial)
    #expect(decoded?.route == .floodOnly)
    #expect(decoded?.decrypt == .all)
    #expect(decoded?.groupsArrivals == false)
    #expect(decoded?.hiddenPayloadTypeRawValues.isEmpty == true)

    let unknownRoute = #"{"route":"sideways"}"#
    #expect(RxLogFilter(storageString: unknownRoute)?.route == .all)

    let unknownType = #"{"hiddenPayloadTypeRawValues":[4,250]}"#
    let kept = RxLogFilter(storageString: unknownType)
    #expect(kept?.hiddenPayloadTypeRawValues.contains(250) == true)
    #expect(kept?.hiddenPayloadTypes == [.advert])
  }

  // MARK: - Helpers

  @MainActor
  private final class HangingEntryLoad {
    private(set) var started = false
    private var continuation: CheckedContinuation<[RxLogEntryDTO], Never>?

    func load() async -> [RxLogEntryDTO] {
      started = true
      return await withCheckedContinuation { continuation = $0 }
    }

    func complete(with entries: [RxLogEntryDTO] = []) {
      continuation?.resume(returning: entries)
      continuation = nil
    }
  }

  private func listeningViewModel() async throws -> (RxLogViewModel, RxLogService) {
    let services = try await ServiceContainer.forTesting(
      session: MeshCoreSession(transport: MockTransport())
    )
    let service = services.rxLogService
    await service.startEventMonitoring(radioID: UUID())
    let (defaults, _) = isolatedDefaults()
    let viewModel = RxLogViewModel(defaults: defaults)
    viewModel.configure(
      rxLogService: { service },
      dataStore: { services.dataStore },
      radioID: { nil }
    )
    return (viewModel, service)
  }

  private func isolatedDefaults() -> (UserDefaults, String) {
    let suiteName = "RxLog-\(UUID().uuidString)"
    guard let defaults = UserDefaults(suiteName: suiteName) else {
      preconditionFailure("Could not create UserDefaults suite \(suiteName)")
    }
    defaults.removePersistentDomain(forName: suiteName)
    return (defaults, suiteName)
  }

  private func rebuiltGroupCounts(_ entries: [RxLogEntryDTO]) -> [String: Int] {
    Dictionary(grouping: entries, by: \.packetHash).mapValues(\.count)
  }

  private func parsedPacket(raw: UInt8) -> ParsedRxLogData {
    ParsedRxLogData(
      snr: nil,
      rssi: nil,
      rawPayload: Data([raw]),
      routeType: .flood,
      payloadType: .unknown,
      payloadVersion: 0,
      payloadTypeBits: 0,
      transportCode: nil,
      pathLength: 0,
      pathNodes: [],
      packetPayload: Data([raw])
    )
  }

  private func makeDTO(
    routeType: RouteType = .flood,
    payloadType: PayloadType = .unknown,
    pathLength: UInt8 = 0,
    pathNodes: [UInt8] = [],
    packetPayload: Data = Data(),
    channelName: String? = nil,
    fromContactName: String? = nil,
    decodedText: String? = nil
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
    return RxLogEntryDTO(
      radioID: UUID(),
      from: parsed,
      channelName: channelName,
      fromContactName: fromContactName,
      decodedText: decodedText
    )
  }

  private func makeContact(
    name: String,
    publicKey: Data,
    nickname: String? = nil,
    radioID: UUID = UUID()
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: publicKey,
      name: name,
      typeRawValue: 0,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nickname,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }
}
