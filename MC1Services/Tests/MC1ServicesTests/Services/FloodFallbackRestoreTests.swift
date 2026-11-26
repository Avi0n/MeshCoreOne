import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Flood fallback route restore", .timeLimit(.minutes(1)))
struct FloodFallbackRestoreTests {
  private let directPath = Data([0xAB])
  private let directLength: UInt8 = 1
  private let cancelledSleep = Duration.seconds(30)

  @Test
  func `a failed exchange restores the saved direct path`() async throws {
    let harness = try await Harness.make(path: directPath, pathLength: directLength)
    defer { harness.cleanup() }

    await #expect(throws: ProbeError.self) {
      try await harness.service.attemptFloodFallback(
        radioID: harness.radioID,
        publicKey: harness.publicKey,
        rethrowIfUnavailable: ProbeError()
      ) {
        throw ProbeError()
      }
    }

    try await harness.expectRestored(path: directPath, pathLength: directLength)
  }

  @Test
  func `a completed exchange does not write the saved path back`() async throws {
    let harness = try await Harness.make(path: directPath, pathLength: directLength)
    defer { harness.cleanup() }

    let value = try await harness.service.attemptFloodFallback(
      radioID: harness.radioID,
      publicKey: harness.publicKey,
      rethrowIfUnavailable: ProbeError()
    ) {
      1
    }

    #expect(value == 1)
    #expect(await harness.session.resetPathPublicKeys == [harness.publicKey])
    #expect(await harness.session.addContactInvocations.isEmpty)
    let stored = try await harness.store.fetchContact(radioID: harness.radioID, publicKey: harness.publicKey)
    #expect(stored?.outPathLength == PacketBuilder.floodPathSentinel)
  }

  @Test
  func `fallback off leaves the direct path and skips the exchange`() async throws {
    let harness = try await Harness.make(path: directPath, pathLength: directLength, fallbackEnabled: false)
    defer { harness.cleanup() }

    await #expect(throws: ProbeError.self) {
      _ = try await harness.service.attemptFloodFallback(
        radioID: harness.radioID,
        publicKey: harness.publicKey,
        rethrowIfUnavailable: ProbeError()
      ) {
        Issue.record("fallback off should not invoke the operation")
        return 7
      }
    }

    #expect(await harness.session.resetPathPublicKeys.isEmpty)
    #expect(await harness.session.addContactInvocations.isEmpty)
    let stored = try await harness.store.fetchContact(radioID: harness.radioID, publicKey: harness.publicKey)
    #expect(stored?.outPath == directPath)
    #expect(stored?.outPathLength == directLength)
  }

  @Test
  func `cancellation before radio write preserves direct path without restore`() async throws {
    let harness = try await Harness.make(path: directPath, pathLength: directLength)
    defer { harness.cleanup() }
    await harness.session.setStubbedResetPathError(CancellationError())

    await #expect(throws: CancellationError.self) {
      try await harness.service.attemptFloodFallback(
        radioID: harness.radioID,
        publicKey: harness.publicKey,
        rethrowIfUnavailable: ProbeError()
      ) {
        Issue.record("The exchange should not run after the reset fails")
        return 0
      }
    }

    #expect(await harness.session.resetPathPublicKeys == [harness.publicKey])
    #expect(await harness.session.addContactInvocations.isEmpty)
  }

  @Test
  func `cancellation surfacing from the reset after radio write restores the saved path`() async throws {
    let harness = try await Harness.make(path: directPath, pathLength: directLength)
    defer { harness.cleanup() }
    await harness.session.setStubbedResetPathWritesFloodBeforeError(true)
    await harness.session.setStubbedResetPathError(CancellationError())

    await #expect(throws: CancellationError.self) {
      try await harness.service.attemptFloodFallback(
        radioID: harness.radioID,
        publicKey: harness.publicKey,
        rethrowIfUnavailable: ProbeError()
      ) {
        Issue.record("The exchange should not run after the reset fails")
        return 0
      }
    }

    #expect(await harness.session.resetPathPublicKeys == [harness.publicKey])
    try await harness.expectRestored(path: directPath, pathLength: directLength)
  }

  @Test
  func `cancelling the caller after reset still restores the saved path`() async throws {
    let harness = try await Harness.make(path: directPath, pathLength: directLength)
    defer { harness.cleanup() }
    let started = StartedFlag()
    let sleep = cancelledSleep
    let task = Task {
      try await harness.service.attemptFloodFallback(
        radioID: harness.radioID,
        publicKey: harness.publicKey,
        rethrowIfUnavailable: ProbeError()
      ) {
        await started.mark()
        try await Task.sleep(for: sleep)
        return 0
      }
    }

    await started.wait()
    task.cancel()
    await #expect(throws: CancellationError.self) { try await task.value }
    try await harness.expectRestored(path: directPath, pathLength: directLength)
  }
}

private struct ProbeError: Error, Sendable {}

private actor StartedFlag {
  private var started = false
  private var waiters: [CheckedContinuation<Void, Never>] = []

  func mark() {
    started = true
    let pending = waiters
    waiters.removeAll()
    for waiter in pending {
      waiter.resume()
    }
  }

  func wait() async {
    if started { return }
    await withCheckedContinuation { waiters.append($0) }
  }
}

private struct Harness {
  let service: ContactService
  let session: MockMeshCoreSession
  let store: MockPersistenceStore
  let radioID: UUID
  let publicKey: Data
  let suiteName: String

  static func make(path: Data, pathLength: UInt8, fallbackEnabled: Bool = true) async throws -> Harness {
    let suiteName = "FloodFallbackRestoreTests.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defaults.removePersistentDomain(forName: suiteName)
    if !fallbackEnabled {
      defaults.set(false, forKey: AppStorageKey.remoteFloodFallback.rawValue)
    }
    let session = MockMeshCoreSession()
    let store = MockPersistenceStore()
    let radioID = UUID()
    let publicKey = Data(repeating: 0x11, count: ProtocolLimits.publicKeySize)
    let contact = ContactDTO.testContact(
      radioID: radioID,
      publicKey: publicKey,
      outPathLength: pathLength,
      outPath: path
    )
    try await store.saveContact(contact)
    await session.seedRadioContact(MeshContact(
      id: publicKey.hexString,
      publicKey: publicKey,
      type: .repeater,
      flags: ContactFlags(rawValue: 0),
      outPathLength: pathLength,
      outPath: path,
      advertisedName: "Repeater",
      lastAdvertisement: Date(timeIntervalSince1970: 1_700_000_000),
      latitude: 0,
      longitude: 0,
      lastModified: Date(timeIntervalSince1970: 1_700_000_100)
    ))
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: nil,
      cleanupCoordinator: nil,
      defaults: defaults
    )
    return Harness(
      service: service,
      session: session,
      store: store,
      radioID: radioID,
      publicKey: publicKey,
      suiteName: suiteName
    )
  }

  func cleanup() {
    UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName)
  }

  func expectRestored(path: Data, pathLength: UInt8) async throws {
    let written = await session.addContactInvocations
    #expect(written.count == 1)
    #expect(written.first?.contact.outPath == path)
    #expect(written.first?.contact.outPathLength == pathLength)
    let stored = try await store.fetchContact(radioID: radioID, publicKey: publicKey)
    #expect(stored?.outPath == path)
    #expect(stored?.outPathLength == pathLength)
  }
}
