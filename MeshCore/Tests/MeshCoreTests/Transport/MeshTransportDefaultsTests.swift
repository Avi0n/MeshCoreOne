import Foundation
@testable import MeshCore
import Testing

@Suite("MeshTransport default capability behavior")
struct MeshTransportDefaultsTests {
  @Test
  func `Default sendWithoutResponse forwards to send`() async throws {
    let transport = MockTransport()
    try await transport.connect()
    let payload = Data([0xAB, 0xCD])

    try await transport.sendWithoutResponse(payload)

    let sent = await transport.sentData
    #expect(sent == [payload])
  }

  @Test
  func `Default supportsWriteWithoutResponse is false`() async {
    let transport = MockTransport()
    let supported = await transport.supportsWriteWithoutResponse
    #expect(supported == false)
  }

  @Test
  func `Default supportsPipelinedReads mirrors supportsWriteWithoutResponse`() async {
    let transport = MockTransport()
    let defaultPipelined = await transport.supportsPipelinedReads
    #expect(defaultPipelined == false)

    await transport.setSupportsWriteWithoutResponse(true)
    let mirroredPipelined = await transport.supportsPipelinedReads
    #expect(mirroredPipelined == true)
  }
}
