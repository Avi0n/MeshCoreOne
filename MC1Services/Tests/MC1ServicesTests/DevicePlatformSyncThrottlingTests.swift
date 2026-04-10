import Testing
import Foundation
@testable import MC1Services

@Suite("DevicePlatform Channel Sync Config")
struct DevicePlatformChannelSyncConfigTests {

    @Test("ESP32 has 30s channel sync skip window")
    func esp32ChannelSyncSkipWindow() {
        let platform = DevicePlatform.esp32
        #expect(platform.channelSyncSkipWindow == .seconds(30))
    }

    @Test("nRF52 has zero channel sync skip window")
    func nrf52ChannelSyncSkipWindow() {
        let platform = DevicePlatform.nrf52
        #expect(platform.channelSyncSkipWindow == .zero)
    }

    @Test("Unknown has zero channel sync skip window")
    func unknownChannelSyncSkipWindow() {
        let platform = DevicePlatform.unknown
        #expect(platform.channelSyncSkipWindow == .zero)
    }
}
