import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Observation
import Testing

@Suite("Watchlist refresh", .timeLimit(.minutes(1)))
@MainActor
struct WatchlistRefreshCoordinatorTests {
  @Test
  func `refresh readers observe start and completion when every request fails`() async throws {
    let harness = try await Harness()
    let contact = harness.repeater(name: "North", byte: 0xA0)
    await harness.session.enqueueFailure(status: true, owner: true, firmware: nil)

    await confirmation("Refresh reader observes start") { changed in
      let initial = withObservationTracking {
        harness.coordinator.isRefreshing
      } onChange: {
        changed()
      }
      #expect(!initial)
      harness.coordinator.refresh([contact])
    }
    try await waitUntil("status entered") { await harness.session.entered == 1 }

    try await confirmation("Refresh reader observes failed completion") { changed in
      let initial = withObservationTracking {
        harness.coordinator.isRefreshing
      } onChange: {
        changed()
      }
      #expect(initial)
      try await harness.releaseRemaining(count: 2)
    }
    #expect(harness.contactChanges == 0)
    #expect(harness.coordinator.failedContactIDs == [contact.id])
    harness.coordinator.shutdown()
  }

  @Test
  func `refresh readers observe cancellation on disconnect`() async throws {
    let harness = try await Harness()
    let contact = harness.repeater(name: "North", byte: 0xA0)
    await harness.session.enqueue(status: 3000, firmware: "1")
    harness.coordinator.refresh([contact])
    try await waitUntil("status entered") { await harness.session.entered == 1 }

    try await confirmation("Refresh reader observes cancellation") { changed in
      let initial = withObservationTracking {
        harness.coordinator.isRefreshing
      } onChange: {
        changed()
      }
      #expect(initial)
      harness.yield(.disconnected)
      try await waitUntil("walk cancelled") { !harness.coordinator.isRefreshing }
    }
    await harness.session.releaseOne()
    #expect(harness.contactChanges == 0)
    harness.coordinator.shutdown()
  }

  @Test
  func `each repeater logs in with its saved password or a blank one before any request`() async throws {
    let harness = try await Harness()
    defer { harness.coordinator.shutdown() }
    let saved = harness.repeater(name: "A", byte: 0x81)
    let blank = harness.repeater(name: "B", byte: 0x82, pathLength: 2)
    try await harness.store.saveContact(saved)
    try await harness.store.saveContact(blank)
    await harness.session.setSavedPassword("secret", for: saved.publicKey)
    await harness.session.enqueue(status: 3000, firmware: "1")
    await harness.session.enqueue(status: 3100, firmware: "2")

    harness.coordinator.refresh([saved, blank])
    try await harness.releaseRemaining(count: 4)

    #expect(await harness.session.logins == [
      .init(publicKey: saved.publicKey, password: "secret", pathLength: saved.outPathLength, samePasswordExemption: false),
      .init(publicKey: blank.publicKey, password: "", pathLength: 2, samePasswordExemption: false),
    ])
    #expect(harness.coordinator.failedContactIDs.isEmpty)
  }

  @Test
  func `opening and an empty capture send nothing, and a second tap waits`() async throws {
    let harness = try await Harness()
    let ada = harness.repeater(name: "Ada", byte: 0xA1)
    let bea = harness.repeater(name: "Bea", byte: 0xB2, nickname: "Aaron")
    try await harness.store.saveContact(ada)
    try await harness.store.saveContact(bea)
    await harness.session.enqueue(status: 3700, firmware: "1.2.3")
    await harness.session.enqueue(status: 0, firmware: "")

    #expect(await harness.session.calls.isEmpty)
    harness.coordinator.refresh([])
    #expect(await harness.session.calls.isEmpty)
    #expect(!harness.coordinator.isRefreshing)

    harness.coordinator.refresh([ada, bea])
    try await waitUntil("first status") { await harness.session.entered == 1 }
    harness.coordinator.refresh([bea, ada])
    await harness.session.releaseOne()
    try await waitUntil("owner follows the first status") { await harness.session.entered == 2 }
    #expect(await harness.session.maxInFlight == 1)
    try await harness.releaseRemaining(count: 3)

    #expect(await harness.session.calls == [
      .status(bea.publicKey), .owner(bea.publicKey), .status(ada.publicKey), .owner(ada.publicKey)
    ])
    let storedBea = try #require(try await harness.store.fetchContact(id: bea.id))
    #expect(harness.coordinator.batteryReadings[bea.id]?.millivolts == 3700)
    #expect(storedBea.myNodeFirmwareVersion == "1.2.3")
    #expect(storedBea.lastHeardTimestamp == 40)
    #expect(!storedBea.isFavorite)
    let storedAda = try #require(try await harness.store.fetchContact(id: ada.id))
    #expect(harness.coordinator.batteryReadings[ada.id]?.millivolts == 0)
    #expect(storedAda.myNodeFirmwareVersion == "")
    let history = try await harness.store.fetchNodeStatusSnapshots(nodePublicKey: ada.publicKey, since: nil)
    #expect(history.map(\.batteryMillivolts) == [0])
    #expect(!harness.coordinator.isRefreshing)
    #expect(harness.coordinator.activeContactID == nil)
    #expect(harness.contactChanges == 2)
  }

  @Test
  func `status and owner failures stay independent and the walk continues`() async throws {
    let harness = try await Harness()
    let first = harness.repeater(name: "First", byte: 0x11, firmware: "old")
    let second = harness.repeater(name: "Second", byte: 0x22, firmware: "keep")
    try await harness.store.saveContact(first)
    try await harness.store.saveContact(second)
    await harness.session.enqueueFailure(status: true, owner: false, firmware: "new")
    await harness.session.enqueueFailure(status: false, owner: true, firmware: nil)

    harness.coordinator.refresh([first, second])
    try await harness.releaseRemaining(count: 4)

    #expect(try await harness.store.fetchContact(id: first.id)?.myNodeFirmwareVersion == "new")
    #expect(harness.coordinator.batteryReadings[first.id] == nil)
    #expect(try await harness.store.fetchContact(id: second.id)?.myNodeFirmwareVersion == "keep")
    #expect(harness.coordinator.batteryReadings[second.id]?.millivolts == 1)
    #expect(harness.coordinator.failedContactIDs == [first.id, second.id])
    #expect(harness.coordinator.timedOutContactIDs.isEmpty)
    #expect(await harness.session.calls.map(\.kind) == [.status, .owner, .status, .owner])
  }

  @Test
  func `a login with no reply skips the requests, offers login, and retry refreshes only that repeater`() async throws {
    let harness = try await Harness()
    defer { harness.coordinator.shutdown() }
    let first = harness.repeater(name: "A", byte: 0x33, firmware: "old")
    let next = harness.repeater(name: "B", byte: 0x34)
    try await harness.store.saveContact(first)
    try await harness.store.saveContact(next)
    await harness.session.enqueueLoginTimeout()
    await harness.session.enqueue(status: 3700, firmware: "next")

    harness.coordinator.refresh([first, next])
    try await harness.releaseRemaining(count: 2)

    #expect(await harness.session.floodFallbackAttempts.isEmpty)
    #expect(harness.coordinator.timedOutContactIDs == [first.id])
    #expect(harness.coordinator.failedContactIDs == [first.id])
    #expect(try await harness.store.fetchContact(id: first.id)?.myNodeFirmwareVersion == "old")
    #expect(try await harness.store.fetchContact(id: next.id)?.myNodeFirmwareVersion == "next")

    await harness.session.enqueue(status: 3600, firmware: "new")
    harness.coordinator.refresh([first])
    try await waitUntil("retry started") { await harness.session.entered == 3 }
    #expect(harness.coordinator.timedOutContactIDs.isEmpty)
    #expect(harness.coordinator.failedContactIDs.isEmpty)
    try await harness.releaseRemaining(count: 2, from: 2)

    #expect(harness.coordinator.batteryReadings[first.id]?.millivolts == 3600)
    #expect(try await harness.store.fetchContact(id: first.id)?.myNodeFirmwareVersion == "new")
    #expect(harness.coordinator.failedContactIDs.isEmpty)
    #expect(await harness.session.calls == [
      .status(next.publicKey), .owner(next.publicKey), .status(first.publicKey), .owner(first.publicKey)
    ])
  }

  @Test
  func `status-first skips login for connected repeater and falls back to login on existing session on timeout`() async throws {
    let harness = try await Harness()
    defer { harness.coordinator.shutdown() }

    let connected = harness.repeater(name: "Connected", byte: 0x36, pathLength: 1)
    let staleSession = harness.repeater(name: "StaleSession", byte: 0x37, pathLength: 1)
    try await harness.store.saveContact(connected)
    try await harness.store.saveContact(staleSession)

    let connectedDTO = RemoteNodeSessionDTO(
      id: UUID(), radioID: harness.radioID, publicKey: connected.publicKey,
      name: connected.name, role: .repeater, isConnected: true
    )
    let staleDTO = RemoteNodeSessionDTO(
      id: UUID(), radioID: harness.radioID, publicKey: staleSession.publicKey,
      name: staleSession.name, role: .repeater, isConnected: true
    )
    await harness.session.setConnectedSession(connectedDTO, for: connected.publicKey)
    await harness.session.setConnectedSession(staleDTO, for: staleSession.publicKey)

    await harness.session.enqueueStatusResponse(3400)
    await harness.session.enqueueOwnerResponse("1.9.0")
    await harness.session.enqueueStatusTimeout()
    await harness.session.enqueueLoginOnly()
    await harness.session.enqueueStatusResponse(3500)
    await harness.session.enqueueOwnerResponse("1.9.1")

    harness.coordinator.refresh([connected, staleSession])
    try await harness.releaseRemaining(count: 5)

    #expect(await harness.session.createSessionCount == 0)
    #expect(await harness.session.loginSessionIDs == [staleDTO.id])
    #expect(await harness.session.floodFallbackAttempts == [staleSession.publicKey])
    #expect(await harness.session.logins == [
      .init(
        publicKey: staleSession.publicKey,
        password: "",
        pathLength: PacketBuilder.floodPathSentinel,
        samePasswordExemption: false
      ),
    ])
    #expect(harness.coordinator.batteryReadings[connected.id]?.millivolts == 3400)
    #expect(harness.coordinator.batteryReadings[staleSession.id]?.millivolts == 3500)
    #expect(harness.coordinator.failedContactIDs.isEmpty)
  }

  @Test
  func `direct login timeout retries over flood with same-password exemption and preserves timeout when unavailable`() async throws {
    let harness = try await Harness()
    defer { harness.coordinator.shutdown() }
    let contact = harness.repeater(name: "Direct", byte: 0x68, firmware: "1.8.0", pathLength: 2)
    let unavailable = harness.repeater(name: "NoFallback", byte: 0x69, firmware: "1.8.0", pathLength: 2)
    try await harness.store.saveContact(contact)
    try await harness.store.saveContact(unavailable)

    await harness.session.enqueueLoginTimeout()
    await harness.session.enqueue(status: 3650, firmware: "1.8.0")
    harness.coordinator.refresh([contact])
    try await harness.releaseRemaining(count: 2)

    #expect(await harness.session.floodFallbackAttempts == [contact.publicKey])
    #expect(await harness.session.logins == [
      .init(publicKey: contact.publicKey, password: "", pathLength: 2, samePasswordExemption: false),
      .init(
        publicKey: contact.publicKey,
        password: "",
        pathLength: PacketBuilder.floodPathSentinel,
        samePasswordExemption: true
      ),
    ])
    #expect(harness.coordinator.batteryReadings[contact.id]?.millivolts == 3650)
    #expect(harness.coordinator.timedOutContactIDs.isEmpty)
    #expect(harness.coordinator.failedContactIDs.isEmpty)

    await harness.session.setFloodFallbackAvailable(false)
    await harness.session.enqueueLoginTimeout()
    harness.coordinator.refresh([unavailable])
    try await waitUntil("unavailable walk ends") { !harness.coordinator.isRefreshing }

    #expect(await harness.session.floodFallbackAttempts == [contact.publicKey, unavailable.publicKey])
    #expect(await harness.session.logins.last == .init(
      publicKey: unavailable.publicKey,
      password: "",
      pathLength: 2,
      samePasswordExemption: false
    ))
    #expect(await harness.session.logins.count == 3)
    #expect(harness.coordinator.timedOutContactIDs == [unavailable.id])
    #expect(harness.coordinator.failedContactIDs == [unavailable.id])
  }

  @Test
  func `connected session status timeout falls back to direct login only when flood fallback is unavailable`() async throws {
    let harness = try await Harness()
    defer { harness.coordinator.shutdown() }

    let failsDirect = harness.repeater(name: "DirectFail", byte: 0x6D, firmware: "1.8.0", pathLength: 1)
    let failsFlood = harness.repeater(name: "FloodFail", byte: 0x6E, firmware: "1.8.0", pathLength: 1)
    for contact in [failsDirect, failsFlood] {
      try await harness.store.saveContact(contact)
      await harness.session.setConnectedSession(
        RemoteNodeSessionDTO(
          id: UUID(),
          radioID: harness.radioID,
          publicKey: contact.publicKey,
          name: contact.name,
          role: .repeater,
          isConnected: true
        ),
        for: contact.publicKey
      )
    }

    await harness.session.setFloodFallbackAvailable(false)
    await harness.session.enqueueStatusTimeout()
    await harness.session.enqueueLoginTimeout()
    harness.coordinator.refresh([failsDirect])
    try await harness.releaseRemaining(count: 1)
    #expect(await harness.session.floodFallbackAttempts == [failsDirect.publicKey])
    #expect(await harness.session.logins == [
      .init(publicKey: failsDirect.publicKey, password: "", pathLength: 1, samePasswordExemption: false),
    ])
    #expect(harness.coordinator.timedOutContactIDs == [failsDirect.id])
    #expect(harness.coordinator.failedContactIDs == [failsDirect.id])

    await harness.session.setFloodFallbackAvailable(true)
    await harness.session.enqueueStatusTimeout()
    await harness.session.enqueueLoginTimeout()
    harness.coordinator.refresh([failsFlood])
    try await harness.releaseRemaining(count: 1, from: 1)
    #expect(await harness.session.floodFallbackAttempts == [failsDirect.publicKey, failsFlood.publicKey])
    #expect(await harness.session.logins.last == .init(
      publicKey: failsFlood.publicKey,
      password: "",
      pathLength: PacketBuilder.floodPathSentinel,
      samePasswordExemption: false
    ))
    #expect(await harness.session.logins.count == 2)
    #expect(harness.coordinator.timedOutContactIDs == [failsDirect.id, failsFlood.id])
  }

  @Test
  func `active phase and deadline track each refresh stage and clear on completion and cancel`() async throws {
    let harness = try await Harness()
    defer { harness.coordinator.shutdown() }

    let contact = harness.repeater(name: "PhaseNode", byte: 0x6F, pathLength: 1)
    try await harness.store.saveContact(contact)
    await harness.session.setConnectedSession(
      RemoteNodeSessionDTO(
        id: UUID(),
        radioID: harness.radioID,
        publicKey: contact.publicKey,
        name: contact.name,
        role: .repeater,
        isConnected: true
      ),
      for: contact.publicKey
    )
    await harness.session.setHoldLogins(true)
    await harness.session.setScriptedTimeout(40)
    await harness.session.enqueueStatusTimeout()
    await harness.session.enqueueLoginOnly()
    await harness.session.enqueueStatusResponse(3550)
    await harness.session.enqueueOwnerResponse("1.9.0")

    harness.coordinator.refresh([contact])
    try await waitUntil("checking status entered") { await harness.session.entered == 1 }
    #expect(harness.coordinator.activePhase == .checkingStatus)
    #expect(harness.coordinator.phaseDeadline == harness.now.addingTimeInterval(40))

    harness.now = harness.now.addingTimeInterval(5)
    await harness.session.releaseOne()
    try await waitUntil("flood retry login entered") { await harness.session.entered == 2 }
    #expect(harness.coordinator.activePhase == .retryingViaFlood)
    #expect(harness.coordinator.phaseDeadline == harness.now.addingTimeInterval(40))

    harness.now = harness.now.addingTimeInterval(5)
    await harness.session.releaseOne()
    try await waitUntil("fetching status entered") { await harness.session.entered == 3 }
    #expect(harness.coordinator.activePhase == .fetchingStatus)
    #expect(harness.coordinator.phaseDeadline == harness.now.addingTimeInterval(40))

    harness.now = harness.now.addingTimeInterval(5)
    await harness.session.releaseOne()
    try await waitUntil("fetching firmware entered") { await harness.session.entered == 4 }
    #expect(harness.coordinator.activePhase == .fetchingFirmware)
    #expect(harness.coordinator.phaseDeadline == harness.now.addingTimeInterval(40))

    await harness.session.releaseOne()
    try await waitUntil("walk finished") { !harness.coordinator.isRefreshing }
    #expect(harness.coordinator.activePhase == nil)
    #expect(harness.coordinator.phaseDeadline == nil)

    await harness.session.enqueueStatusResponse(3600)
    harness.coordinator.refresh([contact])
    try await waitUntil("second run status entered") { await harness.session.entered == 5 }
    #expect(harness.coordinator.activePhase == .checkingStatus)
    #expect(harness.coordinator.phaseDeadline == harness.now.addingTimeInterval(40))
    harness.coordinator.cancelRefresh()
    #expect(harness.coordinator.activePhase == nil)
    #expect(harness.coordinator.phaseDeadline == nil)
    await harness.session.releaseOne()
  }

  @Test
  func `owner info is checked once per app run when firmware version is already known`() async throws {
    let harness = try await Harness()
    defer { harness.coordinator.shutdown() }
    let known = harness.repeater(name: "Known", byte: 0x63, firmware: "1.7.0")
    try await harness.store.saveContact(known)

    await harness.session.enqueue(status: 3500, firmware: "1.8.0")
    harness.coordinator.refresh([known])
    try await harness.releaseRemaining(count: 2)
    #expect(await harness.session.calls.map(\.kind) == [.status, .owner])

    await harness.session.enqueueStatusOnly(status: 3600, firmwareLevel: 2)
    harness.coordinator.refresh([known])
    try await harness.releaseRemaining(count: 1, from: 2)
    #expect(await harness.session.calls.map(\.kind) == [.status, .owner, .status])
  }

  @Test
  func `preemption re-queues before hand-off without limit, caps post-hand-off at one, and keeps completed status`() async throws {
    let harness = try await Harness()
    defer { harness.coordinator.shutdown() }
    let beforeHandOff = harness.repeater(name: "Before", byte: 0x64, firmware: "1.8.0")
    let afterHandOffSkipped = harness.repeater(name: "TwiceAfter", byte: 0x65, firmware: "1.8.0")
    let ownerPreempted = harness.repeater(name: "OwnerPreempt", byte: 0x66)
    try await harness.store.saveContact(beforeHandOff)
    try await harness.store.saveContact(afterHandOffSkipped)
    try await harness.store.saveContact(ownerPreempted)

    await harness.session.enqueueLoginOnly()
    await harness.session.enqueuePreemptedStatus(afterHandOff: false)
    await harness.session.enqueueLoginOnly()
    await harness.session.enqueuePreemptedStatus(afterHandOff: false)
    await harness.session.enqueue(status: 3900, firmware: "1.8.0")
    harness.coordinator.refresh([beforeHandOff])
    try await harness.releaseRemaining(count: 4)
    #expect(harness.coordinator.batteryReadings[beforeHandOff.id]?.millivolts == 3900)
    #expect(harness.coordinator.failedContactIDs.isEmpty)
    #expect(harness.coordinator.timedOutContactIDs.isEmpty)

    await harness.session.enqueueLoginOnly()
    await harness.session.enqueuePreemptedStatus(afterHandOff: true)
    await harness.session.enqueuePreemptedStatus(afterHandOff: true)
    harness.coordinator.refresh([afterHandOffSkipped])
    try await harness.releaseRemaining(count: 2, from: 4)
    #expect(harness.coordinator.batteryReadings[afterHandOffSkipped.id] == nil)
    #expect(harness.coordinator.failedContactIDs.isEmpty)
    #expect(harness.coordinator.timedOutContactIDs.isEmpty)

    await harness.session.enqueueLoginOnly()
    await harness.session.enqueueStatusResponse(3750)
    await harness.session.enqueuePreemptedOwner(afterHandOff: true)
    await harness.session.enqueueOwnerResponse("1.9.2")
    harness.coordinator.refresh([ownerPreempted])
    try await harness.releaseRemaining(count: 3, from: 6)
    let callsForOwnerPreempted = await harness.session.calls.filter { $0.publicKey == ownerPreempted.publicKey }.map(\.kind)
    #expect(callsForOwnerPreempted == [.status, .owner, .owner])
    #expect(harness.coordinator.batteryReadings[ownerPreempted.id]?.millivolts == 3750)
    #expect(try await harness.store.fetchContact(id: ownerPreempted.id)?.myNodeFirmwareVersion == "1.9.2")
  }

  @Test
  func `flood-routed repeater gets at most two status writes and no resetPath`() async throws {
    let transport = MockTransport()
    let clock = TestClock()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 5,
        clientIdentifier: "MCTst",
        binaryRequestOverallTimeout: 40,
        binaryRequestRetransmitInterval: 10
      ),
      clock: clock
    )
    try await startSession(session, transport: transport)
    defer { Task { await session.stop() } }

    let radioID = UUID()
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let services = ServiceContainer(session: session, dataStore: store, radioID: radioID)
    let key = Data(repeating: 0x35, count: 32)
    let contact = ContactDTO(from: Contact(
      radioID: radioID, publicKey: key, name: "Flood", typeRawValue: ContactType.repeater.rawValue,
      outPathLength: PacketBuilder.floodPathSentinel, lastHeardTimestamp: 40,
      isMyNode: true, myNodeFirmwareVersion: "1.8.0"
    ))
    try await store.saveContact(contact)
    let connected = RemoteNodeSessionDTO(
      id: UUID(), radioID: radioID, publicKey: key, name: "Flood", role: .repeater, isConnected: true
    )
    try await store.saveRemoteNodeSessionDTO(connected)

    let coordinator = WatchlistRefreshCoordinator()
    coordinator.configure(
      store: store,
      now: { Date(timeIntervalSince1970: 1_700_002_200) },
      onContactsChanged: {},
      dependencies: WatchlistRefreshCoordinator.Dependencies(
        operations: { WatchlistRefreshCoordinator.Operations(services: services) },
        radioID: { radioID },
        connectionState: { .ready },
        sessionIdentity: { ObjectIdentifier(session) },
        connectionStates: { AsyncStream { $0.finish() } }
      )
    )

    coordinator.refresh([contact])
    try await waitUntil("first flood status sent") {
      await transport.sentData.filter { $0.first == CommandCode.sendStatusRequest.rawValue }.count == 1
    }
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0x21, 0x22, 0x23, 0x24]), suggestedTimeoutMs: 6000))
    try await waitUntil("flood status parked") { clock.nextSleepDuration == .seconds(12) }
    clock.advance(by: .seconds(12))

    try await waitUntil("second flood status sent") {
      await transport.sentData.filter { $0.first == CommandCode.sendStatusRequest.rawValue }.count == 2
    }
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0x25, 0x26, 0x27, 0x28]), suggestedTimeoutMs: 6000))
    try await waitUntil("flood status resend parked") { clock.nextSleepDuration == .seconds(12) }
    clock.advance(by: .seconds(35))

    let statusWrites = await transport.sentData.filter { $0.first == CommandCode.sendStatusRequest.rawValue }.count
    let resetWrites = await transport.sentData.filter { $0.first == CommandCode.resetPath.rawValue }.count
    #expect(statusWrites == 2)
    #expect(resetWrites == 0)
    coordinator.shutdown()
  }

  @Test
  func `background status queued past 45s gets no write before admission and records no failure`() async throws {
    let transport = MockTransport()
    let clock = TestClock()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 5,
        clientIdentifier: "MCTst",
        binaryRequestOverallTimeout: 60,
        binaryRequestRetransmitInterval: nil
      ),
      clock: clock
    )
    try await startSession(session, transport: transport)
    defer { Task { await session.stop() } }

    let radioID = UUID()
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let services = ServiceContainer(session: session, dataStore: store, radioID: radioID)
    let otherKey = Data(repeating: 0x41, count: 32)
    let watchlistKey = Data(repeating: 0x42, count: 32)
    let contact = ContactDTO(from: Contact(
      radioID: radioID, publicKey: watchlistKey, name: "Back", typeRawValue: ContactType.repeater.rawValue,
      outPathLength: PacketBuilder.floodPathSentinel, lastHeardTimestamp: 40,
      isMyNode: true, myNodeFirmwareVersion: "1.8.0"
    ))
    try await store.saveContact(contact)
    try await store.saveRemoteNodeSessionDTO(RemoteNodeSessionDTO(
      id: UUID(), radioID: radioID, publicKey: watchlistKey, name: "Back", role: .repeater, isConnected: true
    ))

    let coordinator = WatchlistRefreshCoordinator()
    coordinator.configure(
      store: store,
      now: { Date(timeIntervalSince1970: 1_700_002_300) },
      onContactsChanged: {},
      dependencies: WatchlistRefreshCoordinator.Dependencies(
        operations: { WatchlistRefreshCoordinator.Operations(services: services) },
        radioID: { radioID },
        connectionState: { .ready },
        sessionIdentity: { ObjectIdentifier(session) },
        connectionStates: { AsyncStream { $0.finish() } }
      )
    )
    defer { coordinator.shutdown() }

    let interactiveTask = Task {
      try await services.binaryProtocolService.requestTelemetry(from: otherKey, priority: .interactive)
    }
    try await waitUntil("interactive getSelfTelemetry sent") {
      await transport.sentData.contains { $0.first == CommandCode.getSelfTelemetry.rawValue }
    }
    let interactiveTag = Data([0x91, 0x92, 0x93, 0x94])
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: interactiveTag))
    try await waitUntil("interactive parked") { clock.nextSleepDuration == .seconds(60) }

    coordinator.refresh([contact])
    await Task.yield()
    clock.advance(by: .seconds(46))
    await Task.yield()

    #expect(await transport.sentData.allSatisfy { $0.first != CommandCode.sendStatusRequest.rawValue })

    var telemetryPacket = Data([ResponseCode.binaryResponse.rawValue, 0x00])
    telemetryPacket.append(interactiveTag)
    await transport.simulateReceive(telemetryPacket)
    _ = try await interactiveTask.value

    try await waitUntil("background status admitted and written") {
      await transport.sentData.contains { $0.first == CommandCode.sendStatusRequest.rawValue }
    }
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0xA1, 0xA2, 0xA3, 0xA4])))
    await transport.simulateReceive(
      makeStatusResponsePacket(publicKeyPrefix: Data(watchlistKey.prefix(6)), battery: 3650)
    )
    try await waitUntil("ownerInfo binaryRequest sent") {
      await transport.sentData.filter { $0.first == CommandCode.binaryRequest.rawValue }.count == 1
    }
    let ownerTag = Data([0xB1, 0xB2, 0xB3, 0xB4])
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: ownerTag))
    await transport.simulateReceive(makeOwnerInfoPacket(tag: ownerTag, firmware: "1.8.0"))

    try await waitUntil("walk completed") { !coordinator.isRefreshing }
    #expect(coordinator.batteryReadings[contact.id]?.millivolts == 3650)
    #expect(coordinator.failedContactIDs.isEmpty)
    #expect(coordinator.timedOutContactIDs.isEmpty)
  }

  @Test
  func `an old firmware level skips owner info and records an unknown version once`() async throws {
    let harness = try await Harness()
    defer { harness.coordinator.shutdown() }
    let unchecked = harness.repeater(name: "A", byte: 0x38)
    let known = harness.repeater(name: "B", byte: 0x39, firmware: "1.0")
    try await harness.store.saveContact(unchecked)
    try await harness.store.saveContact(known)
    await harness.session.enqueueStatusOnly(status: 3000, firmwareLevel: 1)
    await harness.session.enqueueStatusOnly(status: 3100, firmwareLevel: 1)

    harness.coordinator.refresh([unchecked, known])
    try await harness.releaseRemaining(count: 2)

    #expect(await harness.session.calls.map(\.kind) == [.status, .status])
    #expect(try await harness.store.fetchContact(id: unchecked.id)?.myNodeFirmwareVersion == "")
    #expect(try await harness.store.fetchContact(id: known.id)?.myNodeFirmwareVersion == "1.0")
    #expect(harness.coordinator.failedContactIDs.isEmpty)
  }

  @Test
  func `an owner info timeout after a battery reply records an unknown version without failing`() async throws {
    let harness = try await Harness()
    defer { harness.coordinator.shutdown() }
    let unchecked = harness.repeater(name: "A", byte: 0x3B)
    let known = harness.repeater(name: "B", byte: 0x3C, firmware: "1.7.0")
    try await harness.store.saveContact(unchecked)
    try await harness.store.saveContact(known)
    await harness.session.enqueueFailure(status: false, owner: true, firmware: nil, error: RemoteNodeError.timeout)
    await harness.session.enqueueFailure(status: false, owner: true, firmware: nil, error: RemoteNodeError.timeout)

    harness.coordinator.refresh([unchecked, known])
    try await harness.releaseRemaining(count: 4)

    #expect(try await harness.store.fetchContact(id: unchecked.id)?.myNodeFirmwareVersion == "")
    #expect(try await harness.store.fetchContact(id: known.id)?.myNodeFirmwareVersion == "1.7.0")
    #expect(harness.coordinator.failedContactIDs.isEmpty)
    #expect(harness.coordinator.timedOutContactIDs.isEmpty)
  }

  @Test
  func `opening refreshes only repeaters without a reading in the current window`() async throws {
    let harness = try await Harness()
    defer { harness.coordinator.shutdown() }
    let fresh = harness.repeater(name: "Fresh", byte: 0x3D)
    let stale = harness.repeater(name: "Stale", byte: 0x3E)
    let never = harness.repeater(name: "Never", byte: 0x3F)
    for contact in [fresh, stale, never] {
      try await harness.store.saveContact(contact)
    }
    try await harness.seedBattery(fresh, millivolts: 3900, age: NodeSnapshotPolicy.minimumInterval / 2)
    try await harness.seedBattery(stale, millivolts: 3500, age: NodeSnapshotPolicy.minimumInterval * 2)
    await harness.session.enqueue(status: 3600, firmware: "1")
    await harness.session.enqueue(status: 3700, firmware: "2")

    await harness.coordinator.loadReadings(for: [fresh, stale, never])
    #expect(harness.coordinator.batteryReadings[fresh.id]?.millivolts == 3900)
    #expect(harness.coordinator.batteryReadings[stale.id]?.millivolts == 3500)
    #expect(harness.coordinator.batteryReadings[never.id] == nil)
    harness.coordinator.refreshStale([fresh, stale, never])
    harness.coordinator.refreshStale([fresh, stale, never])
    try await harness.releaseRemaining(count: 4)

    #expect(await harness.session.calls.map(\.publicKey) == [
      never.publicKey, never.publicKey, stale.publicKey, stale.publicKey
    ])
    #expect(harness.coordinator.batteryReadings[stale.id]?.millivolts == 3700)
    #expect(harness.coordinator.batteryReadings[fresh.id]?.millivolts == 3900)
  }

  @Test
  func `cancelling a refresh starts no later repeater`() async throws {
    let harness = try await Harness()
    defer { harness.coordinator.shutdown() }
    let first = harness.repeater(name: "A", byte: 0x47)
    let second = harness.repeater(name: "B", byte: 0x48)
    try await harness.store.saveContact(first)
    try await harness.store.saveContact(second)
    await harness.session.enqueue(status: 3000, firmware: "1")
    await harness.session.enqueue(status: 3100, firmware: "2")

    harness.coordinator.refresh([first, second])
    try await waitUntil("status entered") { await harness.session.entered == 1 }
    harness.coordinator.cancelRefresh()
    #expect(!harness.coordinator.isRefreshing)
    #expect(harness.coordinator.progress == nil)
    await harness.session.releaseOne()
    await Task.yield()

    #expect(await harness.session.calls == [.status(first.publicKey)])
    #expect(harness.coordinator.batteryReadings[second.id] == nil)
  }

  @Test
  func `progress counts finished repeaters and clears at the end`() async throws {
    let harness = try await Harness()
    defer { harness.coordinator.shutdown() }
    let first = harness.repeater(name: "A", byte: 0x49)
    let second = harness.repeater(name: "B", byte: 0x4A)
    try await harness.store.saveContact(first)
    try await harness.store.saveContact(second)
    await harness.session.enqueue(status: 3000, firmware: "1")
    await harness.session.enqueue(status: 3100, firmware: "2")

    harness.coordinator.refresh([first, second])
    #expect(harness.coordinator.progress == .init(completed: 0, total: 2))
    try await waitUntil("first status") { await harness.session.entered == 1 }
    await harness.session.releaseOne()
    try await waitUntil("first owner") { await harness.session.entered == 2 }
    await harness.session.releaseOne()
    try await waitUntil("second repeater started") { harness.coordinator.progress?.completed == 1 }
    #expect(harness.coordinator.progress?.total == 2)
    try await harness.releaseRemaining(count: 2, from: 2)
    #expect(harness.coordinator.progress == nil)
  }

  @Test
  func `disconnect before a reply sends nothing further and keeps completed values`() async throws {
    let harness = try await Harness()
    let contact = harness.repeater(name: "North", byte: 0x41, firmware: "old")
    try await harness.store.saveContact(contact)
    await harness.session.enqueue(status: 9999, firmware: "new")

    harness.coordinator.refresh([contact])
    try await waitUntil("status entered") { await harness.session.entered == 1 }
    harness.yield(.disconnected)
    try await waitUntil("walk cancelled") { !harness.coordinator.isRefreshing }
    await harness.session.releaseOne()
    await Task.yield()

    #expect(await harness.session.calls == [.status(contact.publicKey)])
    #expect(try await harness.store.fetchContact(id: contact.id)?.myNodeFirmwareVersion == "old")
    #expect(try await harness.store.fetchNodeStatusSnapshots(nodePublicKey: contact.publicKey, since: nil).isEmpty)
    #expect(!harness.coordinator.failedContactIDs.contains(contact.id))
    #expect(harness.coordinator.activeContactID == nil)
  }

  @Test
  func `a reply that lands before disconnect keeps that save and skips the rest`() async throws {
    let harness = try await Harness()
    let contact = harness.repeater(name: "North", byte: 0x42)
    try await harness.store.saveContact(contact)
    await harness.session.enqueue(status: 2500, firmware: "nope")

    harness.coordinator.refresh([contact])
    try await waitUntil("status entered") { await harness.session.entered == 1 }
    await harness.session.releaseOne()
    try await waitUntil("owner entered") { await harness.session.entered == 2 }
    harness.yield(.disconnected)
    try await waitUntil("walk cancelled") { !harness.coordinator.isRefreshing }
    await harness.session.releaseOne()

    #expect(harness.coordinator.batteryReadings[contact.id]?.millivolts == 2500)
    #expect(try await harness.store.fetchContact(id: contact.id)?.myNodeFirmwareVersion == nil)
    #expect(await harness.session.calls.map(\.kind) == [.status, .owner])
    #expect(!harness.coordinator.failedContactIDs.contains(contact.id))
  }

  @Test
  func `cancelling a firmware patch keeps the previous version and does not continue`() async throws {
    let harness = try await Harness()
    let contact = harness.repeater(name: "North", byte: 0x43, firmware: "old")
    let later = harness.repeater(name: "Zed", byte: 0x44)
    try await harness.store.saveContact(contact)
    try await harness.store.saveContact(later)
    await harness.session.enqueue(status: 10, firmware: "new")
    await harness.store.setWatchlistSaveFaultInjection { throw CancellationError() }

    harness.coordinator.refresh([contact, later])
    try await harness.releaseRemaining(count: 2)

    #expect(try await harness.store.fetchContact(id: contact.id)?.myNodeFirmwareVersion == "old")
    #expect(await harness.session.calls.map(\.publicKey) == [contact.publicKey, contact.publicKey])
    #expect(!harness.coordinator.failedContactIDs.contains(contact.id))
  }

  @Test
  func `radio replacement and a new session on the same radio stop the walk`() async throws {
    let harness = try await Harness()
    let contact = harness.repeater(name: "North", byte: 0x45)
    try await harness.store.saveContact(contact)
    await harness.session.enqueue(status: 10, firmware: "new")
    harness.coordinator.refresh([contact])
    try await waitUntil("status entered") { await harness.session.entered == 1 }
    harness.currentRadioID = UUID()
    harness.yield(.ready)
    try await waitUntil("radio replacement cancelled the walk") { !harness.coordinator.isRefreshing }
    await harness.session.releaseOne()
    #expect(harness.coordinator.batteryReadings[contact.id] == nil)

    harness.currentRadioID = harness.radioID
    await harness.session.enqueue(status: 10, firmware: "new")
    harness.coordinator.refresh([contact])
    try await waitUntil("second status entered") { await harness.session.entered == 2 }
    harness.identityToken = IdentityToken()
    harness.yield(.syncing)
    try await waitUntil("session replacement cancelled the walk") { !harness.coordinator.isRefreshing }
    await harness.session.releaseOne()
    #expect(await harness.session.calls.map(\.kind) == [.status, .status])
  }

  @Test
  func `a late reply from the previous run cannot publish into the next one`() async throws {
    let harness = try await Harness()
    let first = harness.repeater(name: "First", byte: 0x51)
    let second = harness.repeater(name: "Second", byte: 0x52)
    try await harness.store.saveContact(first)
    try await harness.store.saveContact(second)
    let previous = harness.session
    await previous.enqueue(status: 1111, firmware: "old-run")
    harness.coordinator.refresh([first])
    try await waitUntil("previous status") { await previous.entered == 1 }

    harness.identityToken = IdentityToken()
    let replacement = ScriptedRemote()
    await replacement.enqueue(status: 2222, firmware: "new-run")
    harness.currentRemote = replacement
    harness.yield(.disconnected)
    try await waitUntil("previous walk cancelled") { !harness.coordinator.isRefreshing }
    harness.state = .ready
    harness.coordinator.refresh([second])
    try await waitUntil("replacement status") { await replacement.entered == 1 }
    #expect(harness.coordinator.activeContactID == second.id)
    await previous.releaseOne()
    await Task.yield()
    #expect(harness.coordinator.activeContactID == second.id)
    #expect(try await harness.store.fetchNodeStatusSnapshots(nodePublicKey: first.publicKey, since: nil).isEmpty)
    #expect(harness.coordinator.failedContactIDs.isEmpty)
    await replacement.releaseOne()
    try await waitUntil("replacement owner") { await replacement.entered == 2 }
    await replacement.releaseOne()
    try await waitUntil("replacement finished") { !harness.coordinator.isRefreshing }
    #expect(harness.coordinator.batteryReadings[second.id]?.millivolts == 2222)
  }

  @Test
  func `syncing on the same session lets the walk continue`() async throws {
    let harness = try await Harness()
    let contact = harness.repeater(name: "North", byte: 0x61)
    try await harness.store.saveContact(contact)
    await harness.session.enqueue(status: 3000, firmware: "1")
    harness.coordinator.refresh([contact])
    try await waitUntil("status entered") { await harness.session.entered == 1 }
    harness.yield(.syncing)
    await harness.session.releaseOne()
    try await waitUntil("owner still starts") { await harness.session.entered == 2 }
    await harness.session.releaseOne()
    try await waitUntil("walk finished") { !harness.coordinator.isRefreshing }
    #expect(try await harness.store.fetchContact(id: contact.id)?.myNodeFirmwareVersion == "1")
  }

  @Test
  func `unmarking or deleting during the walk does not restore membership or the row`() async throws {
    let harness = try await Harness()
    let unmarked = harness.repeater(name: "Unmark", byte: 0x71, isMyNode: true)
    let deleted = harness.repeater(name: "Delete", byte: 0x72, isMyNode: true)
    try await harness.store.saveContact(unmarked)
    try await harness.store.saveContact(deleted)
    await harness.session.enqueue(status: 1, firmware: "d")
    await harness.session.enqueue(status: 3300, firmware: "u")

    harness.coordinator.refresh([deleted, unmarked])
    try await waitUntil("first status") { await harness.session.entered == 1 }
    _ = try await harness.store.updateWatchlistMembership(
      contactID: unmarked.id, radioID: harness.radioID, isMember: false
    )
    try await harness.store.deleteContact(id: deleted.id)
    try await harness.releaseRemaining(count: 4)

    let stored = try #require(try await harness.store.fetchContact(id: unmarked.id))
    #expect(!stored.isMyNode)
    #expect(stored.myNodeFirmwareVersion == "u")
    #expect(harness.coordinator.batteryReadings[unmarked.id]?.millivolts == 3300)
    #expect(try await harness.store.fetchContact(id: deleted.id) == nil)
    #expect(try await harness.store.fetchContacts(radioID: harness.radioID).count == 1)
    #expect(harness.coordinator.failedContactIDs.contains(deleted.id))
  }

  @Test
  func `a live session logs in and keeps another radio command behind the board request`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 10,
        clientIdentifier: "MCTst",
        binaryRequestOverallTimeout: 10,
        binaryRequestRetransmitInterval: nil
      )
    )
    try await startSession(session, transport: transport)
    let radioID = UUID()
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let services = ServiceContainer(session: session, dataStore: store, radioID: radioID)
    await services.remoteNodeService.startEventMonitoring()
    let contact = ContactDTO(from: Contact(
      radioID: radioID,
      publicKey: Data(repeating: 0x31, count: 32),
      name: "Live",
      typeRawValue: ContactType.repeater.rawValue,
      outPathLength: PacketBuilder.floodPathSentinel,
      lastHeardTimestamp: 40,
      isMyNode: true
    ))
    try await store.saveContact(contact)
    let coordinator = WatchlistRefreshCoordinator()
    coordinator.configure(
      store: store,
      now: { Date(timeIntervalSince1970: 1_700_002_100) },
      onContactsChanged: {},
      dependencies: WatchlistRefreshCoordinator.Dependencies(
        operations: { WatchlistRefreshCoordinator.Operations(services: services) },
        radioID: { radioID },
        connectionState: { .ready },
        sessionIdentity: { ObjectIdentifier(session) },
        connectionStates: { AsyncStream { $0.finish() } }
      )
    )

    coordinator.refresh([contact])
    try await waitUntil("login is on the wire") {
      await transport.sentData.contains { $0.first == CommandCode.sendLogin.rawValue }
    }
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0x01, 0x02, 0x03, 0x04])))
    await transport.simulateReceive(makeLoginSuccessPacket(publicKeyPrefix: Data(contact.publicKey.prefix(6))))
    try await waitUntil("status request is on the wire") {
      await transport.sentData.contains { $0.first == CommandCode.sendStatusRequest.rawValue }
    }
    let battery = Task { try await session.getBattery() }
    try await waitUntil("the next command should wait on the serializer") {
      await session.requestResponseSerializer.waiterCountForTest == 1
    }
    #expect(await transport.sentData.allSatisfy { $0.first != CommandCode.getBattery.rawValue })

    await transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0x11, 0x22, 0x33, 0x44])))
    await transport.simulateReceive(
      makeStatusResponsePacket(publicKeyPrefix: Data(contact.publicKey.prefix(6)), battery: 3600)
    )
    try await waitUntil("battery command follows the status exchange") {
      await transport.sentData.contains { $0.first == CommandCode.getBattery.rawValue }
    }
    #expect(await transport.sentData.allSatisfy { $0.first != CommandCode.binaryRequest.rawValue })
    await transport.simulateReceive(makeBatteryPacket(level: 4000))
    let localBattery = try await battery.value
    #expect(localBattery.level == 4000)

    try await waitUntil("owner info follows the queued radio command") {
      await transport.sentData.contains { $0.first == CommandCode.binaryRequest.rawValue }
    }
    let ownerTag = Data([0x55, 0x66, 0x77, 0x88])
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: ownerTag))
    await transport.simulateReceive(makeOwnerInfoPacket(tag: ownerTag, firmware: "1.8.0"))
    try await waitUntil("saved firmware") {
      await (try? store.fetchContact(id: contact.id)?.myNodeFirmwareVersion) == "1.8.0"
    }
    #expect(coordinator.batteryReadings[contact.id]?.millivolts == 3600)
    let history = try await store.fetchNodeStatusSnapshots(nodePublicKey: contact.publicKey, since: nil)
    #expect(history.map(\.batteryMillivolts) == [3600])
    await session.stop()
  }
}

private final class IdentityToken {}

@MainActor
private final class Harness {
  let radioID = UUID()
  let store: PersistenceStore
  let session: ScriptedRemote
  let coordinator = WatchlistRefreshCoordinator()
  var now = Date()
  var contactChanges = 0
  var state: DeviceConnectionState = .ready
  var currentRadioID: UUID
  var currentRemote: ScriptedRemote
  var identityToken: AnyObject = IdentityToken()
  let yield: AsyncStream<DeviceConnectionState>.Continuation

  init() async throws {
    store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    session = ScriptedRemote()
    currentRadioID = radioID
    currentRemote = session
    let (stream, continuation) = AsyncStream.makeStream(of: DeviceConnectionState.self)
    yield = continuation
    coordinator.configure(
      store: store,
      now: { [weak self] in self?.now ?? .now },
      onContactsChanged: { [weak self] in self?.contactChanges += 1 },
      dependencies: WatchlistRefreshCoordinator.Dependencies(
        operations: { [weak self] in self?.currentRemote.operations },
        radioID: { [weak self] in self?.currentRadioID },
        connectionState: { [weak self] in self?.state ?? .disconnected },
        sessionIdentity: { [weak self] in self.map { ObjectIdentifier($0.identityToken) } },
        connectionStates: { stream }
      )
    )
  }

  func yield(_ state: DeviceConnectionState) {
    self.state = state
    yield.yield(state)
  }

  func repeater(
    name: String,
    byte: UInt8,
    nickname: String? = nil,
    isMyNode: Bool = false,
    firmware: String? = nil,
    pathLength: UInt8 = PacketBuilder.floodPathSentinel
  ) -> ContactDTO {
    ContactDTO(from: Contact(
      radioID: radioID,
      publicKey: Data(repeating: byte, count: 32),
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      outPathLength: pathLength,
      lastHeardTimestamp: 40,
      nickname: nickname,
      isMyNode: isMyNode,
      myNodeFirmwareVersion: firmware
    ))
  }

  func seedBattery(_ contact: ContactDTO, millivolts: UInt16, age: TimeInterval) async throws {
    _ = try await store.saveNodeStatusSnapshot(
      timestamp: now.addingTimeInterval(-age), nodePublicKey: contact.publicKey, batteryMillivolts: millivolts,
      lastSNR: nil, lastRSSI: nil, noiseFloor: nil, uptimeSeconds: 1, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil, receiveErrors: nil
    )
  }

  func releaseRemaining(count: Int, from start: Int = 0) async throws {
    var seen = start
    for _ in 0..<count {
      let reached = seen
      try await waitUntil("scripted call \(reached + 1)") { await self.session.entered > reached }
      seen = await self.session.entered
      await self.session.releaseOne()
    }
    try await waitUntil("walk finished") { !self.coordinator.isRefreshing }
  }
}

/// Scripts login (answered at once) and the held status and owner-info requests.
private actor ScriptedRemote {
  struct Login: Equatable {
    let publicKey: Data
    let password: String
    let pathLength: UInt8
    let samePasswordExemption: Bool
  }

  enum Call: Equatable {
    case status(Data)
    case owner(Data)

    var kind: Kind {
      switch self {
      case .status: .status
      case .owner: .owner
      }
    }

    var publicKey: Data {
      switch self {
      case let .status(key), let .owner(key): key
      }
    }

    enum Kind: Equatable {
      case status
      case owner
    }
  }

  private static let currentFirmwareLevel: UInt8 = 2

  /// Per-request queues, so a reply left behind by a cancelled run cannot shift another kind.
  private var loginOutcomes: [Result<UInt8?, Error>] = []
  private var statusOutcomes: [Result<StatusResponse, Error>] = []
  private var ownerOutcomes: [Result<OwnerInfoResponse, Error>] = []
  private var held: [CheckedContinuation<Void, Never>] = []
  private var passwords: [Data: String] = [:]
  private var sessionKeys: [UUID: Data] = [:]
  private var connectedSessions: [Data: RemoteNodeSessionDTO] = [:]
  private var floodFallbackAvailable = true
  private var holdLogins = false
  private var scriptedTimeoutSeconds: Int?
  private(set) var calls: [Call] = []
  private(set) var logins: [Login] = []
  private(set) var loginSessionIDs: [UUID] = []
  private(set) var floodFallbackAttempts: [Data] = []
  private(set) var createSessionCount = 0
  private(set) var entered = 0
  private(set) var maxInFlight = 0
  private var inFlight = 0

  nonisolated var operations: WatchlistRefreshCoordinator.Operations {
    WatchlistRefreshCoordinator.Operations(
      savedPassword: { await self.password(for: $0) },
      connectedSession: { await self.connectedSession(forPrefix: $0) },
      createSession: { await self.session(for: $0) },
      logIn: {
        try await self.logIn(
          sessionID: $0,
          password: $1,
          pathLength: $2,
          samePasswordExemption: $3,
          onTimeoutKnown: $4
        )
      },
      requestStatus: { try await self.requestStatus(sessionID: $0, onTimeoutKnown: $1) },
      requestOwnerInfo: { try await self.requestOwnerInfo(sessionID: $0, onTimeoutKnown: $1) },
      attemptFloodFallback: { try await self.attemptFloodFallback(contact: $0, rethrowIfUnavailable: $1, operation: $2) }
    )
  }

  func setFloodFallbackAvailable(_ available: Bool) {
    floodFallbackAvailable = available
  }

  func setHoldLogins(_ hold: Bool) {
    holdLogins = hold
  }

  func setScriptedTimeout(_ seconds: Int?) {
    scriptedTimeoutSeconds = seconds
  }

  func setSavedPassword(_ password: String, for publicKey: Data) {
    passwords[publicKey] = password
  }

  func setConnectedSession(_ session: RemoteNodeSessionDTO?, for publicKey: Data) {
    let prefix = Data(publicKey.prefix(6))
    connectedSessions[prefix] = session
    if let session {
      sessionKeys[session.id] = session.publicKey
    }
  }

  func enqueue(status millivolts: Int, firmware: String) {
    loginOutcomes.append(.success(Self.currentFirmwareLevel))
    statusOutcomes.append(.success(Self.status(millivolts)))
    ownerOutcomes.append(.success(OwnerInfoResponse(firmwareVersion: firmware, nodeName: "Node", ownerInfo: "")))
  }

  func enqueueFailure(
    status failsStatus: Bool,
    owner failsOwner: Bool,
    firmware: String?,
    error: any Error = ScriptFailure()
  ) {
    loginOutcomes.append(.success(Self.currentFirmwareLevel))
    statusOutcomes.append(failsStatus ? .failure(error) : .success(Self.status(1)))
    if failsOwner {
      ownerOutcomes.append(.failure(error))
    } else {
      ownerOutcomes.append(.success(OwnerInfoResponse(firmwareVersion: firmware ?? "", nodeName: "", ownerInfo: "")))
    }
  }

  func enqueueLoginTimeout() {
    loginOutcomes.append(.failure(RemoteNodeError.timeout))
  }

  func enqueueLoginOnly(firmwareLevel: UInt8 = 2) {
    loginOutcomes.append(.success(firmwareLevel))
  }

  func enqueueStatusResponse(_ millivolts: Int) {
    statusOutcomes.append(.success(Self.status(millivolts)))
  }

  func enqueueStatusTimeout() {
    statusOutcomes.append(.failure(RemoteNodeError.timeout))
  }

  func enqueueOwnerResponse(_ firmware: String) {
    ownerOutcomes.append(.success(OwnerInfoResponse(firmwareVersion: firmware, nodeName: "Node", ownerInfo: "")))
  }

  func enqueuePreemptedStatus(afterHandOff: Bool) {
    statusOutcomes.append(.failure(MeshCoreError.preempted(afterHandOff: afterHandOff)))
  }

  func enqueuePreemptedOwner(afterHandOff: Bool) {
    ownerOutcomes.append(.failure(MeshCoreError.preempted(afterHandOff: afterHandOff)))
  }

  func enqueueStatusOnly(status millivolts: Int, firmwareLevel: UInt8) {
    loginOutcomes.append(.success(firmwareLevel))
    statusOutcomes.append(.success(Self.status(millivolts)))
  }

  func releaseOne() {
    guard !held.isEmpty else { return }
    held.removeFirst().resume()
  }

  private func password(for contact: ContactDTO) -> String? {
    passwords[contact.publicKey]
  }

  private func connectedSession(forPrefix prefix: Data) -> RemoteNodeSessionDTO? {
    connectedSessions[prefix]
  }

  private func session(for contact: ContactDTO) -> RemoteNodeSessionDTO {
    createSessionCount += 1
    let id = sessionKeys.first { $0.value == contact.publicKey }?.key ?? UUID()
    sessionKeys[id] = contact.publicKey
    return RemoteNodeSessionDTO(
      id: id, radioID: contact.radioID, publicKey: contact.publicKey, name: contact.name, role: .repeater
    )
  }

  private func logIn(
    sessionID: UUID,
    password: String,
    pathLength: UInt8,
    samePasswordExemption: Bool,
    onTimeoutKnown: WatchlistRefreshCoordinator.TimeoutHandler
  ) async throws -> LoginResult {
    let key = sessionKeys[sessionID] ?? Data()
    logins.append(
      Login(
        publicKey: key,
        password: password,
        pathLength: pathLength,
        samePasswordExemption: samePasswordExemption
      )
    )
    loginSessionIDs.append(sessionID)
    if let seconds = scriptedTimeoutSeconds {
      await onTimeoutKnown(seconds)
    }
    if holdLogins {
      entered += 1
      await withCheckedContinuation { held.append($0) }
    }
    let level = try loginOutcomes.isEmpty ? Self.currentFirmwareLevel : loginOutcomes.removeFirst().get()
    return LoginResult(
      success: true, isAdmin: false, aclPermissions: nil, publicKeyPrefix: Data(key.prefix(6)),
      firmwareLevel: level
    )
  }

  private func attemptFloodFallback(
    contact: ContactDTO,
    rethrowIfUnavailable: any Error,
    operation: @Sendable () async throws -> LoginResult
  ) async throws -> LoginResult {
    floodFallbackAttempts.append(contact.publicKey)
    guard floodFallbackAvailable else { throw rethrowIfUnavailable }
    return try await operation()
  }

  private func requestStatus(
    sessionID: UUID,
    onTimeoutKnown: WatchlistRefreshCoordinator.TimeoutHandler
  ) async throws -> StatusResponse {
    if let seconds = scriptedTimeoutSeconds {
      await onTimeoutKnown(seconds)
    }
    try await record(.status(sessionKeys[sessionID] ?? Data()))
    guard !statusOutcomes.isEmpty else { throw ScriptFailure() }
    return try statusOutcomes.removeFirst().get()
  }

  private func requestOwnerInfo(
    sessionID: UUID,
    onTimeoutKnown: WatchlistRefreshCoordinator.TimeoutHandler
  ) async throws -> OwnerInfoResponse {
    if let seconds = scriptedTimeoutSeconds {
      await onTimeoutKnown(seconds)
    }
    try await record(.owner(sessionKeys[sessionID] ?? Data()))
    guard !ownerOutcomes.isEmpty else { throw ScriptFailure() }
    return try ownerOutcomes.removeFirst().get()
  }

  private func record(_ call: Call) async throws {
    calls.append(call)
    inFlight += 1
    maxInFlight = max(maxInFlight, inFlight)
    entered += 1
    defer { inFlight -= 1 }
    await withCheckedContinuation { held.append($0) }
  }

  private static func status(_ millivolts: Int) -> StatusResponse {
    StatusResponse(
      publicKeyPrefix: Data(repeating: 0, count: 6),
      battery: millivolts,
      txQueueLength: 0,
      noiseFloor: 0,
      lastRSSI: 0,
      packetsReceived: 0,
      packetsSent: 0,
      airtime: 0,
      uptime: 0,
      sentFlood: 0,
      sentDirect: 0,
      receivedFlood: 0,
      receivedDirect: 0,
      fullEvents: 0,
      lastSNR: 0,
      directDuplicates: 0,
      floodDuplicates: 0,
      rxAirtime: 0
    )
  }
}

private func makeLoginSuccessPacket(publicKeyPrefix: Data) -> Data {
  var packet = Data([ResponseCode.loginSuccess.rawValue, 0])
  packet.append(publicKeyPrefix)
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(0).littleEndian) { Array($0) })
  packet.append(0)
  packet.append(2)
  return packet
}

private struct ScriptFailure: Error {}

private func startSession(
  _ session: MeshCoreSession,
  transport: MockTransport
) async throws {
  let startTask = Task { try await session.start() }
  try await waitUntil("app start") { await transport.sentData.count == 1 }
  await transport.simulateReceive(makeSelfInfoPacket())
  try await startTask.value
}

private func makeSelfInfoPacket() -> Data {
  var payload = Data()
  payload.append(1)
  payload.append(UInt8(bitPattern: 22))
  payload.append(UInt8(bitPattern: 22))
  payload.append(Data(repeating: 0x01, count: 32))
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
  payload.append(0)
  payload.append(0)
  payload.append(0)
  payload.append(0)
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
  payload.append(7)
  payload.append(5)
  payload.append(contentsOf: "Test".utf8)
  return Data([ResponseCode.selfInfo.rawValue]) + payload
}

private func makeMessageSentPacket(expectedAck: Data, suggestedTimeoutMs: UInt32 = 5000) -> Data {
  var packet = Data([ResponseCode.messageSent.rawValue, 0])
  packet.append(expectedAck)
  packet.append(contentsOf: withUnsafeBytes(of: suggestedTimeoutMs.littleEndian) { Array($0) })
  return packet
}

private func makeStatusResponsePacket(publicKeyPrefix: Data, battery: UInt16) -> Data {
  var packet = Data([ResponseCode.statusResponse.rawValue, 0x00])
  packet.append(publicKeyPrefix)
  packet.append(contentsOf: withUnsafeBytes(of: battery.littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt16(0).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: Int16(-110).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: Int16(-85).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(100).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(50).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(25).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(3600).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(5).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(10).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(15).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(20).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt16(0).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: Int16(0).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt16(0).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt16(0).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(0).littleEndian) { Array($0) })
  return packet
}

private func makeOwnerInfoPacket(tag: Data, firmware: String) -> Data {
  var packet = Data([ResponseCode.binaryResponse.rawValue, 0x00])
  packet.append(tag)
  packet.append(contentsOf: "\(firmware)\nNode\n".utf8)
  return packet
}

private func makeBatteryPacket(level: UInt16) -> Data {
  var packet = Data([ResponseCode.battery.rawValue])
  packet.append(contentsOf: withUnsafeBytes(of: level.littleEndian) { Array($0) })
  return packet
}
