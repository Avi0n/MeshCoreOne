import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Sensor settings workspace", .timeLimit(.minutes(1)))
@MainActor
struct SensorSettingsViewModelTests {
  private static let commandTimeout: TimeInterval = 10
  /// A CLI poll sleeps at most half a second. A command wait uses `commandTimeout`.
  private static let pollSleepCeiling: Duration = .seconds(1)
  private static let pollReleaseSettle: Duration = .milliseconds(100)

  @MainActor
  private final class Harness {
    let transport = MockTransport()
    let clock: TestClock
    let session: MeshCoreSession
    let store: PersistenceStore
    let remote: RemoteNodeService
    let descriptor: RemoteNodeSessionDTO
    let workspaces = RemoteAdminWorkspaces()
    var currentRemote: RemoteNodeService?
    var model: SensorSettingsViewModel {
      workspaces.sensor(for: descriptor).settings
    }

    init() async throws {
      clock = TestClock()
      session = MeshCoreSession(
        transport: transport,
        configuration: SessionConfiguration(defaultTimeout: SensorSettingsViewModelTests.commandTimeout, clientIdentifier: "Test"),
        clock: clock
      )
      store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
      descriptor = RemoteNodeSessionDTO(radioID: UUID(), publicKey: Data(repeating: 0xA4, count: 32),
                                        name: "Sensor", role: .sensor, isConnected: true, permissionLevel: .admin)
      remote = RemoteNodeService(session: session, dataStore: store, keychainService: KeychainService())
      currentRemote = remote
      try await store.saveRemoteNodeSessionDTO(descriptor)
      let start = Task { try await session.start() }
      try await waitUntil("app start should send") { await self.transport.sentData.count == 1 }
      var info = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
      info.append(Data(repeating: 1, count: 32))
      info.append(Data(repeating: 0, count: 12))
      info.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
      info.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) })
      info.append(contentsOf: [7, 5])
      await transport.simulateReceive(info)
      try await start.value
      try #require(clock.sleeperCount == 0)
      await transport.clearSentData()
      await remote.startEventMonitoring()
      configure()
      try await waitUntil("persisted authorization should load") { self.model.isAuthorized }
    }

    func configure() {
      workspaces.configureSensor(for: descriptor, remoteNodeService: { [weak self] in self?.currentRemote },
                                 dataStore: { [weak self] in self?.store }, connectionState: { .ready })
    }

    var commands: [Data] {
      get async { await transport.sentData.filter { $0.first == CommandCode.sendMessage.rawValue } }
    }

    func reply(_ text: String, index: Int, hostFirst: Bool = true) async throws {
      try await waitUntil("CLI command should send") { await self.commands.count > index }
      let frame = await commands[index]
      let headerSize = 13
      let command = String(decoding: frame.dropFirst(headerSize), as: UTF8.self)
      let prefix = try #require(CLIResponse.splitEchoedPrefix(command)?.prefix)
      if hostFirst { await acceptHost() }
      await session.dispatcher.dispatch(.contactMessageReceived(ContactMessage(
        senderPublicKeyPrefix: Data(descriptor.publicKey.prefix(6)), pathLength: 0, textType: 1,
        senderTimestamp: Date(), signature: nil, text: prefix + text, snr: nil
      )))
      if !hostFirst { await acceptHost() }
      await session.dispatcher.dispatch(.noMoreMessages)
      try await releaseCLIPoll()
    }

    /// The CLI poll holds the serializer until its short sleep ends. Wake that sleep after the reply.
    /// A follow-up command then parks on the full command timeout; leave that wait for the next reply.
    private func releaseCLIPoll() async throws {
      let sentAtRelease = await commands.count
      var idleSince: ContinuousClock.Instant?
      try await waitUntil(timeout: .seconds(2), "cli poll should release the session") {
        if self.clock.sleeperCount > 0,
           let parked = self.clock.nextSleepDuration,
           parked < SensorSettingsViewModelTests.pollSleepCeiling {
          self.clock.advanceToNextSleep()
          idleSince = nil
          return false
        }
        let callers = await self.session.getMessageCallerCountForTest
        guard callers == 0 else {
          idleSince = nil
          return false
        }
        if self.clock.sleeperCount > 0 {
          idleSince = nil
          return await self.commands.count > sentAtRelease
        }
        let start = idleSince ?? ContinuousClock.now
        if idleSince == nil { idleSince = start }
        return ContinuousClock.now - start >= SensorSettingsViewModelTests.pollReleaseSettle
      }
    }

    func acceptHost() async {
      await session.dispatcher.dispatch(.messageSent(MessageSentInfo(route: 0, expectedAck: Data(), suggestedTimeoutMs: 1)))
    }

    func load(_ text: String = "1 vars\ngps=0") async throws {
      let count = await commands.count
      let task = Task { await model.reload() }
      try await reply(text, index: count)
      await task.value
      #expect(model.hasLoaded)
    }

    func close() async {
      workspaces.reset()
      await currentRemote?.retire()
      await remote.retire()
      await session.stop()
    }
  }

  @Test(arguments: [true, false])
  func `settings load in either reply order and empty is complete`(hostFirst: Bool) async throws {
    let h = try await Harness()
    let load = Task { await h.model.reload() }
    try await h.reply("no custom var", index: 0, hostFirst: hostFirst)
    await load.value
    #expect(h.model.hasLoaded)
    #expect(h.model.settings.isEmpty)
    #expect(h.model.errorMessage == nil)
    #expect(h.model.canSend)
    await h.close()
  }

  @Test
  func `accepted save survives reopening and preserves A newer draft`() async throws {
    let h = try await Harness()
    try await h.load()
    h.model[draft: "gps"] = "1"
    h.model.save("gps")
    h.model.save("gps")
    try await waitUntil("one setter should send") { await h.commands.count == 2 }
    let reopened = h.workspaces.sensor(for: h.descriptor).settings
    #expect(reopened === h.model)
    #expect(reopened.isMutating)
    h.configure()
    h.model[draft: "gps"] = "2"
    try await h.reply("ok", index: 1)
    try await h.reply("> 1", index: 2)
    try await waitUntil("verification should finish") { !h.model.isMutating }
    #expect(h.model.settings.first?.value == "1")
    #expect(h.model[draft: "gps"] == "2")
    #expect(h.model.canSave("gps"))
    #expect(h.model.statusMessage == L10n.RemoteNodes.RemoteNodes.Sensor.saved)
    #expect(await h.commands.count == 3)
    await h.close()
  }

  @Test
  func `verification mismatch requires reload without discarding draft`() async throws {
    let h = try await Harness()
    try await h.load()
    h.model[draft: "gps"] = "1"
    h.model.save("gps")
    try await h.reply("ok", index: 1)
    try await h.reply("> 0", index: 2)
    try await waitUntil("unknown outcome should be published") { h.model.requiresReload && !h.model.isMutating }
    #expect(h.model[draft: "gps"] == "1")
    #expect(h.model.errorMessage != nil)
    #expect(!h.model.canSave("gps"))
    h.model.save("gps")
    #expect(await h.commands.count == 3)
    try await h.load()
    #expect(!h.model.requiresReload)
    #expect(h.model[draft: "gps"] == "1")
    #expect(h.model.canSave("gps"))
    await h.close()
  }

  @Test
  func `reload keeps A draft edited while the read was pending`() async throws {
    let h = try await Harness()
    try await h.load()
    let task = Task { await h.model.reload() }
    try await waitUntil("reload should send") { await h.commands.count == 2 }
    h.model[draft: "gps"] = "draft"
    try await h.reply("1 vars\ngps=1", index: 1)
    await task.value
    #expect(h.model.settings.first?.value == "1")
    #expect(h.model[draft: "gps"] == "draft")
    await h.close()
  }

  @Test
  func `provider replacement cancels mutation and cannot replay it`() async throws {
    let h = try await Harness()
    try await h.load()
    h.model[draft: "gps"] = "1"
    h.model.save("gps")
    try await waitUntil("setter should send") { await h.commands.count == 2 }
    let replacement = RemoteNodeService(session: h.session, dataStore: h.store, keychainService: KeychainService())
    await replacement.startEventMonitoring()
    h.currentRemote = replacement
    h.configure()
    #expect(!h.model.isMutating)
    #expect(h.model.requiresReload)
    try await h.reply("ok", index: 1)
    #expect(h.model.settings.first?.value == "0")
    #expect(h.model.statusMessage == nil)
    #expect(h.model[draft: "gps"] == "1")
    #expect(await h.commands.count == 2)
    await h.close()
  }

  @Test
  func `rebind ignores a foreign radio and an obsolete connection`() async throws {
    let h = try await Harness()
    let replacement = RemoteNodeService(session: h.session, dataStore: h.store, keychainService: KeychainService())
    for (radioID, isCurrent) in [(UUID(), true), (h.descriptor.radioID, false)] {
      await h.workspaces.rebind(
        radioID: radioID,
        repeaterAdminService: { nil },
        roomAdminService: { nil },
        contactService: { nil },
        nodeSnapshotService: { nil },
        deviceHashSize: { nil },
        remoteNodeService: { replacement },
        dataStore: { h.store },
        connectionState: { .ready },
        isCurrent: { isCurrent }
      )
      #expect(h.model.isBound(to: h.remote))
      #expect(h.model.isAuthorized)
    }
    await h.close()
  }

  @Test
  func `reboot waits for host acceptance then disables further commands`() async throws {
    let h = try await Harness()
    h.model.reboot()
    h.model.reboot()
    try await waitUntil("one reboot should send") { await h.commands.count == 1 }
    #expect(h.model.isMutating)
    #expect(h.model.statusMessage == nil)
    await h.acceptHost()
    try await waitUntil("accepted reboot should finish") { !h.model.isMutating }
    #expect(!h.model.isAuthorized)
    #expect(h.model.statusMessage == L10n.RemoteNodes.RemoteNodes.Sensor.rebootSent)
    h.model.reboot()
    #expect(await h.commands.count == 1)
    await h.close()
  }

  @Test
  func `session events disable controls without erasing outcomes`() async throws {
    let h = try await Harness()
    try await h.load()
    await h.remote.disconnect(sessionID: h.descriptor.id)
    try await waitUntil("session event should revoke controls") { !h.model.isAuthorized }
    #expect(h.model.settings.first?.value == "0")
    #expect(!h.model.canSave("gps"))
    await h.close()
  }
}
