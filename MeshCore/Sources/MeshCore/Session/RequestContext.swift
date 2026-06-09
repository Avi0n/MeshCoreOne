import Foundation
import os

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
                self.timeout(tag: tag)
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

/// Serializes every command-response exchange that relies on event matching.
///
/// Many MeshCore commands wait for generic events such as `.ok`, `.error`, or a
/// singleton typed response. Binary requests (status, telemetry, owner info, etc.)
/// additionally learn their `expectedAck` from a `.messageSent` event whose tag is
/// not known in advance. Because `EventDispatcher` broadcasts to every live
/// subscription with no per-command correlation, two exchanges in flight at once can
/// consume each other's responses — a unicast send and a binary request both match a
/// bare `.messageSent`, and either can steal the other's tag. Routing every exchange
/// through one serializer guarantees a single request/response is outstanding at a
/// time, which is the only structural defense given the learned (not precomputed) ack.
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
    ///
    /// The slot is held until the wire exchange the operation owns actually terminates —
    /// a matching response is consumed or the command's own timeout elapses — even after
    /// the caller has been resumed. If the caller's task is cancelled mid-flight, it is
    /// resumed immediately with `CancellationError`, but the operation keeps running so a
    /// late (orphaned) response is drained here under the held slot instead of leaking to
    /// the next command, which would otherwise consume it as its own. A command cancelled
    /// before its exchange begins releases the slot without writing.
    public func withSerialization<T: Sendable>(
        _ operation: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        await acquire()

        let pending = OSAllocatedUnfairLock<CheckedContinuation<T, Error>?>(initialState: nil)

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<T, Error>) in
                let cancelledBeforeStart = pending.withLock { stored -> Bool in
                    if Task.isCancelled {
                        return true
                    }
                    stored = continuation
                    return false
                }

                if cancelledBeforeStart {
                    release()
                    continuation.resume(throwing: CancellationError())
                    return
                }

                // This task is not a child of the caller, so cancelling the caller does
                // not abort it. It holds the slot until the exchange resolves, then
                // releases and hands the result to the caller if it is still waiting.
                Task {
                    let outcome: Result<T, Error>
                    do {
                        outcome = .success(try await operation())
                    } catch {
                        outcome = .failure(error)
                    }
                    release()
                    let waiting = pending.withLock { stored -> CheckedContinuation<T, Error>? in
                        let continuation = stored
                        stored = nil
                        return continuation
                    }
                    waiting?.resume(with: outcome)
                }
            }
        } onCancel: {
            let waiting = pending.withLock { stored -> CheckedContinuation<T, Error>? in
                let continuation = stored
                stored = nil
                return continuation
            }
            waiting?.resume(throwing: CancellationError())
        }
    }
}
