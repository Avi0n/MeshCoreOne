import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import SwiftUI
import Testing

@Suite("Node config explicit identity recovery")
@MainActor
struct NodeConfigImportRecoveryTests {
  @Test
  func `retry is identity only and releases input when its result is dismissed`() async throws {
    try await withInterruptedImport { env, model, recovery in
      let servicesUnavailable = env.manager.services == nil
      #expect(servicesUnavailable)
      env.appState.navigation.clearPerDeviceSelection()
      #expect(env.appState.navigation.selectedSetting == nil)
      #expect(model.beginRecovery(recovery))
      let retained = model.importedConfig != nil
      #expect(retained)
      model.prepareConfirmation()
      #expect(model.showConfirmation)
      #expect(model.confirmTitle == L10n.Settings.ConfigImport.confirmTitleIdentityRecovery)
      #expect(model.confirmButtonRole == .destructive)
      #expect(!model.recoverySheetDismissalDisabled)
      #expect(model.confirmMessage.contains(recovery.targetDescription))
      let writesBefore = await identityWriteCount(env.bluetooth)
      await env.bluetooth.setConnectError(nil)
      await env.bluetooth.setPublicKey(Self.recoveredPublicKey, for: env.device.id)

      model.applyConfig()
      #expect(model.recoverySheetDismissalDisabled)
      try await waitUntil(timeout: .seconds(35)) { !model.isApplying }

      #expect(!model.recoverySheetDismissalDisabled)
      #expect(model.identityRecoveryComplete)
      #expect(!model.importComplete)
      #expect(model.errorMessage == nil)
      #expect(env.manager.identityImportRecoveries.isEmpty)
      let writesAfterRetry = await identityWriteCount(env.bluetooth)
      #expect(writesAfterRetry == writesBefore + 1)
      let device = try #require(env.manager.connectedDevice)
      #expect(device.radioID == env.device.radioID)
      #expect(device.publicKey == Self.recoveredPublicKey)
      let channels = try await env.manager.persistenceStore.fetchChannels(radioID: device.radioID)
      #expect(!channels.contains { $0.name == Self.tailChannelName })
      let frames = await env.bluetooth.sentData
      let tailWasNotWritten = !frames.contains { $0.first == CommandCode.setChannel.rawValue }
      #expect(tailWasNotWritten)
      #expect(try await #require(env.manager.session).getTime().timeIntervalSince1970 > 0)

      model.handleDismissal()
      let released = model.importedConfig == nil
      #expect(released)
      #expect(model.recovery == nil)

      model.beginImport()
      model.importedConfig = MeshCoreNodeConfig(
        channels: [.init(name: Self.tailChannelName, secret: String(repeating: "CD", count: 16))]
      )
      model.sections = ConfigSections(channels: true)
      model.prepareConfirmation()
      try await waitUntil(timeout: .seconds(35)) { model.showConfirmation || model.errorMessage != nil }
      #expect(model.showConfirmation)
      #expect(model.confirmButtonRole == nil)
      #expect(!model.recoverySheetDismissalDisabled)
      model.applyConfig()
      try await waitUntil(timeout: .seconds(35)) { !model.isApplying }
      #expect(model.importComplete)
      #expect(try await env.manager.persistenceStore.fetchChannels(radioID: device.radioID)
        .contains { $0.name == Self.tailChannelName })
    }
  }

  @Test(arguments: StaleAction.allCases)
  func `stale or cancelled confirmation cannot start recovery`(action: StaleAction) async throws {
    try await withInterruptedImport { env, model, recovery in
      #expect(model.beginRecovery(recovery))
      model.prepareConfirmation()
      #expect(model.showConfirmation)
      let writesBefore = await identityWriteCount(env.bluetooth)
      switch action {
      case .cancel:
        model.cancelConfirmation()
      case .dismiss:
        model.handleDismissal()
      case .replaceInput:
        try await selectFile(MeshCoreNodeConfig(privateKey: Self.replacementPrivateKey), model: model)
      case .newRequest:
        _ = try await connectOtherRadio(env)
      }

      model.applyConfig()
      await Task.yield()

      #expect(!model.isApplying)
      #expect(!model.identityRecoveryComplete)
      let writesAfterAction = await identityWriteCount(env.bluetooth)
      #expect(writesAfterAction == writesBefore)
      #expect(env.manager.identityImportRecoveries.contains { $0.receiptID == recovery.receiptID })
      if action == .newRequest {
        #expect(env.manager.currentTransportType == .wifi)
        #expect(try await #require(env.manager.session).getTime().timeIntervalSince1970 > 0)
      }
    }
  }

  @Test
  func `a new settings importer requests file and can confirm deliberately selected input`() async throws {
    try await withInterruptedImport { env, _, recovery in
      let reopened = NodeConfigImportViewModel()
      configure(reopened, environment: env)
      #expect(reopened.beginRecovery(recovery))
      reopened.prepareConfirmation()
      #expect(reopened.showFilePicker)
      #expect(!reopened.showConfirmation)
      #expect(reopened.errorMessage == L10n.Settings.ConfigImport.identityRecoveryKeyRequired)
      reopened.showFilePicker = false
      try await selectFile(MeshCoreNodeConfig(privateKey: Self.replacementPrivateKey), model: reopened)
      reopened.prepareConfirmation()
      #expect(reopened.showConfirmation)
      #expect(reopened.confirmTitle == L10n.Settings.ConfigImport.confirmTitleIdentityRecovery)
      #expect(reopened.confirmButtonRole == .destructive)
      #expect(reopened.confirmMessage == L10n.Settings.ConfigImport.confirmIdentityRecovery(
        "\(recovery.sourceName) (\(recovery.targetDescription))"
      ))
      reopened.cancelConfirmation()
      reopened.handleDismissal()
      let retainedForReceipt = reopened.importedConfig != nil
      #expect(retainedForReceipt)
      #expect(reopened.beginRecovery(recovery))
      reopened.prepareConfirmation()
      #expect(reopened.showConfirmation)
    }
  }

  @Test
  func `input retained for another unresolved radio requires deliberate selection`() async throws {
    try await withInterruptedImport { env, model, recoveryA in
      _ = try await connectOtherRadio(env)
      model.beginImport()
      let configB = MeshCoreNodeConfig(privateKey: Self.replacementPrivateKey)
      try await selectFile(configB, model: model)
      model.prepareConfirmation()
      try await waitUntil(timeout: .seconds(35)) { model.showConfirmation || model.errorMessage != nil }
      #expect(model.showConfirmation)
      await env.wifi.enqueueResponsePackets(for: .importPrivateKey, packets: [])
      model.applyConfig()
      try await waitUntil(timeout: .seconds(35)) { !model.isApplying }
      let recoveryB = try #require(model.recovery)
      #expect(recoveryB.receiptID != recoveryA.receiptID)
      let retainedB = model.importedConfig == configB
      #expect(retainedB)

      #expect(model.beginRecovery(recoveryA))
      let refusedAutomaticReuse = model.importedConfig == nil
      #expect(refusedAutomaticReuse)
      model.prepareConfirmation()
      #expect(!model.showConfirmation)
      #expect(model.showFilePicker)
      model.showFilePicker = false

      try await selectFile(configB, model: model)
      model.prepareConfirmation()
      #expect(model.showConfirmation)
      #expect(model.inputReceiptID == recoveryA.receiptID)
      #expect(env.manager.identityImportRecoveries.count == 2)
      model.cancelConfirmation()
    }
  }

  @Test
  func `recovery required errors use localized copy through ordinary connection wrappers`() async throws {
    try await withInterruptedImport { _, _, recovery in
      let interrupted = IdentityImportRecoveryRequired(recovery: recovery)
      let direct: any Error = interrupted
      let wrapped: any Error = ConnectionRequestFailure(requestID: UUID(), underlying: interrupted)
      let expected = L10n.Settings.ConfigImport.identityOutcomeUnknown(recovery.sourceName)
      #expect(direct.userFacingMessage == expected)
      #expect(wrapped.userFacingMessage == expected)
    }
  }

  @Test
  func `ordinary app state connect surfaces current unknown outcome and rejects it after another choice`() async throws {
    try await withInterruptedImport { env, _, recovery in
      await env.bluetooth.setConnectError(nil)
      await env.bluetooth.setPublicKey(Self.recoveredPublicKey, for: env.device.id)
      var connectionFailure: (any Error)?
      do {
        _ = try await env.appState.connectViaBluetooth(peripheralID: env.device.id)
        Issue.record("An ordinary connection must require explicit identity recovery")
      } catch {
        connectionFailure = error
      }
      let captured = try #require(connectionFailure)
      let current = try #require(env.appState.currentConnectionFailure(captured))
      #expect(current is IdentityImportRecoveryRequired)
      #expect(current.userFacingMessage == L10n.Settings.ConfigImport.identityOutcomeUnknown(recovery.sourceName))
      let servicesUnavailable = env.manager.services == nil
      #expect(servicesUnavailable)

      _ = try await connectOtherRadio(env)

      #expect(env.appState.currentConnectionFailure(captured) == nil)
      #expect(env.manager.currentTransportType == .wifi)
      #expect(try await #require(env.manager.session).getTime().timeIntervalSince1970 > 0)
    }
  }

  private func withInterruptedImport(
    _ body: (ConnectionLinkSwitchHarness, NodeConfigImportViewModel, IdentityImportRecovery) async throws -> Void
  ) async throws {
    let env = try await ConnectionLinkSwitchHarness.make()
    let model = NodeConfigImportViewModel()
    configure(model, environment: env)
    do {
      model.importedConfig = MeshCoreNodeConfig(
        privateKey: Self.privateKey,
        channels: [.init(name: Self.tailChannelName, secret: String(repeating: "CD", count: 16))]
      )
      model.sections = ConfigSections(nodeIdentity: true, channels: true, contacts: false)
      model.prepareConfirmation()
      try await waitUntil(timeout: .seconds(35)) { model.showConfirmation || model.errorMessage != nil }
      #expect(model.showConfirmation)
      await env.bluetooth.enqueueResponsePackets(for: .importPrivateKey, packets: [])
      model.applyConfig()
      try await waitUntil(timeout: .seconds(35)) { !model.isApplying }
      let recovery = try #require(env.manager.identityImportRecoveries.first)
      #expect(model.errorMessage == L10n.Settings.ConfigImport.identityOutcomeUnknown(recovery.sourceName))
      try await body(env, model, recovery)
    } catch {
      model.cancelImport()
      await env.bluetooth.releaseAllGates()
      await env.wifi.releaseAllGates()
      await model.importTask?.value
      await env.close()
      throw error
    }
    model.cancelImport()
    await model.importTask?.value
    await env.close()
  }

  private func configure(_ model: NodeConfigImportViewModel, environment env: ConnectionLinkSwitchHarness) {
    model.configure(
      nodeConfigService: { env.manager.services?.nodeConfigService },
      settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice }, connectionManager: env.manager
    )
  }

  private func connectOtherRadio(_ env: ConnectionLinkSwitchHarness) async throws -> DeviceDTO {
    let other = env.device.copy {
      $0.id = UUID()
      $0.radioID = UUID()
      $0.publicKey = Data(repeating: 0xB2, count: ProtocolLimits.publicKeySize)
      $0.nodeName = "Other radio"
      $0.connectionMethods = [.wifi(host: "other.local", port: 4242, displayName: nil)]
    }
    try await env.manager.persistenceStore.saveDevice(other)
    await env.wifi.setDeviceID(other.id)
    await env.wifi.setPublicKey(other.publicKey, for: other.id)
    await env.wifi.setConnectError(nil)
    _ = try await env.manager.connectViaWiFi(host: "other.local", port: 4242)
    #expect(env.manager.connectedDevice?.radioID == other.radioID)
    return other
  }

  private func selectFile(_ config: MeshCoreNodeConfig, model: NodeConfigImportViewModel) async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let url = directory.appendingPathComponent("config.json")
    try JSONEncoder().encode(config).write(to: url)
    model.parseFile(at: url)
    try await waitUntil(timeout: .seconds(35)) { !model.isParsing }
    let parsed = model.importedConfig == config
    #expect(parsed)
  }

  private func identityWriteCount(_ transport: ConnectionTestRadioTransport) async -> Int {
    await transport.sentData.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
  }

  enum StaleAction: CaseIterable, Sendable { case cancel, dismiss, replaceInput, newRequest }

  private static let tailChannelName = "Must not import"
  private static let privateKey = "7065e18fd9fabb70c1ed90dca19907de698c88b709ea146eafd93d9b830c7b60"
    + "c4681193c79bbc39945ba8064104bb618f8fd7a84a0af6f57033d6e8ddcd6471"
  private static let replacementPrivateKey = String(repeating: "AB", count: ProtocolLimits.privateKeySize)
  private static let recoveredPublicKey = Data(repeating: 0xD5, count: ProtocolLimits.publicKeySize)
}
