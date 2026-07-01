import Foundation
import os

/// Drop-in replacement for Logger that uses the shared DebugLogBuffer.
/// Writes to both OSLog (for system integration) and SwiftData (for persistence).
public struct PersistentLogger: Sendable {
  private let logger: Logger
  private let subsystem: String
  private let category: String

  public init(subsystem: String, category: String) {
    logger = Logger(subsystem: subsystem, category: category)
    self.subsystem = subsystem
    self.category = category
  }

  public func debug(_ message: String) {
    logger.debug("\(message)")
    persist(level: .debug, message: message)
  }

  public func info(_ message: String) {
    logger.info("\(message)")
    persist(level: .info, message: message)
  }

  public func notice(_ message: String) {
    logger.notice("\(message)")
    persist(level: .notice, message: message)
  }

  public func warning(_ message: String) {
    logger.warning("\(message)")
    persist(level: .warning, message: message)
  }

  public func error(_ message: String) {
    logger.error("\(message)")
    persist(level: .error, message: message)
  }

  public func fault(_ message: String) {
    logger.fault("\(message)")
    persist(level: .fault, message: message)
  }

  private func persist(level: DebugLogLevel, message: String) {
    guard let buffer = DebugLogBuffer.shared else { return }

    let dto = DebugLogEntryDTO(
      level: level,
      subsystem: subsystem,
      category: category,
      message: message
    )
    Task {
      await buffer.append(dto)
    }
  }
}
