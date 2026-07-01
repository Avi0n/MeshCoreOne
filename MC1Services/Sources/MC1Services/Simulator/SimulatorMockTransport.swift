import Foundation
import MeshCore

/// Mock transport for simulator connections.
/// This is a minimal stub that fulfills the MeshTransport protocol
/// but doesn't actually communicate with a device.
actor SimulatorMockTransport: MeshTransport {
  private let continuation: AsyncStream<Data>.Continuation
  private var _isConnected = false

  /// Stream of received data (always empty for simulator)
  let receivedData: AsyncStream<Data>

  var isConnected: Bool {
    _isConnected
  }

  init() {
    var cont: AsyncStream<Data>.Continuation!
    receivedData = AsyncStream { continuation in
      cont = continuation
    }
    continuation = cont
  }

  func connect() async throws {
    _isConnected = true
  }

  func disconnect() async {
    continuation.finish()
    _isConnected = false
  }

  func send(_ data: Data) async throws {
    guard _isConnected else {
      throw MeshTransportError.notConnected
    }
    // Simulator transport doesn't actually send data
  }
}
