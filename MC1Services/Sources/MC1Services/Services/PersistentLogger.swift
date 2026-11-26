import Foundation
import os

/// Drop-in replacement for Logger that uses the shared DebugLogBuffer.
/// Writes to OSLog for every level; `.info` and above also persist to SwiftData.
public struct PersistentLogger: Sendable {
  private let osLog: OSLog
  private let logger: Logger
  private let subsystem: String
  private let category: String

  public init(subsystem: String, category: String) {
    let osLog = OSLog(subsystem: subsystem, category: category)
    self.osLog = osLog
    logger = Logger(osLog)
    self.subsystem = subsystem
    self.category = category
  }

  public func debug(_ message: @autoclosure () -> String) {
    // Logger.isEnabled is a libswiftos export added in iOS 26.4. A strong
    // reference aborts launch on iOS 18. OSLog.isEnabled is os_log_type_enabled.
    guard osLog.isEnabled(type: .debug) else { return }
    let text = message()
    logger.debug("\(text)")
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
    guard DebugLogBuffer.isCollectionEnabled else { return }
    DebugLogBuffer.record(DebugLogEntryDTO(
      level: level,
      subsystem: subsystem,
      category: category,
      message: message
    ))
  }
}
