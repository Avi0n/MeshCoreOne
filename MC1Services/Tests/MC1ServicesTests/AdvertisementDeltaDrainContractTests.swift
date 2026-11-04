import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Advertisement delta drain contract")
@MainActor
struct AdvertisementDeltaDrainContractTests {
  @Test
  func `stop joins admitted delta effects`() async throws {
    let radioID = UUID()
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let transport = ConnectionTestRadioTransport()
    let session = MeshCoreSession(transport: transport)
    let contacts = ContactService(session: session, dataStore: store, syncCoordinator: nil, cleanupCoordinator: nil)
    let adverts = AdvertisementService(
      session: session, dataStore: store, contactService: contacts,
      advertSyncDebounce: .zero, advertSyncMinInterval: .zero, advertSyncBusyBackoff: .zero
    )
    let gate = Gate()
    let receipt = Receipt()
    let key = Data(repeating: 0x34, count: ProtocolLimits.publicKeySize)
    let frame = ContactFrame(
      publicKey: key, type: .chat, flags: 0, outPathLength: 0, outPath: Data(),
      name: "Admitted", lastAdvertTimestamp: 0, latitude: 0, longitude: 0, lastModified: 0
    )
    var stopping: Task<Void, Never>?
    do {
      try await session.start()
      await adverts.setDeltaSyncHandler { _ in
        await gate.wait()
        _ = try? await store.saveContact(radioID: radioID, from: frame)
        return .synced
      }
      await adverts.startEventMonitoring(radioID: radioID)
      try await waitUntil("actual advert subscription should be installed") {
        await session.subscriberCountForTest > 0
      }
      await transport.inject(Data([ResponseCode.advertisement.rawValue]) + key)
      try await waitUntil("actual advert should enter its admitted delta handler") { await gate.isWaiting }
      stopping = Task {
        await adverts.stopEventMonitoring()
        receipt.stopped = true
      }
      try await waitUntil("stop should revoke new delta admission") { await adverts.deltaSyncHandler == nil }
      try? await Task.sleep(for: .milliseconds(50))
      #expect(!receipt.stopped, "Cancellation alone cannot settle admitted store work")
      await gate.release()
      await stopping?.value
      #expect(try await store.fetchContact(radioID: radioID, publicKey: key) != nil)
      #expect(await adverts.deltaSyncTask == nil)
      _ = try await session.getTime()
    } catch {
      await gate.release()
      await stopping?.value
      await adverts.stopEventMonitoring()
      adverts.finishEvents()
      await session.stop()
      await session.waitForQuiescence()
      throw error
    }
    adverts.finishEvents()
    await session.stop()
    await session.waitForQuiescence()
    #expect(await session.subscriberCountForTest == 0)
  }

  @MainActor
  private final class Receipt { var stopped = false }

  private actor Gate {
    private var waiter: CheckedContinuation<Void, Never>?
    private var isReleased = false
    private(set) var isWaiting = false
    func wait() async {
      guard !isReleased else { return }
      isWaiting = true
      await withCheckedContinuation { waiter = $0 }
      isWaiting = false
    }

    func release() {
      isReleased = true
      waiter?.resume()
      waiter = nil
    }
  }
}
