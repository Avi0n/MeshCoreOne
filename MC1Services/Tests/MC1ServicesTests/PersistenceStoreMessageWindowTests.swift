import Foundation
@testable import MC1Services
import Testing

@Suite("PersistenceStore message window")
struct PersistenceStoreMessageWindowTests {
  private func makeStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  @Test
  func `nil anchor applies the floor and reports hasMore`() async throws {
    let store = try await makeStore()
    let contactID = UUID()
    let radioID = UUID()
    try await persistDirectMessages(store, radioID: radioID, contactID: contactID, timestamps: 1...10)

    let window = try await store.fetchMessageWindow(
      contactID: contactID,
      anchorSortDate: nil,
      floorLimit: 3
    )
    #expect(window.messages.map(\.timestamp) == [8, 9, 10])
    #expect(window.hasMore)
  }

  @Test
  func `anchor widening beats the floor`() async throws {
    let store = try await makeStore()
    let contactID = UUID()
    let radioID = UUID()
    try await persistDirectMessages(store, radioID: radioID, contactID: contactID, timestamps: 1...10)
    let anchor = Date(timeIntervalSince1970: 4)

    let window = try await store.fetchMessageWindow(
      contactID: contactID,
      anchorSortDate: anchor,
      floorLimit: 3
    )
    #expect(window.messages.map(\.timestamp) == [4, 5, 6, 7, 8, 9, 10])
    #expect(window.hasMore)
  }

  @Test
  func `tie rows at the anchor are included`() async throws {
    let store = try await makeStore()
    let contactID = UUID()
    let radioID = UUID()
    let anchor = Date(timeIntervalSince1970: 5)
    for timestamp in [3, 5, 5, 7] as [UInt32] {
      try await store.saveMessage(
        MessageDTO.testDirectMessage(
          radioID: radioID,
          contactID: contactID,
          text: "m\(timestamp)",
          timestamp: timestamp,
          createdAt: timestamp == 5 ? anchor : Date(timeIntervalSince1970: TimeInterval(timestamp))
        )
      )
    }

    let window = try await store.fetchMessageWindow(
      contactID: contactID,
      anchorSortDate: anchor,
      floorLimit: 1
    )
    #expect(window.messages.filter { $0.sortDate == anchor }.count == 2)
    #expect(window.messages.map(\.timestamp).contains(3) == false)
    #expect(window.hasMore)
  }

  @Test
  func `probe row is dropped and hasMore is exact at the boundary`() async throws {
    let store = try await makeStore()
    let contactID = UUID()
    let radioID = UUID()
    try await persistDirectMessages(store, radioID: radioID, contactID: contactID, timestamps: 1...4)

    let window = try await store.fetchMessageWindow(
      contactID: contactID,
      anchorSortDate: nil,
      floorLimit: 3
    )
    #expect(window.messages.map(\.timestamp) == [2, 3, 4])
    #expect(window.hasMore)

    let exact = try await store.fetchMessageWindow(
      contactID: contactID,
      anchorSortDate: nil,
      floorLimit: 4
    )
    #expect(exact.messages.map(\.timestamp) == [1, 2, 3, 4])
    #expect(exact.hasMore == false)
  }

  @Test
  func `channel window matches the contact variant`() async throws {
    let store = try await makeStore()
    let radioID = UUID()
    let channelIndex: UInt8 = 2
    for timestamp in 1...5 as ClosedRange<UInt32> {
      try await store.saveMessage(
        MessageDTO.testChannelMessage(
          radioID: radioID,
          channelIndex: channelIndex,
          text: "c\(timestamp)",
          timestamp: timestamp,
          createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp))
        )
      )
    }

    let window = try await store.fetchMessageWindow(
      radioID: radioID,
      channelIndex: channelIndex,
      anchorSortDate: Date(timeIntervalSince1970: 3),
      floorLimit: 2
    )
    #expect(window.messages.map(\.timestamp) == [3, 4, 5])
    #expect(window.hasMore)
  }

  private func persistDirectMessages(
    _ store: PersistenceStore,
    radioID: UUID,
    contactID: UUID,
    timestamps: ClosedRange<UInt32>
  ) async throws {
    for timestamp in timestamps {
      try await store.saveMessage(
        MessageDTO.testDirectMessage(
          radioID: radioID,
          contactID: contactID,
          text: "m\(timestamp)",
          timestamp: timestamp,
          createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp))
        )
      )
    }
  }
}
