import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Retained conversation partition admission")
@MainActor
struct RetainedConversationPartitionTests {
  @Test(arguments: ChatAction.allCases, [false, true])
  func `retained chat action cannot acquire canonical B`(action: ChatAction, channel: Bool) async throws {
    try await RetainedConversationPartitionHarness.withHarness { harness in
      let model = channel ? harness.channelModel : harness.directModel
      let message = channel ? harness.channelMessage : harness.directMessage
      harness.environment.appState.navigation.setChatsRoute(channel ? .channel(harness.channel) : .direct(harness.contact))
      try await harness.importCanonicalIdentity()
      let before = try await harness.store.fetchMessage(id: message.id)
      let framesBefore = await harness.messageFrameCount()
      await harness.radio.rejectNext(.sendMessage)

      switch (action, channel) {
      case (.sendAgain, _): await model.sendAgain(message)
      case (.retry, false): await model.retryMessage(message)
      case (.retry, true): await model.retryChannelMessage(message)
      }
      await harness.joinCurrentChatDrain()

      let framesAfter = await harness.messageFrameCount()
      let noForeignFrame = framesAfter == framesBefore
      #expect(noForeignFrame)
      let canonicalPending = try await harness.store.fetchPendingSends(radioID: harness.canonicalRadioID)
      let noForeignPending = !canonicalPending.contains { $0.messageID == message.id }
      #expect(noForeignPending)
      let after = try await harness.store.fetchMessage(id: message.id)
      let retainedHistoryUnchanged = after == before
      #expect(retainedHistoryUnchanged)
      let sourcePending = try await harness.store.fetchPendingSends(radioID: harness.environment.device.radioID)
      let noNewSourcePending = !sourcePending.contains { $0.messageID == message.id }
      #expect(noNewSourcePending)
      try await harness.proveLaterIO()
    }
  }

  @Test(arguments: RoomAction.allCases)
  func `retained room action cannot acquire canonical B`(action: RoomAction) async throws {
    try await RetainedConversationPartitionHarness.withHarness { harness in
      harness.environment.appState.navigation.navigateToRoom(with: harness.room)
      try await harness.importCanonicalIdentity()
      let before = try await harness.store.fetchRoomMessages(sessionID: harness.room.id)
      let framesBefore = await harness.messageFrameCount()
      await harness.radio.rejectNext(.sendMessage)

      switch action {
      case .post: await harness.roomModel.sendMessage(text: Self.newRoomText)
      case .retry: await harness.roomModel.retryMessage(id: harness.roomMessage.id)
      }
      if action == .post,
         let admitted = try await harness.store.fetchRoomMessages(sessionID: harness.room.id).first(where: { $0.text == Self.newRoomText }) {
        try await harness.waitUntil {
          await (try? harness.store.fetchRoomMessage(id: admitted.id)?.status) == .failed
        }
      }

      let framesAfter = await harness.messageFrameCount()
      let noForeignFrame = framesAfter == framesBefore
      #expect(noForeignFrame)
      let after = try await harness.store.fetchRoomMessages(sessionID: harness.room.id)
      let retainedHistoryUnchanged = after == before
      #expect(retainedHistoryUnchanged)
      try await harness.proveLaterIO()
    }
  }

  @Test(arguments: [false, true])
  func `same partition chat send again still enters real queue`(channel: Bool) async throws {
    try await RetainedConversationPartitionHarness.withHarness { harness in
      let model = channel ? harness.channelModel : harness.directModel
      let message = channel ? harness.channelMessage : harness.directMessage
      let framesBefore = await harness.messageFrameCount()
      await harness.radio.rejectNext(.sendMessage)

      await model.sendAgain(message)
      await harness.joinCurrentChatDrain()

      let framesAfter = await harness.messageFrameCount()
      let realSendEntered = framesAfter == framesBefore + 1
      #expect(realSendEntered)
      try await harness.proveLaterIO()
    }
  }

  @Test
  func `disconnected same partition store still reads history`() async throws {
    try await RetainedConversationPartitionHarness.withHarness { harness in
      await harness.environment.appState.disconnect(reason: .userInitiated)

      let messages = try await harness.directModel.dataStore?.fetchMessages(contactID: harness.contact.id)

      let retainedOfflineHistory = messages?.contains { $0.id == harness.directMessage.id } == true
      #expect(retainedOfflineHistory)
    }
  }

  enum ChatAction: CaseIterable, Sendable { case sendAgain, retry }
  enum RoomAction: CaseIterable, Sendable { case post, retry }
  private static let newRoomText = "Refuse retained A room post"
}
