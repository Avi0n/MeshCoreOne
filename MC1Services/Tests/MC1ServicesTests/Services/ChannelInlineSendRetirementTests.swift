import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Inline channel send retirement")
@MainActor
struct ChannelInlineSendRetirementTests {
  @Test
  func `terminal scope recovery reports connection loss after the inline send commits Sent`() async throws {
    let harness = try ConnectionManagerRadioTestHarness()
    let commitGate = AsyncStream<Void>.makeStream()
    do {
      _ = try await harness.connect(.bluetooth)
      let radioID = try #require(harness.manager.connectedDevice?.radioID)
      let store = harness.manager.persistenceStore
      let channel = ChannelDTO.testChannel(radioID: radioID, index: 0, floodScope: .region("Selected"))
      try await store.saveChannel(channel)
      await harness.ble.setChannel(index: channel.index, name: channel.name, secret: channel.secret)
      let service = try #require(harness.manager.services?.messageService)
      await service.setInlineSendAcceptedGateForTesting {
        var iterator = commitGate.stream.makeAsyncIterator()
        _ = await iterator.next()
      }
      await harness.ble.setConnectError(BLEError.deviceNotFound)
      await harness.ble.enqueueResponsePackets(for: .setFloodScope, packets: [Data([ResponseCode.ok.rawValue])])
      await harness.ble.enqueueResponsePackets(
        for: .setFloodScope, packets: [Data([ResponseCode.error.rawValue, ErrorCode.unsupportedCommand.rawValue])]
      )

      var lossObserved = false
      var statusAtLoss: MessageStatus?
      harness.manager.onConnectionLost = { _ in
        statusAtLoss = try? await store.fetchMessages(
          radioID: radioID, channelIndex: channel.index, limit: 1, offset: 0
        ).first?.status
        lossObserved = true
      }

      let send = Task {
        try await service.sendChannelMessage(text: "quick reply", channelIndex: channel.index, radioID: radioID)
      }
      try await waitUntil("recovery should either wait for the inline send or report loss") {
        let waiterCount = await service.inlineSendWaiterCountForTesting
        return waiterCount > 0 || lossObserved
      }
      #expect(!lossObserved)
      #expect(try await store.fetchMessages(
        radioID: radioID, channelIndex: channel.index, limit: 1, offset: 0
      ).first?.status == .pending)

      commitGate.continuation.finish()
      _ = try await send.value
      try await waitUntil("the replacement connection should fail") { lossObserved }
      #expect(harness.manager.connectionState == .disconnected)
      #expect(statusAtLoss == .sent)
      #expect(await harness.ble.sentData.filter { $0.first == CommandCode.sendChannelMessage.rawValue }.count == 1)
      #expect(try await store.fetchPendingSends(radioID: radioID).isEmpty)
      await harness.close()
    } catch {
      commitGate.continuation.finish()
      await harness.close()
      throw error
    }
  }
}
