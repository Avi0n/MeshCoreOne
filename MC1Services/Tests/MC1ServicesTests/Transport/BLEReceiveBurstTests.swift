import CommonCrypto
@preconcurrency import CoreBluetooth
import CryptoKit
import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("BLE RX log burst delivery", .timeLimit(.minutes(1)))
@MainActor
struct BLEReceiveBurstTests {
  private static let messageCount = 10
  private static let paths: [Data] = [Data([0x11]), Data([0x22]), Data([0x33])]
  private static let timestamp: UInt32 = 1_700_000_000
  private static let secret = Data(repeating: 0x42, count: ChannelCrypto.keySize)
  private static let snr: Double = 8
  private static let rssi = -70

  @Test
  func `thirty native notifications preserve every RX entry and outgoing repeat`() async throws {
    let native = BLETransportHarness()
    let machine = BLEStateMachine(centralManager: native.central)
    let bridge = await machine.delegateHandler
    native.enableAutomaticCallbacks(bridge, onWrite: BLETransportHarness.emptyRadioFrames)
    let adapter = iOSBLETransport(stateMachine: machine)
    await adapter.setDeviceID(native.deviceID)
    let session = MeshCoreSession(transport: adapter)
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    let repeats = HeardRepeatsService(dataStore: store)
    let service = RxLogService(session: session, dataStore: store, heardRepeatsService: repeats)
    let messages = (1...Self.messageCount).map { index in
      MessageDTO.testChannelMessage(radioID: radioID, text: "test\(index)", timestamp: Self.timestamp)
    }

    do {
      try await store.saveChannel(.testChannel(radioID: radioID, secret: Self.secret))
      for message in messages {
        try await store.saveMessage(message)
      }
      await repeats.configure(radioID: radioID)
      try await session.start()
      try await waitUntil("the appStart subscription should finish") {
        await session.subscriberCountForTest == 0
      }
      await service.startEventMonitoring(radioID: radioID)
      try await waitUntil("RX log monitoring should subscribe before the burst") {
        await session.subscriberCountForTest == 1
      }
      let before = try #require(bridge.ingressDiagnostics)
      var expectedPayloads: Set<Data> = []

      // Reuse one characteristic with no suspension between callbacks, as the serial BLE queue does.
      for message in messages {
        native.rx.value = Data([ResponseCode.ok.rawValue])
        bridge.peripheral(native.peripheral, didUpdateValueFor: native.rx, error: nil)
        for path in Self.paths {
          let frame = try Self.rxFrame(message: message, path: path)
          expectedPayloads.insert(Data(frame.dropFirst(3)))
          native.rx.value = frame
          bridge.peripheral(native.peripheral, didUpdateValueFor: native.rx, error: nil)
        }
      }

      let expectedEntryCount = Self.messageCount * Self.paths.count
      let lastID = try #require(messages.last?.id)
      try await waitUntil("all burst entries and repeats should reach persistence") {
        let count = try await store.fetchRxLogEntries(radioID: radioID).count
        let last = try await store.fetchMessage(id: lastID)
        return count == expectedEntryCount && last?.heardRepeats == Self.paths.count
      }

      let entries = try await store.fetchRxLogEntries(radioID: radioID)
      #expect(entries.count == expectedEntryCount)
      #expect(Set(entries.map(\.rawPayload)) == expectedPayloads)
      #expect(entries.allSatisfy { $0.decryptStatus == .success && $0.snr == Self.snr && $0.rssi == Self.rssi })
      for message in messages {
        let stored = try #require(try await store.fetchMessage(id: message.id))
        let arrivals = try await store.fetchMessageRepeats(messageID: message.id)
        #expect(stored.heardRepeats == Self.paths.count)
        #expect(arrivals.map(\.pathNodes) == Self.paths)
      }
      let after = try #require(bridge.ingressDiagnostics)
      #expect(after.acceptedFrames - before.acceptedFrames == expectedEntryCount + Self.messageCount)
      #expect(after.droppedFrames == 0)
      #expect(after.rejectedFrames == 0)
      #expect(after.terminatedFrames == 0)
    } catch {
      await service.stopEventMonitoring()
      await session.stop()
      await machine.shutdown()
      throw error
    }
    await service.stopEventMonitoring()
    await session.stop()
    await machine.shutdown()
  }

  @Test(arguments: [false, true])
  func `channel sends retain RX notifications during command replies`(restoreScope: Bool) async throws {
    let native = BLETransportHarness()
    let machine = BLEStateMachine(
      writePacingDelay: DevicePlatform.nrf52.recommendedWritePacing, centralManager: native.central
    )
    let bridge = await machine.delegateHandler
    let adapter = iOSBLETransport(stateMachine: machine)
    await adapter.setDeviceID(native.deviceID)
    let session = MeshCoreSession(transport: adapter)
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    let repeats = HeardRepeatsService(dataStore: store)
    let service = RxLogService(session: session, dataStore: store, heardRepeatsService: repeats)
    let sender = MessageService(session: session, dataStore: store, contactService: nil)
    let messages = (1...Self.messageCount).map { index in
      MessageDTO.testChannelMessage(
        radioID: radioID, text: "test\(index)", timestamp: Self.timestamp, status: .pending
      )
    }
    var responses: [Data: [Data]] = [:]
    var expectedPayloads: Set<Data> = []
    for message in messages {
      let arrivals = try Self.paths.map { try Self.rxFrame(message: message, path: $0) }
      expectedPayloads.formUnion(arrivals.map { Data($0.dropFirst(3)) })
      let command = PacketBuilder.sendChannelMessage(
        channel: 0, text: message.text, timestamp: Date(timeIntervalSince1970: TimeInterval(message.timestamp))
      )
      responses[command] = [arrivals[0], Data([ResponseCode.ok.rawValue]), arrivals[1], arrivals[2]]
    }
    let responseFrames = responses
    native.enableAutomaticCallbacks(bridge) { command in
      responseFrames[command] ?? BLETransportHarness.emptyRadioFrames(for: command)
    }

    do {
      try await store.saveChannel(.testChannel(
        radioID: radioID, secret: Self.secret, floodScope: restoreScope ? .region("#test") : .inherit
      ))
      for message in messages {
        try await store.saveMessage(message)
      }
      await repeats.configure(radioID: radioID)
      try await session.start()
      try await waitUntil("the appStart subscription should finish") {
        await session.subscriberCountForTest == 0
      }
      await service.startEventMonitoring(radioID: radioID)
      try await waitUntil("RX log monitoring should subscribe before sending") {
        await session.subscriberCountForTest == 1
      }
      let before = try #require(bridge.ingressDiagnostics)

      try await withThrowingTaskGroup(of: Void.self) { group in
        for message in messages {
          group.addTask {
            try await sender.sendPendingChannelMessage(messageID: message.id)
          }
        }
        try await group.waitForAll()
      }
      try await waitUntil("all sent echoes should reach persistence") {
        for message in messages {
          guard try await store.fetchMessage(id: message.id)?.heardRepeats == Self.paths.count else { return false }
        }
        return true
      }
      let entries = try await store.fetchRxLogEntries(radioID: radioID)
      #expect(entries.count == Self.messageCount * Self.paths.count)
      #expect(Set(entries.map(\.rawPayload)) == expectedPayloads)
      for message in messages {
        let stored = try #require(try await store.fetchMessage(id: message.id))
        #expect(stored.status == .sent)
        #expect(stored.heardRepeats == Self.paths.count)
        #expect(try await store.fetchMessageRepeats(messageID: message.id).map(\.pathNodes) == Self.paths)
      }
      let commandsPerMessage = restoreScope ? 3 : 2
      #expect(native.writePayloads.count == 1 + Self.messageCount * commandsPerMessage)
      #expect(native.subscriptionCount == 1)
      #expect(await session.subscriberCountForTest == 1)
      let after = try #require(bridge.ingressDiagnostics)
      #expect(after.streamSequence == before.streamSequence)
      #expect(after.acceptedFrames - before.acceptedFrames == Self.messageCount * (Self.paths.count + commandsPerMessage))
      #expect(after.droppedFrames == 0)
      #expect(after.rejectedFrames == 0)
      #expect(after.terminatedFrames == 0)
    } catch {
      await service.stopEventMonitoring()
      await session.stop()
      await machine.shutdown()
      throw error
    }
    await service.stopEventMonitoring()
    await session.stop()
    await machine.shutdown()
  }

  private static func rxFrame(message: MessageDTO, path: Data) throws -> Data {
    var plaintext = withUnsafeBytes(of: message.timestamp.littleEndian) { Data($0) }
    plaintext.append(TextType.plain.rawValue)
    plaintext.append(contentsOf: "Test: \(message.text)".utf8)
    let blockSize = kCCBlockSizeAES128
    let paddedCount = (plaintext.count + blockSize - 1) / blockSize * blockSize
    plaintext.append(Data(count: paddedCount - plaintext.count))

    var ciphertext = Data(count: plaintext.count)
    var encryptedCount: size_t = 0
    let status = ciphertext.withUnsafeMutableBytes { output in
      plaintext.withUnsafeBytes { input in
        secret.withUnsafeBytes { key in
          CCCrypt(
            CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionECBMode),
            key.baseAddress, kCCKeySizeAES128, nil, input.baseAddress, plaintext.count,
            output.baseAddress, plaintext.count, &encryptedCount
          )
        }
      }
    }
    try #require(status == kCCSuccess)
    try #require(encryptedCount == ciphertext.count)
    let mac = HMAC<SHA256>.authenticationCode(for: ciphertext, using: SymmetricKey(data: secret))
    let header = (PayloadType.groupText.rawValue << 2) | RouteType.flood.rawValue
    var frame = Data([
      ResponseCode.logData.rawValue, UInt8(Int(snr * 4)), UInt8(bitPattern: Int8(rssi)),
      header, UInt8(path.count)
    ])
    frame.append(path)
    frame.append(contentsOf: SHA256.hash(data: secret).prefix(1))
    frame.append(contentsOf: mac.prefix(ChannelCrypto.macSize))
    frame.append(ciphertext)
    return frame
  }
}
