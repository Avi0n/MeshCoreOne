import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Channel Slot Occupant Changed")
@MainActor
struct ChannelSlotOccupantChangedTests {
  private func makeDefaults() -> UserDefaults {
    let suiteName = "test.channel-slot.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suiteName)!
    defaults.removePersistentDomain(forName: suiteName)
    return defaults
  }

  private func makeChannel(radioID: UUID, index: UInt8) -> ChannelDTO {
    ChannelDTO(
      id: UUID(),
      radioID: radioID,
      index: index,
      name: "Channel \(index)",
      secret: Data(repeating: index, count: ProtocolLimits.channelSecretSize),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0,
      unreadMentionCount: 0,
      notificationLevel: .all,
      isPinned: false
    )
  }

  @Test
  func `closes the open chat for a changed slot and drops its draft`() {
    let defaults = makeDefaults()
    let radioID = UUID()
    let draftID = ChatConversationID.channel(radioID: radioID, channelIndex: 5)
    DraftStore(defaults: defaults).setDraft("unsent", for: draftID)
    let appState = AppState(defaults: defaults)
    defer { appState.shutdown() }
    let channel = makeChannel(radioID: radioID, index: 5)
    appState.navigation.chatsSelectedRoute = .channel(channel)

    appState.handleChannelSlotOccupantChanged(radioID: radioID, indices: [5])

    #expect(appState.navigation.chatsSelectedRoute == nil)
    #expect(DraftStore(defaults: defaults).draft(for: draftID) == nil)
    #expect(appState.channelSlotGenerations[draftID] == 1)
  }

  @Test
  func `a retired slot editor cannot overwrite the replacement draft`() {
    let defaults = makeDefaults()
    let appState = AppState(defaults: defaults)
    defer { appState.shutdown() }
    let radioID = UUID()
    let id = ChatConversationID.channel(radioID: radioID, channelIndex: 5)
    let old = appState.chatDraftEditor(for: id)
    old.setText("pending")

    appState.handleChannelSlotOccupantChanged(radioID: radioID, indices: [5])

    let replacement = appState.chatDraftEditor(for: id)
    replacement.setText("fresh")
    replacement.flush()
    old.setText("stale")
    old.flush()

    #expect(replacement !== old)
    #expect(old.text == "pending")
    #expect(replacement.text == "fresh")
    #expect(DraftStore(defaults: defaults).draft(for: id) == "fresh")
  }

  @Test
  func `bumps the generation once per change and only for the affected slots`() {
    let appState = AppState()
    let radioID = UUID()
    let slot5 = ChatConversationID.channel(radioID: radioID, channelIndex: 5)
    let slot3 = ChatConversationID.channel(radioID: radioID, channelIndex: 3)

    appState.handleChannelSlotOccupantChanged(radioID: radioID, indices: [5])
    appState.handleChannelSlotOccupantChanged(radioID: radioID, indices: [5, 3])

    #expect(appState.channelSlotGenerations[slot5] == 2)
    #expect(appState.channelSlotGenerations[slot3] == 1)
    #expect(appState.channelSlotGenerations[.channel(radioID: UUID(), channelIndex: 5)] == nil)
  }

  @Test
  func `leaves an open chat on another slot or radio alone`() {
    let appState = AppState()
    let radioID = UUID()
    let channel = makeChannel(radioID: radioID, index: 3)
    appState.navigation.chatsSelectedRoute = .channel(channel)

    appState.handleChannelSlotOccupantChanged(radioID: radioID, indices: [5])
    appState.handleChannelSlotOccupantChanged(radioID: UUID(), indices: [3])

    #expect(appState.navigation.chatsSelectedRoute == .channel(channel))
  }

  @Test
  func `retires the open channel and its scroll target for the changed slot`() {
    let appState = AppState()
    let radioID = UUID()
    let channel = makeChannel(radioID: radioID, index: 5)
    appState.navigation.navigateToChannel(with: channel, scrollToMessageID: UUID())

    appState.handleChannelSlotOccupantChanged(radioID: radioID, indices: [5])

    #expect(appState.navigation.chatsSelectedRoute == nil)
    #expect(appState.navigation.pendingScrollTarget == nil)
  }
}
