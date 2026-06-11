import Foundation
import SwiftData

/// A saved trace path configuration for re-use
@Model
public final class SavedTracePath {
    @Attribute(.unique)
    public var id: UUID

    /// The device this path belongs to
    @Attribute(originalName: "deviceID")
    public var radioID: UUID

    /// User-editable name (e.g., "Tower → Barn → Ridge")
    public var name: String

    /// The full path bytes (outbound + return)
    public var pathBytes: Data

    /// Bytes per hop hash when the path was saved (1, 2, or 4)
    public var hashSize: Int = 1

    /// When this path was first saved
    public var createdDate: Date

    /// Historical runs of this path
    @Relationship(deleteRule: .cascade, inverse: \TracePathRun.savedPath)
    public var runs: [TracePathRun]

    public init(
        id: UUID = UUID(),
        radioID: UUID,
        name: String,
        pathBytes: Data,
        hashSize: Int = 1,
        createdDate: Date = Date()
    ) {
        self.id = id
        self.radioID = radioID
        self.name = name
        self.pathBytes = pathBytes
        self.hashSize = hashSize
        self.createdDate = createdDate
        self.runs = []
    }
}

/// A single execution of a saved trace path
@Model
public final class TracePathRun {
    @Attribute(.unique)
    public var id: UUID

    /// When this run occurred
    public var date: Date

    /// Whether the trace completed successfully
    public var success: Bool

    /// Round-trip time in milliseconds (0 if failed)
    public var roundTripMs: Int

    /// Encoded per-hop SNR data (JSON array of doubles)
    public var hopsData: Data

    /// The saved path this run belongs to
    public var savedPath: SavedTracePath?

    public init(
        id: UUID = UUID(),
        date: Date = Date(),
        success: Bool,
        roundTripMs: Int,
        hopsData: Data
    ) {
        self.id = id
        self.date = date
        self.success = success
        self.roundTripMs = roundTripMs
        self.hopsData = hopsData
    }

    /// Builds a model instance directly from a DTO, re-encoding `hopsSNR` to
    /// the JSON-array format used by `hopsData` storage. Shared by the
    /// diagnostics and backup insert paths so the encoding stays consistent.
    public convenience init(dto: TracePathRunDTO) throws {
        let hopsData = try JSONEncoder().encode(dto.hopsSNR)
        self.init(
            id: dto.id,
            date: dto.date,
            success: dto.success,
            roundTripMs: dto.roundTripMs,
            hopsData: hopsData
        )
    }
}

// MARK: - Computed Properties

public extension SavedTracePath {
    /// Number of runs for this path
    var runCount: Int { runs.count }

    /// Most recent run date
    var lastRunDate: Date? {
        runs.max(by: { $0.date < $1.date })?.date
    }

    /// Average round-trip time of successful runs
    var averageRoundTripMs: Int? {
        let successful = runs.filter { $0.success }
        guard !successful.isEmpty else { return nil }
        let total = successful.reduce(0) { $0 + $1.roundTripMs }
        return total / successful.count
    }

    /// Success rate as a percentage (0-100)
    var successRate: Int {
        guard !runs.isEmpty else { return 100 }
        let successful = runs.filter { $0.success }.count
        return (successful * 100) / runs.count
    }
}

public extension TracePathRun {
    /// Decode hops SNR data to array of doubles
    var hopsSNR: [Double] {
        guard let decoded = try? JSONDecoder().decode([Double].self, from: hopsData) else {
            return []
        }
        return decoded
    }
}

// MARK: - DTOs

/// Sendable snapshot of SavedTracePath for cross-actor transfers
public struct SavedTracePathDTO: Sendable, Identifiable, Equatable, Hashable, Codable {
    public let id: UUID
    public var radioID: UUID
    public let name: String
    public let pathBytes: Data
    public let hashSize: Int
    public let createdDate: Date
    public let runs: [TracePathRunDTO]

    public init(from model: SavedTracePath) {
        self.id = model.id
        self.radioID = model.radioID
        self.name = model.name
        self.pathBytes = model.pathBytes
        self.hashSize = model.hashSize
        self.createdDate = model.createdDate
        self.runs = model.runs.map { TracePathRunDTO(from: $0) }
    }

    public init(
        id: UUID,
        radioID: UUID,
        name: String,
        pathBytes: Data,
        hashSize: Int = 1,
        createdDate: Date,
        runs: [TracePathRunDTO]
    ) {
        self.id = id
        self.radioID = radioID
        self.name = name
        self.pathBytes = pathBytes
        self.hashSize = hashSize
        self.createdDate = createdDate
        self.runs = runs
    }

    public var runCount: Int { runs.count }

    public var lastRunDate: Date? {
        runs.max(by: { $0.date < $1.date })?.date
    }

    public var averageRoundTripMs: Int? {
        let successful = runs.filter { $0.success }
        guard !successful.isEmpty else { return nil }
        let total = successful.reduce(0) { $0 + $1.roundTripMs }
        return total / successful.count
    }

    public var successRate: Int {
        guard !runs.isEmpty else { return 100 }
        let successful = runs.filter { $0.success }.count
        return (successful * 100) / runs.count
    }

    /// Recent RTT values for sparkline (most recent 10)
    public var recentRTTs: [Int] {
        runs.filter { $0.success }
            .sorted { $0.date > $1.date }
            .prefix(10)
            .reversed()
            .map { $0.roundTripMs }
    }

    /// The path as array of hash bytes
    public var pathHashBytes: [UInt8] {
        Array(pathBytes)
    }
}

/// Sendable snapshot of TracePathRun
public struct TracePathRunDTO: Sendable, Identifiable, Equatable, Hashable, Codable {
    public let id: UUID
    public let date: Date
    public let success: Bool
    public let roundTripMs: Int
    public let hopsSNR: [Double]

    public init(from model: TracePathRun) {
        self.id = model.id
        self.date = model.date
        self.success = model.success
        self.roundTripMs = model.roundTripMs
        self.hopsSNR = model.hopsSNR
    }

    public init(id: UUID, date: Date, success: Bool, roundTripMs: Int, hopsSNR: [Double]) {
        self.id = id
        self.date = date
        self.success = success
        self.roundTripMs = roundTripMs
        self.hopsSNR = hopsSNR
    }
}
