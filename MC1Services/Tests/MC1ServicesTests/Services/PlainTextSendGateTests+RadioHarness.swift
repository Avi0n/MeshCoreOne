import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport

extension PlainTextSendGateTests {
  @MainActor
  final class RadioHarness {
    let transport: MockTransport
    let session: MeshCoreSession
    let store: PersistenceStore
    let gate: PlainTextSendGate
    let service: MessageService
    let queue: ChatSendQueueService
    let channelService: ChannelService
    let roomService: RoomServerService
    let remoteNodeService: RemoteNodeService
    let radioID: UUID
    let script: RadioScript
    private let responder: Task<Void, Never>

    init(
      budget: DMSendBudget,
      senderKey: Data,
      ackGiveUpWindow: TimeInterval = 30,
      poolBackoff: PoolBackoffConfig = .default,
      commandTimeout: TimeInterval = 10,
      transportWaitTimeout: TimeInterval = 30,
      script: RadioScript? = nil
    ) async throws {
      let transport = MockTransport()
      let session = MeshCoreSession(transport: transport, configuration: SessionConfiguration(defaultTimeout: commandTimeout))
      let start = Task { try await session.start() }
      try await waitUntil("session should send app start") { await transport.sentData.count == 1 }
      await transport.simulateReceive(Self.selfInfo(publicKey: senderKey))
      try await start.value

      let radioID = UUID()
      let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
      try await store.saveDevice(.testDevice(radioID: radioID, publicKey: senderKey))
      let gate = PlainTextSendGate()
      let service = MessageService(
        session: session,
        dataStore: store,
        contactService: nil,
        config: MessageServiceConfig(minTimeout: 0, ackGiveUpWindow: ackGiveUpWindow, poolBackoff: poolBackoff),
        dmSendBudget: { budget },
        plainTextSendGate: gate
      )
      let channelService = ChannelService(session: session, dataStore: store, rxLogService: nil)
      let queue = ChatSendQueueService(
        radioID: radioID,
        dataStore: store,
        messageService: service,
        channelService: channelService,
        reactionService: ReactionService(),
        config: ChatSendQueueConfig(transportWaitTimeout: transportWaitTimeout),
        plainTextSendGate: gate
      )
      let remote = RemoteNodeService(
        session: session,
        dataStore: store,
        keychainService: KeychainService()
      )
      let roomService = RoomServerService(
        session: session,
        remoteNodeService: remote,
        dataStore: store,
        radioID: radioID,
        plainTextSendGate: gate
      )
      let script = script ?? RadioScript()
      self.transport = transport
      self.session = session
      self.store = store
      self.gate = gate
      self.service = service
      self.queue = queue
      self.channelService = channelService
      self.roomService = roomService
      remoteNodeService = remote
      self.radioID = radioID
      self.script = script
      responder = Task { await script.answer(transport) }
    }

    func stop() {
      responder.cancel()
      Task {
        await session.stop()
        await queue.shutdown()
        await service.stopEventMonitoring()
      }
    }

    func contact(byte: UInt8) async throws -> ContactDTO {
      let contact = ContactDTO.testContact(radioID: radioID, publicKey: Data(repeating: byte, count: 32))
      try await store.saveContact(contact)
      return contact
    }

    func sendPackets() async -> [Data] {
      await transport.sentData.filter { $0.first == CommandCode.sendMessage.rawValue }
    }

    func sendCount() async -> Int {
      await sendPackets().count
    }

    func isWaiting(afterSends expected: Int) async -> Bool {
      let sends = await sendCount()
      let waiting = await gate.waiterCountForTesting
      return sends == expected && waiting == 1
    }

    func channelCount() async -> Int {
      await transport.sentData.count { $0.first == CommandCode.sendChannelMessage.rawValue }
    }

    func resetIndex() async -> Int? {
      let packets = await transport.sentData
      guard let index = packets.firstIndex(where: { $0.first == CommandCode.resetPath.rawValue }) else { return nil }
      return packets[..<index].count { $0.first == CommandCode.sendMessage.rawValue }
    }

    func pendingRow(_ messageID: UUID) async throws -> PendingSendDTO? {
      try await store.fetchPendingSends(radioID: radioID).first { $0.messageID == messageID }
    }

    private static func selfInfo(publicKey: Data) -> Data {
      var payload = Data([1, 22, 22])
      payload.append(publicKey)
      payload.append(Data(repeating: 0, count: 8))
      payload.append(contentsOf: [0, 0, 0])
      payload.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
      payload.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
      payload.append(contentsOf: [7, 5])
      payload.append(contentsOf: "Test".utf8)
      var packet = Data([ResponseCode.selfInfo.rawValue])
      packet.append(payload)
      return packet
    }
  }
}
