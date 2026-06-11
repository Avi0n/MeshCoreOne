import Testing
import Foundation
import CommonCrypto
import CryptoKit
@testable import MC1Services
@testable import MeshCore

@Suite("RxLogService reprocessing and streaming")
struct RxLogServiceReprocessTests {

    private actor EntryCollector {
        var entries: [RxLogEntryDTO] = []
        func record(_ entry: RxLogEntryDTO) { entries.append(entry) }
        var count: Int { entries.count }
    }

    @Test("late-decrypted channel entries keep the matching channel index and name")
    func reprocessAttributesMatchingChannel() async throws {
        let radioID = UUID()
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)
        let session = MeshCoreSession(transport: MockTransport())
        let service = RxLogService(session: session, dataStore: dataStore)

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

    @Test("replacing the entry stream subscriber keeps the replacement connected")
    func replacedSubscriberKeepsReplacementConnected() async throws {
        let radioID = UUID()
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)
        let session = MeshCoreSession(transport: MockTransport())
        let service = RxLogService(session: session, dataStore: dataStore)
        await service.startEventMonitoring(radioID: radioID)
        defer { Task { await service.stopEventMonitoring() } }

        let streamA = await service.entryStream()
        let streamB = await service.entryStream()

        // The second subscription finished A; drain it so its termination
        // callback has run (it must not disconnect B).
        for await _ in streamA {}

        let collector = EntryCollector()
        let collectTask = Task {
            for await entry in streamB {
                await collector.record(entry)
            }
        }

        await service.process(Self.makeParsed(
            payloadType: .advert,
            packetPayload: Data([0x01, 0x02, 0x03, 0x04])
        ))

        try await waitUntil("the replacement subscriber must keep receiving entries") {
            await collector.count > 0
        }
        collectTask.cancel()
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
}
