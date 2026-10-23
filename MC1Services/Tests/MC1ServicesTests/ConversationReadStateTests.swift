import Foundation
@testable import MC1Services
import Testing

@Suite("Conversation read state")
struct ConversationReadStateTests {
  @Test
  func `read clears unread and mention counts without changing message flags`() async throws {
    let store = try makeStore()
    let rows = try await makeRows(store: store, unreadCount: 4, mentionCount: 2)
    let incoming = MessageDTO.testDirectMessage(
      radioID: rows.radioID, contactID: rows.contact.id, direction: .incoming, isRead: false
    )
    let failed = MessageDTO.testDirectMessage(
      radioID: rows.radioID, contactID: rows.contact.id, status: .failed
    )
    try await store.saveMessage(incoming)
    try await store.saveMessage(failed)
    try await store.saveMessage(.testChannelMessage(
      radioID: rows.radioID, channelIndex: rows.channel.index, status: .failed
    ))
    try await store.saveRoomMessage(.testRoomMessage(sessionID: rows.room.id, isFromSelf: true, status: .failed))
    let failedBefore = try await store.fetchFailedSendConversationKeys(radioID: rows.radioID)

    try await setReadState(true, rows: rows, store: store)

    try await expectCounts(0, mentions: 0, rows: rows, store: store)
    #expect(try await store.fetchMessage(id: incoming.id)?.isRead == false)
    #expect(try await store.fetchMessage(id: failed.id)?.failureSeen == false)
    #expect(try await store.fetchFailedSendConversationKeys(radioID: rows.radioID) == failedBefore)
  }

  @Test
  func `unread is an idempotent reminder and preserves later arrivals`() async throws {
    let store = try makeStore()
    let rows = try await makeRows(store: store)

    try await setReadState(false, rows: rows, store: store)
    try await setReadState(false, rows: rows, store: store)
    try await expectCounts(1, mentions: 0, rows: rows, store: store)

    try await store.incrementUnreadCount(contactID: rows.contact.id)
    try await store.incrementChannelUnreadCount(channelID: rows.channel.id)
    try await store.incrementRoomUnreadCount(rows.room.id)
    try await setReadState(false, rows: rows, store: store)
    try await expectCounts(2, mentions: 0, rows: rows, store: store)
  }

  @Test
  func `unread preserves existing counts and mention state`() async throws {
    let store = try makeStore()
    let rows = try await makeRows(store: store, unreadCount: 6, mentionCount: 2)

    try await setReadState(false, rows: rows, store: store)

    try await expectCounts(6, mentions: 2, rows: rows, store: store)
  }

  @Test
  func `missing rows throw typed persistence errors`() async throws {
    let store = try makeStore()
    await #expect {
      try await store.setContactReadState(UUID(), isRead: true)
    } throws: { error in
      guard let error = error as? PersistenceStoreError, case .contactNotFound = error else { return false }
      return true
    }
    await #expect {
      try await store.setChannelReadState(UUID(), isRead: false)
    } throws: { error in
      guard let error = error as? PersistenceStoreError, case .channelNotFound = error else { return false }
      return true
    }
    await #expect {
      try await store.setRoomReadState(UUID(), isRead: true)
    } throws: { error in
      guard let error = error as? PersistenceStoreError, case .remoteNodeSessionNotFound = error else { return false }
      return true
    }
  }

  @Test
  func `read changes only the targeted radio`() async throws {
    let store = try makeStore()
    let target = try await makeRows(store: store, unreadCount: 3, mentionCount: 1)
    let other = try await makeRows(store: store, unreadCount: 3, mentionCount: 1)

    try await setReadState(true, rows: target, store: store)

    try await expectCounts(0, mentions: 0, rows: target, store: store)
    try await expectCounts(3, mentions: 1, rows: other, store: store)
  }

  private struct Rows {
    let radioID: UUID
    let contact: ContactDTO
    let channel: ChannelDTO
    let room: RemoteNodeSessionDTO
  }

  private func makeStore() throws -> PersistenceStore {
    try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
  }

  private func makeRows(store: PersistenceStore, unreadCount: Int = 0, mentionCount: Int = 0) async throws -> Rows {
    let radioID = UUID()
    let contact = ContactDTO.testContact(
      radioID: radioID, isPinned: true, lastMessageDate: Date(),
      unreadCount: unreadCount, unreadMentionCount: mentionCount
    )
    let channel = ChannelDTO.testChannel(
      radioID: radioID, unreadCount: unreadCount, unreadMentionCount: mentionCount
    )
    let room = RemoteNodeSessionDTO.testSession(radioID: radioID, unreadCount: unreadCount)
    try await store.saveContact(contact)
    try await store.saveChannel(channel)
    try await store.saveRemoteNodeSessionDTO(room)
    return Rows(radioID: radioID, contact: contact, channel: channel, room: room)
  }

  private func setReadState(_ isRead: Bool, rows: Rows, store: PersistenceStore) async throws {
    try await store.setContactReadState(rows.contact.id, isRead: isRead)
    try await store.setChannelReadState(rows.channel.id, isRead: isRead)
    try await store.setRoomReadState(rows.room.id, isRead: isRead)
  }

  private func expectCounts(_ count: Int, mentions: Int, rows: Rows, store: PersistenceStore) async throws {
    let contact = try #require(await store.fetchContact(id: rows.contact.id))
    let channel = try #require(await store.fetchChannel(id: rows.channel.id))
    let room = try #require(await store.fetchRemoteNodeSession(id: rows.room.id))
    #expect(contact.unreadCount == count)
    #expect(contact.unreadMentionCount == mentions)
    #expect(channel.unreadCount == count)
    #expect(channel.unreadMentionCount == mentions)
    #expect(room.unreadCount == count)
  }
}
