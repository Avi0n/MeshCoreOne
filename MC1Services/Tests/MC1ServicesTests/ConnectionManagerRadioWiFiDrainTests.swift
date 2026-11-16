import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("ConnectionManager WiFi mutation drain")
@MainActor
struct ConnectionManagerRadioWiFiDrainTests {
  @Test
  func `replacement radio waits for a submitted Tools write and retains only its source evidence`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness(wifiIsSameRadio: false) { harness in
      try await harness.connectBluetooth()
      let original = try #require(harness.manager.currentConnection)
      let stateA = original.services.settingsService.radioWiFiState
      let stateB = harness.manager.radioWiFiState(for: harness.deviceB.radioID)
      let stream = await original.services.settingsService.events()
      let consumer = harness.track(Task { for await _ in stream {} })
      await harness.bluetooth.holdNextSendCompletion()
      let write = harness.track(Task {
        try await original.services.settingsService.runCliCommand("set wifi.pwd synthetic-terminal")
      })
      try await waitUntil("Tools setter should reach actual physical completion gate") {
        await harness.bluetooth.sendCompletionIsHeld
      }
      #expect(await harness.bluetooth.cliCommands.contains("set wifi.pwd synthetic-terminal"))
      write.cancel()
      consumer.cancel()
      await consumer.value
      let replacement = harness.track(Task {
        try await harness.manager.connectViaWiFi(host: "radio.local", port: 4242)
      })
      try await waitUntil("replacement should retire the old publication") {
        harness.manager.currentConnection == nil && harness.manager.connectionTeardownCount > 0
      }
      #expect(await harness.wifi.connectCount == 0)
      #expect(!stateB.rebootPending)

      await harness.bluetooth.releaseSendCompletion()
      _ = await write.result
      _ = try await replacement.value

      #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
      #expect(harness.manager.connectionState == .ready)
      #expect(stateA.rebootPending)
      #expect(stateA.stableRevision() != nil)
      #expect(!stateB.rebootPending)
      #expect(harness.manager.services?.settingsService.radioWiFiState === stateB)
      try await harness.connectBluetooth()
      #expect(harness.manager.services?.settingsService.radioWiFiState === stateA)
      #expect(stateA.rebootPending)
    }
  }
}
