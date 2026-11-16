import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

extension SettingsIdentityMetadataProvenanceTests {
  @Test(arguments: [DirectIdentityCase.bluetoothImport, .wifiImport, .bluetoothRegenerate, .wifiRegenerate],
        [DirectOutcome.success, .rejected, .disabled])
  func `standalone identity actions keep their settings event persistence`(
    entry: DirectIdentityCase, outcome: DirectOutcome
  ) async throws {
    let action = entry.action
    try await ConnectionLinkSwitchHarness.withHarness(live: entry.transport, wifiHost: "source.local") { env in
      let radio = entry.transport == .bluetooth ? env.bluetooth : env.wifi
      await env.appState.wireServicesIfConnected()
      guard let source = env.manager.currentConnection else {
        throw NSError(domain: "SettingsIdentityMetadataProvenance", code: 3)
      }
      await radio.setIdentityImportPublicKey(Self.importedPublicKey)
      switch outcome {
      case .success: break
      case .rejected: await radio.rejectNext(.importPrivateKey)
      case .disabled:
        await radio.setResponsePackets(for: .importPrivateKey, packets: [Data([ResponseCode.disabled.rawValue])])
      }
      let importModel = ImportKeyViewModel()
      importModel.configure(settingsService: { env.manager.services?.settingsService })
      let regenerateModel = RegenerateIdentityViewModel()
      regenerateModel.configure(settingsService: { env.manager.services?.settingsService })
      let expandedKey = Data([
        0x70, 0x65, 0xE1, 0x8F, 0xD9, 0xFA, 0xBB, 0x70, 0xC1, 0xED, 0x90, 0xDC, 0xA1, 0x99, 0x07, 0xDE,
        0x69, 0x8C, 0x88, 0xB7, 0x09, 0xEA, 0x14, 0x6E, 0xAF, 0xD9, 0x3D, 0x9B, 0x83, 0x0C, 0x7B, 0x60,
        0xC4, 0x68, 0x11, 0x93, 0xC7, 0x9B, 0xBC, 0x39, 0x94, 0x5B, 0xA8, 0x06, 0x41, 0x04, 0xBB, 0x61,
        0x8F, 0x8F, 0xD7, 0xA8, 0x4A, 0x0A, 0xF6, 0xF5, 0x70, 0x33, 0xD6, 0xE8, 0xDD, 0xCD, 0x64, 0x71
      ])
      let succeeded: Bool
      switch action {
      case .importKey:
        importModel.hexInput = Self.expandedKeyHex
        importModel.validateAndConfirm()
        let confirmationIsReady = importModel.showingReplaceAlert && importModel.errorMessage == nil
        try #require(confirmationIsReady)
        succeeded = await importModel.importKey()
      case .regenerate:
        regenerateModel.generatedKey = .init(
          expandedKey: expandedKey, publicKeyHex: Self.importedPublicKey.hexString,
          privateKeyHex: Self.expandedKeyHex, accessibilityLabel: "Synthetic public key"
        )
        succeeded = await regenerateModel.replaceIdentity()
      }
      let successCount = action == .importKey ? importModel.successTrigger : regenerateModel.successTrigger
      let outcomeError = action == .importKey ? importModel.errorMessage : regenerateModel.errorMessage
      let expectedKey = outcome == .success ? Self.importedPublicKey : source.device.publicKey
      if outcome == .success {
        try await waitUntil("the real direct action refresh must reach AppState") {
          env.manager.connectedDevice?.publicKey == Self.importedPublicKey
        }
      }
      await env.manager.metadataCommitTask?.value
      let stored = try await env.manager.persistenceStore.fetchDevice(id: source.device.id)
      let storedKeyIsExpected = stored?.publicKey == expectedKey
      let publishedKeyIsExpected = env.manager.connectedDevice?.publicKey == expectedKey
      let stableIdentityAxesAndMethods = stored?.id == source.device.id && stored?.radioID == source.device.radioID
        && stored?.connectionMethods == source.device.connectionMethods
      let oneKeyAttempt = await radio.sentData.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count == 1
      #expect(succeeded == (outcome == .success))
      #expect(successCount == (outcome == .success ? 1 : 0))
      #expect(storedKeyIsExpected)
      #expect(publishedKeyIsExpected)
      #expect(stableIdentityAxesAndMethods)
      #expect(oneKeyAttempt)
      switch outcome {
      case .success: #expect(outcomeError == nil)
      case .rejected: #expect(outcomeError == L10n.Settings.RegenerateIdentity.Error.deviceRejected)
      case .disabled: #expect(outcomeError == L10n.Settings.RegenerateIdentity.Error.featureDisabled)
      }
      let laterTime = try await source.services.settingsService.getTime()
      #expect(laterTime.timeIntervalSince1970 > 0)
    }
  }

  @Test
  func `accepted verified settings event merges before same radio replacement dials`() async throws {
    let env = try await ConnectionLinkSwitchHarness.make(wifiHost: "source.local")
    let gate = Gate()
    var blocker: Task<Void, Error>?
    var setter: Task<Void, Error>?
    var successor: Task<ConnectionResult, Error>?
    var bodyError: (any Error)?
    do {
      await env.appState.wireServicesIfConnected()
      guard let source = env.manager.currentConnection else {
        throw NSError(domain: "SettingsIdentityMetadataProvenance", code: 3)
      }
      await env.wifi.setConnectError(nil)
      await env.wifi.holdConnect()
      blocker = env.manager.enqueueMetadata { store in
        await gate.wait()
        try await store.addDeviceKnownRegion(radioID: source.device.radioID, region: "Accepted local region")
      }
      await env.bluetooth.enqueueResponsePackets(
        for: .appStart, packets: [Self.telemetryPacket(publicKey: source.device.publicKey)]
      )
      await env.bluetooth.enqueueResponsePackets(
        for: .appStart, packets: [Self.telemetryPacket(publicKey: source.device.publicKey)]
      )
      setter = Task {
        _ = try await source.services.settingsService.setOtherParamsVerified(
          autoAddContacts: !source.device.manualAddContacts, telemetryModes: Self.telemetryModes,
          advertLocationPolicy: source.device.advertLocationPolicyMode, multiAcks: source.device.multiAcks
        )
      }
      try await setter?.value
      try await waitUntil("the ordinary Settings event must be accepted before retirement") {
        env.manager.connectedDevice?.telemetryModeBase == Self.telemetryMarker
      }
      successor = Task { try await env.manager.connectViaWiFi(host: "source.local", port: ConnectionMethod.defaultWiFiPort) }
      try await waitUntil("the same-radio successor must retire the event's original request") {
        env.manager.connectionRequestID != source.requestID
      }
      let noEarlySuccessorDial = await env.wifi.connectCount == env.initialWiFiAttempts
      #expect(noEarlySuccessorDial)
      gate.open()
      try await blocker?.value
      try await waitUntil("the actual successor dial must follow accepted metadata settlement") { await env.wifi.connectIsHeld }
      let saved = try await env.manager.persistenceStore.fetchDevice(id: source.device.id)
      let acceptedFieldIsSaved = saved?.telemetryModeBase == Self.telemetryMarker
      let unrelatedFreshFieldSurvives = saved?.knownRegions == ["Accepted local region"]
      let originalTupleSurvives = saved?.id == source.device.id && saved?.radioID == source.device.radioID
        && saved?.publicKey == source.device.publicKey
      #expect(acceptedFieldIsSaved)
      #expect(unrelatedFreshFieldSurvives)
      #expect(originalTupleSurvives)
      await env.wifi.releaseConnect()
      _ = try await successor?.value
      let laterTime = try await env.manager.services?.settingsService.getTime()
      #expect(laterTime != nil)
    } catch {
      bodyError = error
    }
    gate.open()
    setter?.cancel()
    successor?.cancel()
    await env.bluetooth.releaseAllGates()
    await env.wifi.releaseAllGates()
    _ = await blocker?.result
    _ = await setter?.result
    _ = await successor?.result
    await env.manager.metadataCommitTask?.value
    await env.close()
    await env.manager.retirementTask?.value
    if let bodyError { throw bodyError }
  }

  enum DirectAction: Sendable { case importKey, regenerate }
  enum DirectOutcome: Sendable { case success, rejected, disabled }

  enum DirectIdentityCase: Sendable {
    case bluetoothImport, wifiImport, bluetoothRegenerate, wifiRegenerate

    var action: DirectAction {
      self == .bluetoothImport || self == .wifiImport ? .importKey : .regenerate
    }

    var transport: TransportType {
      self == .bluetoothImport || self == .bluetoothRegenerate ? .bluetooth : .wifi
    }
  }
}
