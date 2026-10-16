import Foundation
#if !canImport(os)
import MeshCore
#endif

/// Routes bridge diagnostics, and MeshCore's internal logger on non-Apple
/// platforms, to a single `BridgeLogListener`.
///
/// On Apple platforms MeshCore logs through `os.Logger`, so only the bridge's own
/// lines flow here. On Android/Linux MeshCore renders to `MeshCoreLog.sink`;
/// ``install(_:)`` repoints that sink at the same listener so the Kotlin side
/// sees one unified stream (the Phase 0 finding that on-device debugging is
/// LLDB-only makes this the primary diagnostic channel).
struct BridgeLog: Sendable {
  private let listener: any BridgeLogListener

  init(listener: any BridgeLogListener) {
    self.listener = listener
  }

  /// Redirects MeshCore's non-Apple log sink to this listener. A no-op on Apple.
  func install() {
    #if !canImport(os)
    let listener = listener
    MeshCoreLog.sink = { line in
      listener.onLog(line: line)
    }
    #endif
  }

  func debug(_ message: String) { listener.onLog(line: "DEBUG [bridge] \(message)") }
  func info(_ message: String) { listener.onLog(line: "INFO [bridge] \(message)") }
  func warning(_ message: String) { listener.onLog(line: "WARNING [bridge] \(message)") }
  func error(_ message: String) { listener.onLog(line: "ERROR [bridge] \(message)") }
}
