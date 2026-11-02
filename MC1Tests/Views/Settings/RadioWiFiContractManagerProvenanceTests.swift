import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Synchronization
import Testing

@Suite("Radio WiFi manager CLI request provenance")
@MainActor
struct RadioWiFiContractManagerProvenanceTests {
  @Test(arguments: [false, true])
  func `timed out password cannot cross actual manager session replacement`(canonicalImport: Bool) async throws {
    let env = try await ConnectionLinkSwitchHarness.make()
    let model = RadioWiFiViewModel()
    let importer = NodeConfigImportViewModel()
    importer.configure(
      nodeConfigService: { env.manager.services?.nodeConfigService },
      settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice },
      connectionManager: env.manager
    )
    let firmware = Firmware()
    let oldRequestID = env.manager.connectionRequestID
    var workers: [Task<Void, Never>] = []
    var sessions: [MeshCoreSession] = []
    model.configure(
      settingsService: { env.manager.services?.settingsService },
      connectionState: { env.manager.connectionState },
      connectedDevice: { env.manager.connectedDevice },
      currentTransportType: { env.manager.currentTransportType }
    )
    do {
      await env.bluetooth.setConnectError(nil)
      await env.bluetooth.setCLIResponder { firmware.reply(to: $0) }
      let initialCLICommands = await env.bluetooth.cliCommands.map {
        ConnectionTestRadioTransport.splitCLICommand($0).command
      }
      let originalSettings = try #require(env.manager.services?.settingsService)
      await env.bluetooth.holdCLIReplies(for: "get wifi.pwd")
      let first: Task<Void, Never> = Task {
        _ = await #expect {
          try await originalSettings.readRadioWiFiCredentials(editorID: UUID())
        } throws: { error in
          guard let error = error as? SettingsServiceError,
                case .sessionError(.timeout) = error else { return false }
          return true
        }
      }
      workers.append(first)
      try await waitUntil("the original password frame remains physically delayed") {
        await env.bluetooth.heldCLIReplyCount(for: "get wifi.pwd") == 1
      }
      await first.value
      let oldPasswordCommand = try #require(await env.bluetooth.cliCommands.last {
        ConnectionTestRadioTransport.splitCLICommand($0).command == "get wifi.pwd"
      })
      let oldSession = try #require(env.manager.session)
      sessions.append(oldSession)
      await env.bluetooth.simulateAutoReconnecting(deviceID: env.device.id)
      try await waitUntil("the previous session is withdrawn during automatic Bluetooth recovery") {
        env.manager.services == nil
      }
      await env.bluetooth.simulateReconnected()
      try await waitUntil("the same request finishes recovery with a new session") {
        env.manager.connectionState == .ready && env.manager.services != nil
          && env.manager.session !== oldSession && env.manager.installedLink?.didCompleteSync == true
      }
      let recoveredSession = try #require(env.manager.session)
      sessions.append(recoveredSession)
      let recoveryInitialization = await env.bluetooth.cliCommands.dropFirst(initialCLICommands.count + 2).map {
        ConnectionTestRadioTransport.splitCLICommand($0).command
      }
      #expect(recoveryInitialization == initialCLICommands)
      let canonicalInitializationStart = await env.bluetooth.cliCommands.count
      if canonicalImport {
        let previousSettings = try #require(env.manager.services?.settingsService)
        let ghost = env.device.copy {
          $0.id = UUID()
          $0.radioID = UUID()
          $0.publicKey = Self.canonicalKey
          $0.isActive = false
          $0.connectionMethods = []
        }
        try await env.manager.persistenceStore.saveDevice(ghost)
        await env.bluetooth.setPublicKey(Self.canonicalKey, for: env.device.id)
        importer.importedConfig = MeshCoreNodeConfig(privateKey: Self.privateKey)
        importer.sections = ConfigSections(nodeIdentity: true, channels: false, contacts: false)
        importer.prepareConfirmation()
        try await waitUntil("the real identity import caller presents confirmation") {
          importer.showConfirmation || importer.errorMessage != nil
        }
        #expect(importer.showConfirmation)
        importer.applyConfig()
        try await waitUntil("the actual identity import and canonical graph rebuild settle") {
          !importer.isApplying && importer.importComplete && env.manager.connectionState == .ready
            && env.manager.connectedDevice?.radioID == ghost.radioID
            && env.manager.installedLink?.didCompleteSync == true
        }
        #expect(env.manager.services?.settingsService !== previousSettings)
        #expect(env.manager.session !== recoveredSession)
        let canonicalInitialization = await env.bluetooth.cliCommands.dropFirst(canonicalInitializationStart).map {
          ConnectionTestRadioTransport.splitCLICommand($0).command
        }
        #expect(canonicalInitialization == initialCLICommands)
        await recoveredSession.waitForQuiescence()
        #expect(await recoveredSession.subscriberCountForTest == 0)
      }
      #expect(env.manager.connectionRequestID == oldRequestID)
      #expect(env.manager.session !== oldSession)
      try sessions.append(#require(env.manager.session))
      await oldSession.waitForQuiescence()
      #expect(await oldSession.subscriberCountForTest == 0)
      await env.bluetooth.holdCLIReplies(for: "get wifi.ssid")
      let fresh = Task { await model.noteSessionReadiness() }
      workers.append(fresh)
      try await waitUntil("the replacement session's first SSID request is physically delayed") {
        await env.bluetooth.heldCLIReplyCount(for: "get wifi.ssid") == 1
      }
      let freshSSIDCommand = try #require(await env.bluetooth.cliCommands.last {
        ConnectionTestRadioTransport.splitCLICommand($0).command == "get wifi.ssid"
      })
      let oldPrefix = ConnectionTestRadioTransport.splitCLICommand(oldPasswordCommand).prefix
      let freshPrefix = ConnectionTestRadioTransport.splitCLICommand(freshSSIDCommand).prefix
      #expect(!oldPrefix.isEmpty)
      #expect(!freshPrefix.isEmpty)
      #expect(oldPrefix != freshPrefix)
      await env.bluetooth.releaseCLIReplies(for: "get wifi.pwd")
      await env.bluetooth.releaseCLIReplies(for: "get wifi.ssid")
      await fresh.value
      let correctNetwork = model.networkName == Firmware.network
      let secretAbsentFromNonsecretDisplay = model.networkName?.contains(Firmware.secret) != true
        && !model.rebootMessage.contains(Firmware.secret)
      #expect(correctNetwork)
      #expect(secretAbsentFromNonsecretDisplay)
      model.openEditor()
      await model.loadEditor()
      let correctPassword = model.passwordField == Firmware.secret
      #expect(correctPassword)
      model.ssidField = "after-real-replacement"
      let saveEligibilityMatchesContract1 = model.canSave
      #expect(saveEligibilityMatchesContract1)
      await model.requestSave()
      await model.refresh()
      let networkObservationMatchesContract1 = model.networkName == "after-real-replacement"
      #expect(networkObservationMatchesContract1)
      #expect(try await env.manager.services?.settingsService.getTime() != nil)
    } catch {
      importer.cancelImport()
      await env.bluetooth.releaseAllGates()
      await env.manager.disconnect(reason: .userInitiated)
      for worker in workers {
        await worker.value
      }
      await close(model, env: env, sessions: sessions)
      throw error
    }
    importer.cancelImport()
    for worker in workers {
      await worker.value
    }
    await close(model, env: env, sessions: sessions)
  }

  private func close(
    _ model: RadioWiFiViewModel, env: ConnectionLinkSwitchHarness, sessions: [MeshCoreSession]
  ) async {
    model.configure(
      settingsService: { nil }, connectionState: { .disconnected },
      connectedDevice: { nil }, currentTransportType: { nil }
    )
    await model.noteSessionReadiness()
    await env.close()
    for session in sessions {
      await session.waitForQuiescence()
      #expect(await session.subscriberCountForTest == 0)
    }
    let passwordKnowledgeMatchesContract1 = model.passwordField.isEmpty
    #expect(passwordKnowledgeMatchesContract1)
    #expect(env.manager.session == nil)
  }

  private func waitUntil(
    _ reason: String, _ predicate: @escaping @MainActor () async -> Bool
  ) async throws {
    let deadline = ContinuousClock.now + .seconds(15)
    while await !predicate() {
      guard ContinuousClock.now < deadline else { throw NSError(domain: reason, code: 1) }
      try await Task.sleep(for: .milliseconds(1))
    }
  }

  private final class Firmware: Sendable {
    static let network = "configured-network"
    static let secret = "synthetic-manager-password"
    private let storage: Mutex<RadioWiFiFirmware>

    init() {
      var firmware = RadioWiFiFirmware()
      firmware.savedSSID = Self.network
      firmware.savedPassword = Self.secret
      firmware.boot()
      storage = Mutex(firmware)
    }

    func reply(to command: String) -> String? {
      let effective = ConnectionTestRadioTransport.splitCLICommand(command).command
      if effective == "get tz.offset" { return RadioWiFiFirmware.reflectedReply("> 0", to: command) }
      if effective.hasPrefix("set tz.offset ") { return RadioWiFiFirmware.reflectedReply("OK", to: command) }
      return storage.withLock { $0.reply(to: command) }
    }
  }

  private static let canonicalKey = Data([
    0x1E, 0xC7, 0x71, 0x75, 0xB0, 0x91, 0x8E, 0xD2, 0x06, 0xF9, 0xAE, 0x04, 0xEC, 0x13, 0x6D, 0x6D,
    0x5D, 0x43, 0x15, 0xBB, 0x26, 0x30, 0x54, 0x27, 0xF6, 0x45, 0xB4, 0x92, 0xE9, 0x35, 0x0C, 0x10
  ])
  private static let privateKey = "7065e18fd9fabb70c1ed90dca19907de698c88b709ea146eafd93d9b830c7b60"
    + "c4681193c79bbc39945ba8064104bb618f8fd7a84a0af6f57033d6e8ddcd6471"
}
