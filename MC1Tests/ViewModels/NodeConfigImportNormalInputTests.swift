import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Node config normal import input", .serialized)
@MainActor
struct NodeConfigImportNormalInputTests {
  @Test(arguments: InterruptedSettlement.allCases)
  func `first normal route after same device reconnect offers fresh file selection`(
    settlement: InterruptedSettlement
  ) async throws {
    try await withImporter(hostNavigation: true) { env, model in
      model.beginImport()
      try await selectFile(Self.originalConfig, model: model)
      let originalSource = model.inputSource
      let inputWasBoundToLiveSource = originalSource != nil && originalSource == env.manager.currentConnection.map {
        NodeConfigImportViewModel.ImportSource($0)
      }
      try #require(inputWasBoundToLiveSource)
      try await prepareConfirmation(model)
      await env.bluetooth.holdReplies(for: .setCoordinates)
      await env.bluetooth.rejectNext(.setCoordinates)

      model.applyConfig()
      try await waitUntil(timeout: .seconds(35)) { await self.positionWriteCount(env.bluetooth) == 1 }
      let disconnect = env.startDisconnect()
      try await waitUntil(timeout: .seconds(35)) { env.appState.navigation.selectedSetting == nil }
      let detachedWhileApplying = env.manager.services == nil && model.isApplying
      try #require(detachedWhileApplying)

      model.handleDismissal()

      let retainedWhileApplying = model.importedConfig == Self.originalConfig && model.isApplying
      #expect(retainedWhileApplying)
      if settlement == .cancel { model.cancelImport() }
      await env.bluetooth.releaseReplies(for: .setCoordinates)
      await model.importTask?.value
      await disconnect.value
      let failedImportSettled = !model.isApplying && !model.importComplete && model.errorMessage != nil
      try #require(failedImportSettled)
      let retainedAfterFailure = model.importedConfig == Self.originalConfig
      try #require(retainedAfterFailure)

      await env.bluetooth.setConnectError(nil)
      _ = try await env.appState.connectViaBluetooth(peripheralID: env.device.id)
      let replacementSource = env.manager.currentConnection.map { NodeConfigImportViewModel.ImportSource($0) }
      let sameDeviceReconnected = replacementSource?.deviceID == originalSource?.deviceID
        && replacementSource?.radioID == originalSource?.radioID
      let connectionIdentityChanged = replacementSource?.generation != originalSource?.generation
        && replacementSource?.requestID != originalSource?.requestID
      try #require(sameDeviceReconnected)
      try #require(connectionIdentityChanged)
      env.appState.navigation.navigateToSetting(.advanced)

      model.beginImport()

      let fileSelectionAvailable = model.importedConfig == nil && model.recovery == nil && !model.isApplying
      #expect(fileSelectionAvailable, "The first reopened normal route must expose Select File")
      let stalePreviewCleared = model.inputSource == nil && model.sections == ConfigSections() && model.errorMessage == nil
      #expect(stalePreviewCleared)

      try await selectFile(Self.replacementConfig, model: model)
      let deliberateInputUsesFreshSource = model.inputSource == replacementSource
      try #require(deliberateInputUsesFreshSource)
      try await prepareConfirmation(model)
      model.applyConfig()
      try await waitUntil(timeout: .seconds(35)) { !model.isApplying }
      let selectedImportCompleted = model.importComplete && model.didApplyAnyWrite && model.errorMessage == nil
      #expect(selectedImportCompleted)
      let writesAfterSelection = await positionWriteCount(env.bluetooth)
      #expect(writesAfterSelection == 2)
      let replacementWasWritten = await env.bluetooth.sentData.contains(
        PacketBuilder.setCoordinates(latitude: Self.replacementLatitude, longitude: Self.replacementLongitude)
      )
      #expect(replacementWasWritten)
      let identityWrites = await env.bluetooth.sentData.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
      #expect(identityWrites == 0)
      let laterTime = try await env.manager.session?.getTime()
      let laterIOCompleted = (laterTime?.timeIntervalSince1970 ?? 0) > 0
      #expect(laterIOCompleted)
      await model.importTask?.value
      let successfulImportReleasedInput = model.importedConfig == nil
      #expect(successfulImportReleasedInput)
    }
  }

  @Test
  func `unchanged live source retains failed input and can retry`() async throws {
    try await withImporter(hostNavigation: false) { env, model in
      model.beginImport()
      try await selectFile(Self.originalConfig, model: model)
      let inputID = model.inputID
      let source = model.inputSource
      try await prepareConfirmation(model)
      await env.bluetooth.rejectNext(.setCoordinates)
      model.applyConfig()
      await model.importTask?.value
      let failedImportSettled = !model.isApplying && !model.importComplete && model.errorMessage != nil
      try #require(failedImportSettled)

      model.beginImport()

      let failedInputRetained = model.importedConfig == Self.originalConfig
        && model.inputID == inputID && model.inputSource == source
      #expect(failedInputRetained)
      try await prepareConfirmation(model)
      model.applyConfig()
      try await waitUntil(timeout: .seconds(35)) { !model.isApplying }
      let retryCompleted = model.importComplete && model.didApplyAnyWrite && model.errorMessage == nil
      #expect(retryCompleted)
      let writesAfterRetry = await positionWriteCount(env.bluetooth)
      #expect(writesAfterRetry == 2)
      let laterTime = try await env.manager.session?.getTime()
      let laterIOCompleted = (laterTime?.timeIntervalSince1970 ?? 0) > 0
      #expect(laterIOCompleted)
      await model.importTask?.value
    }
  }

  @Test
  func `idle normal dismissal releases parsed input and confirmation`() async throws {
    try await withImporter(hostNavigation: false) { env, model in
      model.beginImport()
      try await selectFile(Self.originalConfig, model: model)
      try await prepareConfirmation(model)

      model.handleDismissal()

      let inputReleased = model.importedConfig == nil && model.inputSource == nil && model.recovery == nil
      let previewReleased = model.sections == ConfigSections() && !model.showConfirmation && model.confirmation == nil
      #expect(inputReleased)
      #expect(previewReleased)
      let writesAfterDismissal = await positionWriteCount(env.bluetooth)
      #expect(writesAfterDismissal == 0)
    }
  }

  private func withImporter(
    hostNavigation: Bool,
    body: (ConnectionLinkSwitchHarness, NodeConfigImportViewModel) async throws -> Void
  ) async throws {
    let env = try await ConnectionLinkSwitchHarness.make()
    let model = NodeConfigImportViewModel()
    model.configure(
      nodeConfigService: { env.manager.services?.nodeConfigService },
      settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice }, connectionManager: env.manager
    )
    var host: SettingsTabHarness?
    var bodyError: (any Error)?
    do {
      if hostNavigation {
        let tabHost = try SettingsTabHarness.make(connected: false, injectedAppState: env.appState)
        host = tabHost
        let settingsVisible = try await tabHost.showSettingsList()
        try #require(settingsVisible)
        env.appState.navigation.navigateToSetting(.advanced)
        let advancedSelected = try await tabHost.waitUntil { env.appState.navigation.selectedSetting == .advanced }
        try #require(advancedSelected)
      }
      try await body(env, model)
    } catch {
      bodyError = error
    }
    model.cancelImport()
    model.parseTask?.cancel()
    model.previewTask?.cancel()
    await env.bluetooth.releaseAllGates()
    await env.wifi.releaseAllGates()
    await model.importTask?.value
    await model.parseTask?.value
    await model.previewTask?.value
    await env.close()
    host?.tearDown()
    if let bodyError { throw bodyError }
  }

  private func selectFile(_ config: MeshCoreNodeConfig, model: NodeConfigImportViewModel) async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let url = directory.appendingPathComponent("config.json")
    try JSONEncoder().encode(config).write(to: url)
    model.parseFile(at: url)
    await model.parseTask?.value
    let parsedSelectedInput = model.importedConfig == config && !model.isParsing
    try #require(parsedSelectedInput)
  }

  private func prepareConfirmation(_ model: NodeConfigImportViewModel) async throws {
    model.prepareConfirmation()
    await model.previewTask?.value
    let confirmationReady = model.showConfirmation && !model.isPreparingConfirmation && model.errorMessage == nil
    try #require(confirmationReady)
  }

  private func positionWriteCount(_ transport: ConnectionTestRadioTransport) async -> Int {
    await transport.sentData.filter { $0.first == CommandCode.setCoordinates.rawValue }.count
  }

  enum InterruptedSettlement: CaseIterable, Sendable { case failure, cancel }

  private static let originalConfig = MeshCoreNodeConfig(
    positionSettings: .init(latitude: "37.5", longitude: "-122.5")
  )
  private static let replacementLatitude = 38.5
  private static let replacementLongitude = -121.5
  private static let replacementConfig = MeshCoreNodeConfig(
    positionSettings: .init(latitude: String(replacementLatitude), longitude: String(replacementLongitude))
  )
}
