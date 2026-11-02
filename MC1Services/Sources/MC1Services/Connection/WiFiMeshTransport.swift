import Foundation
import MeshCore

/// Socket construction is injectable while session setup stays in the connection runner.
public protocol WiFiMeshTransport: MeshTransport, AnyObject {
  var connectionInfo: (host: String, port: UInt16)? { get async }
  func setConnectionInfo(host: String, port: UInt16) async
  func setDisconnectionHandler(_ handler: @escaping @Sendable (Error?, CFAbsoluteTime) -> Void) async
}

extension WiFiTransport: WiFiMeshTransport {}
