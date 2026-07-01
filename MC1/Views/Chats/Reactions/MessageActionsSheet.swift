import MC1Services
import SwiftUI

struct MessageActionsSheet: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.horizontalSizeClass) private var horizontalSizeClass
  let message: MessageDTO
  let senderName: String
  let senderMatchKind: NodeNameMatchKind
  let recentEmojis: [String]
  let onAction: (MessageAction) -> Void

  private var availability: MessageActionAvailability {
    MessageActionAvailability(message: message)
  }

  private func performAction(_ action: MessageAction) {
    if action == .delete || action == .blockSender {
      destructiveHapticTrigger += 1
    }
    onAction(action)
    dismiss()
  }

  @State private var destructiveHapticTrigger = 0
  @State private var showEmojiPicker = false
  @State private var isDetailExpanded = false
  @State private var repeats: [MessageRepeatDTO]?
  @State private var contacts: [ContactDTO] = []
  @State private var discoveredNodes: [DiscoveredNodeDTO] = []
  @State private var pathViewModel = MessagePathViewModel()

  var body: some View {
    VStack(spacing: 0) {
      ActionsPreviewHeader(
        message: message,
        senderName: senderName,
        senderMatchKind: senderMatchKind
      )

      Divider()

      if !dynamicTypeSize.isAccessibilitySize {
        ActionsEmojiSection(
          recentEmojis: recentEmojis,
          showEmojiPicker: $showEmojiPicker,
          onSelectEmoji: { performAction(.react($0)) }
        )
        Divider()
      }

      ScrollViewReader { proxy in
        ScrollView {
          VStack(spacing: 0) {
            if dynamicTypeSize.isAccessibilitySize {
              ActionsEmojiSection(
                recentEmojis: recentEmojis,
                showEmojiPicker: $showEmojiPicker,
                onSelectEmoji: { performAction(.react($0)) }
              )
              Divider()
            }
            ActionsButtonsSection(
              availability: availability,
              onSelectAction: performAction
            )
            ActionsDetailsSection(
              message: message,
              availability: availability,
              isDetailExpanded: $isDetailExpanded,
              repeats: repeats,
              contacts: contacts,
              discoveredNodes: discoveredNodes,
              pathViewModel: pathViewModel
            )
            ActionsDestructiveSection(
              availability: availability,
              onSelectAction: performAction
            )
          }
        }
        .onChange(of: isDetailExpanded) { _, expanded in
          if expanded {
            withAnimation(reduceMotion ? nil : .default) {
              proxy.scrollTo("expandedContent", anchor: .top)
            }
          }
        }
      }
    }
    .presentationDetents(
      (horizontalSizeClass == .regular || dynamicTypeSize.isAccessibilitySize)
        ? [.large] : [.medium, .large]
    )
    .presentationContentInteraction(.scrolls)
    .presentationDragIndicator(.visible)
    .presentationBackground(Color(.systemBackground))
    .sensoryFeedback(.warning, trigger: destructiveHapticTrigger)
    .task {
      guard let services = appState.services else { return }
      if availability.canShowRepeatDetails {
        // The three loads share no data, so run them concurrently rather
        // than stacking three actor round-trips while the detail rows are blank.
        async let fetchedRepeats = services.heardRepeatsService.refreshRepeats(for: message.id)
        do {
          async let fetchedContacts = services.dataStore.fetchContacts(radioID: message.radioID)
          async let fetchedNodes = services.dataStore.fetchDiscoveredNodes(radioID: message.radioID)
          contacts = try await fetchedContacts
          discoveredNodes = try await fetchedNodes
        } catch {
          contacts = []
          discoveredNodes = []
        }
        repeats = await fetchedRepeats
      } else if availability.canViewPath {
        await pathViewModel.loadContacts(services: services, radioID: message.radioID)
      }
    }
  }
}

#Preview("Outgoing Message") {
  let message = Message(
    radioID: UUID(),
    contactID: UUID(),
    text: "Hello world!",
    directionRawValue: MessageDirection.outgoing.rawValue,
    statusRawValue: MessageStatus.delivered.rawValue
  )
  message.roundTripTime = 234
  message.heardRepeats = 2
  return MessageActionsSheet(
    message: MessageDTO(from: message),
    senderName: "My Device",
    senderMatchKind: .exact,
    recentEmojis: RecentEmojisStore.defaultEmojis,
    onAction: { print("Action: \($0)") }
  )
}

#Preview("Incoming Message") {
  let message = Message(
    radioID: UUID(),
    contactID: UUID(),
    text: "Hey, can you meet me at the coffee shop downtown later today? I have something important to discuss.",
    directionRawValue: MessageDirection.incoming.rawValue,
    statusRawValue: MessageStatus.delivered.rawValue,
    pathLength: 2
  )
  message.pathNodes = Data([0xA3, 0x7F])
  message.snr = 8.5
  return MessageActionsSheet(
    message: MessageDTO(from: message),
    senderName: "Alice",
    senderMatchKind: .exact,
    recentEmojis: RecentEmojisStore.defaultEmojis,
    onAction: { print("Action: \($0)") }
  )
}
