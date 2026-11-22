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
  func `group datagram decrypt exposes the data field and a nil sender timestamp`() async throws {
    let radioID = UUID()
    let secret = Data(repeating: 0x42, count: 16)
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)
    try await dataStore.saveChannel(ChannelDTO.testChannel(
      radioID: radioID, index: 0, name: "Public", secret: secret
    ))
    await service.startEventMonitoring(radioID: radioID)
    defer { Task { await service.stopEventMonitoring() } }
    await service.updateChannels(secrets: [0: secret], names: [0: "Public"])

    let blob = Data([0x13, 0x01, 0xAA])
    await service.process(Self.makeParsed(
      payloadType: .groupData,
      packetPayload: Self.encryptedGroupDatagram(dataType: 0x0120, data: blob, secret: secret)
    ))

    let stored = try #require(try await dataStore.fetchRxLogEntries(radioID: radioID).first)
    #expect(stored.channelIndex == 0)
    #expect(stored.senderTimestamp == nil)
    #expect(stored.decryptStatus == .success)
    #expect(stored.datagramDataType == nil, "the data field is not a stored column")

    let decoded = await service.decryptEntry(stored)
    #expect(decoded.datagramDataType == 0x0120)
    #expect(decoded.datagramData == blob)
    #expect(decoded.senderTimestamp == nil)
    #expect(decoded.decodedText == nil)
  }

  @Test
  func `a truncated or bad MAC datagram does not set a channel or sender timestamp`() async throws {
    let radioID = UUID()
    let secret = Data(repeating: 0x42, count: 16)
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)
    try await dataStore.saveChannel(ChannelDTO.testChannel(
      radioID: radioID, index: 0, name: "Public", secret: secret
    ))
    await service.startEventMonitoring(radioID: radioID)
    defer { Task { await service.stopEventMonitoring() } }
    await service.updateChannels(secrets: [0: secret], names: [0: "Public"])

    await service.process(Self.makeParsed(
      payloadType: .groupData,
      packetPayload: Data(repeating: 0, count: 8)
    ))
    var badMAC = Self.encryptedGroupDatagram(dataType: 0x0120, data: Data([0x01]), secret: secret)
    badMAC[1] ^= 0xFF
    badMAC[2] ^= 0xFF
    await service.process(Self.makeParsed(payloadType: .groupData, packetPayload: badMAC))
    var overLong = Data(count: 16)
    overLong[0] = 0x20
    overLong[1] = 0x01
    overLong[2] = 14
    await service.process(Self.makeParsed(
      payloadType: .groupData,
      packetPayload: Self.encryptedRawPlaintext(overLong, secret: secret)
    ))

    let stored = try await dataStore.fetchRxLogEntries(radioID: radioID)
    #expect(stored.count == 3)
    for entry in stored {
      #expect(entry.channelIndex == nil)
      #expect(entry.senderTimestamp == nil)
      #expect(entry.decryptStatus != .success)
      let decoded = await service.decryptEntry(entry)
      #expect(decoded.datagramDataType == nil)
      #expect(decoded.senderTimestamp == nil)
    }
  }

  @Test
  func `a group datagram saved with no matching key gains its channel and keeps a nil sender timestamp`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let session = MeshCoreSession(transport: MockTransport())
    let service = RxLogService(session: session, dataStore: dataStore, heardRepeatsService: nil)

    let secret = Data(repeating: 0x42, count: 16)
    try await dataStore.saveChannel(ChannelDTO.testChannel(
      radioID: radioID, index: 3, name: "Three", secret: secret
    ))
    await service.startEventMonitoring(radioID: radioID)
    defer { Task { await service.stopEventMonitoring() } }

    let blob = Data([0x13, 0x04, 0x01])
    let entry = RxLogEntryDTO(
      radioID: radioID,
      from: Self.makeParsed(
        payloadType: .groupData,
        packetPayload: Self.encryptedGroupDatagram(dataType: 0x0120, data: blob, secret: secret)
      ),
      decryptStatus: .noMatchingKey
    )
    try await dataStore.saveRxLogEntry(entry)

    await service.updateChannels(secrets: [3: secret], names: [3: "Three"])

    let updated = try #require(try await dataStore.fetchRxLogEntries(radioID: radioID).first { $0.id == entry.id })
    #expect(updated.channelIndex == 3)
    #expect(updated.channelName == "Three")
    #expect(updated.senderTimestamp == nil)
    #expect(updated.decryptStatus == .success)
    let decoded = await service.decryptEntry(updated)
    #expect(decoded.datagramDataType == 0x0120)
    #expect(decoded.datagramData == blob)
    #expect(decoded.senderTimestamp == nil)
    #expect(decoded.decodedText == nil)
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
    return Data([0x00]) + mac + ciphertext.prefix(encryptedCount)
  }

  private static func encryptedGroupDatagram(dataType: UInt16, data: Data, secret: Data) -> Data {
    var plaintext = withUnsafeBytes(of: dataType.littleEndian) { Data($0) }
    plaintext.append(UInt8(data.count))
    plaintext.append(data)
    return encryptedRawPlaintext(plaintext, secret: secret)
  }

  private static func encryptedRawPlaintext(_ plaintext: Data, secret: Data) -> Data {
    var padded = plaintext
    let blockSize = kCCBlockSizeAES128
    let paddedCount = (padded.count + blockSize - 1) / blockSize * blockSize
    if padded.count < paddedCount {
      padded.append(Data(count: paddedCount - padded.count))
    }
    var ciphertext = Data(count: padded.count)
    var encryptedCount: size_t = 0
    let keyBytes = secret.prefix(kCCKeySizeAES128)
    let status = ciphertext.withUnsafeMutableBytes { outPtr in
      padded.withUnsafeBytes { inPtr in
        keyBytes.withUnsafeBytes { keyPtr in
          CCCrypt(
            CCOperation(kCCEncrypt),
            CCAlgorithm(kCCAlgorithmAES),
            CCOptions(kCCOptionECBMode),
            keyPtr.baseAddress, kCCKeySizeAES128,
            nil,
            inPtr.baseAddress, padded.count,
            outPtr.baseAddress, padded.count,
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
    return Data([0x00]) + mac + ciphertext.prefix(encryptedCount)
  }
}
