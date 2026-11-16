import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@MainActor
final class RetainedConversationPartitionHarness {
  let environment: ConnectionLinkSwitchHarness
  let canonicalRadioID: UUID
  let contact: ContactDTO
  let channel: ChannelDTO
  let room: RemoteNodeSessionDTO
  let directMessage: MessageDTO
  let channelMessage: MessageDTO
  let roomMessage: RoomMessageDTO
  let directModel: ChatViewModel
  let channelModel: ChatViewModel
  let roomModel: RoomConversationViewModel
  let radio: ConnectionTestRadioTransport

  var manager: ConnectionManager {
    environment.manager
  }

  var store: PersistenceStore {
    manager.persistenceStore
  }

  private init(
    environment: ConnectionLinkSwitchHarness, canonicalRadioID: UUID, contact: ContactDTO,
    channel: ChannelDTO, room: RemoteNodeSessionDTO, directMessage: MessageDTO,
    channelMessage: MessageDTO, roomMessage: RoomMessageDTO,
    directModel: ChatViewModel, channelModel: ChatViewModel, roomModel: RoomConversationViewModel,
    radio: ConnectionTestRadioTransport
  ) {
    self.environment = environment
    self.canonicalRadioID = canonicalRadioID
    self.contact = contact
    self.channel = channel
    self.room = room
    self.directMessage = directMessage
    self.channelMessage = channelMessage
    self.roomMessage = roomMessage
    self.directModel = directModel
    self.channelModel = channelModel
    self.roomModel = roomModel
    self.radio = radio
  }

  static func make(live: TransportType = .bluetooth) async throws -> RetainedConversationPartitionHarness {
    let env = try await ConnectionLinkSwitchHarness.make(live: live, wifiHost: live == .wifi ? "radio.local" : nil)
    do {
      let radio = live == .bluetooth ? env.bluetooth : env.wifi
      await radio.setConnectError(nil)
      let source = env.manager.currentConnection
      let sourceAvailable = source != nil
      try #require(sourceAvailable)
      guard let source else { throw HarnessError.sourceUnavailable }
      let store = env.manager.persistenceStore
      let ghost = env.device.copy {
        $0.id = UUID()
        $0.radioID = UUID()
        $0.publicKey = canonicalPublicKey
        $0.isActive = false
        $0.connectionMethods = []
      }
      try await store.saveDevice(ghost)
      let contact = makeContact(radioID: env.device.radioID, type: .chat, keyByte: 0xD1)
      let roomContact = makeContact(radioID: env.device.radioID, type: .room, keyByte: 0xD2)
      try await store.saveContact(contact)
      try await store.saveContact(roomContact)
      let channel = ChannelDTO(
        id: UUID(), radioID: env.device.radioID, index: channelIndex, name: "Retained A",
        secret: channelSecret, isEnabled: true, lastMessageDate: nil, unreadCount: 0
      )
      try await store.saveChannel(channel)
      await radio.setChannel(index: channelIndex, name: "Retained A", secret: channelSecret)
      let room = RemoteNodeSessionDTO(
        radioID: env.device.radioID, publicKey: roomContact.publicKey, name: "Retained A room",
        role: .roomServer, isConnected: true, permissionLevel: .readWrite
      )
      try await store.saveRemoteNodeSessionDTO(room)
      let directMessage = try await source.services.messageService.createPendingMessage(text: "Retained A DM", to: contact)
        .copy { $0.status = .failed; $0.timestamp = originalTimestamp }
      let channelMessage = try await source.services.messageService.createPendingChannelMessage(
        text: "Retained A channel", channelIndex: channelIndex, radioID: env.device.radioID
      ).copy { $0.status = .failed; $0.timestamp = originalTimestamp }
      try await store.saveMessage(directMessage)
      try await store.saveMessage(channelMessage)
      let roomMessage = RoomMessageDTO(
        sessionID: room.id, authorKeyPrefix: Data(env.device.publicKey.prefix(4)), text: "Retained A room retry",
        timestamp: originalTimestamp, isFromSelf: true, status: .failed
      )
      try await store.saveRoomMessage(roomMessage)
      let directModel = configureChat(environment: env, conversation: .dm(contact))
      let channelModel = configureChat(environment: env, conversation: .channel(channel))
      _ = await directModel.primeInitialMessages(for: contact, populateMode: .refreshWindow)
      _ = await channelModel.primeInitialChannelMessages(for: channel, populateMode: .refreshWindow)
      let roomModel = RoomConversationViewModel()
      roomModel.configure(
        roomServerService: { env.appState.connectionSnapshot(for: room.radioID)?.services.roomServerService },
        dataStore: { env.appState.connectionSnapshot(for: room.radioID)?.services.dataStore },
        syncCoordinator: { env.appState.connectionSnapshot(for: room.radioID)?.services.syncCoordinator },
        notificationService: { env.appState.connectionSnapshot(for: room.radioID)?.services.notificationService }
      )
      await roomModel.loadMessages(for: room)
      return RetainedConversationPartitionHarness(
        environment: env, canonicalRadioID: ghost.radioID, contact: contact, channel: channel,
        room: room, directMessage: directMessage, channelMessage: channelMessage, roomMessage: roomMessage,
        directModel: directModel, channelModel: channelModel, roomModel: roomModel, radio: radio
      )
    } catch {
      await env.close()
      throw error
    }
  }

  func importCanonicalIdentity(includeSelectedTail: Bool = false) async throws {
    let source = manager.currentConnection
    let sourceAvailable = source != nil
    try #require(sourceAvailable)
    guard let source else { throw HarnessError.sourceUnavailable }
    await radio.setIdentityImportPublicKey(Self.canonicalPublicKey)
    try await source.services.nodeConfigService.importConfig(
      MeshCoreNodeConfig(
        privateKey: Self.privateKey,
        otherSettings: includeSelectedTail ? .init(multiAcks: Self.selectedTailMultiAcks) : nil
      ), sections: ConfigSections(nodeIdentity: true, otherSettings: includeSelectedTail),
      radioID: environment.device.radioID
    )
    try await waitUntil {
      self.manager.connectionState == .ready && self.manager.connectedDevice?.radioID == self.canonicalRadioID
    }
    let handlePreserved = manager.connectedDevice?.id == environment.device.id
    let partitionChanged = manager.connectedDevice?.radioID != environment.device.radioID
    try #require(handlePreserved)
    try #require(partitionChanged)
  }

  func seedDirectPending() async throws -> PendingSendDTO {
    await joinCurrentChatDrain()
    let pending = PendingSendDTO(
      id: UUID(), radioID: contact.radioID, messageID: directMessage.id, kind: .dm,
      contactID: contact.id, channelIndex: nil, isResend: true, messageText: "Retained prior pending text",
      messageTimestamp: Self.originalTimestamp, localNodeName: "Retained prior owner", sequence: Self.pendingSequence,
      enqueuedAt: Date(timeIntervalSince1970: TimeInterval(Self.originalTimestamp)), attemptCount: Self.pendingAttempts
    )
    try await store.upsertPendingSend(pending)
    return pending
  }

  func messageFrameCount() async -> Int {
    await radio.sentData.filter {
      $0.first == CommandCode.sendMessage.rawValue || $0.first == CommandCode.sendChannelMessage.rawValue
    }.count
  }

  func joinCurrentChatDrain() async {
    await manager.services?.chatSendQueueService.awaitDrainCompletion()
  }

  func proveLaterIO() async throws {
    let time = try await manager.session?.getTime()
    let exchangeCompleted = (time?.timeIntervalSince1970 ?? 0) > 0
    #expect(exchangeCompleted)
    let info = try await manager.session?.sendAppStart()
    let currentRadioAnswered = info != nil && info?.publicKey == manager.connectedDevice?.publicKey
    #expect(currentRadioAnswered)
  }

  func waitUntil(_ predicate: @escaping @MainActor () async -> Bool) async throws {
    let deadline = ContinuousClock.now + Timing.timeout
    while await !predicate() {
      guard ContinuousClock.now < deadline else { throw HarnessError.timedOut }
      try await Task.sleep(for: Timing.pollInterval)
    }
  }

  static func withHarness(
    live: TransportType = .bluetooth, _ body: (RetainedConversationPartitionHarness) async throws -> Void
  ) async throws {
    let harness = try await make(live: live)
    do {
      try await body(harness)
      await harness.environment.close()
    } catch {
      await harness.environment.close()
      throw error
    }
  }

  private static func configureChat(
    environment env: ConnectionLinkSwitchHarness, conversation: ChatConversationType
  ) -> ChatViewModel {
    let model = ChatViewModel()
    model.configure(
      dependencies: env.appState.makeChatViewModelDependencies(radioID: conversation.radioID), onNavigateToMap: nil,
      linkPreviewCache: nil, chatCoordinatorRegistry: env.appState.ensureChatCoordinatorRegistry(), conversation: conversation
    )
    return model
  }

  private static func makeContact(radioID: UUID, type: ContactType, keyByte: UInt8) -> ContactDTO {
    ContactDTO(
      id: UUID(), radioID: radioID, publicKey: Data(repeating: keyByte, count: ProtocolLimits.publicKeySize),
      name: "Retained A contact", typeRawValue: type.rawValue, flags: 0, outPathLength: 0, outPath: Data(),
      lastAdvertTimestamp: 0, latitude: 0, longitude: 0, lastModified: 0, lastHeardTimestamp: nil,
      nickname: nil, isBlocked: false, isMuted: false, isFavorite: false, lastMessageDate: nil, unreadCount: 0
    )
  }

  private enum HarnessError: Error { case sourceUnavailable, timedOut }
  private enum Timing {
    static let timeout: Duration = .seconds(10)
    static let pollInterval: Duration = .milliseconds(1)
  }

  private static let channelIndex: UInt8 = 0
  private static let channelSecret = Data(repeating: 0xCD, count: 16)
  private static let originalTimestamp: UInt32 = 1_700_000_000
  private static let pendingSequence = 17
  private static let pendingAttempts = 3
  private static let selectedTailMultiAcks: UInt8 = 1
  private static let canonicalPublicKey = Data(repeating: 0xD5, count: ProtocolLimits.publicKeySize)
  private static let privateKey = "7065e18fd9fabb70c1ed90dca19907de698c88b709ea146eafd93d9b830c7b60"
    + "c4681193c79bbc39945ba8064104bb618f8fd7a84a0af6f57033d6e8ddcd6471"
}
