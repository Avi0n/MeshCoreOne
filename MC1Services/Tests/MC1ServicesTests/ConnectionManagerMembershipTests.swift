import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("ConnectionManager membership handoff")
@MainActor
struct ConnectionManagerMembershipTests {
  @Test
  func `same-radio WiFi handoff restores only connected source memberships after readiness`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      let activeA = try await harness.addMembership(radioID: harness.deviceA.radioID, connected: true)
      let manualA = try await harness.addMembership(radioID: harness.deviceA.radioID, connected: false)
      let activeB = try await harness.addMembership(radioID: harness.deviceB.radioID, connected: true)
      let channelSecret = Data(repeating: 0xC1, count: 16)
      await harness.bluetooth.setChannel(index: 0, name: "Queue", secret: channelSecret)
      await harness.wifi.setChannel(index: 0, name: "Queue", secret: channelSecret)
      try await harness.connectBluetooth()
      #expect(await loginDestinations(harness.bluetooth).isEmpty)
      #expect(await loginDestinations(harness.wifi).isEmpty)
      await harness.wifi.holdLoginResults()
      let handoff = harness.track(Task {
        try await harness.manager.connectViaWiFi(host: "radio.local", port: 4242)
      })
      try await waitUntil("room login result should remain held after readiness") {
        await harness.wifi.heldLoginResultCount == 1
      }
      let current = try #require(harness.manager.currentConnection)
      #expect(current.device.radioID == harness.deviceA.radioID)
      #expect(harness.manager.connectionState == .ready)
      #expect(harness.manager.wifiHeartbeatTask != nil)
      #expect(harness.manager.membershipBatch?.sessionIDs == [activeA.id])
      #expect(try await !harness.isConnected(activeA))
      #expect(try await !harness.isConnected(manualA))
      #expect(try await harness.isConnected(activeB))

      let message = try await current.services.messageService.createPendingChannelMessage(
        text: "queue remains usable", channelIndex: 0, radioID: current.device.radioID
      )
      try await current.services.chatSendQueueService.enqueueChannel(ChannelMessageEnvelope(
        messageID: message.id, channelIndex: 0, isResend: false,
        messageText: message.text, messageTimestamp: message.timestamp, localNodeName: nil
      ))
      try await waitUntil("channel queue should finish while room login remains held") {
        await (try? harness.manager.persistenceStore.fetchMessage(id: message.id))?.status == .sent
      }
      #expect(await harness.wifi.heldLoginResultCount == 1)
      #expect(await loginDestinations(harness.wifi) == [activeA.publicKey])
      await harness.wifi.releaseLoginResults()
      _ = try await handoff.value

      #expect(try await harness.isConnected(activeA))
      #expect(try await !harness.isConnected(manualA))
      #expect(try await harness.isConnected(activeB))
      #expect(harness.manager.membershipBatch?.sessionIDs.isEmpty == true)
    }
  }

  @Test
  func `foreign WiFi identity discards source restoration without touching the other partition`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness(wifiIsSameRadio: false) { harness in
      let activeA = try await harness.addMembership(radioID: harness.deviceA.radioID, connected: true)
      let activeB = try await harness.addMembership(radioID: harness.deviceB.radioID, connected: true)
      try await harness.connectBluetooth()

      _ = try await harness.manager.connectViaWiFi(host: "radio.local", port: 4242)

      #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
      #expect(harness.manager.membershipBatch == nil)
      #expect(await loginDestinations(harness.wifi).isEmpty)
      #expect(try await !harness.isConnected(activeA))
      #expect(try await harness.isConnected(activeB))
    }
  }

  @Test
  func `a second BLE loss retains the new batch while the previous room login retires`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      let activeA = try await harness.addMembership(radioID: harness.deviceA.radioID, connected: true)
      try await harness.connectBluetooth()
      try await waitUntil("BLE lifecycle source should be installed") { await harness.bluetooth.hasLifecycleHandler }
      await harness.bluetooth.holdLoginResults()
      await harness.bluetooth.simulateAutoReconnecting(deviceID: harness.deviceA.id)
      try await waitUntil("first BLE loss should capture its membership batch") {
        harness.manager.currentConnection == nil && harness.manager.connectionTeardownCount == 0
      }
      await harness.bluetooth.simulateReconnected()
      try await waitUntil("first BLE rebuild should hold its room login") {
        await harness.bluetooth.heldLoginResultCount == 1
      }
      let firstBatch = try #require(harness.manager.membershipBatch)
      let firstServices = try #require(harness.manager.services)
      let addedDuringRestore = try await harness.addMembership(radioID: harness.deviceA.radioID, connected: true)

      await harness.bluetooth.simulateAutoReconnecting(deviceID: harness.deviceA.id)
      try await waitUntil("second BLE loss should retire the previous room login") {
        harness.manager.currentConnection == nil && harness.manager.connectionTeardownCount == 0
          && harness.manager.membershipBatch?.id != firstBatch.id
      }
      #expect(await firstServices.remoteNodeService.pendingLoginCount == 0)
      await harness.bluetooth.releaseLoginResults()
      await harness.bluetooth.holdLoginResults()
      await harness.bluetooth.simulateReconnected()
      try await waitUntil("new BLE batch should attempt both retained memberships") {
        await harness.bluetooth.heldLoginResultCount == 2
      }

      #expect(harness.manager.connectionState == .ready)
      #expect(harness.manager.membershipBatch?.sessionIDs == [activeA.id, addedDuringRestore.id])
      #expect(try await !harness.isConnected(activeA))
      #expect(try await !harness.isConnected(addedDuringRestore))
      await harness.bluetooth.releaseLoginResults()
      try await waitUntil("new batch should restore both memberships") {
        let firstConnected = await (try? harness.isConnected(activeA)) == true
        let addedConnected = await (try? harness.isConnected(addedDuringRestore)) == true
        return firstConnected && addedConnected
      }
      try await waitUntil("only the completed batch should be consumed") {
        harness.manager.membershipBatch?.sessionIDs.isEmpty == true
      }
    }
  }

  @Test
  func `explicit disconnect cancels a held restore and the next manual connection stays manual`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      let activeA = try await harness.addMembership(radioID: harness.deviceA.radioID, connected: true)
      try await harness.connectBluetooth()
      await harness.wifi.holdLoginResults()
      let handoff = harness.track(Task {
        try await harness.manager.connectViaWiFi(host: "radio.local", port: 4242)
      })
      try await waitUntil("WiFi restore should hold its room login") {
        await harness.wifi.heldLoginResultCount == 1
      }

      _ = await harness.manager.disconnect(reason: .userInitiated)
      await harness.wifi.releaseLoginResults()
      _ = await handoff.result
      #expect(harness.manager.membershipBatch == nil)
      #expect(try await !harness.isConnected(activeA))
      try await harness.connectBluetooth()

      #expect(await loginDestinations(harness.bluetooth).isEmpty)
      #expect(harness.manager.membershipBatch == nil)
      #expect(try await !harness.isConnected(activeA))
    }
  }

  private func loginDestinations(_ radio: ConnectionTestRadioTransport) async -> [Data] {
    await radio.sentData.filter { $0.first == CommandCode.sendLogin.rawValue }.map {
      Data($0.dropFirst().prefix(ProtocolLimits.publicKeySize))
    }
  }
}
