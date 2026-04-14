import Foundation
import MeshCore
@testable import MC1Services

/// Mock implementation of ChannelServiceProtocol for testing.
///
/// Configure the mock by setting the stub properties before calling methods.
/// Track method calls by examining the recorded invocations.
public actor MockChannelService: ChannelServiceProtocol {

    // MARK: - Stubs

    /// Result to return from syncChannels
    public var stubbedSyncChannelsResult: Result<ChannelSyncResult, Error> = .success(
        ChannelSyncResult(channelsSynced: 0, errors: [])
    )

    /// Result to return from retryFailedChannels
    public var stubbedRetryResult: Result<ChannelSyncResult, Error> = .success(
        ChannelSyncResult(channelsSynced: 0, errors: [])
    )

    // MARK: - Recorded Invocations

    public struct SyncChannelsInvocation: Sendable, Equatable {
        public let radioID: UUID
        public let maxChannels: UInt8
    }

    public struct RetryInvocation: Sendable, Equatable {
        public let radioID: UUID
        public let indices: [UInt8]
    }

    public private(set) var syncChannelsInvocations: [SyncChannelsInvocation] = []
    public private(set) var retryInvocations: [RetryInvocation] = []

    // MARK: - Initialization

    public init() {}

    // MARK: - Protocol Methods

    public func syncChannels(radioID: UUID, maxChannels: UInt8) async throws -> ChannelSyncResult {
        syncChannelsInvocations.append(SyncChannelsInvocation(radioID: radioID, maxChannels: maxChannels))
        switch stubbedSyncChannelsResult {
        case .success(let result):
            return result
        case .failure(let error):
            throw error
        }
    }

    public func retryFailedChannels(radioID: UUID, indices: [UInt8]) async throws -> ChannelSyncResult {
        retryInvocations.append(RetryInvocation(radioID: radioID, indices: indices))
        switch stubbedRetryResult {
        case .success(let result):
            return result
        case .failure(let error):
            throw error
        }
    }

    // MARK: - Test Helpers

    /// Resets all recorded invocations
    public func reset() {
        syncChannelsInvocations = []
        retryInvocations = []
    }
}
