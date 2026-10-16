@testable import MC1Services
import Testing

@Suite("DeviceConnectionState")
struct DeviceConnectionStateTests {
  @Test
  func `isOperational returns true only for syncing and ready`() {
    #expect(!DeviceConnectionState.disconnected.isOperational)
    #expect(!DeviceConnectionState.connecting.isOperational)
    #expect(!DeviceConnectionState.connected.isOperational)
    #expect(DeviceConnectionState.syncing.isOperational)
    #expect(DeviceConnectionState.ready.isOperational)
  }

  @Test
  func `isConnected returns true for connected, syncing, and ready`() {
    #expect(!DeviceConnectionState.disconnected.isConnected)
    #expect(!DeviceConnectionState.connecting.isConnected)
    #expect(DeviceConnectionState.connected.isConnected)
    #expect(DeviceConnectionState.syncing.isConnected)
    #expect(DeviceConnectionState.ready.isConnected)
  }
}
