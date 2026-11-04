import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Node config import private tail presentation", .serialized)
@MainActor
struct NodeConfigImportTailPresentationTests {
  @Test(arguments: TailSettlement.allCases)
  func `normal import survives hosted navigation clear without publishing its private tail`(
    settlement: TailSettlement
  ) async throws {
    let env = try await ConnectionLinkSwitchHarness.make(live: .wifi, wifiHost: "source.local")
    let model = NodeConfigImportViewModel()
    model.configure(
      nodeConfigService: { env.manager.services?.nodeConfigService },
      settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice }, connectionManager: env.manager
    )
    var host: SettingsTabHarness?
    var callers: [Task<Void, Never>] = []
    var bodyError: (any Error)?
    do {
      await env.bluetooth.setConnectError(nil)
      await env.wifi.setConnectError(nil)
      await env.wifi.setIdentityImportPublicKey(Self.importedPublicKey)
      await env.wifi.setResponsePackets(
        for: .getDefaultFloodScope, packets: [Data([ResponseCode.defaultFloodScope.rawValue])]
      )
      guard let source = env.manager.currentConnection else {
        throw NSError(domain: "NodeConfigImportTailPresentation", code: 2)
      }
      let hostedStartupScopeIsUnset = try await source.services.settingsService.getDefaultFloodScope() == nil
      try #require(hostedStartupScopeIsUnset)
      let store = env.manager.persistenceStore
      let radioID = source.device.radioID
      let retainedRecipient = ChannelDTO(
        id: UUID(), radioID: radioID, index: 0, name: "Retained recipient",
        secret: Self.channelSecret, isEnabled: true, lastMessageDate: nil, unreadCount: 0
      )
      try await store.saveChannel(retainedRecipient)
      let settingsHost = try SettingsTabHarness.make(connected: false, injectedAppState: env.appState)
      host = settingsHost
      let settingsListIsVisible = try await settingsHost.showSettingsList()
      try #require(settingsListIsVisible)
      env.appState.navigation.navigateToSetting(.advanced)
      try await waitUntil(timeout: .seconds(35), "the actual Settings host should select Advanced") {
        env.appState.navigation.selectedSetting == .advanced
      }

      model.beginImport()
      try await selectFile(Self.config, model: model)
      let inputID = model.inputID
      model.prepareConfirmation()
      await model.previewTask?.value
      let confirmationReady = model.showConfirmation && model.confirmation != nil && model.errorMessage == nil
      try #require(confirmationReady)
      await env.wifi.holdReplies(for: .setCoordinates)
      if settlement == .failure { await env.wifi.rejectNext(.setCoordinates) }
      model.applyConfig()
      try await waitUntil(timeout: .seconds(35), "the selected position stage should hold its actual post-key response") {
        let positionIsHeld = await env.wifi.heldReplyCount(for: .setCoordinates) == 1
        return positionIsHeld || !model.isApplying
      }
      let heldPosition = await env.wifi.heldReplyCount(for: .setCoordinates) == 1
      let prerequisiteFrameCodes = await env.wifi.sentData.compactMap(\.first)
      let prerequisiteError = model.errorMessage ?? "none"
      try #require(heldPosition, "Import must reach its selected tail; error=\(prerequisiteError), frameCodes=\(prerequisiteFrameCodes)")
      try await waitUntil(timeout: .seconds(35), "the importer should retain its actual private-key progress") {
        model.didApplyAnyWrite
      }
      let heldFrames = await env.wifi.sentData
      let keyIndex = heldFrames.firstIndex { $0.first == CommandCode.importPrivateKey.rawValue }
      let importedFrameIndex = try #require(keyIndex)
      let keyWriteCount = heldFrames.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
      #expect(keyWriteCount == 1)
      let acceptedIdentityIsSaved = try await store.fetchDevice(id: source.device.id)?.publicKey == Self.importedPublicKey
      #expect(acceptedIdentityIsSaved)
      let channelTailHasNotStarted = heldFrames.contains { $0.first == CommandCode.setChannel.rawValue } == false
      #expect(channelTailHasNotStarted)

      let hasPublicSnapshot = env.manager.currentConnection != nil
      let hasPublicServices = env.appState.services != nil
      let hasPublicDevice = env.appState.connectedDevice != nil
      let hasPublicSession = env.manager.session != nil
      #expect(hasPublicSnapshot == false)
      #expect(hasPublicServices == false)
      #expect(hasPublicDevice == false)
      #expect(hasPublicSession == false)
      if !hasPublicDevice {
        try await waitUntil(timeout: .seconds(35), "MainTabView should clear the device route during the private interval") {
          env.appState.navigation.selectedSetting == nil
        }
      }
      let routeWasCleared = env.appState.navigation.selectedSetting == nil
      #expect(routeWasCleared)
      settingsHost.selectTab(.chats)
      // The real host clears navigation; the separately retained importer uses Settings' provider lifetime.
      model.handleDismissal()
      let applyingInputSurvivedDismissal = model.isApplying && model.importedConfig == Self.config
        && model.inputID == inputID && model.recovery == nil && !model.importComplete
      #expect(applyingInputSurvivedDismissal)
      let exactHeldProgress = model.applyProgress == Self.privateKeyProgress
        && model.applyStepDescription == L10n.Settings.ConfigImport.stepPrivateKey
      #expect(exactHeldProgress)

      var foregroundEntered = false
      var wiringEntered = false
      callers.append(Task {
        foregroundEntered = true
        await env.appState.handleReturnToForeground()
      })
      callers.append(Task {
        wiringEntered = true
        await env.appState.wireServicesIfConnected()
      })
      try await waitUntil(timeout: .seconds(35), "actual foreground and direct wiring callers should enter") {
        foregroundEntered && wiringEntered
      }
      let pendingBeforeIntent = try await store.fetchPendingSends(radioID: radioID).count
      let intentOutcome = try await SendMessageIntent.performSend(
        message: Self.heldIntentMessage, recipient: .channel(retainedRecipient), in: env.appState
      )
      #expect(intentOutcome == .mustForeground)
      let pendingAfterIntent = try await store.fetchPendingSends(radioID: radioID).count
      #expect(pendingAfterIntent == pendingBeforeIntent)
      let framesWhileHeld = await env.wifi.sentData
      let postKeyCodes = framesWhileHeld.dropFirst(importedFrameIndex + 1).compactMap(\.first)
      let earlyBatteryCount = postKeyCodes.filter { $0 == CommandCode.getBattery.rawValue }.count
      let earlyQueueSendCount = postKeyCodes.filter { $0 == CommandCode.sendChannelMessage.rawValue }.count
      #expect(earlyBatteryCount == 0)
      #expect(earlyQueueSendCount == 0)
      if settlement == .cancel {
        model.cancelImport()
        let cancellationWasRequested = model.importTask?.isCancelled == true
        #expect(cancellationWasRequested)
      }
      let stillApplyingAtHeldResponse = model.isApplying && !model.importComplete
      #expect(stillApplyingAtHeldResponse)
      let stillNoPublishedSnapshot = env.manager.currentConnection == nil
      #expect(stillNoPublishedSnapshot)

      await env.wifi.releaseReplies(for: .setCoordinates)
      try await waitUntil(timeout: .seconds(35), "the original importer outcome must settle after its held exchange") {
        !model.isApplying
      }
      let wholeImportSucceeded = model.importComplete
      let outcomeError = model.errorMessage
      switch settlement {
      case .success:
        #expect(wholeImportSucceeded)
        #expect(outcomeError == nil)
      case .failure:
        #expect(wholeImportSucceeded == false)
        let originalTailError = NodeConfigImportViewModel.failureMessage(
          for: SettingsServiceError.sessionError(.deviceError(code: 1)), didApplyAnyWrite: true
        )
        #expect(outcomeError == originalTailError)
      case .cancel:
        #expect(wholeImportSucceeded == false)
        #expect(outcomeError == L10n.Settings.ConfigImport.cancelledPartial)
      }
      for caller in callers {
        await caller.value
      }
      callers.removeAll()
      try await waitUntil(timeout: .seconds(35), "the known accepted identity should activate after tail settlement") {
        env.manager.connectionState == .ready && env.manager.currentConnection != nil
      }
      guard let live = env.manager.currentConnection else {
        throw NSError(domain: "NodeConfigImportTailPresentation", code: 3)
      }
      let samePartitionIsLive = live.device.radioID == radioID && live.device.publicKey == Self.importedPublicKey
      #expect(samePartitionIsLive)
      let observedTime = try await live.services.settingsService.getTime()
      #expect(observedTime.timeIntervalSince1970 > 0)
      let tailChannelWriteCount = await env.wifi.sentData.filter { $0.first == CommandCode.setChannel.rawValue }.count
      #expect(tailChannelWriteCount == (settlement == .success ? 1 : 0))
      if settlement == .success {
        let restoredSecret = await env.wifi.channelMatches(index: 0, secret: Self.channelSecret)
        #expect(restoredSecret)
        let laterOutcome = try await SendMessageIntent.performSend(
          message: Self.liveIntentMessage, recipient: .channel(retainedRecipient), in: env.appState
        )
        #expect(laterOutcome == .queued)
        try await waitUntil(timeout: .seconds(35), "a fresh activated queue should complete its actual frame") {
          let hasLiveFrame = await env.wifi.sentData.contains { $0.range(of: Data(Self.liveIntentMessage.utf8)) != nil }
          let pendingCount = await (try? store.fetchPendingSends(radioID: radioID).count) ?? 1
          return hasLiveFrame && pendingCount == 0
        }
      }
      await model.importTask?.value
      let inputLifetimeIsCorrect = settlement == .success
        ? model.importedConfig == nil
        : model.importedConfig == Self.config && model.inputID == inputID
      #expect(inputLifetimeIsCorrect)
    } catch {
      bodyError = error
    }
    model.cancelImport()
    model.parseTask?.cancel()
    model.previewTask?.cancel()
    await env.bluetooth.releaseAllGates()
    await env.wifi.releaseAllGates()
    for caller in callers {
      await caller.value
    }
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
    let selectedInputWasParsed = model.importedConfig == config && !model.isParsing
    try #require(selectedInputWasParsed)
  }

  enum TailSettlement: CaseIterable, Sendable { case success, failure, cancel }

  private static let privateKeyProgress = 1.0 / 3.0
  private static let heldIntentMessage = "Shortcut must wait for the private tail"
  private static let liveIntentMessage = "Activated Shortcut queue is live"
  private static let channelSecret = Data(repeating: 0xCD, count: 16)
  private static let importedPublicKey = Data([
    0x1E, 0xC7, 0x71, 0x75, 0xB0, 0x91, 0x8E, 0xD2, 0x06, 0xF9, 0xAE, 0x04, 0xEC, 0x13, 0x6D, 0x6D,
    0x5D, 0x43, 0x15, 0xBB, 0x26, 0x30, 0x54, 0x27, 0xF6, 0x45, 0xB4, 0x92, 0xE9, 0x35, 0x0C, 0x10
  ])
  private static let config = MeshCoreNodeConfig(
    privateKey: "7065e18fd9fabb70c1ed90dca19907de698c88b709ea146eafd93d9b830c7b60"
      + "c4681193c79bbc39945ba8064104bb618f8fd7a84a0af6f57033d6e8ddcd6471",
    positionSettings: .init(latitude: "37.5", longitude: "-122.5"),
    channels: [.init(name: "Restored selected tail", secret: String(repeating: "CD", count: 16))]
  )
}
