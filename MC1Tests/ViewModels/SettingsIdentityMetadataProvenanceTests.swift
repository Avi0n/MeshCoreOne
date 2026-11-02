import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Settings identity metadata provenance", .serialized)
@MainActor
struct SettingsIdentityMetadataProvenanceTests {
  @Test(
    arguments: [TransportType.bluetooth, .wifi],
    [ImportCase.noGhostCancelled, .ghostCancelled, .noGhostSuccessful, .ghostSuccessful]
  )
  func `verified settings observation cannot revert canonical import`(
    transport: TransportType, outcome: ImportCase
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
    let observation = Gate()
    var setter: Task<Void, Error>?
    var bodyError: (any Error)?
    do {
      await env.bluetooth.setConnectError(nil)
      await env.wifi.setConnectError(nil)
      await radio.setIdentityImportPublicKey(Self.importedPublicKey)
      await env.appState.wireServicesIfConnected()
      guard let source = env.manager.currentConnection else {
        throw NSError(domain: "SettingsIdentityMetadataProvenance", code: 3)
      }
      let operationalSource = env.manager.connectionState == .ready && source.services.isAcceptingRuntimeWork
      try #require(operationalSource)
      let originalID = source.device.id
      let originalRadioID = source.device.radioID
      let originalMethods = source.device.connectionMethods
      let ghost = source.device.copy {
        $0.id = UUID()
        $0.radioID = UUID()
        $0.publicKey = Self.importedPublicKey
        $0.isActive = false
        $0.connectionMethods = []
      }
      if outcome.hasGhost { try await env.manager.persistenceStore.saveDevice(ghost) }
      let expectedRadioID = outcome.hasGhost ? ghost.radioID : originalRadioID
      model.beginImport()
      try await selectFile(Self.config, model: model)
      model.prepareConfirmation()
      await model.previewTask?.value
      let confirmationReady = model.showConfirmation && model.confirmation != nil && model.errorMessage == nil
      try #require(confirmationReady)

      env.manager.testIdentityBeforeAdmission = { await admission.wait() }
      env.manager.testIdentityBeforeCommit = { await commit.wait() }
      model.applyConfig()
      try await waitUntil("normal import must finish its plan and reach identity admission") { admission.arrived }
      await radio.holdReplies(for: .setOtherParams)
      await radio.enqueueResponsePackets(
        for: .appStart, packets: [Self.telemetryPacket(publicKey: source.device.publicKey)]
      )
      await radio.enqueueResponsePackets(
        for: .appStart, packets: [Self.telemetryPacket(publicKey: source.device.publicKey)]
      )
      await source.services.settingsService.setTestSelfInfoResultHandler { await observation.wait() }
      setter = Task {
        _ = try await source.services.settingsService.setOtherParamsVerified(
          autoAddContacts: !source.device.manualAddContacts, telemetryModes: Self.telemetryModes,
          advertLocationPolicy: source.device.advertLocationPolicyMode, multiAcks: source.device.multiAcks
        )
      }
      try await waitUntil("the real telemetry setter must submit its preference frame") {
        await radio.heldReplyCount(for: .setOtherParams) == 1
      }
      await radio.releaseReplies(for: .setOtherParams)
      try await waitUntil("the verified Settings result must complete before admission closes") { observation.arrived }
      await source.services.settingsService.setTestSelfInfoResultHandler(nil)
      let originalSourceStillInstalled = env.manager.isCurrent(source) && env.manager.connectionState == .ready
      try #require(originalSourceStillInstalled)
      // Keep the authenticated radio state distinct from the held source result.
      await radio.setTelemetryModeBase(Self.authenticatedTelemetryBase)
      admission.open()
      try await waitUntil("identity metadata must own the chain before the verified Settings event publishes") {
        env.manager.connectionState == .connected
      }
      observation.open()
      try await waitUntil("the actual import must acknowledge its key and verify new SelfInfo") { commit.arrived }
      try await waitUntil("the AppState Settings consumer must accept the old telemetry observation") {
        env.manager.isCurrent(source) && env.manager.connectedDevice?.telemetryModeBase == Self.telemetryMarker
      }
      try await setter?.value
      let keyCountAtCommit = await radio.sentData.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
      try #require(keyCountAtCommit == 1)
      let sourceStillHasOriginalIdentity = env.manager.connectedDevice?.publicKey == source.device.publicKey
        && env.manager.connectedDevice?.radioID == originalRadioID
      try #require(sourceStillHasOriginalIdentity)

      await radio.holdReplies(for: .setOtherParams)
      commit.open()
      try await waitUntil("the normal importer must submit its selected post-identity tail") {
        await radio.heldReplyCount(for: .setOtherParams) == 1
      }
      await env.manager.metadataCommitTask?.value
      let storedBeforeTail = try await env.manager.persistenceStore.fetchDevice(id: originalID)
      let authenticatedKeySurvivesBeforeRefresh = storedBeforeTail?.publicKey == Self.importedPublicKey
      let canonicalPartitionSurvivesBeforeRefresh = storedBeforeTail?.radioID == expectedRadioID
      let staleTelemetryDoesNotCrossIdentity = storedBeforeTail?.telemetryModeBase == Self.authenticatedTelemetryBase
      let privateTailIsUnpublished = env.manager.currentConnection == nil && env.appState.services == nil
      #expect(authenticatedKeySurvivesBeforeRefresh)
      #expect(canonicalPartitionSurvivesBeforeRefresh)
      #expect(staleTelemetryDoesNotCrossIdentity)
      #expect(privateTailIsUnpublished)
      if outcome.isCancelled {
        model.cancelImport()
        let cancellationRequested = model.importTask?.isCancelled == true
        #expect(cancellationRequested)
      }
      await radio.releaseReplies(for: .setOtherParams)
      try await waitUntil("the normal import outcome must settle after its selected exchange") { !model.isApplying }
      let expectedOutcome = outcome.isCancelled
        ? !model.importComplete && model.errorMessage == L10n.Settings.ConfigImport.cancelledPartial
        : model.importComplete && model.errorMessage == nil
      #expect(expectedOutcome)
      try await waitUntil("canonical runtime must activate after the selected tail settles") {
        env.manager.connectionState == .ready && env.manager.currentConnection != nil
      }
      if !outcome.isCancelled {
        try await waitUntil("the successful import's separate refresh must reach AppState") {
          env.manager.connectedDevice?.publicKey == Self.importedPublicKey
        }
      }
      await env.manager.metadataCommitTask?.value
      let saved = try await env.manager.persistenceStore.fetchDevice(id: originalID)
      let storedIdentityIsAuthenticated = saved?.publicKey == Self.importedPublicKey && saved?.radioID == expectedRadioID
      let publishedIdentityIsAuthenticated = env.manager.connectedDevice?.publicKey == Self.importedPublicKey
        && env.manager.connectedDevice?.radioID == expectedRadioID
      let originalHandleAndMethodsSurvive = saved?.id == originalID && saved?.connectionMethods == originalMethods
      let noUnknownOutcomeReceipt = env.manager.identityImportRecoveries.isEmpty
      #expect(storedIdentityIsAuthenticated)
      #expect(publishedIdentityIsAuthenticated)
      #expect(originalHandleAndMethodsSurvive)
      #expect(noUnknownOutcomeReceipt)
      if outcome.hasGhost {
        let consumedGhostIsGone = try await env.manager.persistenceStore.fetchDevice(id: ghost.id) == nil
        #expect(consumedGhostIsGone)
      }
      let frames = await radio.sentData
      let keyWriteCount = frames.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
      let selectedTailWasSubmitted = frames.last { $0.first == CommandCode.setOtherParams.rawValue }
        == Self.selectedTailPacket
      #expect(keyWriteCount == 1)
      #expect(selectedTailWasSubmitted)
      guard let live = env.manager.currentConnection else {
        throw NSError(domain: "SettingsIdentityMetadataProvenance", code: 3)
      }
      let laterTime = try await live.services.settingsService.getTime()
      #expect(laterTime.timeIntervalSince1970 > 0)
      let laterInfo = try await live.services.settingsService.getSelfInfo()
      let laterWireIdentityIsAuthenticated = laterInfo.publicKey == Self.importedPublicKey
      #expect(laterWireIdentityIsAuthenticated)
      await model.importTask?.value
    } catch {
      bodyError = error
    }
    admission.open()
    commit.open()
    observation.open()
    env.manager.testIdentityBeforeAdmission = nil
    env.manager.testIdentityBeforeCommit = nil
    model.cancelImport()
    model.parseTask?.cancel()
    model.previewTask?.cancel()
    setter?.cancel()
    await env.bluetooth.releaseAllGates()
    await env.wifi.releaseAllGates()
    _ = await setter?.result
    await model.importTask?.value
    await model.parseTask?.value
    await model.previewTask?.value
    await env.manager.metadataCommitTask?.value
    await env.close()
    await env.manager.retirementTask?.value
    if let bodyError { throw bodyError }
  }

  enum ImportCase: Sendable {
    case noGhostCancelled, ghostCancelled, noGhostSuccessful, ghostSuccessful

    var hasGhost: Bool {
      self == .ghostCancelled || self == .ghostSuccessful
    }

    var isCancelled: Bool {
      self == .noGhostCancelled || self == .ghostCancelled
    }
  }
}
