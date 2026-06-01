import Testing
import Foundation
@testable import MeshCore

@Suite("MeshTransport default capability behavior")
struct MeshTransportDefaultsTests {

    @Test("Default sendWithoutResponse forwards to send")
    func defaultSendWithoutResponseForwardsToSend() async throws {
        let transport = MockTransport()
        try await transport.connect()
        let payload = Data([0xAB, 0xCD])

        try await transport.sendWithoutResponse(payload)

        let sent = await transport.sentData
        #expect(sent == [payload])
    }

    @Test("Default supportsWriteWithoutResponse is false")
    func defaultSupportsWriteWithoutResponseIsFalse() async {
        let transport = MockTransport()
        let supported = await transport.supportsWriteWithoutResponse
        #expect(supported == false)
    }
}
