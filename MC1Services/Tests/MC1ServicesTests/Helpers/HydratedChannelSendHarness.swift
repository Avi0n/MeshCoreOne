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
  let queue: ChatSendQueueService
  let message: MessageDTO
  let radioID: UUID

  private init(
    store: PersistenceStore, radio: ConnectionTestRadioTransport, session: MeshCoreSession,
    queue: ChatSendQueueService, message: MessageDTO, radioID: UUID
  ) {
    self.store = store
    self.radio = radio
    self.session = session
    self.queue = queue
    self.message = message
    self.radioID = radioID
  }

  static func make(clock: (any Clock<Duration>)? = nil) async throws -> HydratedChannelSendHarness {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radio = ConnectionTestRadioTransport()
    let session = MeshCoreSession(
      transport: radio, configuration: SessionConfiguration(defaultTimeout: 2), clock: clock
    )
    try await session.start()
    let saved = DeviceDTO.testDevice(publicKey: radio.publicKey)
    try await store.saveDevice(saved)
    let message = try await seed(store: store, radioID: saved.radioID)
    await radio.setChannel(index: 0, name: "Original", secret: secretA)
    let queue = ChatSendQueueService(
      radioID: saved.radioID, dataStore: store,
      messageService: MessageService(session: session, dataStore: store, contactService: nil),
      channelService: ChannelService(session: session, dataStore: store, rxLogService: nil),
      reactionService: ReactionService()
    )
    return HydratedChannelSendHarness(store: store, radio: radio, session: session, queue: queue, message: message, radioID: saved.radioID)
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

  static func channelPacket(secret: Data) -> Data {
    let name = Data("Original".utf8)
    return Data([ResponseCode.channelInfo.rawValue, 0]) + name + Data(repeating: 0, count: 32 - name.count) + secret
  }

  var sendFrames: [Data] {
    get async { await radio.sentData.filter { $0.first == CommandCode.sendChannelMessage.rawValue } }
  }

  func waitForValidation() async throws {
    try await waitUntil("hydrated work must reach occupancy validation or expose an unchecked send") {
      if await self.radio.heldReplyCount(for: .getChannel) == 1 { return true }
      return await !self.sendFrames.isEmpty
    }
    try #require(await radio.heldReplyCount(for: .getChannel) == 1, "A hydrated envelope reached send without validating its slot")
  }

  func close() async {
    await radio.releaseAllGates()
    await queue.shutdown()
    await session.stop()
  }
}
