import Testing
import Foundation
@testable import MC1Services
@testable import MeshCore

@Suite("MessageService Tests")
struct MessageServiceTests {

    // MARK: - Test Constants

    private let testTimeout: TimeInterval = 30.0
    private let expiredTimeOffset: TimeInterval = -31.0
    private let validAckCode = Data([0x01, 0x02, 0x03, 0x04])

    // MARK: - PendingAck Tests

    @Test("PendingAck isExpired returns false when within timeout")
    func pendingAckNotExpiredWithinTimeout() {
        let ack = PendingAck(
            messageID: UUID(),
            contactID: UUID(),
            ackCodes: [validAckCode],
            sentAt: Date(),
            timeout: testTimeout
        )
        #expect(!ack.isExpired)
    }

    @Test("PendingAck isExpired returns true after timeout")
    func pendingAckExpiredAfterTimeout() {
        let ack = PendingAck(
            messageID: UUID(),
            contactID: UUID(),
            ackCodes: [validAckCode],
            sentAt: Date().addingTimeInterval(expiredTimeOffset),
            timeout: testTimeout
        )
        #expect(ack.isExpired)
    }

    @Test("PendingAck isExpired returns false when delivered")
    func pendingAckNotExpiredWhenDelivered() {
        var ack = PendingAck(
            messageID: UUID(),
            contactID: UUID(),
            ackCodes: [validAckCode],
            sentAt: Date().addingTimeInterval(expiredTimeOffset),
            timeout: testTimeout
        )
        ack.isDelivered = true
        #expect(!ack.isExpired)
    }

    // MARK: - MessageServiceConfig Tests

    @Test("MessageServiceConfig default values")
    func messageServiceConfigDefaults() {
        let config = MessageServiceConfig.default
        #expect(config.floodFallbackOnRetry == true)
        #expect(config.maxAttempts == 4)
        #expect(config.maxFloodAttempts == 2)
        #expect(config.floodAfter == 2)
        #expect(config.minTimeout == 0)
        #expect(config.triggerPathDiscoveryAfterFlood == true)
    }

    @Test("MessageServiceConfig custom values")
    func messageServiceConfigCustomValues() {
        let config = MessageServiceConfig(
            floodFallbackOnRetry: false,
            maxAttempts: 5,
            maxFloodAttempts: 3,
            floodAfter: 3,
            minTimeout: 10.0,
            triggerPathDiscoveryAfterFlood: false
        )
        #expect(config.floodFallbackOnRetry == false)
        #expect(config.maxAttempts == 5)
        #expect(config.maxFloodAttempts == 3)
        #expect(config.floodAfter == 3)
        #expect(config.minTimeout == 10.0)
        #expect(config.triggerPathDiscoveryAfterFlood == false)
    }
}
