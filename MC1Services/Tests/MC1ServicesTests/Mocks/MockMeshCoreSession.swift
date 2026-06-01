import Foundation
import MeshCore

/// Mock implementation of MeshCoreSessionProtocol for testing.
///
/// Configure the mock by setting the stub properties before calling methods.
/// Track method calls by examining the recorded invocations.
public actor MockMeshCoreSession: MeshCoreSessionProtocol {

    // MARK: - Connection State

    public var connectionState: AsyncStream<ConnectionState> {
        AsyncStream { continuation in
            continuation.yield(stubbedConnectionState)
            continuation.finish()
        }
    }

    // MARK: - Stubs

    /// The connection state to return from connectionState stream
    public var stubbedConnectionState: ConnectionState = .disconnected

    /// Result to return from sendMessage
    public var stubbedSendMessageResult: Result<MessageSentInfo, Error> = .success(
        MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 5000)
    )

    /// Result to return from sendChannelMessage
    public var stubbedSendChannelMessageError: Error?

    /// Contacts to return from getContacts
    public var stubbedContacts: [MeshContact] = []

    /// Error to throw from getContacts
    public var stubbedGetContactsError: Error?

    /// Contact to return from getContact (by public key)
    public var stubbedContact: MeshContact?

    /// Error to throw from getContact
    public var stubbedGetContactError: Error?

    /// Error to throw from addContact
    public var stubbedAddContactError: Error?

    /// Error to throw from removeContact
    public var stubbedRemoveContactError: Error?

    /// Error to throw from resetPath
    public var stubbedResetPathError: Error?

    /// Result to return from sendPathDiscovery
    public var stubbedSendPathDiscoveryResult: Result<MessageSentInfo, Error> = .success(
        MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 5000)
    )

    /// Channel info to return from getChannel, keyed by index
    public var stubbedChannels: [UInt8: ChannelInfo] = [:]

    /// Error to throw from getChannel
    public var stubbedGetChannelError: Error?

    /// Error to throw from setChannel
    public var stubbedSetChannelError: Error?

    // MARK: - Recorded Invocations

    public struct SendMessageInvocation: Sendable, Equatable {
        public let destination: Data
        public let text: String
        public let timestamp: Date
        public let attempt: UInt8
    }

    public struct SendChannelMessageInvocation: Sendable, Equatable {
        public let channel: UInt8
        public let text: String
        public let timestamp: Date
    }

    public struct AddContactInvocation: Sendable, Equatable {
        public let contact: MeshContact
    }

    public struct SetChannelInvocation: Sendable, Equatable {
        public let index: UInt8
        public let name: String
        public let secret: Data
    }

    public private(set) var sendMessageInvocations: [SendMessageInvocation] = []
    public private(set) var sendChannelMessageInvocations: [SendChannelMessageInvocation] = []
    public private(set) var getContactsInvocations: [Date?] = []
    public private(set) var getContactPublicKeys: [Data] = []
    public private(set) var addContactInvocations: [AddContactInvocation] = []
    public private(set) var removeContactPublicKeys: [Data] = []
    public private(set) var resetPathPublicKeys: [Data] = []
    public private(set) var sendPathDiscoveryDestinations: [Data] = []
    public private(set) var getChannelIndices: [UInt8] = []
    public private(set) var setChannelInvocations: [SetChannelInvocation] = []

    // MARK: - Initialization

    public init() {}

    // MARK: - Test Configuration

    /// Sets the contacts returned by `getContacts(since:)`. Actor isolation forbids writing the
    /// stub property directly from a test, so configuration goes through this isolated setter.
    public func setStubbedContacts(_ contacts: [MeshContact]) {
        stubbedContacts = contacts
    }

    // MARK: - Protocol Methods

    public func sendMessage(to destination: Data, text: String, timestamp: Date, attempt: UInt8) async throws -> MessageSentInfo {
        sendMessageInvocations.append(SendMessageInvocation(destination: destination, text: text, timestamp: timestamp, attempt: attempt))
        switch stubbedSendMessageResult {
        case .success(let info):
            return info
        case .failure(let error):
            throw error
        }
    }

    public func sendChannelMessage(channel: UInt8, text: String, timestamp: Date) async throws {
        sendChannelMessageInvocations.append(SendChannelMessageInvocation(channel: channel, text: text, timestamp: timestamp))
        if let error = stubbedSendChannelMessageError {
            throw error
        }
    }

    public func getContacts(since lastModified: Date?) async throws -> [MeshContact] {
        getContactsInvocations.append(lastModified)
        if let error = stubbedGetContactsError {
            throw error
        }
        return stubbedContacts
    }

    public func getContact(publicKey: Data) async throws -> MeshContact? {
        getContactPublicKeys.append(publicKey)
        if let error = stubbedGetContactError {
            throw error
        }
        return stubbedContact
    }

    public func addContact(_ contact: MeshContact) async throws {
        addContactInvocations.append(AddContactInvocation(contact: contact))
        if let error = stubbedAddContactError {
            throw error
        }
    }

    public func removeContact(publicKey: Data) async throws {
        removeContactPublicKeys.append(publicKey)
        if let error = stubbedRemoveContactError {
            throw error
        }
    }

    public func resetPath(publicKey: Data) async throws {
        resetPathPublicKeys.append(publicKey)
        if let error = stubbedResetPathError {
            throw error
        }
    }

    public func sendPathDiscovery(to destination: Data) async throws -> MessageSentInfo {
        sendPathDiscoveryDestinations.append(destination)
        switch stubbedSendPathDiscoveryResult {
        case .success(let info):
            return info
        case .failure(let error):
            throw error
        }
    }

    public func getChannel(index: UInt8) async throws -> ChannelInfo {
        getChannelIndices.append(index)
        if let error = stubbedGetChannelError {
            throw error
        }
        if let channel = stubbedChannels[index] {
            return channel
        }
        // Return a default empty channel
        return ChannelInfo(index: index, name: "", secret: Data(repeating: 0, count: 16))
    }

    public func setChannel(index: UInt8, name: String, secret: Data) async throws {
        setChannelInvocations.append(SetChannelInvocation(index: index, name: name, secret: secret))
        if let error = stubbedSetChannelError {
            throw error
        }
    }

    public func shareContact(publicKey: Data) async throws {
        // Stub - not used in current tests
    }

    public func exportContact(publicKey: Data?) async throws -> String {
        // Stub - not used in current tests
        return ""
    }

    public func importContact(cardData: Data) async throws {
        // Stub - not used in current tests
    }

    public func changeContactFlags(_ contact: MeshContact, flags: ContactFlags) async throws {
        // Stub - not used in current tests
    }

    // MARK: - Test Helpers

    /// Resets all recorded invocations
    public func reset() {
        sendMessageInvocations = []
        sendChannelMessageInvocations = []
        getContactsInvocations = []
        getContactPublicKeys = []
        addContactInvocations = []
        removeContactPublicKeys = []
        resetPathPublicKeys = []
        sendPathDiscoveryDestinations = []
        getChannelIndices = []
        setChannelInvocations = []
    }
}
