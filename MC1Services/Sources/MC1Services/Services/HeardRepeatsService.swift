import Foundation
import MeshCore
import OSLog

/// Service for correlating RX log entries to sent channel messages
/// and tracking "heard repeats" - evidence of message propagation through the mesh.
public actor HeardRepeatsService {
    private let dataStore: any HeardRepeatPersisting
    private let logger = PersistentLogger(subsystem: "com.mc1", category: "HeardRepeatsService")

    /// Device ID for the current session
    private var radioID: UUID?

    /// Local node name for matching sender in decrypted messages
    private var localNodeName: String?

    /// Multicast broadcaster for heard-repeat events.
    private nonisolated let eventBroadcaster = EventBroadcaster<HeardRepeatEvent>()

    init(dataStore: any HeardRepeatPersisting) {
        self.dataStore = dataStore
    }

    /// Returns a fresh stream of heard-repeat events. Registration is
    /// synchronous, so events yielded after this call are never dropped.
    /// Consumers must re-subscribe per connection because the owning
    /// `ServiceContainer` is rebuilt on every connection.
    public nonisolated func events() -> AsyncStream<HeardRepeatEvent> {
        eventBroadcaster.subscribe()
    }

    /// Ends every `events()` subscriber's for-await loop. Called by
    /// `ServiceContainer.tearDown()` so consumer tasks release the service
    /// references they hold.
    nonisolated func finishEvents() {
        eventBroadcaster.finish()
    }

    /// Configure the service with device context.
    /// Must be called once before processing any RX log entries.
    /// Thread-safe due to actor isolation.
    public func configure(radioID: UUID, localNodeName: String) {
        self.radioID = radioID
        self.localNodeName = localNodeName
        logger.info("Configured with radioID: \(radioID), nodeName: \(localNodeName)")
    }

    /// Checks if a repeat has already been recorded for this RX log entry.
    private func isDuplicateRepeat(_ entryID: UUID) async -> Bool {
        do {
            return try await dataStore.messageRepeatExists(rxLogEntryID: entryID)
        } catch {
            logger.error("Failed to check for existing repeat: \(error.localizedDescription)")
            return true // Assume duplicate on error to prevent potential duplicates
        }
    }

    /// Process an RX log entry to check if it's a repeat of a sent message.
    ///
    /// Called by RxLogService for each new entry. Only processes successfully
    /// decrypted channel messages within the 10-second matching window.
    ///
    /// - Parameter entry: The RX log entry to process
    /// - Returns: The updated heardRepeats count if a match was found, nil otherwise
    @discardableResult
    public func processForRepeats(_ entry: RxLogEntryDTO) async -> Int? {
        // Only process successfully decrypted channel messages
        guard entry.payloadType == .groupText else { return nil }
        guard entry.decryptStatus == .success else { return nil }
        guard let decodedText = entry.decodedText else { return nil }
        guard let channelIndex = entry.channelIndex else { return nil }
        guard let senderTimestamp = entry.senderTimestamp else { return nil }
        guard let radioID = self.radioID else { return nil }
        guard let localNodeName = self.localNodeName else { return nil }

        // Parse "NodeName: MessageText" format using shared utility
        guard let (senderName, messageText) = ChannelMessageFormat.parse(decodedText) else {
            logger.info("Failed to parse channel message text: \(decodedText.prefix(50))")
            return nil
        }

        // Only match messages from our own node
        guard senderName == localNodeName else { return nil }

        // Check for duplicate (already processed this RX entry)
        if await isDuplicateRepeat(entry.id) {
            logger.info("Repeat already recorded for RX entry: \(entry.id)")
            return nil
        }

        // Find matching sent message
        do {
            guard let message = try await dataStore.findSentChannelMessage(
                radioID: radioID,
                channelIndex: channelIndex,
                timestamp: senderTimestamp,
                text: messageText,
                withinSeconds: 10
            ) else {
                return nil
            }

            // Create repeat entry
            let repeatDTO = MessageRepeatDTO(
                messageID: message.id,
                receivedAt: entry.receivedAt,
                pathNodes: entry.pathNodes,
                pathLength: entry.pathLength,
                snr: entry.snr,
                rssi: entry.rssi,
                rxLogEntryID: entry.id
            )

            try await dataStore.saveMessageRepeat(repeatDTO)

            // Increment and return new count
            let newCount = try await dataStore.incrementMessageHeardRepeats(id: message.id)

            logger.info("Recorded repeat #\(newCount) for message \(message.id)")

            eventBroadcaster.yield(HeardRepeatEvent(messageID: message.id, count: newCount))

            return newCount

        } catch {
            logger.error("Failed to process repeat: \(error.localizedDescription)")
            return nil
        }
    }

    /// Refresh repeats for a specific message by querying the RX log.
    /// Used when opening the Repeat Details sheet to catch any missed repeats.
    ///
    /// - Parameter messageID: The message to refresh repeats for
    /// - Returns: Array of repeat DTOs sorted by receivedAt
    public func refreshRepeats(for messageID: UUID) async -> [MessageRepeatDTO] {
        // Return existing repeats from database
        logger.info("refreshRepeats called for messageID: \(messageID)")
        do {
            let results = try await dataStore.fetchMessageRepeats(messageID: messageID)
            logger.info("refreshRepeats returning \(results.count) repeats")
            return results
        } catch {
            logger.error("Failed to fetch repeats: \(error.localizedDescription)")
            return []
        }
    }
}
