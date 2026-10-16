package one.meshcore.shared.chat

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.linkpreview.LinkPreviewUrlExtractor
import one.meshcore.shared.chat.linkpreview.requestPreviewFetch

/** Admits message rows before resolving preview content in background work. */
internal class ChatThreadAdmission(private val host: ChatThreadViewModel) {
    suspend fun admit(message: MessageDto) {
        currentCoroutineContext().ensureActive()
        appendAdmitted(message)
        schedulePrefetch(message)
    }

    private fun schedulePrefetch(message: MessageDto) {
        val prefetcher = host.inlineImagePrefetcher ?: return
        val env = host.dependencies.envInputs()
        if (!env.previewsEnabled) return
        if (LinkPreviewUrlExtractor.extractAll(message.text).isEmpty()) return
        val isChannel = !host.isDM
        val allowImageProbes = host.dependencies.linkPreviewCache()?.shouldAutoResolve(isChannel) == true
        val messageID = message.id
        host.ownedJobs.launch {
            prefetcher.prefetch(
                text = message.text,
                isChannelMessage = isChannel,
                allowImageProbes = allowImageProbes,
            )
            host.rebuildRenderItem(messageID)
        }
    }

    /**
     * Appends a live or optimistic message. Skips a duplicate id, builds the single row against its
     * predecessor (earlier rows never change on append), and applies the auto-scroll gate: a near-bottom
     * viewport pins to the new row; otherwise an incoming arrival raises the scroll-to-bottom affordance
     * and an outgoing or self-sent row does not (iOS `countsTowardUnread`). Mirrors `appendMessageIfNew`.
     */
    private fun appendAdmitted(message: MessageDto) {
        val previous = host.coordinator.messages.lastOrNull()
        if (host.writer?.append(message) != true) return
        if (!host.isDM) {
            host.channelSenderIndex = host.channelSenderIndex.adding(
                senderName = message.senderNodeName,
                radioID = host.conversationID.radioID,
                timestamp = message.timestamp,
            )
        }
        previous?.id?.let(host::rebuildRenderItem)
        val flags = computeDisplayFlags(message, previous, next = null)
        val env = host.dependencies.envInputs()
        val inputs = buildInputsForMessage(
            message = message,
            flags = flags,
            dividerMessageID = host.dividerMessageID,
            resolveSender = host.dependencies.resolveSender,
            context = MessageBuildContext(
                currentUserName = env.currentUserName,
                mapSnapshotStore = host.dependencies.mapSnapshotStore(),
                inlineImageDimensions = host.dependencies.inlineImageDimensions(),
                envInputs = env,
                linkPreviewController = host.linkPreviewController,
                incomingAvatarTable = host.dependencies.incomingAvatarTable,
            ),
        )
        host.writer?.appendRenderItem(MessageFragmentBuilder.makeItem(message, inputs, env))
        if (host.isAtBottom) {
            host.landAtBottom()
        } else if (!message.isOutgoing) {
            host.unreadWhileScrolledUp += 1
        }
        host.publish()
        if (env.previewsEnabled) {
            host.linkPreviewController?.requestPreviewFetch(message)
        }
    }
}
