import Foundation
import MeshCore

/// A `MeshTransport` whose physical link lives in Kotlin.
///
/// MeshCore drives this like any other transport: `connect()` suspends until
/// Kotlin reports the link is up, `send`/`sendWithoutResponse` hand frames to the
/// `BridgeSendListener`, and inbound frames arrive via ``deliverReceived(_:)``.
/// Because the BLE/TCP connect is asynchronous on the Kotlin side, `connect()`
/// parks on a continuation that ``reportConnected()`` / ``reportConnectFailed(_:)``
/// resume.
actor KotlinTransport: MeshTransport {
  private let sendListener: any BridgeSendListener

  private let dataStream: AsyncStream<Data>
  // Nonisolated so inbound frames yield synchronously in the caller's order.
  // Routing each frame through an actor hop would let separate delivery calls
  // reorder, corrupting the framed byte stream. The default unbounded buffer
  // holds frames that arrive before the session's receive loop starts draining.
  nonisolated private let dataContinuation: AsyncStream<Data>.Continuation

  private var connected = false
  private var pendingConnect: CheckedContinuation<Void, Error>?

  init(sendListener: any BridgeSendListener) {
    self.sendListener = sendListener
    let (stream, continuation) = AsyncStream.makeStream(of: Data.self)
    dataStream = stream
    dataContinuation = continuation
  }

  // MARK: - MeshTransport

  var receivedData: AsyncStream<Data> {
    dataStream
  }

  var isConnected: Bool {
    connected
  }

  var supportsWriteWithoutResponse: Bool {
    sendListener.advertisesWriteWithoutResponse
  }

  var supportsPipelinedReads: Bool {
    sendListener.supportsPipelinedReads
  }

  func connect() async throws {
    if connected { return }
    // Only one connect can be outstanding; a second concurrent attempt fails
    // fast rather than silently orphaning the first continuation. The check is
    // atomic because connect() is actor-isolated.
    guard pendingConnect == nil else {
      throw MeshTransportError.connectionFailed("connect already in progress")
    }
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      pendingConnect = continuation
    }
  }

  func disconnect() async {
    connected = false
    dataContinuation.finish()
    resumePendingConnect(.failure(MeshTransportError.notConnected))
  }

  func send(_ data: Data) async throws {
    guard connected else { throw MeshTransportError.notConnected }
    sendListener.sendBytes(Array(data), withoutResponse: false)
  }

  func sendWithoutResponse(_ data: Data) async throws {
    guard connected else { throw MeshTransportError.notConnected }
    sendListener.sendBytes(Array(data), withoutResponse: true)
  }

  // MARK: - Kotlin-side signals

  /// Kotlin reports the physical link is established.
  func reportConnected() {
    connected = true
    resumePendingConnect(.success(()))
  }

  /// Kotlin reports the connect attempt failed.
  func reportConnectFailed(_ message: String) {
    connected = false
    resumePendingConnect(.failure(MeshTransportError.connectionFailed(message)))
  }

  /// Kotlin reports the link dropped after it was up. Finishes the receive
  /// stream so the session's receive loop observes the disconnect.
  func reportDisconnected() {
    connected = false
    dataContinuation.finish()
    resumePendingConnect(.failure(MeshTransportError.connectionFailed("disconnected during connect")))
  }

  /// Kotlin delivers an inbound frame. Each notification is a complete frame.
  /// Nonisolated so frames preserve call order (see ``dataContinuation``).
  nonisolated func deliverReceived(_ bytes: [UInt8]) {
    dataContinuation.yield(Data(bytes))
  }

  private func resumePendingConnect(_ result: Result<Void, Error>) {
    guard let continuation = pendingConnect else { return }
    pendingConnect = nil
    continuation.resume(with: result)
  }
}
