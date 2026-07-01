import Foundation
@testable import MC1Services
import Testing

@Suite("DevicePlatform Channel Sync Config")
struct DevicePlatformChannelSyncConfigTests {
  @Test
  func `ESP32 has 30s channel sync skip window`() {
    let platform = DevicePlatform.esp32
    #expect(platform.channelSyncSkipWindow == .seconds(30))
  }

  @Test
  func `nRF52 has zero channel sync skip window`() {
    let platform = DevicePlatform.nrf52
    #expect(platform.channelSyncSkipWindow == .zero)
  }

  @Test
  func `Unknown has zero channel sync skip window`() {
    let platform = DevicePlatform.unknown
    #expect(platform.channelSyncSkipWindow == .zero)
  }

  @Test
  @MainActor
  func `WiFi uses ESP32 channel sync cooldown`() throws {
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

  @Test
  @MainActor
  func `nRF52 over BLE enables pipelined channel reads`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.setTestState(detectedPlatform: .nrf52)

    let config = manager.currentChannelSyncConfig(for: UUID(), transportType: .bluetooth)

    #expect(config.usePipelinedChannelRead)
  }

  @Test
  @MainActor
  func `nRF52 over WiFi does not pipeline channel reads`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.setTestState(detectedPlatform: .nrf52)

    let config = manager.currentChannelSyncConfig(for: UUID(), transportType: .wifi)

    #expect(!config.usePipelinedChannelRead)
  }

  @Test
  @MainActor
  func `ESP32 over BLE does not pipeline channel reads`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.setTestState(detectedPlatform: .esp32)

    let config = manager.currentChannelSyncConfig(for: UUID(), transportType: .bluetooth)

    #expect(!config.usePipelinedChannelRead)
  }

  @Test
  @MainActor
  func `ESP32 over WiFi enables pipelined channel reads`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.setTestState(detectedPlatform: .esp32)

    let config = manager.currentChannelSyncConfig(for: UUID(), transportType: .wifi)

    #expect(config.usePipelinedChannelRead)
  }

  @Test
  @MainActor
  func `WiFi connect resolves a recognized ESP32 model to ESP32`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.detectAndStorePlatform(model: "Heltec V3", transportType: .wifi)

    #expect(manager.detectedPlatform == .esp32)
  }

  @Test
  @MainActor
  func `WiFi connect resolves an unrecognized model to ESP32 (WiFi implies ESP32-class)`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.detectAndStorePlatform(model: "Totally Unknown Radio 9000", transportType: .wifi)

    #expect(manager.detectedPlatform == .esp32)

    // The skip window is earned and, because the resolved platform is ESP32 over WiFi,
    // the channel-read pipeline is also enabled.
    let config = manager.currentChannelSyncConfig(for: UUID(), transportType: .wifi)
    #expect(config.channelSyncSkipWindow == .seconds(30))
    #expect(config.usePipelinedChannelRead)
  }

  @Test
  @MainActor
  func `BLE connect leaves an unrecognized model as unknown (no WiFi coalesce)`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.detectAndStorePlatform(model: "Totally Unknown Radio 9000", transportType: .bluetooth)

    #expect(manager.detectedPlatform == .unknown)
  }

  @Test
  @MainActor
  func `BLE connect resolves an nRF52 model to nRF52`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.detectAndStorePlatform(model: "T1000-E", transportType: .bluetooth)

    #expect(manager.detectedPlatform == .nrf52)
  }

  @Test
  @MainActor
  func `Heartbeat pauses while syncing`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.setTestState(connectionState: .syncing)

    #expect(manager.shouldPauseWiFiHeartbeatProbe)
  }
}
