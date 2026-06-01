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

    @Test("WiFi uses ESP32 channel sync cooldown")
    @MainActor
    func wifiUsesESP32ChannelSyncCooldown() throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        let radioID = UUID()
        let attemptedAt = Date()

        manager.setTestState(
            detectedPlatform: .esp32,
            lastAttemptedChannelSync: (radioID: radioID, attemptedAt: attemptedAt)
        )

        let config = manager.currentChannelSyncConfig(for: radioID, transportType: .wifi)

        #expect(config.channelSyncSkipWindow == .seconds(30))
        #expect(config.lastAttemptedChannelSync == attemptedAt)
    }

    @Test("nRF52 over BLE enables pipelined channel reads")
    @MainActor
    func nrf52OverBLEEnablesPipelinedRead() throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        manager.setTestState(detectedPlatform: .nrf52)

        let config = manager.currentChannelSyncConfig(for: UUID(), transportType: .bluetooth)

        #expect(config.usePipelinedChannelRead)
    }

    @Test("nRF52 over WiFi does not pipeline channel reads")
    @MainActor
    func nrf52OverWiFiDoesNotPipeline() throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        manager.setTestState(detectedPlatform: .nrf52)

        let config = manager.currentChannelSyncConfig(for: UUID(), transportType: .wifi)

        #expect(!config.usePipelinedChannelRead)
    }

    @Test("ESP32 over BLE does not pipeline channel reads")
    @MainActor
    func esp32OverBLEDoesNotPipeline() throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        manager.setTestState(detectedPlatform: .esp32)

        let config = manager.currentChannelSyncConfig(for: UUID(), transportType: .bluetooth)

        #expect(!config.usePipelinedChannelRead)
    }

    @Test("Heartbeat pauses while syncing")
    @MainActor
    func heartbeatPausesWhileSyncing() throws {
        let (manager, _) = try ConnectionManager.createForTesting()

        manager.setTestState(connectionState: .syncing)

        #expect(manager.shouldPauseWiFiHeartbeatProbe)
    }
}
