import Foundation

/// Receives log lines from the bridge and, on non-Apple platforms, from
/// MeshCore's internal logger.
///
/// Swift-on-Android debugging is LLDB-only with no turnkey symbolication, so the
/// bridge routes both its own diagnostics and MeshCore's `MeshCoreLog.sink`
/// output through this single callback. Each line is prefixed with a level token
/// naming its severity. Kotlin forwards these to logcat.
public protocol BridgeLogListener: AnyObject, Sendable {
  /// A single log line, level-prefixed.
  func onLog(line: String)
}
