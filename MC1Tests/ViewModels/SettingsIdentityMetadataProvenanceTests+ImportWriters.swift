import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

extension SettingsIdentityMetadataProvenanceTests {
  @Test(arguments: [TransportType.bluetooth, .wifi], [false, true])
  func `imported settings writers adopt private runtime`(transport: TransportType, hasGhost: Bool) async throws {
    let env = try await ConnectionLinkSwitchHarness.make(live: transport, wifiHost: "source.local")
    let radio = transport == .bluetooth ? env.bluetooth : env.wifi
    let model = makeWriterImportModel(env)
    let selection = Gate()
    var bodyError: (any Error)?
    do {
      await env.bluetooth.setConnectError(nil)
      await env.wifi.setConnectError(nil)
      await radio.setIdentityImportPublicKey(Self.importedPublicKey)
      await env.appState.wireServicesIfConnected()
      let source = try #require(env.manager.currentConnection)
      try #require(env.manager.connectionState == .ready && source.services.isAcceptingRuntimeWork)
      let ghost = source.device.copy {
        $0.id = UUID()
        $0.radioID = UUID()
        $0.publicKey = Self.importedPublicKey
        $0.isActive = false
        $0.connectionMethods = []
      }
      if hasGhost { try await env.manager.persistenceStore.saveDevice(ghost) }
      let expectedRadioID = hasGhost ? ghost.radioID : source.device.radioID
      let originalChannel = ChannelDTO(
        id: UUID(), radioID: source.device.radioID, index: 0, name: Writers.originalName,
        secret: Writers.originalSecret, isEnabled: true, lastMessageDate: nil, unreadCount: 0
      )
      let originalContact = ContactDTO(
        id: UUID(), radioID: source.device.radioID, publicKey: Writers.contactKey, name: Writers.originalName,
        typeRawValue: ContactType.chat.rawValue, flags: 0, outPathLength: 0xFF, outPath: Data(),
        lastAdvertTimestamp: 0, latitude: 0, longitude: 0, lastModified: 0, lastHeardTimestamp: 0,
        nickname: nil, isBlocked: false, isMuted: false, isFavorite: false, lastMessageDate: nil, unreadCount: 0
      )
      try await env.manager.persistenceStore.saveChannel(originalChannel)
      try await env.manager.persistenceStore.saveContact(originalContact)
      try await prepareWriterImport(Writers.config, model: model)
      for code in Writers.stages {
        await radio.holdReplies(for: code)
      }
      env.manager.testConnectionSelectionHandler = { _ in await selection.wait() }
      let start = await radio.sentData.count
      model.applyConfig()
      try await waitUntil("the changed name must use the adopted runtime before partition resolution") {
        await radio.heldReplyCount(for: .setName) == 1
      }
      let attempt = try #require(env.manager.desiredRequest?.attempt)
      let candidate = try #require(attempt.preparedServices)
      let adoptedSession = await candidate.settingsService.configurationSessionForRead() as? MeshCoreSession
      let replacementOwnsSettings = candidate.settingsService !== source.services.settingsService
        && candidate.session !== source.services.session && adoptedSession === candidate.session
      try #require(replacementOwnsSettings)
      try #require(!source.services.isAcceptingRuntimeWork)
      let canonicalSave = try await env.manager.persistenceStore.fetchDevice(id: source.device.id)
      let savedBeforeName = canonicalSave?.publicKey == Self.importedPublicKey && canonicalSave?.radioID == expectedRadioID
      try #require(savedBeforeName)
      _ = try await env.manager.persistenceStore.saveChannel(
        radioID: expectedRadioID, from: ChannelInfo(index: 0, name: Writers.channelName, secret: Writers.secret)
      )
      let occupant = try await env.manager.persistenceStore.fetchChannel(radioID: expectedRadioID, index: 0)
      let pendingOccupantMatchesSelectedSlot = occupant?.name == Writers.channelName && occupant?.secret == Writers.secret
      try #require(pendingOccupantMatchesSelectedSlot)
      let pendingDate = Date()
      let pending = MessageDTO(
        id: UUID(), radioID: expectedRadioID, contactID: nil, channelIndex: 0, text: "Imported queued message",
        timestamp: UInt32(pendingDate.timeIntervalSince1970), createdAt: pendingDate, direction: .outgoing, status: .pending,
        textType: .plain, ackCode: nil, pathLength: 0, snr: nil, senderKeyPrefix: nil, senderNodeName: nil,
        isRead: false, replyToID: nil, roundTripTime: nil, heardRepeats: 0, sendCount: 1, retryAttempt: 0, maxRetryAttempts: 0
      )
      try await env.manager.persistenceStore.saveMessage(pending)
      let envelope = ChannelMessageEnvelope(
        messageID: pending.id, channelIndex: 0, isResend: false, messageText: pending.text,
        messageTimestamp: pending.timestamp, localNodeName: "Test Radio"
      )
      _ = try await env.manager.persistenceStore.insertPendingSendAssigningSequence(PendingSendDTO(envelope: envelope, radioID: expectedRadioID))
      try await expectWriterPublicAdmissionRefused(source.services.nodeConfigService, radioID: source.device.radioID)
      var oldSettingsRefused = false
      do { _ = try await source.services.settingsService.getTime() } catch { oldSettingsRefused = true }
      #expect(oldSettingsRefused)

      for (index, code) in Writers.stages.enumerated() {
        try await waitUntil("each selected writer must submit on the private runtime in order") {
          await radio.heldReplyCount(for: code) == 1
        }
        let privateRuntime = env.manager.currentConnection == nil && env.manager.services == nil
          && env.manager.session == nil && env.manager.connectedDevice == nil && env.appState.services == nil
          && env.manager.connectionState == .connected && model.isApplying
          && env.manager.desiredRequest?.attempt === attempt && attempt.binding.isActive
        #expect(privateRuntime)
        let frames = await Array(radio.sentData.dropFirst(start))
        let writesMatchSelectedPrefix = Writers.selectedWrites(frames) == Array(Writers.packets.prefix(index + 1))
        #expect(writesMatchSelectedPrefix)
        expectNoOrdinaryWriterWork(frames)
        if code == .setOtherParams {
          let betweenPositionAndOther = frames.drop { $0.first != CommandCode.setCoordinates.rawValue }.dropFirst().dropLast()
          let cachedAuthenticatedParams = betweenPositionAndOther.allSatisfy { $0.first != CommandCode.appStart.rawValue }
          #expect(cachedAuthenticatedParams)
        }
        if code == .setChannel {
          let afterOtherParams = frames.drop { $0.first != CommandCode.setOtherParams.rawValue }.dropFirst()
          let refreshedAdoptedParams = afterOtherParams.filter { $0.first == CommandCode.appStart.rawValue }.count == 1
          #expect(refreshedAdoptedParams)
        }
        await radio.releaseReplies(for: code)
      }
      try await waitUntil("all selected writes must settle before the canonical selection") { selection.arrived }
      let framesAtSelection = await Array(radio.sentData.dropFirst(start))
      let allSelectedFramesMatch = Writers.selectedWrites(framesAtSelection) == Writers.packets
      let oneKeyFrame = framesAtSelection.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count == 1
      let selectedCodes = framesAtSelection.compactMap(\.first).filter {
        $0 == CommandCode.importPrivateKey.rawValue || Writers.stages.map(\.rawValue).contains($0)
      }
      let keyThenSelectedOrder = selectedCodes == [CommandCode.importPrivateKey.rawValue] + Writers.stages.map(\.rawValue)
      #expect(allSelectedFramesMatch)
      #expect(oneKeyFrame)
      #expect(keyThenSelectedOrder)
      expectNoOrdinaryWriterWork(framesAtSelection)
      #expect(env.manager.currentConnection == nil && env.appState.services == nil)
      try await expectWriterPublicAdmissionRefused(source.services.nodeConfigService, radioID: source.device.radioID)
      try await expectWriterPartition(env, radioID: expectedRadioID)
      let retainedPending = try await env.manager.persistenceStore.fetchMessage(id: pending.id)
      let pendingSendRetained = try await env.manager.persistenceStore.hasPendingSend(messageID: pending.id)
      let noPrematureQueueEffect = retainedPending == pending && pendingSendRetained
      #expect(noPrematureQueueEffect)
      if hasGhost {
        let retainedChannel = try await env.manager.persistenceStore.fetchChannel(id: originalChannel.id)
        let retainedContact = try await env.manager.persistenceStore.fetchContact(id: originalContact.id)
        let originalPartitionIsUntouched = retainedChannel == originalChannel && retainedContact == originalContact
        #expect(originalPartitionIsUntouched)
      }
      selection.open()
      try await waitUntil("the complete normal import must report its result") { !model.isApplying }
      #expect(model.importComplete && model.errorMessage == nil)
      try await waitUntil("ordinary setup must publish the adopted runtime after the tail") {
        env.manager.connectionState == .ready && env.manager.currentConnection != nil
      }
      await env.appState.wireServicesIfConnected()
      await env.manager.metadataCommitTask?.value
      let live = try #require(env.manager.currentConnection)
      let saved = try await env.manager.persistenceStore.fetchDevice(id: source.device.id)
      let finalCanonicalIdentity = saved?.publicKey == Self.importedPublicKey && saved?.radioID == expectedRadioID
        && live.device.publicKey == Self.importedPublicKey && live.device.radioID == expectedRadioID
      let exactRuntimeWasPublished = live.services === candidate && live.generation != source.generation
      let originalHandleAndMethods = saved?.id == source.device.id && saved?.connectionMethods == source.device.connectionMethods
      #expect(finalCanonicalIdentity)
      #expect(exactRuntimeWasPublished)
      #expect(originalHandleAndMethods)
      #expect(env.manager.identityImportRecoveries.isEmpty)
      if hasGhost {
        let consumedGhost = try await env.manager.persistenceStore.fetchDevice(id: ghost.id) == nil
        #expect(consumedGhost)
      }
      try await expectWriterPartition(env, radioID: expectedRadioID)
      try await waitUntil("ordinary canonical hydration must send the preserved pending row after the tail") {
        await (try? env.manager.persistenceStore.fetchMessage(id: pending.id))?.status == .sent
      }
      let noUnconfiguredSend = await radio.unconfiguredChannelSendCount == 0
      #expect(noUnconfiguredSend)
      let time = try await live.services.settingsService.getTime()
      let info = try await live.services.settingsService.getSelfInfo()
      let laterIdentityIsAuthenticated = info.publicKey == Self.importedPublicKey
      #expect(time.timeIntervalSince1970 > 0)
      #expect(laterIdentityIsAuthenticated)
      try await expectWriterPublicAdmissionRefused(source.services.nodeConfigService, radioID: source.device.radioID)
      let finalKeyCount = await radio.sentData.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
      #expect(finalKeyCount == 1)
    } catch { bodyError = error }
    selection.open()
    env.manager.testConnectionSelectionHandler = nil
    await closeWriterImport(model, env: env)
    if let bodyError { throw bodyError }
  }

  @Test(arguments: [TransportType.bluetooth, .wifi])
  func `key free settings import retains service and diff gates`(transport: TransportType) async throws {
    let env = try await ConnectionLinkSwitchHarness.make(live: transport, wifiHost: "source.local")
    let radio = transport == .bluetooth ? env.bluetooth : env.wifi
    let model = makeWriterImportModel(env)
    var bodyError: (any Error)?
    do {
      await env.appState.wireServicesIfConnected()
      let source = try #require(env.manager.currentConnection)
      var changed = Writers.config
      changed.privateKey = nil
      try await prepareWriterImport(changed, model: model)
      let beforeChanged = await radio.sentData.count
      model.applyConfig()
      try await waitUntil("the key-free changed import must settle") { !model.isApplying }
      try #require(model.importComplete && model.errorMessage == nil)
      let changedFrames = await Array(radio.sentData.dropFirst(beforeChanged))
      let keyFreeWritesMatch = Writers.selectedWrites(changedFrames) == Writers.packets
      let changedImportKeptOriginalService = env.manager.isCurrent(source) && env.manager.services?.settingsService === source.services.settingsService
      #expect(keyFreeWritesMatch)
      #expect(changedImportKeptOriginalService)
      await model.importTask?.value
      try await expectWriterPartition(env, radioID: source.device.radioID)
      try await prepareWriterImport(changed, model: model)
      let beforeUnchanged = await radio.sentData.count
      model.applyConfig()
      try await waitUntil("unchanged selected settings and resident channel/contact must be diff-gated") { !model.isApplying }
      #expect(model.importComplete && model.errorMessage == nil)
      let unchangedFrames = await Array(radio.sentData.dropFirst(beforeUnchanged))
      let noRedundantWrites = Writers.selectedWrites(unchangedFrames).isEmpty
      let noKeyWrites = await radio.sentData.allSatisfy { $0.first != CommandCode.importPrivateKey.rawValue }
      let originalRuntimeSurvives = env.manager.isCurrent(source) && env.manager.services === source.services
        && env.manager.services?.settingsService === source.services.settingsService
      #expect(noRedundantWrites)
      #expect(noKeyWrites)
      #expect(originalRuntimeSurvives)
      await env.manager.metadataCommitTask?.value
      let saved = try await env.manager.persistenceStore.fetchDevice(id: source.device.id)
      let originalIdentitySurvives = saved?.publicKey == source.device.publicKey && saved?.radioID == source.device.radioID
      #expect(originalIdentitySurvives)
      try await expectWriterPartition(env, radioID: source.device.radioID)
      let laterTime = try await source.services.settingsService.getTime()
      #expect(laterTime.timeIntervalSince1970 > 0)
    } catch { bodyError = error }
    await closeWriterImport(model, env: env)
    if let bodyError { throw bodyError }
  }

  private func makeWriterImportModel(_ env: ConnectionLinkSwitchHarness) -> NodeConfigImportViewModel {
    let model = NodeConfigImportViewModel()
    model.configure(
      nodeConfigService: { env.manager.services?.nodeConfigService }, settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice }, connectionManager: env.manager
    )
    return model
  }

  private func prepareWriterImport(_ config: MeshCoreNodeConfig, model: NodeConfigImportViewModel) async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let url = directory.appendingPathComponent("settings-writers.json")
    try JSONEncoder().encode(config).write(to: url)
    model.beginImport()
    model.parseFile(at: url)
    await model.parseTask?.value
    let allSectionsParsed = model.importedConfig != nil && !model.isParsing && model.errorMessage == nil && model.sections.allSelected
    try #require(allSectionsParsed)
    model.prepareConfirmation()
    await model.previewTask?.value
    try #require(model.showConfirmation && model.confirmation != nil && model.errorMessage == nil)
  }

  private func expectWriterPublicAdmissionRefused(_ service: NodeConfigService, radioID: UUID) async throws {
    var refusals = 0
    do { _ = try await service.exportConfig(sections: ConfigSections(nodeIdentity: true)) } catch is CancellationError { refusals += 1 }
    do { _ = try await service.previewImport(Writers.config, sections: ConfigSections(nodeIdentity: true)) } catch is CancellationError { refusals += 1 }
    do { try await service.importConfig(Writers.config, sections: ConfigSections(nodeIdentity: true), radioID: radioID) } catch is CancellationError { refusals += 1 }
    #expect(refusals == 3)
  }

  private func expectNoOrdinaryWriterWork(_ frames: [Data]) {
    let afterKey = frames.drop { $0.first != CommandCode.importPrivateKey.rawValue }
    let ordinaryWorkWasWithheld = afterKey.allSatisfy { !Writers.ordinaryCodes.contains($0.first ?? 0) }
    #expect(ordinaryWorkWasWithheld)
  }

  private func expectWriterPartition(_ env: ConnectionLinkSwitchHarness, radioID: UUID) async throws {
    let channel = try await env.manager.persistenceStore.fetchChannel(radioID: radioID, index: 0)
    let contact = try await env.manager.persistenceStore.fetchContact(radioID: radioID, publicKey: Writers.contactKey)
    let selectedChannelIsCanonical = channel?.radioID == radioID && channel?.name == Writers.channelName && channel?.secret == Writers.secret
    let selectedContactIsCanonical = contact?.radioID == radioID && contact?.name == Writers.contactName
    #expect(selectedChannelIsCanonical)
    #expect(selectedContactIsCanonical)
  }

  private func closeWriterImport(_ model: NodeConfigImportViewModel, env: ConnectionLinkSwitchHarness) async {
    model.cancelImport()
    model.parseTask?.cancel()
    model.previewTask?.cancel()
    await env.bluetooth.releaseAllGates()
    await env.wifi.releaseAllGates()
    await model.importTask?.value
    await model.parseTask?.value
    await model.previewTask?.value
    await env.manager.metadataCommitTask?.value
    await env.close()
    await env.manager.retirementTask?.value
  }

  @MainActor
  private enum Writers {
    static let originalName = "Original partition"
    static let originalSecret = Data(repeating: 0x4A, count: 16)
    static let secret = Data(repeating: 0x5A, count: 16)
    static let contactKey = Data(repeating: 0x6B, count: 32)
    static let channelName = "Imported channel"
    static let contactName = "Imported contact"
    static let stages: [CommandCode] = [.setName, .setCoordinates, .setOtherParams, .setChannel, .updateContact, .setRadio, .setTxPower]
    static let ordinaryCodes = Set([CommandCode.getChannel, .getContacts, .getMessage, .sendMessage, .sendChannelMessage].map(\.rawValue))
    static var config: MeshCoreNodeConfig {
      MeshCoreNodeConfig(
        name: "Imported Radio", privateKey: SettingsIdentityMetadataProvenanceTests.expandedKeyHex,
        radioSettings: .init(frequency: 916_000, bandwidth: 250_000, spreadingFactor: 8, codingRate: 6, txPower: 20),
        positionSettings: .init(latitude: "37.123456", longitude: "-122.654321"), otherSettings: .init(multiAcks: 1),
        channels: [.init(name: channelName, secret: secret.hexString)],
        contacts: [.init(type: 1, name: contactName, publicKey: contactKey.hexString, flags: 0,
                         latitude: "0", longitude: "0", lastAdvert: 1, lastModified: 1)]
      )
    }

    static var packets: [Data] {
      let contact = MeshContact(
        id: contactKey.hexString, publicKey: contactKey, type: .chat, flags: [], outPathLength: 0xFF, outPath: Data(),
        advertisedName: contactName, lastAdvertisement: Date(timeIntervalSince1970: 1), latitude: 0, longitude: 0,
        lastModified: Date(timeIntervalSince1970: 1)
      )
      return [PacketBuilder.setName("Imported Radio"), PacketBuilder.setCoordinates(latitude: 37.123456, longitude: -122.654321),
              SettingsIdentityMetadataProvenanceTests.selectedTailPacket,
              PacketBuilder.setChannel(index: 0, name: channelName, secret: secret), PacketBuilder.updateContact(contact),
              PacketBuilder.setRadio(frequency: 916, bandwidth: 250, spreadingFactor: 8, codingRate: 6), PacketBuilder.setTxPower(20)]
    }

    static func selectedWrites(_ frames: [Data]) -> [Data] {
      let codes = Set(stages.map(\.rawValue))
      return frames.filter { codes.contains($0.first ?? 0) }
    }
  }
}
