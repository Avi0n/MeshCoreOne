import Foundation
import SwiftData

/// SwiftData model for persisted debug log entries.
@Model
final class DebugLogEntry {
  #Index<DebugLogEntry>([\.timestamp])

  @Attribute(.unique)
  var id: UUID

  var timestamp: Date
  var level: Int
  var subsystem: String
  var category: String
  var message: String

  init(
    id: UUID = UUID(),
    timestamp: Date = Date(),
    level: Int,
    subsystem: String,
    category: String,
    message: String
  ) {
    self.id = id
    self.timestamp = timestamp
    self.level = level
    self.subsystem = subsystem
    self.category = category
    self.message = message
  }
}

/// Sendable DTO for cross-actor transfer of DebugLogEntry data.
public struct DebugLogEntryDTO: Sendable, Identifiable, Equatable, Hashable {
  public let id: UUID
  public let timestamp: Date
  public let level: DebugLogLevel
  public let subsystem: String
  public let category: String
  public let message: String

  public init(
    id: UUID = UUID(),
    timestamp: Date = Date(),
    level: DebugLogLevel,
    subsystem: String,
    category: String,
    message: String
  ) {
    self.id = id
    self.timestamp = timestamp
    self.level = level
    self.subsystem = subsystem
    self.category = category
    // Truncate message to prevent memory issues
    self.message = String(message.prefix(4000))
  }

  /// Initialize from SwiftData model.
  init(from model: DebugLogEntry) {
    id = model.id
    timestamp = model.timestamp
    level = DebugLogLevel(rawValue: model.level) ?? .info
    subsystem = model.subsystem
    category = model.category
    message = model.message
  }
}
