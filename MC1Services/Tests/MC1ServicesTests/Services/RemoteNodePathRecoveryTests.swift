import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("RemoteNodeService binary admin and CLI path recovery")
struct RemoteNodePathRecoveryTests {
  private static let publicKey = Data(repeating: 0xCD, count: 32)
  private static let directPath = Data([0x0A, 0x0B, 0x0C])

  private final class Harness: @unchecked Sendable {
    let service: RemoteNodeService
    let contacts: ContactService
    let session: MockMeshCoreSession
    let clock: TestClock
    let dataStore: PersistenceStore
    let sessionID: UUID
    let radioID: UUID
    let defaultsSuiteName: String

    init(
      service: RemoteNodeService,
      contacts: ContactService,
      session: MockMeshCoreSession,
      clock: TestClock,
      dataStore: PersistenceStore,
      sessionID: UUID,
      radioID: UUID,
      defaultsSuiteName: String
    ) {
      self.service = service
      self.contacts = contacts
      self.session = session
      self.clock = clock
      self.dataStore = dataStore
      self.sessionID = sessionID
      self.radioID = radioID
      self.defaultsSuiteName = defaultsSuiteName
    }

    deinit {
      UserDefaults(suiteName: defaultsSuiteName)?.removePersistentDomain(forName: defaultsSuiteName)
    }
  }

  private func makeHarness(
    outPathLength: UInt8,
    outPath: Data,
    fallbackEnabled: Bool? = nil
  ) async throws -> Harness {
    let suiteName = "RemoteNodePathRecovery.\(UUID().uuidString)"
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defaults.removePersistentDomain(forName: suiteName)
    if let fallbackEnabled {
      defaults.set(fallbackEnabled, forKey: AppStorageKey.remoteFloodFallback.rawValue)
    }

    do {
      let radioID = UUID()
      let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
      let contact = ContactDTO.testContact(
        radioID: radioID,
        publicKey: Self.publicKey,
        typeRawValue: ContactType.repeater.rawValue,
        outPathLength: outPathLength,
        outPath: outPath
      )
      try await dataStore.saveContact(contact)

      let remoteSession = RemoteNodeSessionDTO.testSession(
        radioID: radioID,
        publicKey: Self.publicKey,
        role: .repeater,
        permissionLevel: .admin
      )
      try await dataStore.saveRemoteNodeSessionDTO(remoteSession)

      let session = MockMeshCoreSession()
      await session.seedRadioContact(MeshContact(
        id: Self.publicKey.hexString,
        publicKey: Self.publicKey,
        type: .repeater,
        flags: ContactFlags(rawValue: 0),
        outPathLength: outPathLength,
        outPath: outPath,
        advertisedName: "Repeater",
        lastAdvertisement: Date(timeIntervalSince1970: 1_700_000_000),
        latitude: 0,
        longitude: 0,
        lastModified: Date(timeIntervalSince1970: 1_700_000_100)
      ))

      let clock = TestClock()
      let contacts = ContactService(
        session: session,
        dataStore: dataStore,
        syncCoordinator: nil,
        cleanupCoordinator: nil,
        clock: clock,
        defaults: defaults
      )
      let owner = ContactRadioOwner()
      owner.bind(contacts)
      let service = RemoteNodeService(
        session: session,
        clock: clock,
        dataStore: dataStore,
        keychainService: KeychainService(),
        contactRadioOwner: owner,
        defaults: defaults
      )
      return Harness(
        service: service,
        contacts: contacts,
        session: session,
        clock: clock,
        dataStore: dataStore,
        sessionID: remoteSession.id,
        radioID: radioID,
        defaultsSuiteName: suiteName
      )
    } catch {
      defaults.removePersistentDomain(forName: suiteName)
      throw error
    }
  }

  private func makeStatusResponse() -> StatusResponse {
    StatusResponse(
      publicKeyPrefix: Self.publicKey.prefix(6),
      battery: 4100,
      txQueueLength: 0,
      noiseFloor: -100,
      lastRSSI: -70,
      packetsReceived: 10,
      packetsSent: 5,
      airtime: 1,
      uptime: 100,
      sentFlood: 0,
      sentDirect: 0,
      receivedFlood: 0,
      receivedDirect: 0,
      fullEvents: 0,
      lastSNR: 9.0,
      directDuplicates: 0,
      floodDuplicates: 0,
      rxAirtime: 1,
      receiveErrors: 0
    )
  }

  @Test
  func `status timeout does not reset the path`() async throws {
    let harness = try await makeHarness(outPathLength: 3, outPath: Self.directPath)
    await harness.session.setRequestStatusResult(.failure(MeshCoreError.timeout))

    await #expect(throws: RemoteNodeError.self) {
      _ = try await harness.service.requestStatus(sessionID: harness.sessionID, priority: .interactive)
    }
    #expect(await harness.session.resetPathPublicKeys.isEmpty)

    let contact = try await harness.dataStore.fetchContact(
      radioID: harness.radioID,
      publicKey: Self.publicKey
    )
    #expect(contact?.isFloodRouted == false)
  }

  @Test
  func `repeated status timeouts still do not reset the path`() async throws {
    let harness = try await makeHarness(outPathLength: 3, outPath: Self.directPath)
    await harness.session.setRequestStatusResults([
      .failure(MeshCoreError.timeout),
      .failure(MeshCoreError.timeout),
      .failure(MeshCoreError.timeout),
    ])

    for _ in 0..<3 {
      await #expect(throws: RemoteNodeError.self) {
        _ = try await harness.service.requestStatus(sessionID: harness.sessionID, priority: .interactive)
      }
    }
    #expect(await harness.session.resetPathPublicKeys.isEmpty)
  }

  @Test
  func `status success does not reset the path`() async throws {
    let harness = try await makeHarness(outPathLength: 3, outPath: Self.directPath)
    await harness.session.setRequestStatusResult(.success(makeStatusResponse()))

    let response = try await harness.service.requestStatus(
      sessionID: harness.sessionID,
      priority: .interactive
    )
    #expect(response.battery == 4100)
    #expect(await harness.session.resetPathPublicKeys.isEmpty)
  }

  @Test
  func `status timeout maps to RemoteNodeError timeout`() async throws {
    let harness = try await makeHarness(outPathLength: 2, outPath: Self.directPath)
    await harness.session.setRequestStatusResult(.failure(MeshCoreError.timeout))

    let error = await #expect(throws: RemoteNodeError.self) {
      _ = try await harness.service.requestStatus(
        sessionID: harness.sessionID,
        priority: .interactive
      )
    }
    guard case .timeout? = error else {
      Issue.record("Expected RemoteNodeError.timeout, got \(String(describing: error))")
      return
    }
    #expect(await harness.session.resetPathPublicKeys.isEmpty)
  }

  @Test
  func `telemetry timeout does not reset the path`() async throws {
    let harness = try await makeHarness(outPathLength: 2, outPath: Self.directPath)
    await harness.session.setRequestTelemetryResults([.failure(MeshCoreError.timeout)])

    await #expect(throws: RemoteNodeError.self) {
      _ = try await harness.service.requestTelemetry(sessionID: harness.sessionID, priority: .interactive)
    }
    #expect(await harness.session.resetPathPublicKeys.isEmpty)
  }

  @Test(arguments: ["reboot", "clkreboot", "poweroff", "shutdown"])
  func `a no-reply command that times out is sent once and keeps the path`(command: String) async throws {
    let harness = try await makeHarness(outPathLength: 3, outPath: Self.directPath)

    let error = await #expect(throws: RemoteNodeError.self) {
      _ = try await harness.service.sendRawCLICommand(
        sessionID: harness.sessionID,
        command: command,
        timeout: .milliseconds(1)
      )
    }
    guard case .timeout? = error else {
      Issue.record("Expected RemoteNodeError.timeout, got \(String(describing: error))")
      return
    }
    #expect(await harness.session.sendCommandInvocations.count == 1)
    #expect(await harness.session.resetPathPublicKeys.isEmpty)

    let contact = try await harness.dataStore.fetchContact(
      radioID: harness.radioID,
      publicKey: Self.publicKey
    )
    #expect(contact?.isFloodRouted == false)
  }

  @Test
  func `a read-only query that times out repairs path via probe, pauses settle, and retries`() async throws {
    let harness = try await makeHarness(outPathLength: 3, outPath: Self.directPath)
    await harness.session.setRequestStatusResult(.success(makeStatusResponse()))
    await harness.service.startEventMonitoring()

    let task = Task {
      try await harness.service.sendRawCLICommand(
        sessionID: harness.sessionID,
        command: "get name",
        timeout: .milliseconds(1)
      )
    }

    try await waitUntil("service pauses for reciprocalPathSettle") {
      harness.clock.sleeperCount == 1 && harness.clock.nextSleepDuration == RemoteOperationTimeoutPolicy.reciprocalPathSettle
    }
    harness.clock.advance(by: RemoteOperationTimeoutPolicy.reciprocalPathSettle)

    try await waitUntil("retry CLI command is sent") {
      await harness.session.sendCommandInvocations.count == 2
    }
    let command = await harness.session.sendCommandInvocations[1].command
    let prefix = try #require(CLIResponse.splitEchoedPrefix(command)?.prefix)
    await harness.session.yieldEvent(.contactMessageReceived(ContactMessage(
      senderPublicKeyPrefix: Self.publicKey.prefix(6),
      pathLength: 0,
      textType: 1,
      senderTimestamp: Date(),
      signature: nil,
      text: prefix + "MyRepeater",
      snr: nil
    )))

    #expect(try await task.value == "MyRepeater")
    #expect(await harness.session.resetPathPublicKeys == [Self.publicKey])
    #expect(await harness.session.requestStatusPriorities == [.interactive])
    await harness.service.retire()
  }

  @Test
  func `a normal command that times out keeps the path when fallback is off`() async throws {
    let harness = try await makeHarness(
      outPathLength: 3,
      outPath: Self.directPath,
      fallbackEnabled: false
    )

    await #expect(throws: RemoteNodeError.self) {
      _ = try await harness.service.sendRawCLICommand(
        sessionID: harness.sessionID,
        command: "get name",
        timeout: .milliseconds(1)
      )
    }
    #expect(await harness.session.sendCommandInvocations.count == 1)
    #expect(await harness.session.resetPathPublicKeys.isEmpty)

    let contact = try await harness.dataStore.fetchContact(
      radioID: harness.radioID,
      publicKey: Self.publicKey
    )
    #expect(contact?.isFloodRouted == false)
  }

  @Test
  func `owner info timeout does not reset the path`() async throws {
    let harness = try await makeHarness(outPathLength: 1, outPath: Data([0xFF]))
    await harness.session.setRequestOwnerInfoResults([.failure(MeshCoreError.timeout)])

    await #expect(throws: RemoteNodeError.self) {
      _ = try await harness.service.requestOwnerInfo(sessionID: harness.sessionID, priority: .interactive)
    }
    #expect(await harness.session.resetPathPublicKeys.isEmpty)
  }
}
