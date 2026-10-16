#if !canImport(os)
import Foundation

// Source-compatible stand-ins for the Apple `os` logging API, compiled only
// where `os` is unavailable (Linux/Android). Call sites keep their
// `Logger(subsystem:category:)` / `logger.info("...")` shape unchanged; here
// those calls render the message and hand it to ``MeshCoreLog/sink``, which the
// Android bridge repoints at logcat. Defaults to stderr so nothing is silently lost.

/// Global destination for rendered log lines on non-Apple platforms.
public enum MeshCoreLog {
  private static let lock = NSLock()
  nonisolated(unsafe) private static var handler: @Sendable (String) -> Void = { line in
    FileHandle.standardError.write(Data((line + "\n").utf8))
  }

  /// The sink every ``Logger`` and ``OSSignposter`` message is delivered to.
  /// Defaults to stderr; the Android bridge replaces it to route into logcat.
  public static var sink: @Sendable (String) -> Void {
    get { lock.withLock { handler } }
    set { lock.withLock { handler = newValue } }
  }

  static func emit(_ line: String) { sink(line) }
}

/// No-op privacy markers; the fields exist so `\(value, privacy: .public)`
/// interpolation compiles unchanged.
struct OSLogPrivacy: Sendable {
  static let `public` = OSLogPrivacy()
  static let `private` = OSLogPrivacy()
  static let sensitive = OSLogPrivacy()
  static let auto = OSLogPrivacy()
}

/// A rendered log message. String interpolation is evaluated eagerly, dropping
/// only the privacy qualifier, so the recovered text matches the source order.
struct OSLogMessage: ExpressibleByStringInterpolation, ExpressibleByStringLiteral, Sendable {
  let rendered: String

  struct StringInterpolation: StringInterpolationProtocol {
    var text = ""
    init(literalCapacity: Int, interpolationCount: Int) { text.reserveCapacity(literalCapacity) }
    mutating func appendLiteral(_ literal: String) { text += literal }
    mutating func appendInterpolation(
      _ value: @autoclosure () -> Any,
      privacy: OSLogPrivacy = .auto
    ) {
      text += String(describing: value())
    }
  }

  init(stringLiteral value: String) { rendered = value }
  init(stringInterpolation: StringInterpolation) { rendered = stringInterpolation.text }
}

/// Minimal `os.Logger` replacement that renders each message to ``MeshCoreLog``.
struct Logger: Sendable {
  private let category: String

  init() { category = "" }
  init(subsystem: String, category: String) { self.category = category }

  func trace(_ message: OSLogMessage) { emit("TRACE", message) }
  func debug(_ message: OSLogMessage) { emit("DEBUG", message) }
  func info(_ message: OSLogMessage) { emit("INFO", message) }
  func notice(_ message: OSLogMessage) { emit("NOTICE", message) }
  func warning(_ message: OSLogMessage) { emit("WARNING", message) }
  func error(_ message: OSLogMessage) { emit("ERROR", message) }
  func critical(_ message: OSLogMessage) { emit("CRITICAL", message) }
  func fault(_ message: OSLogMessage) { emit("FAULT", message) }
  func log(_ message: OSLogMessage) { emit("LOG", message) }

  private func emit(_ level: String, _ message: OSLogMessage) {
    let prefix = category.isEmpty ? "" : "[\(category)] "
    MeshCoreLog.emit("\(prefix)\(level) \(message.rendered)")
  }
}

/// Minimal `OSSignposter` replacement. Instruments has no equivalent off Apple,
/// so events are routed to the same recoverable log sink.
struct OSSignposter: Sendable {
  private let category: String

  init() { category = "" }
  init(subsystem: String, category: String) { self.category = category }

  func emitEvent(_ name: StaticString, _ message: OSLogMessage) {
    let prefix = category.isEmpty ? "" : "[\(category)] "
    MeshCoreLog.emit("\(prefix)SIGNPOST \(name) \(message.rendered)")
  }
}
#endif
