import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

extension SettingsIdentityMetadataProvenanceTests {
  @Test(
    arguments: [TransportType.bluetooth, .wifi],
    [LateTail.noGhostCancelled, .ghostCancelled, .noGhostFailed, .ghostFailed]
  )
  func `completed telemetry cannot enter canonical settings subscription`(
    transport: TransportType, tail: LateTail
  ) async throws {
    try await exerciseLateSettingsContinuation(transport: transport, tail: tail, operation: .telemetry)
  }

  @Test(
    arguments: [TransportType.bluetooth, .wifi],
    [LateTail.noGhostCancelled, .ghostCancelled]
  )
  func `completed preset cannot issue next command through canonical runtime`(
    transport: TransportType, tail: LateTail
  ) async throws {
    try await exerciseLateSettingsContinuation(transport: transport, tail: tail, operation: .preset)
  }

  private func exerciseLateSettingsContinuation(
    transport: TransportType, tail: LateTail, operation: LateOperation
  ) async throws {
    let env = try await ConnectionLinkSwitchHarness.make(live: transport, wifiHost: "source.local")
    let radio = transport == .bluetooth ? env.bluetooth : env.wifi
    let model = NodeConfigImportViewModel()
    model.configure(
      nodeConfigService: { env.manager.services?.nodeConfigService },
      settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice }, connectionManager: env.manager
    )
    let admission = Gate()
    let commit = Gate()
    let oldResult = Gate()
    var oldSettings: SettingsService?
    var oldOperation: Task<Void, Error>?
    var bodyError: (any Error)?
    do {
      await env.bluetooth.setConnectError(nil)
      await env.wifi.setConnectError(nil)
      await radio.setIdentityImportPublicKey(Self.importedPublicKey)
      await env.appState.wireServicesIfConnected()
      let source = try #require(env.manager.currentConnection)
      let sourceIsOperational = env.manager.connectionState == .ready && source.services.isAcceptingRuntimeWork
      try #require(sourceIsOperational)
      let settings = source.services.settingsService
      oldSettings = settings
      let ghost = source.device.copy {
        $0.id = UUID()
        $0.radioID = UUID()
        $0.publicKey = Self.importedPublicKey
        $0.isActive = false
        $0.connectionMethods = []
      }
      if tail.hasGhost { try await env.manager.persistenceStore.saveDevice(ghost) }
      let expectedRadioID = tail.hasGhost ? ghost.radioID : source.device.radioID
      model.beginImport()
      try await selectFile(Self.config, model: model)
      model.prepareConfirmation()
      await model.previewTask?.value
      let confirmationIsReady = model.showConfirmation && model.confirmation != nil && model.errorMessage == nil
      try #require(confirmationIsReady)
      env.manager.testIdentityBeforeAdmission = { await admission.wait() }
      env.manager.testIdentityBeforeCommit = { await commit.wait() }
      model.applyConfig()
      try await waitUntil("normal import must reach identity admission") { admission.arrived }

      await radio.enqueueResponsePackets(
        for: .appStart, packets: [Self.telemetryPacket(publicKey: source.device.publicKey)]
      )
      if operation == .telemetry {
        await radio.enqueueResponsePackets(
          for: .appStart, packets: [Self.telemetryPacket(publicKey: source.device.publicKey)]
        )
      }
      let framesBeforeOrdinaryOperation = await radio.sentData.count
      await settings.setTestSelfInfoResultHandler { await oldResult.wait() }
      oldOperation = Task {
        switch operation {
        case .telemetry:
          let info = try await settings.setOtherParamsVerified(autoAddContacts: !source.device.manualAddContacts, telemetryModes: Self.telemetryModes,
                                                               advertLocationPolicy: source.device.advertLocationPolicyMode, multiAcks: source.device.multiAcks)
          let returnedResultBelongsToSource = info.publicKey == source.device.publicKey
          #expect(returnedResultBelongsToSource)
          #expect(info.telemetryModeBase == Self.telemetryMarker)
        case .preset:
          _ = try await settings.applyRadioPresetVerified(Self.continuationPreset)
        }
      }
      try await waitUntil("the ordinary Settings caller must hold its real completed SelfInfo result") { oldResult.arrived }
      await settings.setTestSelfInfoResultHandler(nil)
      await source.services.session.waitForQuiescence()
      let ordinaryFrames = await radio.sentData.dropFirst(framesBeforeOrdinaryOperation)
      let ordinaryReadWasSubmitted = ordinaryFrames.contains { $0.first == CommandCode.appStart.rawValue }
      let ordinaryWriteWasSubmitted = ordinaryFrames.contains {
        $0.first == (operation == .telemetry ? CommandCode.setOtherParams.rawValue : CommandCode.setRadio.rawValue)
      }
      try #require(ordinaryReadWasSubmitted)
      try #require(ordinaryWriteWasSubmitted)
      let oldResultPrecedesAdmission = env.manager.isCurrent(source) && env.manager.connectionState == .ready
      try #require(oldResultPrecedesAdmission)
      let keyWritesBeforeAdmission = await radio.sentData.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
      try #require(keyWritesBeforeAdmission == 0)
      await radio.holdReplies(for: .setOtherParams)
      if !tail.isCancelled { await radio.rejectNext(.setOtherParams) }
      // Keep the authenticated radio state distinct from the held source result.
      await radio.setTelemetryModeBase(Self.authenticatedTelemetryBase)
      admission.open()
      try await waitUntil("the actual key OK and authenticated SelfInfo must reach canonical commit") { commit.arrived }
      let oneKeyWrite = await radio.sentData.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count == 1
      try #require(oneKeyWrite)
      commit.open()
      try await waitUntil("the actual importer must submit the private selected tail") {
        await radio.heldReplyCount(for: .setOtherParams) == 1
      }
      await env.manager.metadataCommitTask?.value
      let canonicalBeforeTail = try await env.manager.persistenceStore.fetchDevice(id: source.device.id)
      let canonicalSavePrecedesRelease = canonicalBeforeTail?.publicKey == Self.importedPublicKey
        && canonicalBeforeTail?.radioID == expectedRadioID
      let selectedTailIsPrivate = env.manager.currentConnection == nil && env.appState.services == nil
        && env.manager.connectionState == .connected && model.isApplying && oldResult.arrived
      try #require(canonicalSavePrecedesRelease)
      try #require(selectedTailIsPrivate)
      if tail.isCancelled {
        model.cancelImport()
        try #require(model.importTask?.isCancelled == true)
      }
      await radio.releaseReplies(for: .setOtherParams)
      await model.importTask?.value
      let expectedTailOutcome = !model.isApplying && !model.importComplete && model.errorMessage != nil
        && (tail.isCancelled ? model.errorMessage == L10n.Settings.ConfigImport.cancelledPartial
          : model.errorMessage != L10n.Settings.ConfigImport.cancelledPartial)
      try #require(expectedTailOutcome)
      try await waitUntil("accepted canonical runtime must publish after the failed or cancelled tail") {
        env.manager.connectionState == .ready && env.manager.currentConnection != nil
      }
      await env.appState.wireServicesIfConnected()
      let canonical = try #require(env.manager.currentConnection)
      #expect(canonical.services.settingsService !== settings)
      let replacementIsAuthenticated = canonical.generation != source.generation
        && canonical.device.publicKey == Self.importedPublicKey && canonical.device.radioID == expectedRadioID
      try #require(replacementIsAuthenticated)
      try #require(canonical.device.autoAddConfig != Self.streamFenceMarker)
      let framesBeforeOldContinuation = await radio.sentData.count
      oldResult.open()
      let worker = try #require(oldOperation)
      let oldOutcome = await worker.result
      switch (operation, oldOutcome) {
      case (.telemetry, .success): break
      case (.preset, .failure): break
      default: Issue.record("The old Settings operation did not preserve its source-bound outcome")
      }
      let oldContinuationFrames = await radio.sentData.dropFirst(framesBeforeOldContinuation)
      let noOldNextCommand = oldContinuationFrames.allSatisfy {
        $0.first != CommandCode.deviceQuery.rawValue && $0.first != CommandCode.setPathHashMode.rawValue
      }
      #expect(noOldNextCommand)

      // This real later event fences the same stream without refreshing SelfInfo.
      await radio.setResponsePackets(
        for: .getAutoAddConfig, packets: [Data([ResponseCode.autoAddConfig.rawValue, Self.streamFenceMarker])]
      )
      try await canonical.services.settingsService.refreshAutoAddConfig()
      try await waitUntil("the real canonical Settings subscriber must consume the later FIFO event") {
        env.manager.connectedDevice?.autoAddConfig == Self.streamFenceMarker
      }
      await env.manager.metadataCommitTask?.value
      let saved = try await env.manager.persistenceStore.fetchDevice(id: source.device.id)
      let storedKeyIsAuthenticated = saved?.publicKey == Self.importedPublicKey
      let publishedKeyIsAuthenticated = env.manager.connectedDevice?.publicKey == Self.importedPublicKey
      let canonicalPartitionSurvives = saved?.radioID == expectedRadioID
        && env.manager.connectedDevice?.radioID == expectedRadioID
      let oldTelemetryDidNotEnterNewSubscriber = saved?.telemetryModeBase == Self.authenticatedTelemetryBase
        && env.manager.connectedDevice?.telemetryModeBase == Self.authenticatedTelemetryBase
      let sourceHandleAndMethodsSurvive = saved?.id == source.device.id
        && saved?.connectionMethods == source.device.connectionMethods
      #expect(storedKeyIsAuthenticated)
      #expect(publishedKeyIsAuthenticated)
      #expect(canonicalPartitionSurvives)
      #expect(oldTelemetryDidNotEnterNewSubscriber)
      #expect(sourceHandleAndMethodsSurvive)
      #expect(env.manager.identityImportRecoveries.isEmpty)
      if tail.hasGhost {
        let ghostWasConsumed = try await env.manager.persistenceStore.fetchDevice(id: ghost.id) == nil
        #expect(ghostWasConsumed)
      }
      if operation == .preset {
        _ = try await canonical.services.settingsService.applyRadioPresetVerified(Self.continuationPreset)
      }
      let laterTime = try await canonical.services.settingsService.getTime()
      let laterInfo = try await canonical.services.settingsService.getSelfInfo()
      let laterWireKeyIsAuthenticated = laterInfo.publicKey == Self.importedPublicKey
      #expect(laterTime.timeIntervalSince1970 > 0)
      #expect(laterWireKeyIsAuthenticated)
      let frames = await radio.sentData
      let keyCount = frames.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
      let selectedTailWasSubmitted = frames.contains(Self.selectedTailPacket)
      #expect(keyCount == 1)
      #expect(selectedTailWasSubmitted)
    } catch {
      bodyError = error
    }
    admission.open()
    commit.open()
    oldResult.open()
    await oldSettings?.setTestSelfInfoResultHandler(nil)
    env.manager.testIdentityBeforeAdmission = nil
    env.manager.testIdentityBeforeCommit = nil
    model.cancelImport()
    model.parseTask?.cancel()
    model.previewTask?.cancel()
    oldOperation?.cancel()
    await env.bluetooth.releaseAllGates()
    await env.wifi.releaseAllGates()
    _ = await oldOperation?.result
    await model.importTask?.value
    await model.parseTask?.value
    await model.previewTask?.value
    await env.manager.metadataCommitTask?.value
    await env.close()
    await env.manager.retirementTask?.value
    if let bodyError { throw bodyError }
  }

  enum LateTail: Sendable {
    case noGhostCancelled, ghostCancelled, noGhostFailed, ghostFailed

    var hasGhost: Bool {
      self == .ghostCancelled || self == .ghostFailed
    }

    var isCancelled: Bool {
      self == .noGhostCancelled || self == .ghostCancelled
    }
  }

  private enum LateOperation: Sendable { case telemetry, preset }
}
