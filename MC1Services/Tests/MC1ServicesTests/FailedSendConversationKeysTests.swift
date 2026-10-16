import Foundation
@testable import MC1Services
import Testing

@Suite("Failed-send conversation keys")
struct FailedSendConversationKeysTests {
  private func createTestStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  @Test
  func `outgoing failed DM includes the contact id`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let contactID = UUID()
    try await store.saveMessage(
      .testDirectMessage(radioID: radioID, contactID: contactID, status: .failed)
    )

    let keys = try await store.fetchFailedSendConversationKeys(radioID: radioID)

    #expect(keys.contactIDs == [contactID])
    #expect(keys.channelIDs.isEmpty)
    #expect(keys.roomSessionIDs.isEmpty)
  }

  @Test
  func `incoming sent retrying pending sending and other radio DMs are absent`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let otherRadioID = UUID()
    let incomingID = UUID()
    let sentID = UUID()
    let retryingID = UUID()
    let pendingID = UUID()
    let sendingID = UUID()
    let otherRadioContactID = UUID()

    try await store.saveMessage(
      .testDirectMessage(
        radioID: radioID,
        contactID: incomingID,
        direction: .incoming,
        status: .failed
      )
    )
    try await store.saveMessage(
      .testDirectMessage(radioID: radioID, contactID: sentID, status: .sent)
    )
    try await store.saveMessage(
      .testDirectMessage(radioID: radioID, contactID: retryingID, status: .retrying)
    )
    try await store.saveMessage(
      .testDirectMessage(radioID: radioID, contactID: pendingID, status: .pending)
    )
    try await store.saveMessage(
      .testDirectMessage(radioID: radioID, contactID: sendingID, status: .sending)
    )
    try await store.saveMessage(
      .testDirectMessage(
        radioID: otherRadioID,
        contactID: otherRadioContactID,
        status: .failed
      )
    )

    let keys = try await store.fetchFailedSendConversationKeys(radioID: radioID)

    #expect(keys.contactIDs.isEmpty)
    #expect(keys.channelIDs.isEmpty)
    #expect(keys.roomSessionIDs.isEmpty)
  }

  @Test
  func `outgoing failed channel message includes that channel id`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let channel = ChannelDTO.testChannel(radioID: radioID, index: 3)
    try await store.saveChannel(channel)
    try await store.saveMessage(
      .testChannelMessage(radioID: radioID, channelIndex: channel.index, status: .failed)
    )

    let keys = try await store.fetchFailedSendConversationKeys(radioID: radioID)

    #expect(keys.channelIDs == [channel.id])
    #expect(keys.contactIDs.isEmpty)
    #expect(keys.roomSessionIDs.isEmpty)
  }

  @Test
  func `failed channel on another radio with the same index is absent`() async throws {
    let store = try await createTestStore()
    let radioA = UUID()
    let radioB = UUID()
    let channelA = ChannelDTO.testChannel(radioID: radioA, index: 0)
    let channelB = ChannelDTO.testChannel(radioID: radioB, index: 0)
    try await store.saveChannel(channelA)
    try await store.saveChannel(channelB)
    try await store.saveMessage(
      .testChannelMessage(radioID: radioA, channelIndex: 0, status: .failed)
    )

    let keysA = try await store.fetchFailedSendConversationKeys(radioID: radioA)
    let keysB = try await store.fetchFailedSendConversationKeys(radioID: radioB)

    #expect(keysA.channelIDs == [channelA.id])
    #expect(!keysA.channelIDs.contains(channelB.id))
    #expect(keysB.channelIDs.isEmpty)
  }

  @Test
  func `failed channel message with no Channel row yields no channel id`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    try await store.saveMessage(
      .testChannelMessage(radioID: radioID, channelIndex: 3, status: .failed)
    )

    let keys = try await store.fetchFailedSendConversationKeys(radioID: radioID)

    #expect(keys.channelIDs.isEmpty)
  }

  @Test
  func `failed self room message includes that session and excludes other radios`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let otherRadioID = UUID()
    let session = RemoteNodeSessionDTO.testSession(radioID: radioID)
    let otherSession = RemoteNodeSessionDTO.testSession(
      radioID: otherRadioID,
      publicKey: Data(repeating: 0xDD, count: 32)
    )
    try await store.saveRemoteNodeSessionDTO(session)
    try await store.saveRemoteNodeSessionDTO(otherSession)

    try await store.saveRoomMessage(
      .testRoomMessage(
        sessionID: session.id,
        text: "failed self send",
        isFromSelf: true,
        status: .failed
      )
    )
    try await store.saveRoomMessage(
      .testRoomMessage(
        sessionID: otherSession.id,
        text: "other radio failed self send",
        isFromSelf: true,
        status: .failed
      )
    )
    try await store.saveRoomMessage(
      .testRoomMessage(
        sessionID: session.id,
        text: "incoming failed",
        isFromSelf: false,
        status: .failed
      )
    )
    try await store.saveRoomMessage(
      .testRoomMessage(
        sessionID: session.id,
        text: "self sent",
        isFromSelf: true,
        status: .sent
      )
    )

    let keys = try await store.fetchFailedSendConversationKeys(radioID: radioID)
    let otherKeys = try await store.fetchFailedSendConversationKeys(radioID: otherRadioID)

    #expect(keys.roomSessionIDs == [session.id])
    #expect(!keys.roomSessionIDs.contains(otherSession.id))
    #expect(otherKeys.roomSessionIDs == [otherSession.id])
    #expect(!otherKeys.roomSessionIDs.contains(session.id))
  }

  @Test
  func `outgoing failed reaction is included`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let contactID = UUID()
    try await store.saveMessage(
      .testDirectMessage(
        radioID: radioID,
        contactID: contactID,
        text: "👍",
        status: .failed
      )
    )

    let keys = try await store.fetchFailedSendConversationKeys(radioID: radioID)

    #expect(keys.contactIDs == [contactID])
  }

  @Test
  func `multiple failed DMs for one contact collapse to one id`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let contactID = UUID()
    try await store.saveMessage(
      .testDirectMessage(radioID: radioID, contactID: contactID, text: "one", status: .failed)
    )
    try await store.saveMessage(
      .testDirectMessage(radioID: radioID, contactID: contactID, text: "two", status: .failed)
    )

    let keys = try await store.fetchFailedSendConversationKeys(radioID: radioID)

    #expect(keys.contactIDs == [contactID])
  }

  @Test
  func `empty store returns empty keys`() async throws {
    let store = try await createTestStore()

    let keys = try await store.fetchFailedSendConversationKeys(radioID: UUID())

    #expect(keys == .empty)
  }

  @Test
  func `seen outgoing failed DM is absent`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let contactID = UUID()
    var message = MessageDTO.testDirectMessage(
      radioID: radioID,
      contactID: contactID,
      status: .failed
    )
    message.failureSeen = true
    try await store.saveMessage(message)

    let keys = try await store.fetchFailedSendConversationKeys(radioID: radioID)

    #expect(keys.contactIDs.isEmpty)
  }

  @Test
  func `markFailedSendsSeen drops only that contact`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let seenID = UUID()
    let otherID = UUID()
    try await store.saveMessage(
      .testDirectMessage(radioID: radioID, contactID: seenID, status: .failed)
    )
    try await store.saveMessage(
      .testDirectMessage(radioID: radioID, contactID: otherID, status: .failed)
    )

    try await store.markFailedSendsSeen(contactID: seenID)
    let keys = try await store.fetchFailedSendConversationKeys(radioID: radioID)

    #expect(keys.contactIDs == [otherID])
  }

  @Test
  func `markFailedSendsSeen drops only that channel index on that radio`() async throws {
    let store = try await createTestStore()
    let radioA = UUID()
    let radioB = UUID()
    let channelA0 = ChannelDTO.testChannel(radioID: radioA, index: 0, name: "A0")
    let channelA1 = ChannelDTO.testChannel(radioID: radioA, index: 1, name: "A1")
    let channelB0 = ChannelDTO.testChannel(radioID: radioB, index: 0, name: "B0")
    try await store.saveChannel(channelA0)
    try await store.saveChannel(channelA1)
    try await store.saveChannel(channelB0)
    try await store.saveMessage(
      .testChannelMessage(radioID: radioA, channelIndex: 0, status: .failed)
    )
    try await store.saveMessage(
      .testChannelMessage(radioID: radioA, channelIndex: 1, status: .failed)
    )
    try await store.saveMessage(
      .testChannelMessage(radioID: radioB, channelIndex: 0, status: .failed)
    )

    try await store.markFailedSendsSeen(radioID: radioA, channelIndex: 0)
    let keysA = try await store.fetchFailedSendConversationKeys(radioID: radioA)
    let keysB = try await store.fetchFailedSendConversationKeys(radioID: radioB)

    #expect(keysA.channelIDs == [channelA1.id])
    #expect(keysB.channelIDs == [channelB0.id])
  }

  @Test
  func `markFailedSendsSeen drops only that room session`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let seen = RemoteNodeSessionDTO.testSession(radioID: radioID)
    let other = RemoteNodeSessionDTO.testSession(
      radioID: radioID,
      publicKey: Data(repeating: 0xDD, count: 32),
      name: "OtherRoom"
    )
    try await store.saveRemoteNodeSessionDTO(seen)
    try await store.saveRemoteNodeSessionDTO(other)
    try await store.saveRoomMessage(
      .testRoomMessage(sessionID: seen.id, isFromSelf: true, status: .failed)
    )
    try await store.saveRoomMessage(
      .testRoomMessage(sessionID: other.id, isFromSelf: true, status: .failed)
    )

    try await store.markFailedSendsSeen(roomSessionID: seen.id)
    let keys = try await store.fetchFailedSendConversationKeys(radioID: radioID)

    #expect(keys.roomSessionIDs == [other.id])
  }

  @Test
  func `transition to failed from sent clears failureSeen`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let contactID = UUID()
    var message = MessageDTO.testDirectMessage(
      radioID: radioID,
      contactID: contactID,
      status: .sent
    )
    message.failureSeen = true
    try await store.saveMessage(message)

    try await store.updateMessageStatus(id: message.id, status: .failed)
    let keys = try await store.fetchFailedSendConversationKeys(radioID: radioID)

    #expect(keys.contactIDs == [contactID])
  }

  @Test
  func `idempotent failed write does not clear failureSeen`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let contactID = UUID()
    let message = MessageDTO.testDirectMessage(
      radioID: radioID,
      contactID: contactID,
      status: .failed
    )
    try await store.saveMessage(message)
    try await store.markFailedSendsSeen(contactID: contactID)

    try await store.updateMessageStatus(id: message.id, status: .failed)
    let keys = try await store.fetchFailedSendConversationKeys(radioID: radioID)

    #expect(keys.contactIDs.isEmpty)
  }
}
