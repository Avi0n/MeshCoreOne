/// Renders one log interpolation the way unified logging does when no debugger is attached.
/// Numbers and booleans stay visible at the default privacy. Strings and other values do not.
enum MeshCoreLogRendering {
  static func render(_ value: Any, privacy: MeshCoreLogPrivacy) -> String {
    switch privacy {
    case .public:
      return String(describing: value)
    case .private, .sensitive:
      return redacted
    case .auto:
      guard isPublicByDefault(value) else { return redacted }
      return String(describing: value)
    }
  }

  private static let redacted = "<private>"

  private static func isPublicByDefault(_ value: Any) -> Bool {
    switch value {
    case is Bool,
         is Int, is Int8, is Int16, is Int32, is Int64,
         is UInt, is UInt8, is UInt16, is UInt32, is UInt64,
         is Float, is Double:
      true
    default:
      false
    }
  }
}

enum MeshCoreLogPrivacy: Equatable {
  case auto
  case `public`
  case `private`
  case sensitive
}

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
    private nonisolated(unsafe) static var handler: @Sendable (String) -> Void = { line in
      FileHandle.standardError.write(Data((line + "\n").utf8))
    }

    /// The sink every ``Logger`` and ``OSSignposter`` message is delivered to.
    /// Defaults to stderr; the Android bridge replaces it to route into logcat.
    public static var sink: @Sendable (String) -> Void {
      get { lock.withLock { handler } }
      set { lock.withLock { handler = newValue } }
    }

    static func emit(_ line: String) {
      sink(line)
    }
  }

  /// Privacy markers so `\(value, privacy: .public)` interpolation compiles unchanged.
  /// `.public` shows the value. `.private` and `.sensitive` do not.
  struct OSLogPrivacy: Sendable {
    fileprivate let level: MeshCoreLogPrivacy

    static let `public` = OSLogPrivacy(level: .public)
    static let `private` = OSLogPrivacy(level: .private)
    static let sensitive = OSLogPrivacy(level: .sensitive)
    static let auto = OSLogPrivacy(level: .auto)
  }

  /// A rendered log message. Interpolation keeps source order.
  /// Values that unified logging would redact are replaced before the line is emitted.
  struct OSLogMessage: ExpressibleByStringInterpolation, ExpressibleByStringLiteral, Sendable {
    let rendered: String

    struct StringInterpolation: StringInterpolationProtocol {
      var text = ""
      init(literalCapacity: Int, interpolationCount: Int) {
        text.reserveCapacity(literalCapacity)
      }

      mutating func appendLiteral(_ literal: String) {
        text += literal
      }

      mutating func appendInterpolation(
        _ value: @autoclosure () -> Any,
        privacy: OSLogPrivacy = .auto
      ) {
        text += MeshCoreLogRendering.render(value(), privacy: privacy.level)
      }
    }

    init(stringLiteral value: String) {
      rendered = value
    }

    init(stringInterpolation: StringInterpolation) {
      rendered = stringInterpolation.text
    }
  }

  /// Minimal `os.Logger` replacement that renders each message to ``MeshCoreLog``.
  struct Logger: Sendable {
    private let category: String

    init() {
      category = ""
    }

    init(subsystem: String, category: String) {
      self.category = category
    }

    func trace(_ message: OSLogMessage) {
      emit("TRACE", message)
    }

    func debug(_ message: OSLogMessage) {
      emit("DEBUG", message)
    }

    func info(_ message: OSLogMessage) {
      emit("INFO", message)
    }

    func notice(_ message: OSLogMessage) {
      emit("NOTICE", message)
    }

    func warning(_ message: OSLogMessage) {
      emit("WARNING", message)
    }

    func error(_ message: OSLogMessage) {
      emit("ERROR", message)
    }

    func critical(_ message: OSLogMessage) {
      emit("CRITICAL", message)
    }

    func fault(_ message: OSLogMessage) {
      emit("FAULT", message)
    }

    func log(_ message: OSLogMessage) {
      emit("LOG", message)
    }

    private func emit(_ level: String, _ message: OSLogMessage) {
      let prefix = category.isEmpty ? "" : "[\(category)] "
      MeshCoreLog.emit("\(prefix)\(level) \(message.rendered)")
    }
  }

  /// Minimal `OSSignposter` replacement. Instruments has no equivalent off Apple,
  /// so events are routed to the same recoverable log sink.
  struct OSSignposter: Sendable {
    private let category: String

    init() {
      category = ""
    }

    init(subsystem: String, category: String) {
      self.category = category
    }

    func emitEvent(_ name: StaticString, _ message: OSLogMessage) {
      let prefix = category.isEmpty ? "" : "[\(category)] "
      MeshCoreLog.emit("\(prefix)SIGNPOST \(name) \(message.rendered)")
    }
  }
#endif
