import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@MainActor
final class HydratedChannelSendHarness {
  static let secretA = Data(repeating: 0xAA, count: 16)
  static let secretB = Data(repeating: 0xBB, count: 16)
  let store: PersistenceStore
  let radio: ConnectionTestRadioTransport
  let session: MeshCoreSession
  let channelService: ChannelService
  let queue: ChatSendQueueService
  let message: MessageDTO
  let radioID: UUID

  private init(
    store: PersistenceStore, radio: ConnectionTestRadioTransport, session: MeshCoreSession,
    channelService: ChannelService, queue: ChatSendQueueService, message: MessageDTO, radioID: UUID
  ) {
    self.store = store
    self.radio = radio
    self.session = session
    self.channelService = channelService
    self.queue = queue
    self.message = message
    self.radioID = radioID
  }

  static func make() async throws -> HydratedChannelSendHarness {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radio = ConnectionTestRadioTransport()
    let session = MeshCoreSession(
      transport: radio, configuration: SessionConfiguration(defaultTimeout: 2)
    )
    try await session.start()
    let saved = DeviceDTO.testDevice(publicKey: radio.publicKey)
    try await store.saveDevice(saved)
    let message = try await seed(store: store, radioID: saved.radioID)
    await radio.setChannel(index: 0, name: "Original", secret: secretA)
    let channelService = ChannelService(session: session, dataStore: store, rxLogService: nil)
    let queue = ChatSendQueueService(
      radioID: saved.radioID, dataStore: store,
      messageService: MessageService(session: session, dataStore: store, contactService: nil),
      channelService: channelService,
      reactionService: ReactionService()
    )
    return HydratedChannelSendHarness(
      store: store, radio: radio, session: session, channelService: channelService,
      queue: queue, message: message, radioID: saved.radioID
    )
  }

  static func seed(store: PersistenceStore, radioID: UUID) async throws -> MessageDTO {
    try await store.saveChannel(ChannelDTO.testChannel(radioID: radioID, secret: secretA))
    let message = MessageDTO.testChannelMessage(radioID: radioID, text: "Only for channel A", status: .pending)
    try await store.saveMessage(message)
    let envelope = ChannelMessageEnvelope(
      messageID: message.id, channelIndex: 0, isResend: false,
      messageText: message.text, messageTimestamp: message.timestamp, localNodeName: nil
    )
    _ = try await store.insertPendingSendAssigningSequence(PendingSendDTO(envelope: envelope, radioID: radioID))
    return message
  }

  var sendFrames: [Data] {
    get async { await radio.sentData.filter { $0.first == CommandCode.sendChannelMessage.rawValue } }
  }

  func close() async {
    await radio.releaseAllGates()
    await queue.shutdown()
    await session.stop()
  }
}
