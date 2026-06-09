import Foundation
import MeshCore
@testable import MC1Services

/// Mock implementation of ContactServiceProtocol for testing.
///
/// Configure the mock by setting the stub properties before calling methods.
/// Track method calls by examining the recorded invocations.
public actor MockContactService: ContactServiceProtocol {

    // MARK: - Stubs

    /// Result to return from syncContacts
    public var stubbedSyncContactsResult: Result<ContactSyncResult, Error> = .success(
        ContactSyncResult(contactsReceived: 0, lastSyncTimestamp: 0, isIncremental: false)
    )

    // MARK: - Recorded Invocations

    public struct SyncContactsInvocation: Sendable, Equatable {
        public let radioID: UUID
        public let since: Date?
    }

    public private(set) var syncContactsInvocations: [SyncContactsInvocation] = []

    // MARK: - Initialization

    public init() {}

    // MARK: - Protocol Methods

    public func syncContacts(radioID: UUID, since: Date? = nil) async throws -> ContactSyncResult {
        syncContactsInvocations.append(SyncContactsInvocation(radioID: radioID, since: since))
        switch stubbedSyncContactsResult {
        case .success(let result):
            return result
        case .failure(let error):
            throw error
        }
    }

    // MARK: - Test Helpers

    /// Resets all recorded invocations
    public func reset() {
        syncContactsInvocations = []
    }

    /// Sets the stubbed result for syncContacts
    public func setStubbedSyncContactsResult(_ result: Result<ContactSyncResult, Error>) {
        stubbedSyncContactsResult = result
    }
}
