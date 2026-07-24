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
  func `hashSecret matches golden hashtag channel vectors`() {
    // Compare via lowercase hex so we avoid the ambiguous Data(hexString:) that exists
    // in both @testable-imported MeshCore and MC1Services modules.
    let vectors: [(passphrase: String, hex: String)] = [
      ("#test", "9cd8fcf22a47333b591d96a2b848b73f"),
      ("#avion-testing2", "3976fbac9120f147576900ac90d41dd2")
    ]
    for vector in vectors {
      let actual = ChannelService.hashSecret(vector.passphrase)
        .map { String(format: "%02x", $0) }
        .joined()
      #expect(actual == vector.hex, "hash mismatch for \(vector.passphrase)")
    }
  }

  @Test
  func `exportChannelURI emits the well-known Public channel secret`() throws {
    // Public uses a fixed well-known key, not hashSecret("Public").
    let publicSecret = Data([
      0x8B, 0x33, 0x87, 0xE9, 0xC5, 0xCD, 0xEA, 0x6A,
      0xC9, 0xE5, 0xED, 0xBA, 0xA1, 0x15, 0xCD, 0x72
    ])
    let uri = ChannelService.exportChannelURI(name: "Public", secret: publicSecret)
    let components = try #require(URLComponents(string: uri))
    #expect(components.queryItems?.first(where: { $0.name == "name" })?.value == "Public")
    #expect(
      components.queryItems?.first(where: { $0.name == "secret" })?.value
        == "8B3387E9C5CDEA6AC9E5EDBAA115CD72"
    )
  }

  @Test
  func `exportChannelURI always emits name and secret via URLComponents`() throws {
    // Build secret bytes directly to avoid ambiguous Data(hexString:).
    let secret = Data([
      0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
      0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF
    ])
    let uri = ChannelService.exportChannelURI(name: "a&b=c", secret: secret)
    let components = try #require(URLComponents(string: uri))
    #expect(components.scheme == "meshcore")
    #expect(components.host == "channel")
    #expect(components.path == "/add")
    let items = try #require(components.queryItems)
    #expect(items.first(where: { $0.name == "name" })?.value == "a&b=c")
    #expect(items.first(where: { $0.name == "secret" })?.value == secret.uppercaseHexString())
    #expect(items.first(where: { $0.name == "region_scope" }) == nil)
  }

  @Test
  func `exportChannelURI emits region_scope only for region flood scope`() throws {
    let secret = Data(repeating: 0xAB, count: ProtocolLimits.channelSecretSize)
    let withRegion = ChannelService.exportChannelURI(
      name: "#test",
      secret: secret,
      floodScope: .region("testregion")
    )
    let components = try #require(URLComponents(string: withRegion))
    #expect(components.queryItems?.first(where: { $0.name == "region_scope" })?.value == "testregion")

    let inheritURI = ChannelService.exportChannelURI(name: "#test", secret: secret, floodScope: .inherit)
    #expect(URLComponents(string: inheritURI)?.queryItems?.contains(where: { $0.name == "region_scope" }) != true)

    let allRegionsURI = ChannelService.exportChannelURI(
      name: "#test",
      secret: secret,
      floodScope: .allRegions
    )
    #expect(
      URLComponents(string: allRegionsURI)?.queryItems?.contains(where: { $0.name == "region_scope" }) != true
    )
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
