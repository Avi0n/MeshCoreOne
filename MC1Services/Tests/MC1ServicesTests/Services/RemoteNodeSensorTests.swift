import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Remote sensor operations")
struct RemoteNodeSensorTests {
  @Test
  func `sensor has its own session role`() {
    #expect(RemoteNodeRole(contactType: .sensor) == .sensor)
  }
}

extension RemoteNodeSensorTests {
  private struct Harness {
    let store: PersistenceStore
    let service: RemoteNodeService
    let session: MockMeshCoreSession
    let remote: RemoteNodeSessionDTO
  }

  private func harness(connected: Bool = true, permission: RoomPermissionLevel = .admin) async throws -> Harness {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let remote = RemoteNodeSessionDTO.testSession(radioID: radioID, role: .sensor,
                                                  isConnected: connected, permissionLevel: permission)
    try await store.saveRemoteNodeSessionDTO(remote)
    let session = MockMeshCoreSession()
    let service = RemoteNodeService(session: session, dataStore: store, keychainService: KeychainService())
    await service.startEventMonitoring()
    try await waitUntil("event stream should be registered") { await session.eventSubscriptionCount == 1 }
    return Harness(store: store, service: service, session: session, remote: remote)
  }

  private func reply(_ text: String, harness: Harness, index: Int) async throws {
    try await waitUntil("command should be sent") { await harness.session.sendCommandInvocations.count > index }
    let command = await harness.session.sendCommandInvocations[index].command
    let prefix = try #require(CLIResponse.splitEchoedPrefix(command)?.prefix)
    await harness.session.yieldEvent(.contactMessageReceived(ContactMessage(
      senderPublicKeyPrefix: Data(harness.remote.publicKey.prefix(6)), pathLength: 0, textType: 1,
      senderTimestamp: Date(), signature: nil, text: prefix + text, snr: nil
    )))
  }

  @Test(arguments: [UInt8(0), 1, 2, 0xC1, 0xC2, 0xC3])
  func `sensor permission requires the admin boolean`(acl: UInt8) {
    let nonadmin = LoginResult(success: true, isAdmin: false, aclPermissions: acl, publicKeyPrefix: Data())
    #expect(nonadmin.permissionLevel(for: .sensor) == .guest)
    let admin = LoginResult(success: true, isAdmin: true, aclPermissions: acl, publicKeyPrefix: Data())
    #expect(admin.permissionLevel(for: .sensor) == .admin)
  }

  @Test
  func `settings load all pages without get fanout`() async throws {
    let h = try await harness()
    let task = Task { try await h.service.fetchSensorSettings(sessionID: h.remote.id) }
    try await reply("3 vars\na=1\nb=x=y\n... next:2", harness: h, index: 0)
    try await reply("3 vars\nc=", harness: h, index: 1)
    #expect(try await task.value.map(\.value) == ["1", "x=y", ""])
    let sent = await h.session.sendCommandInvocations.map(\.command)
    #expect(sent.map { CLIResponse.splitEchoedPrefix($0)?.body } == ["sensor list", "sensor list 2"])
    await h.service.retire()
  }

  @Test
  func `accepted setter reads back exactly once`() async throws {
    let h = try await harness()
    let task = Task {
      try await h.service.setSensorSetting(sessionID: h.remote.id, setting: SensorSetting(key: "gps", value: "0"), value: "1")
    }
    try await reply("ok", harness: h, index: 0)
    try await reply("> 1", harness: h, index: 1)
    #expect(try await task.value == SensorSetting(key: "gps", value: "1"))
    #expect(await h.session.sendCommandInvocations.count == 2)
    #expect(await h.session.resetPathPublicKeys.isEmpty)
    await h.service.retire()
  }

  @Test(arguments: ["reboot", " rebootx", "clkreboot", "poweroff", "shutdownx"])
  func `no reply submission needs only host acceptance`(command: String) async throws {
    let h = try await harness()
    #expect(try await h.service.sendRawCLICommand(sessionID: h.remote.id, command: command) == "")
    #expect(await h.session.sendCommandInvocations.count == 1)
    #expect(await h.session.getMessageTimeouts.isEmpty)
    #expect(try await h.store.fetchRemoteNodeSession(id: h.remote.id)?.isConnected == false)
    await h.service.retire()
  }

  @Test
  func `host timeout is an error and does not repeat reboot`() async throws {
    let h = try await harness()
    await h.session.setSendCommandResult(.failure(MeshCoreError.timeout))
    await #expect(throws: RemoteNodeError.self) { try await h.service.rebootSensor(sessionID: h.remote.id) }
    #expect(await h.session.sendCommandInvocations.count == 1)
    #expect(await h.session.getMessageTimeouts.isEmpty)
    await h.service.retire()
  }

  @Test
  func `ordinary sensor CLI has one attempt on timeout`() async throws {
    let h = try await harness()
    await #expect(throws: RemoteNodeError.self) {
      try await h.service.sendRawCLICommand(sessionID: h.remote.id, command: "sensor set gps 1", timeout: .milliseconds(20))
    }
    #expect(await h.session.sendCommandInvocations.count == 1)
    #expect(await h.session.resetPathPublicKeys.isEmpty)
    #expect(RemoteCLINoReplyCommand.sensorCommand("REBOOT") == nil)
    await h.service.retire()
  }

  @Test
  func `guest and disconnected sensors cannot issue management commands`() async throws {
    for (connected, permission) in [(true, RoomPermissionLevel.guest), (false, .admin)] {
      let h = try await harness(connected: connected, permission: permission)
      await #expect(throws: RemoteNodeError.self) { try await h.service.fetchSensorSettings(sessionID: h.remote.id) }
      await #expect(throws: RemoteNodeError.self) { try await h.service.rebootSensor(sessionID: h.remote.id) }
      #expect(await h.session.sendCommandInvocations.isEmpty)
      await h.service.retire()
    }
  }
}

extension RemoteNodeSensorTests {
  @Test(arguments: [true, false])
  func `nonadmin ACL can authenticate telemetry but cannot open management`(requireAdmin: Bool) async throws {
    let h = try await harness(connected: false)
    let contact = ContactDTO.testContact(radioID: h.remote.radioID, publicKey: h.remote.publicKey, typeRawValue: 4)
    await h.session.setSendLoginResults([.success(MessageSentInfo(route: 0, expectedAck: Data(), suggestedTimeoutMs: 100))])
    let task = Task {
      try await h.service.connectSensor(radioID: contact.radioID, contact: contact, password: "",
                                        rememberPassword: false, requireAdmin: requireAdmin, pathLength: 0, onTimeoutKnown: nil)
    }
    try await waitUntil("ACL login should be sent") { await h.session.sendLoginInvocations.count == 1 }
    await h.service.handleLoginResult(LoginResult(success: true, isAdmin: false, aclPermissions: 2,
                                                  publicKeyPrefix: Data(contact.publicKey.prefix(6))),
                                      fromPublicKeyPrefix: Data(contact.publicKey.prefix(6)))
    if requireAdmin {
      await #expect(throws: RemoteNodeError.self) { try await task.value }
      #expect(try await h.store.fetchRemoteNodeSession(id: h.remote.id)?.isConnected == false)
    } else {
      let result = try await task.value
      #expect(result.isConnected)
      #expect(!result.isAdmin)
    }
    #expect(await h.session.sendCommandInvocations.isEmpty)
    await h.service.retire()
  }

  @Test(arguments: ["1234567890123456", "éééééééé"])
  func `oversized password is rejected before any send`(password: String) async throws {
    let h = try await harness()
    await #expect(throws: RemoteNodeError.self) { try await h.service.login(sessionID: h.remote.id, password: password) }
    #expect(await h.session.sendLoginInvocations.isEmpty)
    await h.service.retire()
  }

  @Test
  func `reconnect authenticates roles sequentially and keeps failures local`() async throws {
    let h = try await harness(connected: false)
    let keychain = KeychainService()
    let roles: [RemoteNodeRole] = [.sensor, .repeater, .roomServer]
    var nodes: [RemoteNodeSessionDTO] = []
    for (index, role) in roles.enumerated() {
      let node = RemoteNodeSessionDTO.testSession(radioID: h.remote.radioID,
                                                  publicKey: Data(repeating: UInt8(0xD0 + index), count: 32), role: role, permissionLevel: .admin)
      nodes.append(node)
      try await h.store.saveRemoteNodeSessionDTO(node)
      try await keychain.storePassword("test", forNodeKey: node.publicKey)
    }
    await h.session.setSendLoginResults(Array(repeating: .success(
      MessageSentInfo(route: 0, expectedAck: Data(), suggestedTimeoutMs: 100)
    ), count: roles.count))
    let task = Task { await h.service.handleBLEReconnection(sessionIDs: Set(nodes.map(\.id))) }
    for index in roles.indices {
      try await waitUntil("one login should be outstanding") { await h.session.sendLoginInvocations.count == index + 1 }
      #expect(await h.service.pendingLoginCount == 1)
      let sent = await h.session.sendLoginInvocations[index]
      let prefix = Data(sent.destination.prefix(6))
      await h.service.handleLoginResult(LoginResult(success: index != 1, isAdmin: true, aclPermissions: nil,
                                                    publicKeyPrefix: prefix), fromPublicKeyPrefix: prefix)
    }
    await task.value
    #expect(await h.session.sendLoginInvocations.count == 3)
    #expect(await h.service.pendingLoginCount == 0)
    for node in nodes {
      try await keychain.deletePassword(forNodeKey: node.publicKey)
    }
    await h.service.retire()
  }
}

extension RemoteNodeSensorTests {
  @Test
  func `unsupported binary operations fail before dispatch`() async throws {
    let h = try await harness()
    await #expect { try await h.service.requestStatus(sessionID: h.remote.id) } throws: { error in
      guard case RemoteNodeError.invalidResponse = error else { return false }
      return true
    }
    await #expect { try await h.service.requestOwnerInfo(sessionID: h.remote.id) } throws: { error in
      guard case RemoteNodeError.invalidResponse = error else { return false }
      return true
    }
    await #expect { try await h.service.sendKeepAlive(sessionID: h.remote.id) } throws: { error in
      guard case RemoteNodeError.invalidResponse = error else { return false }
      return true
    }
    await h.service.retire()
  }

  @Test
  func `queued command rechecks authorization before dispatch`() async throws {
    let h = try await harness()
    let first = Task { try await h.service.sendRawCLICommand(sessionID: h.remote.id, command: "sensor list") }
    try await waitUntil("first command should hold the slot") { await h.session.sendCommandInvocations.count == 1 }
    let second = Task { try await h.service.sendRawCLICommand(sessionID: h.remote.id, command: "reboot") }
    try await waitUntil("second command should wait") { await !h.service.cliSlotWaiters.isEmpty }
    await h.service.disconnect(sessionID: h.remote.id)
    try await reply("no custom var", harness: h, index: 0)
    #expect(try await first.value == "no custom var")
    await #expect(throws: RemoteNodeError.self) { try await second.value }
    #expect(await h.session.sendCommandInvocations.count == 1)
    await h.service.retire()
  }
}

extension RemoteNodeSensorTests {
  @Test
  func `same sensor key keeps separate radio sessions and permissions`() async throws {
    let h = try await harness()
    let otherRadio = UUID()
    let contact = ContactDTO.testContact(radioID: otherRadio, publicKey: h.remote.publicKey, typeRawValue: 4)
    try await h.store.saveContact(contact)
    let other = try await h.service.createSession(radioID: otherRadio, contact: contact)
    #expect(other.id != h.remote.id)
    #expect(other.radioID == otherRadio)
    #expect(other.isSensor)
    #expect(!other.isConnected)
    #expect(!other.isAdmin)
    #expect(try await h.store.fetchRemoteNodeSession(id: h.remote.id)?.isAdmin == true)
    #expect(try await h.store.fetchRemoteNodeSessions(radioID: h.remote.radioID).count == 1)
    await h.service.retire()
  }

  @Test
  func `a cancelled attempt cannot cancel its replacement`() async throws {
    let h = try await harness(connected: false)
    await h.session.setSendLoginResults(Array(repeating: .success(
      MessageSentInfo(route: 0, expectedAck: Data(), suggestedTimeoutMs: 100)
    ), count: 2))
    let prefix = Data(h.remote.publicKey.prefix(6))
    let first = Task { try await h.service.login(sessionID: h.remote.id, password: "old") }
    try await waitUntil("first login should send") { await h.session.sendLoginInvocations.count == 1 }
    let firstID = try #require(await h.service.loginAttemptIDs[prefix])
    let second = Task { try await h.service.login(sessionID: h.remote.id, password: "new") }
    try await waitUntil("replacement login should send") { await h.session.sendLoginInvocations.count == 2 }
    await h.service.cancelPendingLogin(for: prefix, attemptID: firstID)
    #expect(await h.service.pendingLoginCount == 1)
    await h.service.handleLoginResult(LoginResult(success: true, isAdmin: true, aclPermissions: nil,
                                                  publicKeyPrefix: prefix), fromPublicKeyPrefix: prefix)
    await #expect(throws: RemoteNodeError.self) { try await first.value }
    #expect(try await second.value.isAdmin)
    await h.service.retire()
  }
}

extension RemoteNodeSensorTests {
  @Test
  func `a retired sensor connect cannot disconnect the replacement`() async throws {
    let h = try await harness(connected: false)
    let contact = ContactDTO.testContact(radioID: h.remote.radioID, publicKey: h.remote.publicKey, typeRawValue: 4)
    await h.session.setSendLoginResults(Array(repeating: .success(
      MessageSentInfo(route: 0, expectedAck: Data(), suggestedTimeoutMs: 100)
    ), count: 2))
    func connect() async throws -> RemoteNodeSessionDTO {
      try await h.service.connectSensor(radioID: contact.radioID, contact: contact, password: "",
                                        rememberPassword: false, requireAdmin: false, pathLength: 0, onTimeoutKnown: nil)
    }
    let first = Task { try await connect() }
    try await waitUntil("first login should send") { await h.session.sendLoginInvocations.count == 1 }
    let second = Task { try await connect() }
    try await waitUntil("replacement login should send") { await h.session.sendLoginInvocations.count == 2 }
    let prefix = Data(contact.publicKey.prefix(6))
    await h.service.handleLoginResult(LoginResult(success: true, isAdmin: true, aclPermissions: nil,
                                                  publicKeyPrefix: prefix), fromPublicKeyPrefix: prefix)
    #expect(try await second.value.isConnected)
    await #expect(throws: RemoteNodeError.self) { try await first.value }
    #expect(try await h.store.fetchRemoteNodeSession(id: h.remote.id)?.isConnected == true)
    await h.service.retire()
  }
}
