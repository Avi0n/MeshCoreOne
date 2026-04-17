import Foundation

// MARK: - AppBackupEnvelope

/// Top-level container for a full app backup export.
/// All model data is stored as DTO arrays; non-SwiftData items (UserDefaults) have
/// dedicated fields.
public struct AppBackupEnvelope: Codable, Sendable {
    public static let currentVersion = 1

    public let version: Int
    public let exportDate: Date
    public let appVersion: String
    public let appBuild: String
    public var manifest: BackupManifest

    // SwiftData model arrays
    public var devices: [DeviceDTO]
    public var contacts: [ContactDTO]
    public var channels: [ChannelDTO]
    public var messages: [MessageDTO]
    public var messageRepeats: [MessageRepeatDTO]
    public var reactions: [ReactionDTO]
    public var roomMessages: [RoomMessageDTO]
    public var remoteNodeSessions: [RemoteNodeSessionDTO]
    public var savedTracePaths: [SavedTracePathDTO]
    public var blockedChannelSenders: [BlockedChannelSenderDTO]
    public var nodeStatusSnapshots: [NodeStatusSnapshotDTO]

    // Non-SwiftData
    public var userDefaults: BackupUserDefaults?

    public init(
        version: Int = AppBackupEnvelope.currentVersion,
        exportDate: Date = .now,
        appVersion: String,
        appBuild: String,
        manifest: BackupManifest = BackupManifest(),
        devices: [DeviceDTO] = [],
        contacts: [ContactDTO] = [],
        channels: [ChannelDTO] = [],
        messages: [MessageDTO] = [],
        messageRepeats: [MessageRepeatDTO] = [],
        reactions: [ReactionDTO] = [],
        roomMessages: [RoomMessageDTO] = [],
        remoteNodeSessions: [RemoteNodeSessionDTO] = [],
        savedTracePaths: [SavedTracePathDTO] = [],
        blockedChannelSenders: [BlockedChannelSenderDTO] = [],
        nodeStatusSnapshots: [NodeStatusSnapshotDTO] = [],
        userDefaults: BackupUserDefaults? = nil
    ) {
        self.version = version
        self.exportDate = exportDate
        self.appVersion = appVersion
        self.appBuild = appBuild
        self.manifest = manifest
        self.devices = devices
        self.contacts = contacts
        self.channels = channels
        self.messages = messages
        self.messageRepeats = messageRepeats
        self.reactions = reactions
        self.roomMessages = roomMessages
        self.remoteNodeSessions = remoteNodeSessions
        self.savedTracePaths = savedTracePaths
        self.blockedChannelSenders = blockedChannelSenders
        self.nodeStatusSnapshots = nodeStatusSnapshots
        self.userDefaults = userDefaults
    }

}

// MARK: - BackupModelKind

/// A model type carried in backups. Iterating `allCases` drives UI row
/// generation, totals, and manifest lookups from a single source of truth.
public enum BackupModelKind: String, CaseIterable, Sendable {
    case messages
    case contacts
    case channels
    case devices
    case roomMessages
    case reactions
    case messageRepeats
    case savedTracePaths
    case remoteNodeSessions
    case blockedChannelSenders
    case nodeStatusSnapshots
}

// MARK: - PerTypeCounts

/// Insert/merge/skip counts for a single model type during backup import.
public struct PerTypeCounts: Sendable, Equatable {
    public var inserted: Int
    public var merged: Int
    public var skipped: Int

    public static let zero = PerTypeCounts(inserted: 0, merged: 0, skipped: 0)

    public init(inserted: Int = 0, merged: Int = 0, skipped: Int = 0) {
        self.inserted = inserted
        self.merged = merged
        self.skipped = skipped
    }
}

// MARK: - BackupManifest

/// Declared counts per model type, used to validate backup integrity after decoding.
public struct BackupManifest: Codable, Sendable, Equatable {
    public var deviceCount: Int
    public var contactCount: Int
    public var channelCount: Int
    public var messageCount: Int
    public var messageRepeatCount: Int
    public var reactionCount: Int
    public var roomMessageCount: Int
    public var remoteNodeSessionCount: Int
    public var savedTracePathCount: Int
    public var blockedChannelSenderCount: Int
    public var nodeStatusSnapshotCount: Int

    public init(
        deviceCount: Int = 0,
        contactCount: Int = 0,
        channelCount: Int = 0,
        messageCount: Int = 0,
        messageRepeatCount: Int = 0,
        reactionCount: Int = 0,
        roomMessageCount: Int = 0,
        remoteNodeSessionCount: Int = 0,
        savedTracePathCount: Int = 0,
        blockedChannelSenderCount: Int = 0,
        nodeStatusSnapshotCount: Int = 0
    ) {
        self.deviceCount = deviceCount
        self.contactCount = contactCount
        self.channelCount = channelCount
        self.messageCount = messageCount
        self.messageRepeatCount = messageRepeatCount
        self.reactionCount = reactionCount
        self.roomMessageCount = roomMessageCount
        self.remoteNodeSessionCount = remoteNodeSessionCount
        self.savedTracePathCount = savedTracePathCount
        self.blockedChannelSenderCount = blockedChannelSenderCount
        self.nodeStatusSnapshotCount = nodeStatusSnapshotCount
    }

    /// Build a manifest from an envelope's actual array counts.
    public init(from envelope: AppBackupEnvelope) {
        self.deviceCount = envelope.devices.count
        self.contactCount = envelope.contacts.count
        self.channelCount = envelope.channels.count
        self.messageCount = envelope.messages.count
        self.messageRepeatCount = envelope.messageRepeats.count
        self.reactionCount = envelope.reactions.count
        self.roomMessageCount = envelope.roomMessages.count
        self.remoteNodeSessionCount = envelope.remoteNodeSessions.count
        self.savedTracePathCount = envelope.savedTracePaths.count
        self.blockedChannelSenderCount = envelope.blockedChannelSenders.count
        self.nodeStatusSnapshotCount = envelope.nodeStatusSnapshots.count
    }

    /// Returns the declared count for `kind`. Backs `BackupModelKind`-driven UI iteration.
    public func count(for kind: BackupModelKind) -> Int {
        switch kind {
        case .messages: messageCount
        case .contacts: contactCount
        case .channels: channelCount
        case .devices: deviceCount
        case .roomMessages: roomMessageCount
        case .reactions: reactionCount
        case .messageRepeats: messageRepeatCount
        case .savedTracePaths: savedTracePathCount
        case .remoteNodeSessions: remoteNodeSessionCount
        case .blockedChannelSenders: blockedChannelSenderCount
        case .nodeStatusSnapshots: nodeStatusSnapshotCount
        }
    }

    /// Returns `true` if the manifest counts match the actual array counts in the envelope.
    public func validate(against envelope: AppBackupEnvelope) -> Bool {
        self == BackupManifest(from: envelope)
    }
}

// MARK: - ImportResult

/// Tracks per-model insert/merge/skip counts during a backup import.
/// Storage is a `BackupModelKind`-keyed dict so totals and UI row iteration
/// stay consistent when a new model type is added.
public struct ImportResult: Sendable, Equatable {
    public var counts: [BackupModelKind: PerTypeCounts]
    public var userDefaultsRestored: Bool = false

    public init() {
        self.counts = Dictionary(
            uniqueKeysWithValues: BackupModelKind.allCases.map { ($0, .zero) }
        )
    }

    /// Adds to the counts for `kind`. All import phases funnel through this
    /// single mutator so totals and per-kind buckets can't drift.
    public mutating func record(
        _ kind: BackupModelKind,
        inserted: Int = 0,
        merged: Int = 0,
        skipped: Int = 0
    ) {
        var current = counts[kind, default: .zero]
        current.inserted += inserted
        current.merged += merged
        current.skipped += skipped
        counts[kind] = current
    }

    public var totalInserted: Int { counts.values.reduce(0) { $0 + $1.inserted } }
    public var totalMerged: Int { counts.values.reduce(0) { $0 + $1.merged } }
    public var totalSkipped: Int { counts.values.reduce(0) { $0 + $1.skipped } }

    public var totalRestoredRecordCount: Int { totalInserted + totalMerged }

    public var hasRestoredChanges: Bool {
        totalRestoredRecordCount > 0 || userDefaultsRestored
    }
}

func makeBackupJSONEncoder() -> JSONEncoder {
    let encoder = JSONEncoder()
    encoder.dateEncodingStrategy = .secondsSince1970
    // Deterministic key order gives zlib longer match windows on the
    // DTO-array-heavy payload — measurable ratio win at zero runtime cost.
    encoder.outputFormatting = .sortedKeys
    return encoder
}

func makeBackupJSONDecoder() -> JSONDecoder {
    let decoder = JSONDecoder()
    decoder.dateDecodingStrategy = .secondsSince1970
    return decoder
}

// MARK: - parseBackup

/// Maximum compressed backup size accepted by `parseBackup`. Larger files are
/// rejected up front so decompression never runs on clearly-oversized input.
public let maxBackupCompressedBytes = 50 * 1_048_576

/// Maximum uncompressed backup size accepted by `parseBackup`. Streaming
/// decompression aborts once the output crosses this cap so a highly
/// compressible (zip-bomb) payload can't OOM the app.
public let maxBackupUncompressedBytes = 512 * 1_048_576

/// Decompress, decode, and validate a backup file.
/// This is pure computation with no actor isolation needed.
///
/// - Parameters:
///   - data: Compressed backup data (zlib).
///   - maxUncompressedBytes: Upper bound on the decompressed payload. Tests
///     can pass a small value to exercise the cap without allocating the
///     production limit.
/// - Returns: A validated `AppBackupEnvelope`.
/// - Throws: `AppBackupError` on failure.
public func parseBackup(
    data: Data,
    maxUncompressedBytes: Int = maxBackupUncompressedBytes
) throws -> AppBackupEnvelope {
    guard data.count <= maxBackupCompressedBytes else {
        throw AppBackupError.fileTooLarge(
            actualBytes: data.count,
            maxBytes: maxBackupCompressedBytes
        )
    }

    let decompressed: Data
    do {
        decompressed = try data.zlibDecompressed(maxUncompressedBytes: maxUncompressedBytes)
    } catch let error as AppBackupError {
        throw error
    } catch {
        throw AppBackupError.invalidFile
    }

    let envelope: AppBackupEnvelope
    do {
        let decoder = makeBackupJSONDecoder()
        envelope = try decoder.decode(AppBackupEnvelope.self, from: decompressed)
    } catch {
        throw AppBackupError.invalidFile
    }

    guard envelope.version <= AppBackupEnvelope.currentVersion else {
        throw AppBackupError.unsupportedVersion(
            found: envelope.version,
            maxSupported: AppBackupEnvelope.currentVersion
        )
    }

    guard envelope.manifest.validate(against: envelope) else {
        throw AppBackupError.corruptedManifest
    }

    return envelope
}
