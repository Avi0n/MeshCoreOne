import Foundation

/// Provides a type-safe event filter for MeshCore events.
///
/// `EventFilter` provides a convenient way to create predicates for filtering ``MeshEvent``
/// values. Use the built-in static factory methods for common filtering patterns, or create
/// custom filters using the initializer.
///
/// ## Usage
///
/// ```swift
/// // Filter for specific acknowledgement code
/// let ackFilter = EventFilter.acknowledgement(code: expectedAckCode)
///
/// // Filter for messages from a specific sender
/// let senderFilter = EventFilter.contactMessage(fromPrefix: senderKey.prefix(6))
///
/// // Filter for channel messages on a specific channel
/// let channelFilter = EventFilter.channelMessage(channel: 5)
///
/// // Custom filter using initializer
/// let customFilter = EventFilter { event in
///     if case .ok = event { return true }
///     return false
/// }
///
/// // Use with EventDispatcher
/// let stream = await dispatcher.subscribe(filter: filter.matches)
///
/// // Use with waitForEvent
/// let event = await session.waitForEvent(filter: filter, timeout: 5.0)
/// ```
public struct EventFilter: Sendable {
    /// The underlying predicate used for matching.
    private let predicate: @Sendable (MeshEvent) -> Bool

    /// Creates a custom event filter with a predicate.
    ///
    /// - Parameter predicate: A closure that returns `true` for events that should pass the filter.
    public init(_ predicate: @escaping @Sendable (MeshEvent) -> Bool) {
        self.predicate = predicate
    }

    /// Tests whether an event matches this filter.
    ///
    /// - Parameter event: The event to test.
    /// - Returns: `true` if the event matches the filter criteria.
    public func matches(_ event: MeshEvent) -> Bool {
        predicate(event)
    }

    // MARK: - Acknowledgement Filters

    /// Matches any acknowledgement event regardless of code.
    ///
    /// Use for persistent listeners that must see every incoming ACK. Because
    /// the filter is evaluated at dispatch time, unrelated events never enter
    /// the subscription's bounded buffer and cannot evict an ACK.
    public static var anyAcknowledgement: EventFilter {
        EventFilter { event in
            if case .acknowledgement = event { return true }
            return false
        }
    }

    /// Creates a filter for acknowledgement events with a specific code.
    ///
    /// - Parameter code: The exact acknowledgement code to match.
    /// - Returns: A filter that matches only `.acknowledgement` events with the specified code.
    public static func acknowledgement(code: Data) -> EventFilter {
        EventFilter { event in
            if case .acknowledgement(let ackCode, _) = event {
                return ackCode == code
            }
            return false
        }
    }

    // MARK: - Message Filters

    /// Matches any contact message receipt regardless of sender prefix.
    public static var anyContactMessage: EventFilter {
        EventFilter { event in
            if case .contactMessageReceived = event { return true }
            return false
        }
    }

    /// Matches any channel message receipt.
    public static var anyChannelMessage: EventFilter {
        EventFilter { event in
            if case .channelMessageReceived = event { return true }
            return false
        }
    }

    /// Creates a filter for contact messages from a specific sender.
    ///
    /// - Parameter publicKeyPrefix: The sender's public key prefix to match.
    ///   The event's sender prefix must start with this data.
    /// - Returns: A filter that matches `.contactMessageReceived` events from the specified sender.
    public static func contactMessage(fromPrefix publicKeyPrefix: Data) -> EventFilter {
        EventFilter { event in
            if case .contactMessageReceived(let msg) = event {
                return msg.senderPublicKeyPrefix.starts(with: publicKeyPrefix)
            }
            return false
        }
    }

    /// Creates a filter for channel messages on a specific channel.
    ///
    /// - Parameter channel: The channel index to filter for.
    /// - Returns: A filter that matches `.channelMessageReceived` events on the specified channel.
    public static func channelMessage(channel: UInt8) -> EventFilter {
        EventFilter { event in
            if case .channelMessageReceived(let msg) = event {
                return msg.channelIndex == channel
            }
            return false
        }
    }

    // MARK: - Response Filters

    /// Creates a filter for status responses from a specific node.
    ///
    /// - Parameter publicKeyPrefix: The responder's public key prefix to match.
    ///   The response's public key prefix must start with this data.
    /// - Returns: A filter that matches `.statusResponse` events from the specified node.
    public static func statusResponse(fromPrefix publicKeyPrefix: Data) -> EventFilter {
        EventFilter { event in
            if case .statusResponse(let resp) = event {
                return resp.publicKeyPrefix.starts(with: publicKeyPrefix)
            }
            return false
        }
    }

    /// Creates a filter for telemetry responses from a specific node.
    ///
    /// - Parameter publicKeyPrefix: The responder's public key prefix to match.
    /// - Returns: A filter that matches `.telemetryResponse` events from the specified node.
    public static func telemetryResponse(fromPrefix publicKeyPrefix: Data) -> EventFilter {
        EventFilter { event in
            if case .telemetryResponse(let resp) = event {
                return resp.publicKeyPrefix.starts(with: publicKeyPrefix)
            }
            return false
        }
    }

    // MARK: - Network Event Filters

    /// Matches any rxLogData event.
    public static var rxLogData: EventFilter {
        EventFilter { event in
            if case .rxLogData = event { return true }
            return false
        }
    }

    /// Matches any advertisement regardless of sender prefix.
    public static var anyAdvertisement: EventFilter {
        EventFilter { event in
            if case .advertisement = event { return true }
            return false
        }
    }

    /// Creates a filter for advertisement events from a specific node.
    ///
    /// - Parameter publicKeyPrefix: The advertiser's public key prefix to match.
    /// - Returns: A filter that matches `.advertisement` events from the specified node.
    public static func advertisement(fromPrefix publicKeyPrefix: Data) -> EventFilter {
        EventFilter { event in
            if case .advertisement(let pubKey) = event {
                return pubKey.starts(with: publicKeyPrefix)
            }
            return false
        }
    }

    /// Creates a filter for path update events for a specific node.
    ///
    /// - Parameter publicKeyPrefix: The node's public key prefix to match.
    /// - Returns: A filter that matches `.pathUpdate` events for the specified node.
    public static func pathUpdate(forPrefix publicKeyPrefix: Data) -> EventFilter {
        EventFilter { event in
            if case .pathUpdate(let pubKey) = event {
                return pubKey.starts(with: publicKeyPrefix)
            }
            return false
        }
    }

    // MARK: - Event Type Filters

    /// Creates a filter that matches events by type using a custom matcher.
    ///
    /// This is useful for filtering by event type when you don't care about
    /// the associated values.
    ///
    /// - Parameter matcher: A closure that returns `true` for matching events.
    /// - Returns: A filter using the provided matcher.
    public static func eventType(_ matcher: @escaping @Sendable (MeshEvent) -> Bool) -> EventFilter {
        EventFilter(matcher)
    }

    /// Creates a filter that matches any `.ok` response.
    ///
    /// - Returns: A filter that matches `.ok` events regardless of value.
    public static var ok: EventFilter {
        EventFilter { event in
            if case .ok = event { return true }
            return false
        }
    }

    /// Creates a filter that matches any `.error` response.
    ///
    /// - Returns: A filter that matches `.error` events regardless of code.
    public static var error: EventFilter {
        EventFilter { event in
            if case .error = event { return true }
            return false
        }
    }

    /// Creates a filter that matches `.noMoreMessages` events.
    ///
    /// - Returns: A filter that matches the no-more-messages indicator.
    public static var noMoreMessages: EventFilter {
        EventFilter { event in
            if case .noMoreMessages = event { return true }
            return false
        }
    }

    /// Creates a filter that matches `.messagesWaiting` events.
    ///
    /// - Returns: A filter that matches the messages-waiting indicator.
    public static var messagesWaiting: EventFilter {
        EventFilter { event in
            if case .messagesWaiting = event { return true }
            return false
        }
    }

    // MARK: - Login Filters

    /// Matches any successful login response.
    public static var anyLoginSuccess: EventFilter {
        EventFilter { event in
            if case .loginSuccess = event { return true }
            return false
        }
    }

    /// Matches any failed login response.
    public static var anyLoginFailed: EventFilter {
        EventFilter { event in
            if case .loginFailed = event { return true }
            return false
        }
    }

    // MARK: - Combinators

    /// Creates a filter that matches if either this filter or another filter matches.
    ///
    /// - Parameter other: Another filter to combine with.
    /// - Returns: A filter that matches events matched by either filter.
    public func or(_ other: EventFilter) -> EventFilter {
        EventFilter { event in
            self.matches(event) || other.matches(event)
        }
    }

    /// Creates a filter that matches only if both this filter and another filter match.
    ///
    /// - Parameter other: Another filter to combine with.
    /// - Returns: A filter that matches events matched by both filters.
    public func and(_ other: EventFilter) -> EventFilter {
        EventFilter { event in
            self.matches(event) && other.matches(event)
        }
    }

    /// Creates a filter that matches events this filter does not match.
    ///
    /// - Returns: A filter that inverts this filter's logic.
    public var negated: EventFilter {
        EventFilter { event in
            !self.matches(event)
        }
    }
}
