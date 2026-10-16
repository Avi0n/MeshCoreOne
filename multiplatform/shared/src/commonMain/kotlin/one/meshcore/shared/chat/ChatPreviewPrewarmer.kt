package one.meshcore.shared.chat

import kotlinx.coroutines.delay
import one.meshcore.shared.chat.linkpreview.LinkPreviewUrlExtractor
import one.meshcore.shared.model.Uuid

/**
 * Warms inline-image dimensions and link-preview heroes for closed conversations so an open paints
 * at the reserved aspect. The preview half of the iOS `ChatTimelinePrimer` / `prefetchConversation`
 * path; it does not populate a [ChatCoordinator].
 */
class ChatPreviewPrewarmer(
    private val store: () -> ChatThreadStore?,
    private val radioId: () -> Uuid?,
    private val prefetcher: () -> InlineImagePrefetcher?,
    private val previewsEnabled: () -> Boolean,
    private val shouldAutoResolve: (isChannelMessage: Boolean) -> Boolean,
) {
    /**
     * Prefetches URLs in the newest [PREVIEW_WARM_TAIL] messages of [conversation]. Rooms have no
     * inline images and are skipped. A no-op when previews are off or a dependency is missing.
     */
    suspend fun prewarm(conversation: Conversation) {
        if (conversation is Conversation.Room) return
        if (!previewsEnabled()) return
        val prefetcher = prefetcher() ?: return
        val store = store() ?: return
        val radioID = radioId() ?: return
        val isChannel = conversation is Conversation.Channel
        val messages = when (conversation) {
            is Conversation.Direct -> store.fetchDirectMessages(conversation.contact.id, PREVIEW_WARM_TAIL, 0)
            is Conversation.Channel ->
                store.fetchChannelMessages(radioID, conversation.channel.index, PREVIEW_WARM_TAIL, 0)
            is Conversation.Room -> return
        }
        val allowImageProbes = shouldAutoResolve(isChannel)
        for (message in messages) {
            if (LinkPreviewUrlExtractor.extractAll(message.text).isEmpty()) continue
            prefetcher.prefetch(
                text = message.text,
                isChannelMessage = isChannel,
                allowImageProbes = allowImageProbes,
            )
        }
    }

    /**
     * Warms the top [DEFAULT_CAPACITY] chats, staggered so the fetches do not land in one burst.
     * Mirrors `ConversationListContent.prewarmTopConversations`.
     */
    suspend fun prewarmTop(conversations: List<Conversation>) {
        for (conversation in conversations.take(DEFAULT_CAPACITY)) {
            prewarm(conversation)
            delay(STAGGER_MS)
        }
    }

    companion object {
        /** Newest rows whose URLs are warmed, matching iOS `previewWarmTailLimit`. */
        const val PREVIEW_WARM_TAIL: Int = 10

        /** Registry LRU capacity on iOS; the list warms at most this many chats. */
        const val DEFAULT_CAPACITY: Int = 16

        /** Gap between per-conversation warms, matching iOS `prewarmTopConversations`. */
        const val STAGGER_MS: Long = 30
    }
}
