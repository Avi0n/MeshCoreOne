import Testing
@testable import MC1Services

@Suite("DeviceConnectionState")
struct DeviceConnectionStateTests {
    @Test("isOperational returns true only for syncing and ready")
    func isOperational() {
        #expect(!DeviceConnectionState.disconnected.isOperational)
        #expect(!DeviceConnectionState.connecting.isOperational)
        #expect(!DeviceConnectionState.connected.isOperational)
        #expect(DeviceConnectionState.syncing.isOperational)
        #expect(DeviceConnectionState.ready.isOperational)
    }

    @Test("isConnected returns true for connected, syncing, and ready")
    func isConnected() {
        #expect(!DeviceConnectionState.disconnected.isConnected)
        #expect(!DeviceConnectionState.connecting.isConnected)
        #expect(DeviceConnectionState.connected.isConnected)
        #expect(DeviceConnectionState.syncing.isConnected)
        #expect(DeviceConnectionState.ready.isConnected)
    }
}
