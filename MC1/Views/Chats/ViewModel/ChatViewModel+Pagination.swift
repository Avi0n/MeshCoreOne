import MC1Services
import SwiftUI

extension ChatViewModel {
  // MARK: - Pagination

  /// Load older messages when user scrolls near the top
  func loadOlderMessages() async {
    // Guard against duplicate fetches and end of history
    guard !isLoadingOlder, hasMoreMessages else { return }
    guard let dataStore else { return }

    coordinator?.updateRenderState { $0.with(isLoadingOlder: true) }

    // Snapshot conversation context before any await — actor reentrancy
    // means currentContact/currentChannel can change during suspensions
    let contact = currentContact
    let channel = currentChannel

    do {
      let currentOffset = totalFetchedCount
      var olderMessages: [MessageDTO]

      if let contact {
        olderMessages = try await dataStore.fetchMessages(
          contactID: contact.id,
          limit: ChatCoordinator.pageSize,
          offset: currentOffset
        )
      } else if let channel {
        olderMessages = try await dataStore.fetchMessages(
          radioID: channel.radioID,
          channelIndex: channel.index,
          limit: ChatCoordinator.pageSize,
          offset: currentOffset
        )
      } else {
        coordinator?.updateRenderState { $0.with(isLoadingOlder: false) }
        return
      }

      // Use unfiltered count to determine if more messages exist
      let unfilteredCount = olderMessages.count
      coordinator?.updateRenderState { current in
        current.with(
          hasMoreMessages: unfilteredCount < ChatCoordinator.pageSize ? false : current.hasMoreMessages,
          totalFetchedCount: current.totalFetchedCount + unfilteredCount
        )
      }

      // Hide sent reaction messages (unless failed)
      let isDM = contact != nil
      olderMessages = filterOutgoingReactionMessages(olderMessages, isDM: isDM)

      // Filter out messages already in array (race condition: appendMessageIfNew can add
      // a message while this fetch is in-flight, causing duplicates)
      let existingIDs = Set(messages.map(\.id))
      olderMessages = olderMessages.filter { !existingIDs.contains($0.id) }

      // Prepend older messages (they're chronologically earlier).
      // Re-run same-sender reordering across the page boundary to handle
      // clusters that were split between the existing and newly loaded pages.
      coordinator?.prepend(olderMessages)
      let reordered = MessageDTO.reorderSameSenderClusters(messages)
      coordinator?.replaceMessagesPreservingByID(reordered)

      // Register senders from the older page; without this, scrolling
      // back to a sender who only appears in older pages leaves them
      // missing from the @-autocomplete list.
      if let channel {
        for message in olderMessages {
          if let senderName = message.senderNodeName {
            addChannelSenderIfNew(senderName, radioID: channel.radioID, timestamp: message.timestamp)
          }
        }
      }

      buildItems()

      // Clear the spinner now that the prepended messages are visible.
      // Reaction indexing below awaits the actor and can take many
      // hundreds of milliseconds on a busy channel; gating the spinner
      // through it leaves pagination feeling locked-up.
      coordinator?.updateRenderState { $0.with(isLoadingOlder: false) }

      // Index older channel messages for reaction matching and process pending reactions
      if let channel,
         let reactionService = reactionServiceProvider() {
        await indexMessagesForReactions(
          olderMessages,
          scope: .channel(channel, localNodeName: connectedDeviceProvider()?.nodeName),
          reactionService: reactionService,
          dataStore: dataStore
        )
      }

      // Index older DM messages for reaction matching and process pending reactions
      if let contact,
         let reactionService = reactionServiceProvider() {
        await indexMessagesForReactions(
          olderMessages,
          scope: .direct(contact),
          reactionService: reactionService,
          dataStore: dataStore
        )
      }

    } catch {
      coordinator?.updateRenderState { $0.with(isLoadingOlder: false) }
      errorBannerMessage = L10n.Chats.Chats.Error.loadOlderMessagesFailed
      logger.error("Failed to load older messages: \(error)")
    }
  }
}
