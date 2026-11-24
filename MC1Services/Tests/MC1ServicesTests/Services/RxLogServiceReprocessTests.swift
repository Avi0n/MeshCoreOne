import CommonCrypto
import CryptoKit
import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("RxLogService reprocessing and streaming")
struct RxLogServiceReprocessTests {
  private actor EntryCollector {
    var entries: [RxLogEntryDTO] = []
    func record(_ entry: RxLogEntryDTO) {
      entries.append(entry)
    }

    var count: Int {
      entries.count
    }
  }

  @Test
  func `late-decrypted channel entries keep the matching channel index and name`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)

    let channelZeroSecret = Data(repeating: 0x99, count: 16)
    let channelThreeSecret = Data(repeating: 0x42, count: 16)

    // Seed the channels in the store first so the event monitor's secret
    // load cannot interleave with different values than updateChannels sets.
    try await dataStore.saveChannel(ChannelDTO.testChannel(
      radioID: radioID, index: 0, name: "Public", secret: channelZeroSecret
    ))
    try await dataStore.saveChannel(ChannelDTO.testChannel(
      radioID: radioID, index: 3, name: "Three", secret: channelThreeSecret
    ))

    await service.startEventMonitoring(radioID: radioID)
    defer { Task { await service.stopEventMonitoring() } }

    let senderTimestamp: UInt32 = 1_700_000_000
    let payload = Self.encryptedChannelPayload(
      timestamp: senderTimestamp,
      text: "hello",
      secret: channelThreeSecret
    )
    let entry = RxLogEntryDTO(
      radioID: radioID,
      from: Self.makeParsed(payloadType: .groupText, packetPayload: payload),
      decryptStatus: .noMatchingKey
    )
    try await dataStore.saveRxLogEntry(entry)

    await service.updateChannels(
      secrets: [0: channelZeroSecret, 3: channelThreeSecret],
      names: [0: "Public", 3: "Three"]
    )

    let entries = try await dataStore.fetchRxLogEntries(radioID: radioID)
    let updated = try #require(entries.first { $0.id == entry.id })
    #expect(updated.channelIndex == 3,
            "reprocessing must record the channel whose secret matched, not nil")
    #expect(updated.channelName == "Three",
            "reprocessing must not fall back to channel 0's name")
    #expect(updated.senderTimestamp == senderTimestamp)
    #expect(updated.decryptStatus == .success)
  }

  @Test
  func `coexisting entry stream subscribers each receive every entry`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)
    await service.startEventMonitoring(radioID: radioID)
    defer { Task { await service.stopEventMonitoring() } }

    let streamA = service.entryStream()
    let streamB = service.entryStream()

    let collectorA = EntryCollector()
    let collectorB = EntryCollector()
    let taskA = Task {
      for await entry in streamA {
        await collectorA.record(entry)
      }
    }
    let taskB = Task {
      for await entry in streamB {
        await collectorB.record(entry)
      }
    }

    await service.process(Self.makeParsed(
      payloadType: .advert,
      packetPayload: Data([0x01, 0x02, 0x03, 0x04])
    ))

    try await waitUntil("the first subscriber must receive the entry") {
      await collectorA.count > 0
    }
    try await waitUntil("the second subscriber must also receive the entry") {
      await collectorB.count > 0
    }
    taskA.cancel()
    taskB.cancel()
  }

  @Test
  func `finishEntryStream ends every subscriber's iteration`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)

    let streamA = service.entryStream()
    let streamB = service.entryStream()
    let iterationA = Task {
      for await _ in streamA {}
      return true
    }
    let iterationB = Task {
      for await _ in streamB {}
      return true
    }

    service.finishEntryStream()

    #expect(await iterationA.value, "finish must end the first subscriber's loop")
    #expect(await iterationB.value, "finish must end the second subscriber's loop")
  }

  @Test
  func `process skips decryption when channelHash does not match any channel secret`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)

    let secret = Data(repeating: 0x42, count: 16)
    try await dataStore.saveChannel(ChannelDTO.testChannel(
      radioID: radioID, index: 1, name: "Ops", secret: secret
    ))
    await service.startEventMonitoring(radioID: radioID)
    defer { Task { await service.stopEventMonitoring() } }
    await service.updateChannels(secrets: [1: secret], names: [1: "Ops"])

    let validPayload = Self.encryptedChannelPayload(timestamp: 1_700_000_010, text: "Alice: hi", secret: secret)
    var mismatchedPayload = validPayload
    mismatchedPayload[0] = validPayload[0] ^ 0xFF

    await service.process(Self.makeParsed(payloadType: .groupText, packetPayload: mismatchedPayload))

    let stored = try await dataStore.fetchRxLogEntries(radioID: radioID)
    let saved = try #require(stored.first)
    #expect(saved.decryptStatus == .noMatchingKey)
    #expect(saved.channelIndex == nil)
  }

  @Test
  func `process decrypts when two channels share the same 1-byte channelHash`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)

    let secretA = Data(repeating: 0x11, count: 16)
    let targetHash = ChannelCrypto.channelHash(for: secretA)
    var candidate = Data(repeating: 0x22, count: 16)
    var counter: UInt16 = 0
    while ChannelCrypto.channelHash(for: candidate) != targetHash {
      counter &+= 1
      candidate[0] = UInt8(counter & 0xFF)
      candidate[1] = UInt8((counter >> 8) & 0xFF)
    }
    let secretB = candidate

    await service.startEventMonitoring(radioID: radioID)
    defer { Task { await service.stopEventMonitoring() } }
    await service.updateChannels(
      secrets: [0: secretA, 2: secretB],
      names: [0: "Alpha", 2: "Beta"]
    )

    let payload = Self.encryptedChannelPayload(timestamp: 1_700_000_020, text: "Bob: ping", secret: secretB)
    await service.process(Self.makeParsed(payloadType: .groupText, packetPayload: payload))

    let stored = try await dataStore.fetchRxLogEntries(radioID: radioID)
    let saved = try #require(stored.first)
    #expect(saved.decryptStatus == .success)
    #expect(saved.channelIndex == 2)
    #expect(saved.channelName == "Beta")
  }

  // MARK: - Helpers

  private static func makeParsed(payloadType: PayloadType, packetPayload: Data) -> ParsedRxLogData {
    ParsedRxLogData(
      snr: 8.0,
      rssi: -70,
      rawPayload: packetPayload,
      routeType: .flood,
      payloadType: payloadType,
      payloadVersion: 0,
      payloadTypeBits: 0,
      transportCode: nil,
      pathLength: 0,
      pathNodes: [],
      packetPayload: packetPayload
    )
  }

  /// Builds a wire-format channel payload (`[channelHash:1][MAC:2][ciphertext:N]`)
  /// that `ChannelCrypto.decrypt` accepts: AES-128 ECB ciphertext authenticated
  /// with a truncated HMAC-SHA256, mirroring the firmware's encrypt-then-MAC.
  private static func encryptedChannelPayload(timestamp: UInt32, text: String, secret: Data) -> Data {
    var plaintext = withUnsafeBytes(of: timestamp.littleEndian) { Data($0) }
    plaintext.append(0)
    plaintext.append(Data(text.utf8))
    let blockSize = kCCBlockSizeAES128
    let paddedCount = (plaintext.count + blockSize - 1) / blockSize * blockSize
    plaintext.append(Data(count: paddedCount - plaintext.count))

    var ciphertext = Data(count: plaintext.count)
    var encryptedCount: size_t = 0
    let keyBytes = secret.prefix(kCCKeySizeAES128)
    let status = ciphertext.withUnsafeMutableBytes { outPtr in
      plaintext.withUnsafeBytes { inPtr in
        keyBytes.withUnsafeBytes { keyPtr in
          CCCrypt(
            CCOperation(kCCEncrypt),
            CCAlgorithm(kCCAlgorithmAES),
            CCOptions(kCCOptionECBMode),
            keyPtr.baseAddress, kCCKeySizeAES128,
            nil,
            inPtr.baseAddress, plaintext.count,
            outPtr.baseAddress, plaintext.count,
            &encryptedCount
          )
        }
      }
    }
    precondition(status == kCCSuccess, "test fixture encryption failed")

    let mac = Data(
      HMAC<SHA256>.authenticationCode(for: ciphertext, using: SymmetricKey(data: secret))
        .prefix(ChannelCrypto.macSize)
    )
    return Data([ChannelCrypto.channelHash(for: secret)]) + mac + ciphertext.prefix(encryptedCount)
  }
}
