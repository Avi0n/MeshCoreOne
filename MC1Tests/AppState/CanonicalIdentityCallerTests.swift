import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Canonical identity import caller")
@MainActor
struct CanonicalIdentityCallerTests {
  @Test(arguments: [false, true])
  func `interrupted identity apply surfaces unknown outcome and retains source`(losesAcknowledgement: Bool) async throws {
    let env = try await ConnectionLinkSwitchHarness.make()
    let model = NodeConfigImportViewModel()
    model.configure(
      nodeConfigService: { env.manager.services?.nodeConfigService },
      settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice },
      connectionManager: env.manager
    )
    do {
      let source = try #require(env.manager.currentConnection)
      let settings = source.services.settingsService
      _ = try await settings.runCliCommand("set wifi.enabled 0")
      let config = MeshCoreNodeConfig(
        privateKey: "7065e18fd9fabb70c1ed90dca19907de698c88b709ea146eafd93d9b830c7b60"
          + "c4681193c79bbc39945ba8064104bb618f8fd7a84a0af6f57033d6e8ddcd6471"
      )
      model.importedConfig = config
      model.sections = ConfigSections(nodeIdentity: true)
      model.prepareConfirmation()
      try await waitUntil { model.showConfirmation || model.errorMessage != nil }
      #expect(model.showConfirmation)
      await env.bluetooth.enqueueResponsePackets(
        for: losesAcknowledgement ? .importPrivateKey : .appStart, packets: []
      )

      model.applyConfig()
      try await waitUntil(timeout: .seconds(35)) { !model.isApplying }

      #expect(!model.importComplete)
      #expect(model.applyProgress == (losesAcknowledgement ? 0 : 1))
      #expect(model.errorMessage == L10n.Settings.ConfigImport.identityOutcomeUnknown(source.device.nodeName))
      let servicesUnavailable = env.manager.services == nil
      #expect(servicesUnavailable)
      #expect(settings.radioWiFiState.rebootPending)
      let saved = try #require(try await env.manager.persistenceStore.fetchDevice(id: source.device.id))
      #expect(saved.id == source.device.id)
      #expect(saved.radioID == source.device.radioID)
      #expect(saved.publicKey == source.device.publicKey)

      env.appState.navigation.clearPerDeviceSelection()
      model.handleDismissal()
      let retainedOriginalInput = model.importedConfig == config
      #expect(retainedOriginalInput)
      #expect(model.errorMessage == L10n.Settings.ConfigImport.identityOutcomeUnknown(source.device.nodeName))
    } catch {
      model.cancelImport()
      await env.bluetooth.releaseAllGates()
      await env.close()
      throw error
    }
    model.cancelImport()
    await env.close()
  }

  @Test
  func `actual import caller rewires canonical partition and wi fi evidence`() async throws {
    let env = try await ConnectionLinkSwitchHarness.make()
    let model = NodeConfigImportViewModel()
    model.configure(
      nodeConfigService: { env.manager.services?.nodeConfigService },
      settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice },
      connectionManager: env.manager
    )
    do {
      let canonicalKey = Data([
        0x1E, 0xC7, 0x71, 0x75, 0xB0, 0x91, 0x8E, 0xD2, 0x06, 0xF9, 0xAE, 0x04, 0xEC, 0x13, 0x6D, 0x6D,
        0x5D, 0x43, 0x15, 0xBB, 0x26, 0x30, 0x54, 0x27, 0xF6, 0x45, 0xB4, 0x92, 0xE9, 0x35, 0x0C, 0x10
      ])
      let ghost = env.device.copy {
        $0.id = UUID()
        $0.radioID = UUID()
        $0.publicKey = canonicalKey
        $0.isActive = false
        $0.connectionMethods = []
      }
      try await env.manager.persistenceStore.saveDevice(ghost)
      let source = try #require(env.manager.currentConnection)
      let settings = source.services.settingsService
      let originalState = settings.radioWiFiState
      let originalContext = settings.radioWiFiContextID
      _ = try await settings.runCliCommand("set wifi.enabled 0")
      await env.bluetooth.setPublicKey(canonicalKey, for: env.device.id)
      let contactKey = Data(repeating: 0xC9, count: ProtocolLimits.publicKeySize)
      model.importedConfig = MeshCoreNodeConfig(
        privateKey: "7065e18fd9fabb70c1ed90dca19907de698c88b709ea146eafd93d9b830c7b60"
          + "c4681193c79bbc39945ba8064104bb618f8fd7a84a0af6f57033d6e8ddcd6471",
        channels: [.init(name: "Canonical", secret: String(repeating: "CD", count: 16))],
        contacts: [.init(type: 1, name: "Imported", publicKey: contactKey.hexString, flags: 0,
                         latitude: "0", longitude: "0", lastAdvert: 0, lastModified: 0)]
      )
      model.sections = ConfigSections(nodeIdentity: true, channels: true, contacts: true)
      model.prepareConfirmation()
      try await waitUntil { model.showConfirmation || model.errorMessage != nil }
      #expect(model.showConfirmation)
      model.applyConfig()
      try await waitUntil { !model.isApplying }
      #expect(model.importComplete)
      #expect(model.errorMessage == nil)
      try await waitUntil {
        env.manager.connectionState == .ready && env.manager.services?.notificationService.getBadgeCount != nil
      }
      #expect(env.manager.connectedDevice?.radioID == ghost.radioID)
      let canonicalSettings = try #require(env.manager.services?.settingsService)
      #expect(canonicalSettings !== settings)
      #expect(settings.radioWiFiState === originalState)
      #expect(settings.radioWiFiContextID == originalContext)
      #expect(settings.radioWiFiState.radioID == source.device.radioID)
      #expect(settings.radioWiFiState.rebootPending)
      #expect(canonicalSettings.radioWiFiState === env.manager.radioWiFiState(for: ghost.radioID))
      #expect(canonicalSettings.radioWiFiContextID != originalContext)
      #expect(canonicalSettings.radioWiFiState.radioID == ghost.radioID)
      #expect(canonicalSettings.radioWiFiState.rebootPending)
      let cliFramesBefore = await env.bluetooth.sentData.filter { $0.first == CommandCode.runCliCommand.rawValue }.count
      await #expect {
        try await settings.runCliCommand("get wifi.enabled")
      } throws: { $0 is CancellationError }
      let cliFramesAfter = await env.bluetooth.sentData.filter { $0.first == CommandCode.runCliCommand.rawValue }.count
      #expect(cliFramesAfter == cliFramesBefore)
      let settingsTime = try await canonicalSettings.getTime()
      #expect(settingsTime.timeIntervalSince1970 > 0)
      let contact = try #require(try await env.manager.persistenceStore.fetchContacts(radioID: ghost.radioID)
        .first { $0.publicKey == contactKey })
      let channels = try await env.manager.persistenceStore.fetchChannels(radioID: ghost.radioID)
      #expect(channels.contains { $0.name == "Canonical" })
      var incoming = Data([ResponseCode.contactMessageReceived.rawValue]) + Data(contactKey.prefix(6))
      incoming.append(contentsOf: [0, TextType.plain.rawValue])
      incoming.append(withUnsafeBytes(of: UInt32(1_700_000_001).littleEndian) { Data($0) })
      incoming.append(Data("Unread canonical message".utf8))
      await env.bluetooth.inject(incoming)
      try await waitUntil {
        await (try? env.manager.persistenceStore.fetchMessages(contactID: contact.id).contains {
          $0.text == "Unread canonical message"
        }) == true
      }
      try await waitUntil {
        await (try? env.manager.persistenceStore.getUnreadCount(contactID: contact.id)) == 1
      }
      let badgeReader = try #require(env.manager.services?.notificationService.getBadgeCount)
      let badge = await badgeReader()
      #expect(badge.contacts == 1)
      let observedTime = try await #require(env.manager.session).getTime()
      #expect(observedTime.timeIntervalSince1970 > 0)
    } catch {
      model.cancelImport()
      await env.bluetooth.releaseAllGates()
      await env.close()
      throw error
    }
    model.cancelImport()
    await env.close()
  }
}
