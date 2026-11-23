import Foundation
@testable import MC1Services
import Testing

@Suite("Unseen failed DM summary")
struct UnseenFailedDMSummaryTests {
  private func createTestStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  @Test
  func `no failed DMs reads as zero`() async throws {
    let store = try await createTestStore()
    let summary = try await store.fetchUnseenFailedDMSummary(radioID: UUID())
    #expect(summary.count == 0)
    #expect(summary.newestRepeatsHeard == 0)
  }

  @Test
  func `counts unseen failed DMs and reports the newest one's repeats`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let now = Date()
    try await store.saveMessage(.testDirectMessage(
      radioID: radioID, createdAt: now.addingTimeInterval(-60), status: .failed, heardRepeats: 5
    ))
    try await store.saveMessage(.testDirectMessage(
      radioID: radioID, createdAt: now, status: .failed, heardRepeats: 2
    ))

    let summary = try await store.fetchUnseenFailedDMSummary(radioID: radioID)

    #expect(summary.count == 2)
    #expect(summary.newestRepeatsHeard == 2)
  }

  @Test
  func `ignores incoming, in-flight, other-radio and channel messages`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    try await store.saveMessage(.testDirectMessage(radioID: radioID, direction: .incoming, status: .failed))
    try await store.saveMessage(.testDirectMessage(radioID: radioID, status: .sent))
    try await store.saveMessage(.testDirectMessage(radioID: radioID, status: .retrying))
    try await store.saveMessage(.testDirectMessage(radioID: UUID(), status: .failed))

    let summary = try await store.fetchUnseenFailedDMSummary(radioID: radioID)

    #expect(summary.count == 0)
  }

  @Test
  func `a failed DM drops out once the user has seen it`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let contactID = UUID()
    try await store.saveMessage(.testDirectMessage(radioID: radioID, contactID: contactID, status: .failed))
    #expect(try await store.fetchUnseenFailedDMSummary(radioID: radioID).count == 1)

    try await store.markFailedSendsSeen(contactID: contactID)

    #expect(try await store.fetchUnseenFailedDMSummary(radioID: radioID).count == 0)
  }
}
