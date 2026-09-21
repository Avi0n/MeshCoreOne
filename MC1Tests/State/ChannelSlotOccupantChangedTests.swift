import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Channel Slot Occupant Changed")
@MainActor
struct ChannelSlotOccupantChangedTests {
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
      isFavorite: false
    )
  }

  @Test
  func `closes the open chat for a changed slot and drops its draft`() {
    let appState = AppState()
    let radioID = UUID()
    let channel = makeChannel(radioID: radioID, index: 5)
    let draftID = ChatConversationID.channel(radioID: radioID, channelIndex: 5)
    appState.navigation.chatsSelectedRoute = .channel(channel)
    appState.draftStore.setDraft("unsent", for: draftID)

    appState.handleChannelSlotOccupantChanged(radioID: radioID, indices: [5])

    #expect(appState.navigation.chatsSelectedRoute == nil)
    #expect(appState.draftStore.draft(for: draftID) == nil)
    #expect(appState.channelSlotGenerations[draftID] == 1)
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
  func `retires pending channel navigation and scroll for the changed slot`() {
    let appState = AppState()
    let radioID = UUID()
    let channel = makeChannel(radioID: radioID, index: 5)
    appState.navigation.navigateToChannel(with: channel, scrollToMessageID: UUID())

    appState.handleChannelSlotOccupantChanged(radioID: radioID, indices: [5])

    #expect(appState.navigation.chatsSelectedRoute == nil)
    #expect(appState.navigation.pendingChannel == nil)
    #expect(appState.navigation.pendingScrollTarget == nil)
  }
}
