import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("MessagePollingService text type routing")
struct MessagePollingServiceTextTypeTests {
  actor HandlerHits {
    var contact = 0
    var cli = 0

    func recordContact() {
      contact += 1
    }

    func recordCLI() {
      cli += 1
    }
  }

  private func makeMessage(textType: UInt8) -> ContactMessage {
    ContactMessage(
      senderPublicKeyPrefix: Data(repeating: 0xAB, count: 6),
      pathLength: 0,
      textType: textType,
      senderTimestamp: Date(),
      signature: nil,
      text: "get cad",
      snr: nil
    )
  }

  private func runRouting(textType: UInt8) async throws -> HandlerHits {
    let radioID = UUID()
    let session = MockMeshCoreSession()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let polling = MessagePollingService(session: session, dataStore: store)
    let hits = HandlerHits()

    await polling.setContactMessageHandler { _, _, _ in
      await hits.recordContact()
    }
    await polling.setCLIMessageHandler { _, _ in
      await hits.recordCLI()
    }
    await polling.startMessageEventMonitoring(radioID: radioID)

    try await waitUntil("polling should subscribe to session events") {
      await session.eventSubscriptionCount == 1
    }

    await session.yieldEvent(.contactMessageReceived(makeMessage(textType: textType)))

    try await waitUntil("a message handler should run") {
      await hits.contact + hits.cli == 1
    }

    await polling.stopMessageEventMonitoring()
    await session.finishEventStreams()
    return hits
  }

  @Test
  func `cliCommand invokes contact handler not CLI handler`() async throws {
    let hits = try await runRouting(textType: 0x03)
    #expect(await hits.contact == 1)
    #expect(await hits.cli == 0)
  }

  @Test
  func `cliData still invokes CLI handler`() async throws {
    let hits = try await runRouting(textType: 0x01)
    #expect(await hits.contact == 0)
    #expect(await hits.cli == 1)
  }
}
