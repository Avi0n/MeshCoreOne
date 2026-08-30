import MC1Services
import SwiftUI

/// Presence-only badge for a conversation with at least one outgoing failed send.
/// Solid fill — not Liquid Glass — so it stays readable under outdoor glare.
struct FailedSendIndicator: View {
  private enum Metrics {
    static let size: CGFloat = 18
    static let systemImage = "arrow.clockwise"
  }

  var body: some View {
    Image(systemName: Metrics.systemImage)
      .font(.caption.bold())
      .foregroundStyle(.white)
      .frame(width: Metrics.size, height: Metrics.size)
      .background(Color.red, in: .circle)
      .accessibilityLabel(L10n.Chats.Chats.Row.failedSend)
  }
}

#Preview("Badge") {
  FailedSendIndicator()
    .padding()
}

#Preview("Conversation list") {
  FailedSendIndicatorPreviewList()
}

/// Fixture list for canvas / simulator preview of the failed-send row badge.
private struct FailedSendIndicatorPreviewList: View {
  @State private var viewModel: ChatViewModel
  private let alice: ContactDTO
  private let bob: ContactDTO
  private let channel: ChannelDTO
  private let room: RemoteNodeSessionDTO
  private let now: Date

  init() {
    let now = Date()
    let radioID = UUID()
    let alice = ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data(repeating: 0x11, count: 32),
      name: "Alice Chen",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 2,
      outPath: Data([0x10, 0x20]),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: now.addingTimeInterval(-120),
      unreadCount: 2
    )
    let bob = ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data(repeating: 0x22, count: 32),
      name: "Bob Martinez",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 1,
      outPath: Data([0x20]),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: true,
      lastMessageDate: now.addingTimeInterval(-900),
      unreadCount: 0
    )
    let channel = ChannelDTO(
      id: UUID(),
      radioID: radioID,
      index: 0,
      name: "Mesh HQ",
      secret: Data(),
      isEnabled: true,
      lastMessageDate: now.addingTimeInterval(-60),
      unreadCount: 4,
      unreadMentionCount: 1,
      notificationLevel: .all,
      isFavorite: false
    )
    let room = RemoteNodeSessionDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data(repeating: 0x33, count: 32),
      name: "Summit Room",
      role: .roomServer,
      isConnected: false,
      unreadCount: 1,
      lastMessageDate: now.addingTimeInterval(-3600)
    )
    let viewModel = ChatViewModel()
    viewModel.failedSendConversationIDs = [alice.id, channel.id, room.id]
    viewModel.lastMessageCache = [
      alice.id: Self.previewMessage(
        radioID: radioID,
        contactID: alice.id,
        text: "Need extra batteries at the aid station",
        status: .failed,
        now: now
      ),
      bob.id: Self.previewMessage(
        radioID: radioID,
        contactID: bob.id,
        text: "Copy, staging at the trailhead",
        status: .delivered,
        now: now
      ),
      channel.id: Self.previewMessage(
        radioID: radioID,
        channelIndex: channel.index,
        text: "Net check — anyone copy?",
        status: .failed,
        now: now
      )
    ]
    self.now = now
    self.alice = alice
    self.bob = bob
    self.channel = channel
    self.room = room
    _viewModel = State(initialValue: viewModel)
  }

  var body: some View {
    NavigationStack {
      List {
        ConversationRow(contact: alice, viewModel: viewModel, referenceDate: now)
        ConversationRow(contact: bob, viewModel: viewModel, referenceDate: now)
        ChannelConversationRow(channel: channel, viewModel: viewModel, referenceDate: now)
        RoomConversationRow(session: room, viewModel: viewModel, referenceDate: now)
      }
      .listStyle(.plain)
      .navigationTitle(L10n.Chats.Chats.title)
    }
    .environment(\.appState, AppState())
  }

  private static func previewMessage(
    radioID: UUID,
    contactID: UUID? = nil,
    channelIndex: UInt8? = nil,
    text: String,
    status: MessageStatus,
    now: Date
  ) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: radioID,
      contactID: contactID,
      channelIndex: channelIndex,
      text: text,
      timestamp: UInt32(now.timeIntervalSince1970),
      createdAt: now,
      direction: .outgoing,
      status: status,
      textType: .plain,
      ackCode: nil,
      pathLength: 1,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: status == .failed ? 3 : 0,
      maxRetryAttempts: 3
    )
  }
}
