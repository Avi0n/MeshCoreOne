import Foundation
@testable import MC1
@testable import MC1Services
import Testing

extension ContactCleanupTests {
  @Test
  func `automatic cleanup suppresses duplicates and cancels on teardown`() async throws {
    configureAutomaticCleanup()
    let env = try await makeEnvironment()
    defer { env.stop() }
    let appState = AppState(
      modelContainer: env.manager.modelContainer, defaults: defaults, injectedConnectionManager: env.manager
    )
    defer { appState.shutdown() }
    appState.performStaleNodeCleanup()
    try await waitForCommands(1, transport: env.transport)
    let running = try #require(appState.staleNodeCleanupTask)

    appState.performStaleNodeCleanup()
    #expect(!running.isCancelled)

    appState.tearDownAppStateSessionState()
    #expect(running.isCancelled)
    #expect(appState.staleNodeCleanupTask == nil)
    await running.value
    #expect(try await env.services.dataStore.fetchContacts(radioID: env.radioID).count == 3)
    #expect(defaults.double(forKey: AppStorageKey.lastStaleCleanupDate.rawValue) == 0)
  }

  @Test
  func `replacement services get their own automatic cleanup run`() async throws {
    configureAutomaticCleanup()
    let env = try await makeEnvironment()
    defer { env.stop() }
    let appState = AppState(
      modelContainer: env.manager.modelContainer, defaults: defaults, injectedConnectionManager: env.manager
    )
    defer { appState.shutdown() }
    appState.performStaleNodeCleanup()
    try await waitForCommands(1, transport: env.transport)
    let old = try #require(appState.staleNodeCleanupTask)
    let (session, transport) = try await makeSession()
    defer { Task { await session.stop() } }
    let replacement = ServiceContainer(
      session: session,
      dataStore: env.services.dataStore, radioID: env.radioID
    )
    defer { Task { await replacement.tearDown() } }
    env.manager.setTestState(services: replacement)

    appState.performStaleNodeCleanup()
    #expect(old.isCancelled)
    #expect(appState.staleNodeCleanupServiceID == ObjectIdentifier(replacement))
    let current = try #require(appState.staleNodeCleanupTask)
    await old.value
    try await waitForCommands(1, transport: transport)
    #expect(appState.staleNodeCleanupTask?.isCancelled == false)
    #expect(appState.staleNodeCleanupServiceID == ObjectIdentifier(replacement))
    #expect(defaults.double(forKey: AppStorageKey.lastStaleCleanupDate.rawValue) == 0)
    for count in 1...3 {
      try await waitForCommands(count, transport: transport)
      await transport.simulateOK()
    }
    await current.value
    #expect(try await env.services.dataStore.fetchContacts(radioID: env.radioID).isEmpty)
    #expect(appState.staleNodeCleanupTask == nil)
    #expect(defaults.double(forKey: AppStorageKey.lastStaleCleanupDate.rawValue) > 0)
  }

  @Test
  func `disabling automatic cleanup cancels its captured threshold without stamping cooldown`() async throws {
    configureAutomaticCleanup()
    let env = try await makeEnvironment()
    defer { env.stop() }
    let appState = AppState(
      modelContainer: env.manager.modelContainer, defaults: defaults, injectedConnectionManager: env.manager
    )
    defer { appState.shutdown() }
    appState.performStaleNodeCleanup()
    try await waitForCommands(1, transport: env.transport)
    let running = try #require(appState.staleNodeCleanupTask)

    defaults.set(0, forKey: AppStorageKey.autoDeleteStaleNodesDays.rawValue)
    appState.performStaleNodeCleanup(force: true)
    #expect(running.isCancelled)
    #expect(appState.staleNodeCleanupTask == nil)
    await running.value

    #expect(try await env.services.dataStore.fetchContacts(radioID: env.radioID).count == 3)
    #expect(await env.transport.sentData.count == 1)
    #expect(defaults.double(forKey: AppStorageKey.lastStaleCleanupDate.rawValue) == 0)
  }

  @Test
  func `forcing cleanup on the same services replaces the running task`() async throws {
    configureAutomaticCleanup()
    let env = try await makeEnvironment()
    defer { env.stop() }
    let appState = AppState(
      modelContainer: env.manager.modelContainer, defaults: defaults, injectedConnectionManager: env.manager
    )
    defer { appState.shutdown() }
    appState.performStaleNodeCleanup()
    try await waitForCommands(1, transport: env.transport)
    let old = try #require(appState.staleNodeCleanupTask)

    appState.performStaleNodeCleanup(force: true)
    #expect(old.isCancelled)
    #expect(appState.staleNodeCleanupTask?.isCancelled == false)
  }

  @Test
  func `completed automatic cleanup stamps cooldown and suppresses another eligible run`() async throws {
    configureAutomaticCleanup()
    let env = try await makeEnvironment()
    defer { env.stop() }
    let appState = AppState(
      modelContainer: env.manager.modelContainer, defaults: defaults, injectedConnectionManager: env.manager
    )
    defer { appState.shutdown() }
    let contact = try #require(try await env.services.dataStore.fetchContact(id: env.contactIDs[0]))
    let startedAt = Date().timeIntervalSinceReferenceDate
    appState.performStaleNodeCleanup()
    let running = try #require(appState.staleNodeCleanupTask)
    for count in 1...3 {
      try await waitForCommands(count, transport: env.transport)
      await env.transport.simulateOK()
    }
    await running.value

    let stamp = defaults.double(forKey: AppStorageKey.lastStaleCleanupDate.rawValue)
    #expect(stamp >= startedAt)
    #expect(stamp <= Date().timeIntervalSinceReferenceDate)
    #expect(try await env.services.dataStore.fetchContacts(radioID: env.radioID).isEmpty)
    try await env.services.dataStore.saveContact(contact)
    appState.performStaleNodeCleanup()
    #expect(appState.staleNodeCleanupTask == nil)
    #expect(await env.transport.sentData.count == 3)
    #expect(try await env.services.dataStore.fetchContacts(radioID: env.radioID).count == 1)
    #expect(defaults.double(forKey: AppStorageKey.lastStaleCleanupDate.rawValue) == stamp)
  }

  func configureAutomaticCleanup() {
    defaults.set(7, forKey: AppStorageKey.autoDeleteStaleNodesDays.rawValue)
    defaults.removeObject(forKey: AppStorageKey.lastStaleCleanupDate.rawValue)
  }
}
