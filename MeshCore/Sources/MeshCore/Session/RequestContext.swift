import Foundation

/// Encapsulates metadata for tracking a pending request.
///
/// This structure holds the information necessary to correlate an incoming response
/// with a previously sent request, including timeout information and optional context.
public struct RequestContext: Sendable {
    /// The data representing the expected acknowledgment or tag for this request.
    public let expectedAck: Data

    /// The type of binary request being tracked, if applicable.
    public let requestType: BinaryRequestType?

    /// The public key prefix of the target node, if applicable.
    public let publicKeyPrefix: Data?

    /// The date and time when this request expires.
    public let expiresAt: Date

    /// Additional context parameters for specialized request types.
    public let context: [String: Int]

    /// Initializes a new request context.
    ///
    /// - Parameters:
    ///   - expectedAck: The expected acknowledgment data.
    ///   - requestType: The type of binary request.
    ///   - publicKeyPrefix: The target node's public key prefix.
    ///   - expiresAt: The expiration date.
    ///   - context: Additional context parameters.
    public init(
        expectedAck: Data,
        requestType: BinaryRequestType?,
        publicKeyPrefix: Data?,
        expiresAt: Date,
        context: [String: Int] = [:]
    ) {
        self.expectedAck = expectedAck
        self.requestType = requestType
        self.publicKeyPrefix = publicKeyPrefix
        self.expiresAt = expiresAt
        self.context = context
    }
}

/// Defines a composite key for binary response routing.
private struct BinaryRequestKey: Hashable {
    /// The public key prefix of the node.
    let publicKeyPrefix: Data

    /// The type of request.
    let requestType: BinaryRequestType
}

/// Manages pending request continuations and metadata safely.
///
/// `PendingRequests` is an actor that ensures thread-safe access to pending requests.
/// it supports routing responses back to their originators using tags or node-type correlation.
public actor PendingRequests {
    /// Mapping of tags to their respective continuations.
    private var requests: [Data: CheckedContinuation<MeshEvent?, Never>] = [:]

    /// Mapping of tags to their request contexts.
    private var metadata: [Data: RequestContext] = [:]

    /// Mapping of binary request keys to their original tags for routing.
    private var binaryRequestIndex: [BinaryRequestKey: Data] = [:]

    /// Registers a new pending request and waits for its response or timeout.
    ///
    /// - Parameters:
    ///   - tag: The tag used to identify the request.
    ///   - requestType: Optional type for binary requests.
    ///   - publicKeyPrefix: Optional public key prefix of the target node.
    ///   - timeout: The maximum time to wait for a response.
    ///   - context: Additional context for the request.
    /// - Returns: The received `MeshEvent`, or `nil` if the request timed out.
    public func register(
        tag: Data,
        requestType: BinaryRequestType? = nil,
        publicKeyPrefix: Data? = nil,
        timeout: TimeInterval,
        context: [String: Int] = [:]
    ) async -> MeshEvent? {
        let requestContext = RequestContext(
            expectedAck: tag,
            requestType: requestType,
            publicKeyPrefix: publicKeyPrefix,
            expiresAt: Date().addingTimeInterval(timeout),
            context: context
        )
        metadata[tag] = requestContext

        // Index binary requests for routing
        if let type = requestType, let prefix = publicKeyPrefix {
            let key = BinaryRequestKey(publicKeyPrefix: prefix, requestType: type)
            binaryRequestIndex[key] = tag
        }

        return await withCheckedContinuation { continuation in
            requests[tag] = continuation

            // Schedule timeout
            Task {
                try? await Task.sleep(for: .seconds(timeout))
                await self.timeout(tag: tag)
            }
        }
    }

    /// Completes a pending request with the provided event.
    ///
    /// - Parameters:
    ///   - tag: The tag of the request to complete.
    ///   - event: The event to return to the caller.
    public func complete(tag: Data, with event: MeshEvent) {
        if let context = metadata[tag], let type = context.requestType, let prefix = context.publicKeyPrefix {
            let key = BinaryRequestKey(publicKeyPrefix: prefix, requestType: type)
            binaryRequestIndex.removeValue(forKey: key)
        }
        requests.removeValue(forKey: tag)?.resume(returning: event)
        metadata.removeValue(forKey: tag)
    }

    /// Completes a binary request using node prefix and request type.
    ///
    /// This method is used when the response contains the node's prefix but not the original request tag.
    ///
    /// - Parameters:
    ///   - publicKeyPrefix: The public key prefix of the responding node.
    ///   - type: The type of the binary request.
    ///   - event: The event to return to the caller.
    public func completeBinaryRequest(publicKeyPrefix: Data, type: BinaryRequestType, with event: MeshEvent) {
        let key = BinaryRequestKey(publicKeyPrefix: publicKeyPrefix, requestType: type)
        guard let tag = binaryRequestIndex[key] else { return }
        complete(tag: tag, with: event)
    }

    /// Marks a pending request as timed out.
    ///
    /// - Parameter tag: The tag of the request that timed out.
    private func timeout(tag: Data) {
        if let context = metadata[tag], let type = context.requestType, let prefix = context.publicKeyPrefix {
            let key = BinaryRequestKey(publicKeyPrefix: prefix, requestType: type)
            binaryRequestIndex.removeValue(forKey: key)
        }
        requests.removeValue(forKey: tag)?.resume(returning: nil)
        metadata.removeValue(forKey: tag)
    }

    /// Determines if a tag matches a pending binary request of a specific type.
    ///
    /// - Parameters:
    ///   - tag: The tag to check.
    ///   - type: The request type to match.
    /// - Returns: `true` if the tag matches a pending request of the specified type.
    public func matchesBinaryRequest(tag: Data, type: BinaryRequestType) -> Bool {
        guard let context = metadata[tag] else { return false }
        return context.requestType == type
    }

    /// Checks if there is a pending binary request for a specific node and type.
    ///
    /// - Parameters:
    ///   - publicKeyPrefix: The public key prefix of the node.
    ///   - type: The type of the request.
    /// - Returns: `true` if such a request is pending.
    public func hasPendingBinaryRequest(publicKeyPrefix: Data, type: BinaryRequestType) -> Bool {
        let key = BinaryRequestKey(publicKeyPrefix: publicKeyPrefix, requestType: type)
        return binaryRequestIndex[key] != nil
    }

    /// Retrieves metadata for a pending binary request by its tag.
    ///
    /// - Parameter tag: The tag of the request.
    /// - Returns: A tuple containing the request type, public key prefix, and context, or `nil` if not found.
    public func getBinaryRequestInfo(tag: Data) -> (type: BinaryRequestType, publicKeyPrefix: Data, context: [String: Int])? {
        guard let requestContext = metadata[tag],
              let type = requestContext.requestType,
              let prefix = requestContext.publicKeyPrefix else {
            return nil
        }
        return (type, prefix, requestContext.context)
    }
}

/// Serializes binary request operations to prevent race conditions.
///
/// When multiple binary requests (status, telemetry, etc.) are sent concurrently,
/// their `messageSent` events can interleave, causing incorrect `expectedAck` matching.
/// This actor ensures only one binary request is in flight at a time.
public actor BinaryRequestSerializer {
    private var isRequestInFlight = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// Acquires the lock, waiting if another request is in flight.
    public func acquire() async {
        if !isRequestInFlight {
            isRequestInFlight = true
            return
        }

        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
    }

    /// Releases the lock, allowing the next waiter to proceed.
    public func release() {
        if let next = waiters.first {
            waiters.removeFirst()
            next.resume()
        } else {
            isRequestInFlight = false
        }
    }

    /// Executes a binary request operation with serialization.
    ///
    /// This ensures only one binary request is processed at a time,
    /// preventing race conditions with `messageSent` event correlation.
    ///
    /// - Parameter operation: The async throwing operation to execute.
    /// - Returns: The result of the operation.
    public func withSerialization<T: Sendable>(
        _ operation: @Sendable () async throws -> T
    ) async throws -> T {
        await acquire()
        defer { release() }
        return try await operation()
    }
}

/// Serializes broad command-response operations that rely on event matching.
///
/// Many MeshCore commands wait for generic events such as `.ok`, `.error`, or a
/// singleton typed response. Serializing those request/response exchanges avoids
/// cross-command event miscorrelation when multiple callers issue commands at once.
public actor RequestResponseSerializer {
    private var isRequestInFlight = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// Acquires the serializer, waiting if another request/response exchange is active.
    public func acquire() async {
        if !isRequestInFlight {
            isRequestInFlight = true
            return
        }

        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
    }

    /// Releases the serializer to the next waiting request.
    public func release() {
        if let next = waiters.first {
            waiters.removeFirst()
            next.resume()
        } else {
            isRequestInFlight = false
        }
    }

    /// Executes a request/response operation while holding the serializer.
    public func withSerialization<T: Sendable>(
        _ operation: @Sendable () async throws -> T
    ) async throws -> T {
        await acquire()
        defer { release() }
        return try await operation()
    }
}
