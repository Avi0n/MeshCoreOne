import Foundation
@testable import MC1Services
import Testing

@Suite("SyncCoordinator Reaction Timestamp Tests")
@MainActor
struct SyncCoordinatorReactionTimestampTests {
  private static let channelIndex: UInt8 = 0
  private static let localNodeName = "Local"
  private static let senderNodeName = "Contact"
  private static let receiveTime = Date(timeIntervalSince1970: 1_700_000_000)
  private static let reactionText = "r:4294967295000_1_1:👍"

  @Test(arguments: [false, true])
  func `Maximum legacy reaction timestamp does not crash`(isDM: Bool) async throws {
    let store = MockPersistenceStore()
    let radioID = UUID()
    let session = MeshCoreSession(transport: MockMeshTransport())
    let services = try await ServiceContainer.forTesting(session: session, radioID: radioID)
    let dependencies = services.syncDependencies.with(dataStore: store, messagePollingService: MockMessagePollingService())
    let coordinator = SyncCoordinator()

    let consumed: Bool = if isDM {
      await coordinator.handleDMReaction(
        text: Self.reactionText,
        contact: ContactDTO.testContact(radioID: radioID),
        radioID: radioID,
        dependencies: dependencies
      )
    } else {
      await coordinator.handleChannelReaction(
        text: Self.reactionText,
        channelIndex: Self.channelIndex,
        senderNodeName: Self.senderNodeName,
        selfNodeName: Self.localNodeName,
        receiveTime: Self.receiveTime,
        radioID: radioID,
        dependencies: dependencies
      )
    }

    #expect(consumed)
    #expect(await store.savedReactions.isEmpty)
  }
}
