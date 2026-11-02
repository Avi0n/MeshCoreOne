import Foundation
import MeshCore
import Network
import Testing

/// A framed TCP endpoint backed by the same stateful responder as the manager's BLE fixture.
actor ConnectionLoopbackRadioPeer {
  private enum Event: Sendable {
    case listener(NWListener.State)
    case accepted(NWConnection)
  }

  private enum Limits {
    static let receiveSize = 65536
    static let lengthLowOffset = 1
    static let lengthHighOffset = 2
    static let byteShift = 8
  }

  let radio: ConnectionTestRadioTransport
  private let listener: NWListener
  private let events: AsyncStream<Event>
  private let continuation: AsyncStream<Event>.Continuation
  private var listenerWorker: Task<Void, Never>?
  private var receiveWorkers: [Task<Void, Never>] = []
  private var connections: [NWConnection] = []
  private var closing = false
  private(set) var ready = false
  private(set) var listenerEnded = false
  private(set) var receiveEndedCount = 0
  private(set) var failure: NWError?
  private(set) var finishedWriteCount = 0

  var port: UInt16? {
    listener.port?.rawValue
  }

  var acceptedCount: Int {
    connections.count
  }

  var stopped: Bool {
    listenerEnded && receiveEndedCount == receiveWorkers.count
  }

  private init(listener: NWListener, radio: ConnectionTestRadioTransport) {
    self.listener = listener
    self.radio = radio
    (events, continuation) = AsyncStream.makeStream(of: Event.self)
  }

  @MainActor
  static func make(publicKey: Data) async throws -> ConnectionLoopbackRadioPeer {
    let peer = try ConnectionLoopbackRadioPeer(
      listener: NWListener(using: .tcp), radio: ConnectionTestRadioTransport(publicKey: publicKey)
    )
    await peer.start()
    do {
      try await waitUntil("localhost listener must become ready") {
        let ready = await peer.ready
        let failed = await peer.failure != nil
        return ready || failed
      }
      if let failure = await peer.failure { throw failure }
      return peer
    } catch {
      await peer.close()
      throw error
    }
  }

  private func start() {
    let continuation = continuation
    listener.stateUpdateHandler = { continuation.yield(.listener($0)) }
    listener.newConnectionHandler = { continuation.yield(.accepted($0)) }
    listenerWorker = Task {
      for await event in events {
        handle(event)
      }
    }
    listener.start(queue: DispatchQueue(label: "test.connection-loopback-radio"))
  }

  private func handle(_ event: Event) {
    switch event {
    case let .listener(state):
      switch state {
      case .ready: ready = true
      case let .failed(error): failure = error
      case .cancelled:
        listenerEnded = true
        continuation.finish()
      default: break
      }
    case let .accepted(connection):
      guard !closing else { connection.cancel(); return }
      connections.append(connection)
      connection.start(queue: DispatchQueue(label: "test.connection-loopback-client"))
      receiveWorkers.append(Task { await serve(connection) })
    }
  }

  private func serve(_ connection: NWConnection) async {
    defer { receiveEndedCount += 1 }
    var buffer = Data()
    do {
      while !closing {
        let (content, complete) = try await Self.receive(connection)
        if let content { buffer.append(content) }
        while buffer.count >= WiFiFrameCodec.headerSize {
          let start = buffer.startIndex
          guard buffer[start] == WiFiFrameCodec.outboundDelimiter else {
            Issue.record("Client submitted an invalid WiFi frame delimiter")
            return
          }
          let length = Int(buffer[start + Limits.lengthLowOffset])
            | (Int(buffer[start + Limits.lengthHighOffset]) << Limits.byteShift)
          let size = WiFiFrameCodec.headerSize + length
          guard buffer.count >= size else { break }
          let payload = Data(buffer[(start + WiFiFrameCodec.headerSize)..<(start + size)])
          buffer.removeFirst(size)
          for reply in await radio.receiveFramedCommand(payload) {
            var frame = WiFiFrameCodec.encode(reply)
            frame[frame.startIndex] = WiFiFrameCodec.inboundDelimiter
            try await Self.send(frame, on: connection)
          }
        }
        if complete { return }
      }
    } catch {
      if !closing, finishedWriteCount == 0 { Issue.record("Loopback radio receive failed: \(error)") }
    }
  }

  func finishAcceptedConnection() async throws {
    let connection = try #require(connections.last)
    finishedWriteCount += 1
    do { try await Self.send(nil, on: connection, final: true) } catch { finishedWriteCount -= 1; throw error }
  }

  func close() async {
    closing = true
    listener.cancel()
    for connection in connections {
      connection.cancel()
    }
    for worker in receiveWorkers {
      await worker.value
    }
    await listenerWorker?.value
    listener.stateUpdateHandler = nil
    listener.newConnectionHandler = nil
  }

  func prepareToClose() {
    closing = true
  }

  private nonisolated static func receive(_ connection: NWConnection) async throws -> (Data?, Bool) {
    try await withCheckedThrowingContinuation { continuation in
      connection.receive(minimumIncompleteLength: 1, maximumLength: Limits.receiveSize) { content, _, complete, error in
        if let error { continuation.resume(throwing: error) } else { continuation.resume(returning: (content, complete)) }
      }
    }
  }

  private nonisolated static func send(_ content: Data?, on connection: NWConnection, final: Bool = false) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      connection.send(content: content, contentContext: final ? .finalMessage : .defaultMessage, isComplete: true,
                      completion: .contentProcessed { error in
                        if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                      })
    }
  }
}
