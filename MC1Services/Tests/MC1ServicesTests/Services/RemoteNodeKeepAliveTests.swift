import Foundation
import Testing
import MeshCore
@testable import MC1Services

@Suite("RemoteNodeService keep-alive retry logic")
struct RemoteNodeKeepAliveTests {

    // MARK: - Transient failures

    @Test("single transient failure retries without disconnecting")
    func singleTransientFailureRetries() {
        var failures = 0
        let action = KeepAliveRetryPolicy.evaluate(
            error: RemoteNodeError.sessionError(.timeout),
            consecutiveFailures: &failures
        )
        #expect(failures == 1)
        #expect(action == .retryNextInterval)
    }

    @Test("two consecutive transient failures triggers disconnect")
    func twoConsecutiveTransientFailuresDisconnect() {
        var failures = 1
        let action = KeepAliveRetryPolicy.evaluate(
            error: RemoteNodeError.sessionError(.timeout),
            consecutiveFailures: &failures
        )
        #expect(failures == 2)
        #expect(action == .disconnect)
    }

    @Test("success resets failure counter")
    func successResetsCounter() {
        var failures = 1
        KeepAliveRetryPolicy.recordSuccess(consecutiveFailures: &failures)
        #expect(failures == 0)
    }

    // MARK: - Terminal failures

    @Test("sessionNotFound disconnects immediately without incrementing counter")
    func sessionNotFoundDisconnectsImmediately() {
        var failures = 0
        let action = KeepAliveRetryPolicy.evaluate(
            error: RemoteNodeError.sessionNotFound,
            consecutiveFailures: &failures
        )
        #expect(failures == 0)
        #expect(action == .disconnectNow)
    }

    @Test("contactNotFound disconnects immediately without incrementing counter")
    func contactNotFoundDisconnectsImmediately() {
        var failures = 0
        let action = KeepAliveRetryPolicy.evaluate(
            error: RemoteNodeError.contactNotFound,
            consecutiveFailures: &failures
        )
        #expect(failures == 0)
        #expect(action == .disconnectNow)
    }

    // MARK: - Transient error variants

    @Test("deviceError is treated as transient failure")
    func deviceErrorIsTransient() {
        var failures = 0
        let action = KeepAliveRetryPolicy.evaluate(
            error: RemoteNodeError.sessionError(.deviceError(code: 7)),
            consecutiveFailures: &failures
        )
        #expect(failures == 1)
        #expect(action == .retryNextInterval)
    }

    @Test("notConnected is treated as transient failure")
    func notConnectedIsTransient() {
        var failures = 0
        let action = KeepAliveRetryPolicy.evaluate(
            error: RemoteNodeError.sessionError(.notConnected),
            consecutiveFailures: &failures
        )
        #expect(failures == 1)
        #expect(action == .retryNextInterval)
    }

    // MARK: - Skip and stop

    @Test("floodRouted is not counted as a failure")
    func floodRoutedNotCounted() {
        var failures = 0
        let action = KeepAliveRetryPolicy.evaluate(
            error: RemoteNodeError.floodRouted,
            consecutiveFailures: &failures
        )
        #expect(failures == 0)
        #expect(action == .skip)
    }

    @Test("CancellationError stops the loop quietly")
    func cancellationErrorStops() {
        var failures = 0
        let action = KeepAliveRetryPolicy.evaluate(
            error: CancellationError(),
            consecutiveFailures: &failures
        )
        #expect(failures == 0)
        #expect(action == .stop)
    }

    @Test("RemoteNodeError.cancelled stops the loop quietly")
    func cancelledErrorStops() {
        var failures = 0
        let action = KeepAliveRetryPolicy.evaluate(
            error: RemoteNodeError.cancelled,
            consecutiveFailures: &failures
        )
        #expect(failures == 0)
        #expect(action == .stop)
    }

    @Test("unknown non-RemoteNodeError disconnects immediately")
    func unknownErrorDisconnectsImmediately() {
        struct PersistenceError: Error {}
        var failures = 0
        let action = KeepAliveRetryPolicy.evaluate(
            error: PersistenceError(),
            consecutiveFailures: &failures
        )
        #expect(failures == 0)
        #expect(action == .disconnectNow)
    }

    // MARK: - Failure reasons

    @Test("failure reason describes timeout")
    func failureReasonTimeout() {
        let reason = KeepAliveRetryPolicy.failureReason(
            for: RemoteNodeError.sessionError(.timeout)
        )
        #expect(reason == "timeout")
    }

    @Test("failure reason describes device error with code")
    func failureReasonDeviceError() {
        let reason = KeepAliveRetryPolicy.failureReason(
            for: RemoteNodeError.sessionError(.deviceError(code: 42))
        )
        #expect(reason == "device error (code: 42)")
    }

    @Test("failure reason describes transport not connected")
    func failureReasonTransport() {
        let reason = KeepAliveRetryPolicy.failureReason(
            for: RemoteNodeError.sessionError(.notConnected)
        )
        #expect(reason == "transport not connected")
    }

    @Test("failure reason describes session not found")
    func failureReasonSessionNotFound() {
        let reason = KeepAliveRetryPolicy.failureReason(
            for: RemoteNodeError.sessionNotFound
        )
        #expect(reason == "session not found")
    }

    @Test("failure reason describes contact not found")
    func failureReasonContactNotFound() {
        let reason = KeepAliveRetryPolicy.failureReason(
            for: RemoteNodeError.contactNotFound
        )
        #expect(reason == "contact not found")
    }
}
