import Foundation

struct CLISession: Identifiable, Hashable {
  let id: UUID
  let name: String
  let isLocal: Bool
  let pathLength: UInt8

  static func local(deviceName: String) -> CLISession {
    CLISession(id: UUID(), name: deviceName, isLocal: true, pathLength: 0)
  }

  static func remote(id: UUID, name: String, pathLength: UInt8) -> CLISession {
    CLISession(id: id, name: name, isLocal: false, pathLength: pathLength)
  }
}
