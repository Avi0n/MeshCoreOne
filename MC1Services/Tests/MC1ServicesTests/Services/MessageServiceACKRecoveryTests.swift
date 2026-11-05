import Foundation
@testable import MC1Services
@testable import MeshCore
import SwiftData
import Testing

@Suite("MessageService ACK recovery", .serialized)
@MainActor
struct MessageServiceACKRecoveryTests {
  private nonisolated static let senderKey = Data(repeating: 0x41, count: 32)

  @Test(arguments: [false, true])
  func `exhausted send survives a cold store and accepts an earlier attempt ACK without replay`(
    firmwareCode: Bool
  ) async throws {
    let previousLogBuffer = DebugLogBuffer.shared
    defer { DebugLogBuffer.shared = previousLogBuffer }
    let storeURL = FileManager.default.temporaryDirectory
      .appendingPathComponent("ack-recovery-\(UUID().uuidString).store")
    defer { removeStoreFiles(at: storeURL) }
    let originalStore = try makeDiskStore(at: storeURL)
    let radioID = UUID()
    try await originalStore.saveDevice(.testDevice(radioID: radioID, publicKey: Self.senderKey))
    let contact = ContactDTO.testContact(radioID: radioID, publicKey: Data(repeating: 0x42, count: 32))
    try await originalStore.saveContact(contact)
    let original = try await Connection.open(store: originalStore, radioID: radioID)
    let message = try await original.services.messageService.createPendingMessage(text: "ACK recovery", to: contact)
    try await original.services.chatSendQueueService.enqueueDM(
      DirectMessageEnvelope(messageID: message.id, contactID: contact.id)
    )
    await original.services.chatSendQueueService.awaitDrainCompletion()
    let exhausted = try #require(await originalStore.fetchMessage(id: message.id))
    #expect(exhausted.status == .sent)
    #expect(await original.radio.sendCount == 8)
    #expect(await original.services.messageService.pendingAckCount == 1)
    #expect(try await originalStore.fetchPendingSends(radioID: radioID).count == 1,
            "Accepted sends need a durable ACK owner after the send queue finishes")
    let owner = try #require(await originalStore.fetchPendingSends(radioID: radioID).first)
    #expect(owner.ackSentAt != nil)
    #expect(owner.ackDeadline != nil)
    #expect(owner.attemptCount == 1)
    let code = try #require(await original.radio.firstCode(firmware: firmwareCode))
    #expect(owner.ackCodes?.contains(code) == true)
    await original.close()

    let restoredStore = try makeDiskStore(at: storeURL)
    let persisted = try #require(await restoredStore.fetchPendingSends(radioID: radioID).first)
    #expect(persisted.ackCodes == owner.ackCodes)
    #expect(persisted.ackSentAt == owner.ackSentAt)
    #expect(persisted.ackDeadline == owner.ackDeadline)
    let restored = try await Connection.open(store: restoredStore, radioID: radioID)
    await restored.services.chatSendQueueService.awaitDrainCompletion()
    #expect(await restored.radio.sendCount == 0, "ACK recovery must not retransmit the message")
    await restored.services.messageService.handleAcknowledgement(code: code, tripTime: 123)
    let delivered = try #require(await restoredStore.fetchMessage(id: message.id))
    #expect(delivered.status == .delivered)
    #expect(delivered.roundTripTime == 123)
    #expect(try await restoredStore.fetchPendingSends(radioID: radioID).isEmpty)
    await restored.close()
  }

  @Test
  func `hydrated DM waits for every live restored ACK owner`() async throws {
    let previousLogBuffer = DebugLogBuffer.shared
    defer { DebugLogBuffer.shared = previousLogBuffer }
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    try await store.saveDevice(.testDevice(radioID: radioID, publicKey: Self.senderKey))
    let contact = ContactDTO.testContact(radioID: radioID)
    try await store.saveContact(contact)
    let sentAt = Date()
    let deadline = sentAt.addingTimeInterval(60)
    let codes = [Data([1, 2, 3, 4]), Data([5, 6, 7, 8])]
    var restoredMessageIDs: [UUID] = []
    for code in codes {
      let message = MessageDTO.testDirectMessage(radioID: radioID, contactID: contact.id, status: .pending)
      try await store.saveMessage(message)
      let owner = PendingSendDTO(
        envelope: DirectMessageEnvelope(messageID: message.id, contactID: contact.id),
        radioID: radioID, ackCodes: [code], ackSentAt: sentAt, ackDeadline: deadline
      )
      try #require(await store.savePendingAcknowledgement(owner, replacingPendingSendID: nil))
      restoredMessageIDs.append(message.id)
    }
    let queued = MessageDTO.testDirectMessage(radioID: radioID, contactID: contact.id, status: .pending)
    try await store.saveMessage(queued)
    let row = PendingSendDTO(
      envelope: DirectMessageEnvelope(messageID: queued.id, contactID: contact.id), radioID: radioID
    )
    _ = try await store.insertPendingSendAssigningSequence(row)

    let successor = try await Connection.open(store: store, radioID: radioID)
    defer { Task { await successor.close() } }
    try await waitUntil("the hydrated message reaches admission or is sent") {
      if await successor.services.plainTextSendGate.waiterCountForTesting == 1 { return true }
      return await successor.radio.sendCount > 0
    }
    #expect(await successor.radio.sendCount == 0)
    await successor.services.messageService.handleAcknowledgement(code: codes[0], tripTime: 100)
    #expect(try await store.fetchMessage(id: restoredMessageIDs[0])?.status == .delivered)
    #expect(await successor.services.plainTextSendGate.waiterCountForTesting == 1)
    #expect(await successor.radio.sendCount == 0)

    await successor.services.messageService.handleAcknowledgement(code: codes[1], tripTime: 100)
    #expect(try await store.fetchMessage(id: restoredMessageIDs[1])?.status == .delivered)
    try await waitUntil("the queued message sends after both ACKs") { await successor.radio.sendCount > 0 }
    await successor.services.chatSendQueueService.awaitDrainCompletion()
    #expect(await successor.radio.sendCount == 8)
  }

  @Test(arguments: [false, true])
  func `held inline owner save blocks teardown and the successor delivers it without another send`(beforeOwnerSave: Bool) async throws {
    let previousLogBuffer = DebugLogBuffer.shared
    defer { DebugLogBuffer.shared = previousLogBuffer }
    let held = try await holdInlineSave(text: "Held reply", beforeOwnerSave: beforeOwnerSave)
    let code = try #require(await held.original.radio.firstCode(firmware: true))
    #expect(await held.successor.services.messageService.pendingAckCount == 1)
    await held.successor.services.chatSendQueueService.awaitDrainCompletion()
    #expect(await held.successor.radio.sendCount == 0)
    await held.successor.services.messageService.handleAcknowledgement(code: code, tripTime: 321)
    #expect(try await held.store.fetchMessage(id: held.messageID)?.status == .delivered)
    #expect(try await held.store.fetchMessage(id: held.messageID)?.roundTripTime == 321)
    #expect(try await held.store.fetchPendingSends(radioID: held.radioID).isEmpty)
    await held.successor.close()
  }

  @Test(arguments: [false, true])
  func `held inline owner save still expires on the successor without another send`(beforeOwnerSave: Bool) async throws {
    let previousLogBuffer = DebugLogBuffer.shared
    defer { DebugLogBuffer.shared = previousLogBuffer }
    let held = try await holdInlineSave(
      text: "Held expiry", beforeOwnerSave: beforeOwnerSave, messageConfig: MessageServiceConfig(ackGiveUpWindow: 0)
    )
    let row = try #require(await held.store.fetchPendingSends(radioID: held.radioID).first)
    let deadline = try #require(row.ackDeadline)
    try await waitUntil("stored ACK deadline is in the past") { Date() > deadline }
    #expect(await held.successor.services.messageService.pendingAckCount == 1)
    await held.successor.services.chatSendQueueService.awaitDrainCompletion()
    #expect(await held.successor.radio.sendCount == 0)
    try await held.successor.services.messageService.checkExpiredAcks()
    #expect(try await held.store.fetchMessage(id: held.messageID)?.status == .failed)
    #expect(try await held.store.fetchPendingSends(radioID: held.radioID).isEmpty)
    #expect(await held.successor.services.messageService.pendingAckCount == 0)
    await held.successor.close()
  }

  @Test
  func `held queued exhaustion save leaves the converted owner for the successor`() async throws {
    let previousLogBuffer = DebugLogBuffer.shared
    defer { DebugLogBuffer.shared = previousLogBuffer }
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    try await store.saveDevice(.testDevice(radioID: radioID, publicKey: Self.senderKey))
    let contact = ContactDTO.testContact(radioID: radioID, publicKey: Data(repeating: 0x42, count: 32))
    try await store.saveContact(contact)
    let original = try await Connection.open(store: store, radioID: radioID)
    await original.services.messageService.stopAckExpiryChecking()
    let message = try await original.services.messageService.createPendingMessage(text: "Held queue", to: contact)
    let gate = SuspendGate()
    await original.services.messageService.setAcknowledgementSaveGateForTesting {
      await gate.park()
    }
    try await original.services.chatSendQueueService.enqueueDM(
      DirectMessageEnvelope(messageID: message.id, contactID: contact.id)
    )
    try await waitUntil("queued owner save is held") { gate.isParked }
    #expect(await original.radio.sendCount == 8)
    let successor = try await successorAfterHeldSave(
      original: original, store: store, radioID: radioID, gate: gate
    )
    await successor.services.messageService.stopAckExpiryChecking()
    let row = try #require(await store.fetchPendingSends(radioID: radioID).first)
    #expect(row.messageID == message.id)
    #expect(row.ackDeadline != nil)
    #expect(row.ackSentAt != nil)
    #expect(row.attemptCount == 1)
    #expect(await successor.services.messageService.pendingAckForTest(message.id)?.pendingSendID == row.id)
    await successor.services.chatSendQueueService.awaitDrainCompletion()
    #expect(await successor.radio.sendCount == 0)
    await successor.close()
  }

  private func makeDiskStore(at url: URL) throws -> PersistenceStore {
    let configuration = ModelConfiguration(schema: PersistenceStore.schema, url: url)
    return try PersistenceStore(modelContainer: ModelContainer(
      for: PersistenceStore.schema, configurations: [configuration]
    ))
  }

  private func removeStoreFiles(at url: URL) {
    for suffix in ["", "-shm", "-wal"] {
      try? FileManager.default.removeItem(atPath: url.path + suffix)
    }
  }

  private struct HeldInlineSave {
    let store: PersistenceStore
    let radioID: UUID
    let original: Connection
    let successor: Connection
    let messageID: UUID
  }

  private func holdInlineSave(
    text: String, beforeOwnerSave: Bool, messageConfig: MessageServiceConfig = .default
  ) async throws -> HeldInlineSave {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    try await store.saveDevice(.testDevice(radioID: radioID, publicKey: Self.senderKey))
    let contact = ContactDTO.testContact(radioID: radioID)
    try await store.saveContact(contact)
    let original = try await Connection.open(store: store, radioID: radioID, messageConfig: messageConfig)
    await original.services.messageService.stopAckExpiryChecking()
    let gate = SuspendGate()
    if beforeOwnerSave {
      await original.services.messageService.setInlineSendAcceptedGateForTesting { await gate.park() }
    } else {
      await original.services.messageService.setAcknowledgementSaveGateForTesting { await gate.park() }
    }
    let send = Task {
      try await original.services.messageService.sendDirectMessage(text: text, to: contact)
    }
    try await waitUntil("inline owner save is held") { gate.isParked }
    #expect(await original.radio.sendCount == 1)
    let messageID = try #require(
      try await store.fetchMessages(contactID: contact.id, limit: 1, offset: 0).first?.id
    )
    let successor = try await successorAfterHeldSave(
      original: original, store: store, radioID: radioID, gate: gate, messageConfig: messageConfig
    )
    let message = try await send.value
    #expect(message.status == .sent)
    await successor.services.messageService.stopAckExpiryChecking()
    let row = try #require(await store.fetchPendingSends(radioID: radioID).first)
    #expect(row.attemptCount == 1)
    #expect(row.ackDeadline != nil)
    return HeldInlineSave(
      store: store, radioID: radioID, original: original, successor: successor, messageID: messageID
    )
  }

  /// Starts teardown while the owner save is held, then opens the successor only after that teardown returns.
  private func successorAfterHeldSave(
    original: Connection,
    store: PersistenceStore,
    radioID: UUID,
    gate: SuspendGate,
    messageConfig: MessageServiceConfig = .default
  ) async throws -> Connection {
    var handoffFinished = false
    let handoff = Task {
      await original.close()
      let successor = try await Connection.open(store: store, radioID: radioID, messageConfig: messageConfig)
      handoffFinished = true
      return successor
    }
    defer { gate.release() }
    try await waitUntil("teardown waits for the owner save") {
      let service = original.services.messageService
      let acknowledgementWaiters = await service.acknowledgementSaveWaiterCountForTesting
      let inlineWaiters = await service.inlineSendWaiterCountForTesting
      return acknowledgementWaiters + inlineWaiters > 0 || handoffFinished
    }
    #expect(!handoffFinished, "The successor must not restore before the accepted inline send persists its owner")
    let owners = try await store.fetchPendingSends(radioID: radioID)
    #expect(owners.allSatisfy { $0.ackDeadline == nil })
    gate.release()
    return try await handoff.value
  }

  @MainActor
  private final class Connection {
    let session: MeshCoreSession
    let services: ServiceContainer
    let radio: Radio
    private let responder: Task<Void, Never>

    private init(session: MeshCoreSession, services: ServiceContainer, radio: Radio, responder: Task<Void, Never>) {
      self.session = session
      self.services = services
      self.radio = radio
      self.responder = responder
    }

    static func open(
      store: PersistenceStore, radioID: UUID, messageConfig: MessageServiceConfig = .default
    ) async throws -> Connection {
      let transport = MockTransport()
      let radio = Radio(transport: transport)
      let responder = Task { await radio.run() }
      let session = MeshCoreSession(transport: transport)
      try await session.start()
      let services = ServiceContainer(
        session: session, dataStore: store, radioID: radioID, messageConfig: messageConfig
      )
      try await services.warmUp()
      await services.chatSendQueueService.hydrate()
      await services.startEventMonitoring(radioID: radioID, enableAutoFetch: false, enableAdvertisementMonitoring: false)
      return Connection(session: session, services: services, radio: radio, responder: responder)
    }

    func close() async {
      await services.tearDown()
      await session.stop()
      responder.cancel()
      await responder.value
    }
  }

  private actor Radio {
    let transport: MockTransport
    private var processedCount = 0
    private(set) var sendCount = 0
    private var predictedCodes: [Data] = []
    private var firmwareCodes: [Data] = []

    init(transport: MockTransport) {
      self.transport = transport
    }

    func firstCode(firmware: Bool) -> Data? {
      firmware ? firmwareCodes.first : predictedCodes.first
    }

    func run() async {
      while !Task.isCancelled {
        let frames = await transport.sentData
        while processedCount < frames.count {
          let frame = frames[processedCount]
          processedCount += 1
          switch frame.first {
          case CommandCode.appStart.rawValue:
            await transport.simulateReceive(selfInfoPacket())
          case CommandCode.sendMessage.rawValue:
            let timestamp = frame.readUInt32LE(at: 3)
            let text = String(decoding: frame.dropFirst(13), as: UTF8.self)
            predictedCodes.append(AckCodeBuilder.expectedAck(
              timestamp: timestamp, attempt: frame[2], text: text, senderPublicKey: MessageServiceACKRecoveryTests.senderKey
            ))
            let firmwareCode = Data([0xDE, 0xAD, 0xBE, UInt8(sendCount)])
            firmwareCodes.append(firmwareCode)
            sendCount += 1
            var response = Data([ResponseCode.messageSent.rawValue, 0])
            response.append(firmwareCode)
            response.append(Data(repeating: 0, count: 4))
            await transport.simulateReceive(response)
          case CommandCode.resetPath.rawValue:
            await transport.simulateOK()
          default:
            await transport.simulateError(code: 2)
          }
        }
        try? await Task.sleep(for: .milliseconds(1))
      }
    }

    private func selfInfoPacket() -> Data {
      var response = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
      response.append(MessageServiceACKRecoveryTests.senderKey)
      response.append(Data(repeating: 0, count: 12))
      response.append(contentsOf: withUnsafeBytes(of: UInt32(869_525).littleEndian) { Array($0) })
      response.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) })
      response.append(contentsOf: [11, 5])
      return response
    }
  }
}

private final class SuspendGate: @unchecked Sendable {
  private let lock = NSLock()
  private var continuation: CheckedContinuation<Void, Never>?
  private var parked = false

  var isParked: Bool {
    lock.lock()
    defer { lock.unlock() }
    return parked
  }

  func park() async {
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      lock.lock()
      parked = true
      self.continuation = continuation
      lock.unlock()
    }
  }

  func release() {
    lock.lock()
    let continuation = continuation
    self.continuation = nil
    parked = false
    lock.unlock()
    continuation?.resume()
  }
}
