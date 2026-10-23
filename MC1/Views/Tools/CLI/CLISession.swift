import Foundation

struct CLISession: Identifiable, Hashable {
  let id: UUID
  let name: String
  let isLocal: Bool
  let pathLength: UInt8
  /// Nil on the local session. Set from the contact type at login.
  let remoteTarget: CLIRemoteTarget?

  static func local(deviceName: String) -> CLISession {
    CLISession(id: UUID(), name: deviceName, isLocal: true, pathLength: 0, remoteTarget: nil)
  }

  static func remote(id: UUID, name: String, pathLength: UInt8, remoteTarget: CLIRemoteTarget) -> CLISession {
    CLISession(id: id, name: name, isLocal: false, pathLength: pathLength, remoteTarget: remoteTarget)
  }
}
