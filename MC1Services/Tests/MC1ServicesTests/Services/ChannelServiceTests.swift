import Testing
import Foundation
@testable import MC1Services
@testable import MeshCore

@Suite("ChannelService Tests")
struct ChannelServiceTests {

    // MARK: - Secret Hashing Tests

    @Test("hashSecret produces 16-byte output")
    func hashSecretProduces16Bytes() {
        let secret = ChannelService.hashSecret("test passphrase")
        #expect(secret.count == ProtocolLimits.channelSecretSize)
    }

    @Test("hashSecret is deterministic")
    func hashSecretIsDeterministic() {
        let secret1 = ChannelService.hashSecret("same passphrase")
        let secret2 = ChannelService.hashSecret("same passphrase")
        #expect(secret1 == secret2)
    }

    @Test("hashSecret differs for different inputs")
    func hashSecretDiffersForDifferentInputs() {
        let secret1 = ChannelService.hashSecret("passphrase one")
        let secret2 = ChannelService.hashSecret("passphrase two")
        #expect(secret1 != secret2)
    }

    @Test("hashSecret handles empty string")
    func hashSecretHandlesEmptyString() {
        let secret = ChannelService.hashSecret("")
        #expect(secret.count == ProtocolLimits.channelSecretSize)
        #expect(secret == Data(repeating: 0, count: ProtocolLimits.channelSecretSize))
    }

    @Test("hashSecret handles unicode")
    func hashSecretHandlesUnicode() {
        let secret = ChannelService.hashSecret("🔐 secure 密码")
        #expect(secret.count == ProtocolLimits.channelSecretSize)
    }

    @Test("validateSecret accepts 16-byte secrets")
    func validateSecretAccepts16Bytes() {
        let validSecret = Data(repeating: 0xAB, count: ProtocolLimits.channelSecretSize)
        #expect(ChannelService.validateSecret(validSecret))
    }

    @Test("validateSecret rejects wrong-sized secrets")
    func validateSecretRejectsWrongSize() {
        let tooShort = Data(repeating: 0xAB, count: 15)
        let tooLong = Data(repeating: 0xAB, count: 17)
        #expect(!ChannelService.validateSecret(tooShort))
        #expect(!ChannelService.validateSecret(tooLong))
    }

    // MARK: - ChannelSyncError Tests

    @Test("ChannelSyncError timeout is retryable")
    func timeoutErrorIsRetryable() {
        let error = ChannelSyncError(index: 0, errorType: .timeout, description: "Timeout")
        #expect(error.isRetryable)
    }

    @Test("ChannelSyncError send timeout is retryable and counted separately")
    func sendTimeoutErrorIsRetryableAndCountedSeparately() {
        let error = ChannelSyncError(index: 0, errorType: .sendTimeout, description: "Send timed out")
        let result = ChannelSyncResult(channelsSynced: 0, errors: [error])

        #expect(error.isRetryable)
        #expect(result.requestTimeoutCount == 0)
        #expect(result.sendTimeoutCount == 1)
    }

    @Test("ChannelSyncError circuit breaker is not retryable")
    func circuitBreakerErrorIsNotRetryable() {
        let error = ChannelSyncError(index: 0, errorType: .circuitBreaker, description: "Circuit open")
        #expect(!error.isRetryable)
    }

    @Test("ChannelSyncError deviceError is not retryable")
    func deviceErrorIsNotRetryable() {
        let error = ChannelSyncError(index: 0, errorType: .deviceError(code: 0x02), description: "Not found")
        #expect(!error.isRetryable)
    }

    @Test("ChannelSyncError databaseError is not retryable")
    func databaseErrorIsNotRetryable() {
        let error = ChannelSyncError(index: 0, errorType: .databaseError, description: "Save failed")
        #expect(!error.isRetryable)
    }

    @Test("ChannelSyncError unknown is not retryable")
    func unknownErrorIsNotRetryable() {
        let error = ChannelSyncError(index: 0, errorType: .unknown, description: "Unknown error")
        #expect(!error.isRetryable)
    }

    // MARK: - ChannelSyncResult Tests

    @Test("ChannelSyncResult isComplete when no errors")
    func syncResultIsCompleteWithNoErrors() {
        let result = ChannelSyncResult(channelsSynced: 8, errors: [])
        #expect(result.isComplete)
    }

    @Test("ChannelSyncResult is not complete with errors")
    func syncResultIsNotCompleteWithErrors() {
        let error = ChannelSyncError(index: 3, errorType: .timeout, description: "Timeout")
        let result = ChannelSyncResult(channelsSynced: 7, errors: [error])
        #expect(!result.isComplete)
    }

    @Test("ChannelSyncResult retryableIndices filters correctly")
    func syncResultRetryableIndicesFiltersCorrectly() {
        let errors = [
            ChannelSyncError(index: 1, errorType: .timeout, description: "Timeout"),
            ChannelSyncError(index: 2, errorType: .deviceError(code: 0x02), description: "Not found"),
            ChannelSyncError(index: 5, errorType: .timeout, description: "Timeout"),
        ]
        let result = ChannelSyncResult(channelsSynced: 5, errors: errors)

        #expect(result.retryableIndices == [1, 5])
    }

    @Test("ChannelService aborts early when transport send timeouts cascade")
    func syncChannelsAbortsEarlyForSendTimeoutCascade() async throws {
        let radioID = UUID()
        let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 6)
        let transport = SendTimeoutTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.01, clientIdentifier: "MCTst")
        )
        let service = ChannelService(session: session, dataStore: dataStore)

        let result = try await service.syncChannels(radioID: radioID, maxChannels: 6)

        #expect(result.sendTimeoutCount == 3)
        #expect(result.circuitBreakerAborted)
        #expect(await transport.sendCount == 3)
    }

    @Test("ChannelSyncResult retryableIndices empty when no retryable errors")
    func syncResultRetryableIndicesEmptyWhenNoRetryable() {
        let errors = [
            ChannelSyncError(index: 2, errorType: .deviceError(code: 0x02), description: "Not found"),
            ChannelSyncError(index: 3, errorType: .databaseError, description: "Save failed"),
        ]
        let result = ChannelSyncResult(channelsSynced: 6, errors: errors)

        #expect(result.retryableIndices.isEmpty)
    }

    @Test("isChannelConfigured returns true for empty name with non-zero secret")
    func isChannelConfiguredEmptyNameNonZeroSecret() {
        let isConfigured = ChannelService.isChannelConfigured(
            name: "",
            secret: Data(repeating: 0x42, count: ProtocolLimits.channelSecretSize)
        )
        #expect(isConfigured)
    }

    @Test("isChannelConfigured returns false for empty name with zero secret")
    func isChannelConfiguredEmptyNameZeroSecret() {
        let isConfigured = ChannelService.isChannelConfigured(
            name: "",
            secret: Data(repeating: 0, count: ProtocolLimits.channelSecretSize)
        )
        #expect(!isConfigured)
    }

    @Test("isChannelConfigured returns true for named zero-secret channel")
    func isChannelConfiguredNamedZeroSecret() {
        let isConfigured = ChannelService.isChannelConfigured(
            name: "Public",
            secret: Data(repeating: 0, count: ProtocolLimits.channelSecretSize)
        )
        #expect(isConfigured)
    }
}

private actor SendTimeoutTransport: MeshTransport {
    private(set) var sendCount = 0

    var receivedData: AsyncStream<Data> {
        AsyncStream { continuation in
            continuation.finish()
        }
    }

    var isConnected: Bool { true }

    func connect() async throws {}

    func disconnect() async {}

    func send(_ data: Data) async throws {
        sendCount += 1
        throw WiFiTransportError.sendTimeout
    }
}
