import Testing
@testable import MC1Services

@Suite("ConnectionState")
struct ConnectionStateTests {
    @Test("isOperational returns true only for syncing and ready")
    func isOperational() {
        #expect(!ConnectionState.disconnected.isOperational)
        #expect(!ConnectionState.connecting.isOperational)
        #expect(!ConnectionState.connected.isOperational)
        #expect(ConnectionState.syncing.isOperational)
        #expect(ConnectionState.ready.isOperational)
    }

    @Test("isConnected returns true for connected, syncing, and ready")
    func isConnected() {
        #expect(!ConnectionState.disconnected.isConnected)
        #expect(!ConnectionState.connecting.isConnected)
        #expect(ConnectionState.connected.isConnected)
        #expect(ConnectionState.syncing.isConnected)
        #expect(ConnectionState.ready.isConnected)
    }
}
