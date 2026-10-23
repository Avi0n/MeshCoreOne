import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Contact cleanup", .serialized)
@MainActor
struct ContactCleanupTests {
  let preferences = IsolatedUserDefaults()

  var defaults: UserDefaults {
    preferences.defaults
  }

  @Test
  func `bulk cleanup waits for each ACK and groups production list updates`() async throws {
    let env = try await makeEnvironment()
    defer { env.stop() }
    let task = Task { try await env.manager.removeUnfavoritedNodes() }

    for count in 1...3 {
      try await waitForCommands(count, transport: env.transport)
      #expect(await env.transport.sentData.count == count)
      #expect(try await env.services.dataStore.fetchContacts(radioID: env.radioID).count == 4 - count)
      #expect(env.services.syncCoordinator.contactsVersion == 0)
      #expect(env.services.syncCoordinator.conversationsVersion == 0)
      await env.transport.simulateOK()
    }
    let result = try await task.value

    #expect(result.removed == 3)
    #expect(env.services.syncCoordinator.contactsVersion == 1)
    #expect(env.services.syncCoordinator.conversationsVersion == 1)
    for id in env.contactIDs {
      #expect(try await env.services.dataStore.fetchMessages(contactID: id, limit: 10, offset: 0).isEmpty)
    }
  }

  @Test
  func `failed radio removal preserves local data and flushes completed deletions`() async throws {
    let env = try await makeEnvironment()
    defer { env.stop() }
    let task = Task { try await env.manager.removeUnfavoritedNodes() }
    try await waitForCommands(1, transport: env.transport)
    await env.transport.simulateOK()
    try await waitForCommands(2, transport: env.transport)
    await env.transport.simulateError(code: ProtocolError.illegalArgument.rawValue)
    let result = try await task.value

    #expect(result.removed == 1)
    #expect(result.total == 3)
    #expect(try await env.services.dataStore.fetchContacts(radioID: env.radioID).count == 2)
    #expect(env.services.syncCoordinator.contactsVersion == 1)
    #expect(env.services.syncCoordinator.conversationsVersion == 1)
  }

  @Test
  func `cancellation flushes confirmed deletions and drains the uncertain ACK`() async throws {
    let env = try await makeEnvironment()
    defer { env.stop() }
    let task = Task { try await env.manager.removeUnfavoritedNodes() }
    try await waitForCommands(1, transport: env.transport)
    await env.transport.simulateOK()
    try await waitForCommands(2, transport: env.transport)
    task.cancel()
    await #expect(throws: CancellationError.self) { try await task.value }

    #expect(try await env.services.dataStore.fetchContacts(radioID: env.radioID).count == 2)
    #expect(env.services.syncCoordinator.contactsVersion == 1)
    #expect(env.services.syncCoordinator.conversationsVersion == 1)
    let foreground = Task { try await env.session.setName("Updated") }
    await env.transport.simulateOK()
    try await waitForCommands(3, transport: env.transport)
    #expect(await env.transport.sentData[2].first == CommandCode.setName.rawValue)
    await env.transport.simulateOK()
    try await foreground.value
  }

  @Test
  func `connection replacement stops further radio removals`() async throws {
    let env = try await makeEnvironment()
    defer { env.stop() }
    let task = Task { try await env.manager.removeUnfavoritedNodes() }
    try await waitForCommands(1, transport: env.transport)
    env.manager.setTestState(services: .some(nil))
    await env.transport.simulateOK()
    let result = try await task.value

    #expect(result.removed == 1)
    #expect(result.total == 3)
    #expect(await env.transport.sentData.count == 1)
    #expect(try await env.services.dataStore.fetchContacts(radioID: env.radioID).count == 2)
    #expect(env.services.syncCoordinator.contactsVersion == 1)
  }

  @Test
  func `bulk cleanup rechecks favorites before removing the next contact`() async throws {
    let env = try await makeEnvironment()
    defer { env.stop() }
    let task = Task { try await env.manager.removeUnfavoritedNodes() }
    try await waitForCommands(1, transport: env.transport)
    let contacts = try await env.services.dataStore.fetchContacts(radioID: env.radioID)
    for contact in contacts {
      try await env.services.dataStore.saveContact(contact.with(isFavorite: true))
    }
    await env.transport.simulateOK()
    let result = try await task.value

    #expect(result.removed == 1)
    #expect(result.total == 1)
    #expect(await env.transport.sentData.count == 1)
    #expect(try await env.services.dataStore.fetchContacts(radioID: env.radioID).allSatisfy(\.isFavorite))
  }

  @Test
  func `stopped session reports partial removal instead of user cancellation`() async throws {
    let env = try await makeEnvironment()
    defer { env.stop() }
    let task = Task { try await env.manager.removeUnfavoritedNodes() }
    try await waitForCommands(1, transport: env.transport)
    await env.transport.simulateOK()
    try await waitForCommands(2, transport: env.transport)
    await env.session.stop()
    let result = try await task.value

    #expect(result.removed == 1)
    #expect(result.total == 3)
    #expect(try await env.services.dataStore.fetchContacts(radioID: env.radioID).count == 2)
    #expect(env.services.syncCoordinator.contactsVersion == 1)
    #expect(env.services.syncCoordinator.conversationsVersion == 1)
  }

  @Test
  func `radio not found deletes locally and continues the batch`() async throws {
    let env = try await makeEnvironment()
    defer { env.stop() }
    let task = Task { try await env.manager.removeUnfavoritedNodes() }
    try await waitForCommands(1, transport: env.transport)
    await env.transport.simulateOK()
    try await waitForCommands(2, transport: env.transport)
    await env.transport.simulateError(code: ProtocolError.notFound.rawValue)
    try await waitForCommands(3, transport: env.transport)
    #expect(try await env.services.dataStore.fetchContacts(radioID: env.radioID).count == 1)
    await env.transport.simulateOK()
    let result = try await task.value

    #expect(result.removed == 3)
    #expect(result.total == 3)
    #expect(try await env.services.dataStore.fetchContacts(radioID: env.radioID).isEmpty)
    #expect(env.services.syncCoordinator.contactsVersion == 1)
    #expect(env.services.syncCoordinator.conversationsVersion == 1)
  }
}
