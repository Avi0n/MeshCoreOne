import Foundation

/// A session's revocable view of its owner's physical transport.
public struct SessionBoundTransport: MeshTransport {
  private let transport: any MeshTransport
  public let binding: SessionTransportBinding

  public init(transport: any MeshTransport, binding: SessionTransportBinding) {
    self.transport = transport
    self.binding = binding
  }

  public func connect() async throws {
    try binding.beginOperation()
    defer { binding.endOperation() }
    try await transport.connect(sessionBinding: binding)
    try binding.checkActive()
  }

  public func disconnect() async {
    await transport.disconnect(sessionBinding: binding)
  }

  public func send(_ data: Data) async throws {
    try binding.beginOperation()
    defer { binding.endOperation() }
    try await transport.send(data, sessionBinding: binding)
  }

  public func sendWithoutResponse(_ data: Data) async throws {
    try binding.beginOperation()
    defer { binding.endOperation() }
    try await transport.sendWithoutResponse(data, sessionBinding: binding)
  }

  public var receivedData: AsyncStream<Data> {
    get async {
      guard binding.isActive else { return AsyncStream { $0.finish() } }
      return await transport.receivedData(sessionBinding: binding)
    }
  }

  public var isConnected: Bool {
    get async {
      guard binding.isActive else { return false }
      return await transport.isConnected
    }
  }

  public var supportsWriteWithoutResponse: Bool {
    get async { await transport.supportsWriteWithoutResponse }
  }

  public var supportsPipelinedReads: Bool {
    get async { await transport.supportsPipelinedReads }
  }
}
