import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("ChannelService Tests")
struct ChannelServiceTests {
  // MARK: - Secret Hashing Tests

  @Test
  func `hashSecret produces 16-byte output`() {
    let secret = ChannelService.hashSecret("test passphrase")
    #expect(secret.count == ProtocolLimits.channelSecretSize)
  }

  @Test
  func `hashSecret is deterministic`() {
    let secret1 = ChannelService.hashSecret("same passphrase")
    let secret2 = ChannelService.hashSecret("same passphrase")
    #expect(secret1 == secret2)
  }

  @Test
  func `hashSecret differs for different inputs`() {
    let secret1 = ChannelService.hashSecret("passphrase one")
    let secret2 = ChannelService.hashSecret("passphrase two")
    #expect(secret1 != secret2)
  }

  @Test
  func `hashSecret handles empty string`() {
    let secret = ChannelService.hashSecret("")
    #expect(secret.count == ProtocolLimits.channelSecretSize)
    #expect(secret == Data(repeating: 0, count: ProtocolLimits.channelSecretSize))
  }

  @Test
  func `hashSecret handles unicode`() {
    let secret = ChannelService.hashSecret("🔐 secure 密码")
    #expect(secret.count == ProtocolLimits.channelSecretSize)
  }

  @Test
  func `validateSecret accepts 16-byte secrets`() {
    let validSecret = Data(repeating: 0xAB, count: ProtocolLimits.channelSecretSize)
    #expect(ChannelService.validateSecret(validSecret))
  }

  @Test
  func `validateSecret rejects wrong-sized secrets`() {
    let tooShort = Data(repeating: 0xAB, count: 15)
    let tooLong = Data(repeating: 0xAB, count: 17)
    #expect(!ChannelService.validateSecret(tooShort))
    #expect(!ChannelService.validateSecret(tooLong))
  }

  // MARK: - ChannelSyncError Tests

  @Test
  func `ChannelSyncError timeout is retryable`() {
    let error = ChannelSyncError(index: 0, errorType: .timeout, description: "Timeout")
    #expect(error.isRetryable)
  }

  @Test
  func `ChannelSyncError send timeout is retryable and counted separately`() {
    let error = ChannelSyncError(index: 0, errorType: .sendTimeout, description: "Send timed out")
    let result = ChannelSyncResult(channelsSynced: 0, errors: [error])

    #expect(error.isRetryable)
    #expect(result.requestTimeoutCount == 0)
    #expect(result.sendTimeoutCount == 1)
  }

  @Test
  func `ChannelSyncError circuit breaker is not retryable`() {
    let error = ChannelSyncError(index: 0, errorType: .circuitBreaker, description: "Circuit open")
    #expect(!error.isRetryable)
  }

  @Test
  func `ChannelSyncError deviceError is not retryable`() {
    let error = ChannelSyncError(index: 0, errorType: .deviceError(code: 0x02), description: "Not found")
    #expect(!error.isRetryable)
  }

  @Test
  func `ChannelSyncError databaseError is not retryable`() {
    let error = ChannelSyncError(index: 0, errorType: .databaseError, description: "Save failed")
    #expect(!error.isRetryable)
  }

  @Test
  func `ChannelSyncError unknown is not retryable`() {
    let error = ChannelSyncError(index: 0, errorType: .unknown, description: "Unknown error")
    #expect(!error.isRetryable)
  }

  // MARK: - ChannelSyncResult Tests

  @Test
  func `ChannelSyncResult isComplete when no errors`() {
    let result = ChannelSyncResult(channelsSynced: 8, errors: [])
    #expect(result.isComplete)
  }

  @Test
  func `ChannelSyncResult is not complete with errors`() {
    let error = ChannelSyncError(index: 3, errorType: .timeout, description: "Timeout")
    let result = ChannelSyncResult(channelsSynced: 7, errors: [error])
    #expect(!result.isComplete)
  }

  @Test
  func `ChannelSyncResult retryableIndices filters correctly`() {
    let errors = [
      ChannelSyncError(index: 1, errorType: .timeout, description: "Timeout"),
      ChannelSyncError(index: 2, errorType: .deviceError(code: 0x02), description: "Not found"),
      ChannelSyncError(index: 5, errorType: .timeout, description: "Timeout"),
    ]
    let result = ChannelSyncResult(channelsSynced: 5, errors: errors)

    #expect(result.retryableIndices == [1, 5])
  }

  @Test
  func `ChannelService aborts early when transport send timeouts cascade`() async throws {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 6)
    let transport = SendTimeoutTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 0.01, clientIdentifier: "MCTst")
    )
    let service = ChannelService(session: session, dataStore: dataStore, rxLogService: nil)

    let result = try await service.syncChannels(radioID: radioID, maxChannels: 6)

    #expect(result.sendTimeoutCount == 3)
    #expect(result.circuitBreakerAborted)
    #expect(await transport.sendCount == 3)
  }

  @Test
  func `ChannelSyncResult retryableIndices empty when no retryable errors`() {
    let errors = [
      ChannelSyncError(index: 2, errorType: .deviceError(code: 0x02), description: "Not found"),
      ChannelSyncError(index: 3, errorType: .databaseError, description: "Save failed"),
    ]
    let result = ChannelSyncResult(channelsSynced: 6, errors: errors)

    #expect(result.retryableIndices.isEmpty)
  }

  @Test
  func `isChannelConfigured returns true for empty name with non-zero secret`() {
    let isConfigured = ChannelService.isChannelConfigured(
      name: "",
      secret: Data(repeating: 0x42, count: ProtocolLimits.channelSecretSize)
    )
    #expect(isConfigured)
  }

  @Test
  func `isChannelConfigured returns false for empty name with zero secret`() {
    let isConfigured = ChannelService.isChannelConfigured(
      name: "",
      secret: Data(repeating: 0, count: ProtocolLimits.channelSecretSize)
    )
    #expect(!isConfigured)
  }

  @Test
  func `isChannelConfigured returns true for named zero-secret channel`() {
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

  var isConnected: Bool {
    true
  }

  func connect() async throws {}

  func disconnect() async {}

  func send(_ data: Data) async throws {
    sendCount += 1
    throw WiFiTransportError.sendTimeout
  }
}
