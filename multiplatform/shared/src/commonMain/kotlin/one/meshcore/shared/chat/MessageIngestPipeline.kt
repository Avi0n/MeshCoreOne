package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingThrowable

/**
 * The shared ingest pipeline for incoming direct and channel messages: timestamp
 * correction, RX-log path correlation, content-based dedup, self-mention detection, blocked-sender drop,
 * persistence, unread bookkeeping, and the real-time UI broadcast. The Kotlin port of the Swift
 * `SyncCoordinator.handleIncomingMessage`. Every collaborator is injected so the pipeline is
 * unit-testable against an in-memory store and a canned session. Reaction ([ReactionHook]) and
 * notification ([IngestNotifier]) behavior is injected at the edges.
 *
 * [selfNodeName] and [now] are read per message (the device name can change on reconnect; the receive
 * time and block anchor must be the wall clock at ingest, not construction). [newMessageId] mints the
 * row identity. All dates are epoch seconds.
 */
class MessageIngestPipeline(
    collaborators: MessageIngestCollaborators,
    edges: MessageIngestEdges = MessageIngestEdges(),
) {
    private val radioID = collaborators.radioID
    private val store = collaborators.store
    private val blockedSenders = collaborators.blockedSenders
    private val broadcaster = collaborators.broadcaster
    private val selfNodeName = collaborators.selfNodeName
    private val now = collaborators.now
    private val newMessageId = collaborators.newMessageId
    private val selfMentionDetector = edges.selfMentionDetector
    private val reactionHook = edges.reactionHook
    private val notifier = edges.notifier
    private val activeConversation = edges.activeConversation
    private val logger = edges.logger
    private val onDuplicateChannelPath = edges.onDuplicateChannelPath
    private val onIncomingChannelSaved = edges.onIncomingChannelSaved

    /**
     * Runs the full pipeline for one received message: correct the timestamp, correlate RX-log path
     * data, dedup, then either short-circuit (duplicate, blocked channel sender, or reaction) or persist
     * the row, update unreads, and broadcast a real-time event. An RX-log lookup fault is logged and
     * ingest continues with the firmware-reported path. Other store failures propagate to the poller.
     */
    suspend fun ingest(incoming: IncomingMessage, context: DeliveryContext) {
        val self = selfNodeName()
        val fields = extractFields(incoming)

        val receiveTime = now()
        val (finalTimestamp, timestampCorrected) = correctTimestampIfNeeded(fields.senderTimestamp, receiveTime)
        val sortDate = sortDate(context, receiveTime)

        val rx = lookupRxLogGuarded(fields)

        val deduplicationKey = DeduplicationKey.contentBased(
            contactID = fields.contactID,
            channelIndex = fields.channelIndex,
            senderNodeName = fields.senderNodeName,
            timestamp = fields.senderTimestamp,
            content = fields.text,
        )

        val hasSelfMention = computeSelfMention(incoming, fields, self, selfMentionDetector)
        val dto = buildDto(
            fields = fields,
            draft = IngestRowDraft(
                finalTimestamp = finalTimestamp,
                timestampCorrected = timestampCorrected,
                receiveTime = receiveTime,
                sortDate = sortDate,
                rx = rx,
                hasSelfMention = hasSelfMention,
                deduplicationKey = deduplicationKey,
            ),
        )

        if (isDuplicateGuarded(deduplicationKey)) {
            if (incoming is IncomingMessage.Channel && dto.pathNodes != null) {
                onDuplicateChannelPath(dto)
            }
            logger?.info(skippingDuplicateLine(incoming.logLabel))
            return
        }
        // Stamp before the reaction early return so bodies and reactions both count.
        stampInboundDirectHeard(incoming, receiveTime)
        if (isReactionOrBlocked(incoming, fields, self, receiveTime)) return
        persistAndNotify(incoming, dto, fields, hasSelfMention, receiveTime)
    }

    /**
     * Phone-clock recency for an inbound DM sender. A store fault is logged and ingest continues,
     * matching iOS `touchContactHeard` around `handleIncomingMessage`.
     */
    private suspend fun stampInboundDirectHeard(incoming: IncomingMessage, receiveTime: Double) {
        val contact = (incoming as? IncomingMessage.Direct)?.contact ?: return
        runReportingThrowable(
            onFailure = { error ->
                // A stamp fault is logged and the inbound message is still saved.
                logger?.error("lastHeard stamp failed for inbound DM: ${error.message ?: error}")
            },
        ) {
            store.touchContactHeard(contact.id, receiveTime.toLong())
        }
    }

    /**
     * iOS `handleIncomingMessage` dedup: a Room fault is logged and ingest continues so a fetch
     * miss cannot drop a live message.
     */
    private suspend fun isDuplicateGuarded(deduplicationKey: String): Boolean = runReportingThrowable(
        onFailure = { error ->
            // A dedup fault must not drop a live message.
            logger?.warning("Dedup check failed, proceeding with save: ${error.message ?: error}")
            false
        },
    ) {
        store.isDuplicateMessage(deduplicationKey, radioID)
    }

    /**
     * iOS `SyncCoordinator.lookupRxLogEntry`: a Room fault is logged and ingest continues with the
     * firmware-reported path length so a correlation miss cannot drop the message.
     */
    private suspend fun lookupRxLogGuarded(fields: WireFields): RxLogCorrelation = runReportingThrowable(
        onFailure = { error ->
            val kind = if (fields.channelIndex != null) "channel message" else "direct message"
            // A correlation fault keeps the firmware path length and still saves the message.
            logger?.error("Failed to lookup RxLogEntry for $kind: ${error.message ?: error}")
            RxLogCorrelation(
                pathNodes = null,
                pathLength = fields.reportedPathLength,
                routeType = null,
                regionScope = null,
                regionScopeMatches = emptyList(),
            )
        },
    ) {
        val correlation = store.lookupRxLog(
            radioID = radioID,
            channelIndex = fields.channelIndex,
            senderTimestamp = fields.senderTimestamp,
            senderKeyPrefix = fields.senderKeyPrefix,
            defaultPathLength = fields.reportedPathLength,
        )
        logRxLogCorrelation(fields, correlation)
        correlation
    }

    private fun logRxLogCorrelation(fields: WireFields, rx: RxLogCorrelation) {
        val channelIndex = fields.channelIndex
        val pathNodes = rx.pathNodes
        if (channelIndex != null) {
            if (pathNodes != null) {
                logger?.info(correlatedChannelRxLogLine(rx.pathLength, pathNodes.size))
            } else {
                logger?.warning(noChannelRxLogEntryLine(channelIndex, fields.senderTimestamp))
            }
        }
    }

    /** Whether the message is a blocked-sender drop or a reaction; either short-circuits before save. */
    private suspend fun isReactionOrBlocked(
        incoming: IncomingMessage,
        fields: WireFields,
        self: String,
        receiveTime: Double,
    ): Boolean = when (incoming) {
        is IncomingMessage.Direct ->
            incoming.contact != null && reactionHook.handleDirectReaction(fields.text, incoming.contact, radioID)

        is IncomingMessage.Channel -> blockedSenders.isBlocked(fields.senderNodeName) ||
            reactionHook.handleChannelReaction(
                ChannelReactionWire(
                    text = fields.text,
                    channelIndex = incoming.message.channelIndex,
                    senderNodeName = fields.senderNodeName,
                    selfNodeName = self,
                    receiveTimeEpochSeconds = receiveTime,
                    radioID = radioID,
                ),
            )
    }

    /** Saves the row, runs the per-kind index/unread/notify side effects, then broadcasts the real-time event. */
    private suspend fun persistAndNotify(
        incoming: IncomingMessage,
        dto: MessageDto,
        fields: WireFields,
        hasSelfMention: Boolean,
        receiveTime: Double,
    ) {
        store.saveMessage(dto)
        if (incoming is IncomingMessage.Channel) onIncomingChannelSaved(dto)
        when (incoming) {
            is IncomingMessage.Direct ->
                indexAndNotifyDirect(
                    dto = dto,
                    notice = DirectIngestNotice(
                        contact = incoming.contact,
                        text = fields.text,
                        timestamp = fields.senderTimestamp,
                        hasSelfMention = hasSelfMention,
                        receiveTime = receiveTime,
                    ),
                )

            is IncomingMessage.Channel ->
                indexAndNotifyChannel(dto, incoming, fields, hasSelfMention, receiveTime)
        }
        notifier.notifyConversationsChanged()
        if (incoming is IncomingMessage.Direct && incoming.contact != null) {
            broadcaster.yield(IncomingMessageEvent.DirectMessageReceived(dto, incoming.contact.id))
        }
    }

    private fun buildDto(fields: WireFields, draft: IngestRowDraft): MessageDto {
        // An out-of-range wire textType would throw on backup decode (MessageDto decodes it
        // non-optionally), so clamp an unknown raw to PLAIN before it reaches persistence.
        val resolvedTextType = TextType.fromRaw(fields.textTypeRaw) ?: run {
            val kind = if (fields.channelIndex != null) INGEST_LOG_LABEL_CHANNEL else INGEST_LOG_LABEL_DIRECT
            logger?.warning(unknownTextTypeLine(kind, fields.textTypeRaw))
            TextType.PLAIN
        }
        return MessageDto(
            id = newMessageId(),
            radioID = radioID,
            contactID = fields.contactID,
            channelIndex = fields.channelIndex,
            text = fields.text,
            timestamp = draft.finalTimestamp,
            createdAt = draft.receiveTime,
            sortDateRaw = draft.sortDate,
            direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED,
            textType = resolvedTextType,
            ackCode = null,
            pathLength = draft.rx.pathLength,
            snr = fields.snr,
            pathNodes = draft.rx.pathNodes,
            senderKeyPrefix = fields.senderKeyPrefix,
            senderNodeName = fields.senderNodeName,
            isRead = false,
            replyToID = null,
            roundTripTime = null,
            heardRepeats = 0,
            sendCount = 1,
            retryAttempt = 0,
            maxRetryAttempts = 0,
            linkPreviewFetched = false,
            deduplicationKey = draft.deduplicationKey,
            containsSelfMention = draft.hasSelfMention,
            mentionSeen = false,
            timestampCorrected = draft.timestampCorrected,
            senderTimestamp = if (draft.timestampCorrected) fields.senderTimestamp else null,
            routeType = draft.rx.routeType,
            regionScope = draft.rx.regionScope,
            regionScopeMatches = draft.rx.regionScopeMatches,
        )
    }

    private suspend fun indexAndNotifyDirect(dto: MessageDto, notice: DirectIngestNotice) {
        val contact = notice.contact
        if (contact != null) {
            reactionHook.indexDirectMessage(dto.id, contact.id, notice.text, notice.timestamp)
            store.updateContactLastMessage(contact.id, notice.receiveTime)
        }
        // A DM from a blocked contact is still persisted; only its unread bump and notification are skipped.
        if (contact != null && !contact.isBlocked) {
            if (activeConversation.activeContactID() != contact.id) {
                store.incrementContactUnread(contact.id)
                if (notice.hasSelfMention) store.incrementContactUnreadMention(contact.id)
            }
            notifier.postDirectMessage(contact, notice.text, dto.id, notice.hasSelfMention)
            notifier.updateBadge()
        }
    }

    private suspend fun indexAndNotifyChannel(
        dto: MessageDto,
        incoming: IncomingMessage.Channel,
        fields: WireFields,
        hasSelfMention: Boolean,
        receiveTime: Double,
    ) {
        val channel = incoming.channel
        val channelIndex = incoming.message.channelIndex
        if (fields.senderNodeName != null) {
            reactionHook.indexChannelMessage(
                messageID = dto.id,
                channelIndex = channelIndex,
                senderNodeName = fields.senderNodeName,
                text = fields.text,
                timestamp = fields.senderTimestamp,
            )
        }
        if (channel != null) {
            store.updateChannelLastMessage(channel.id, receiveTime)
        }
        // Blocked senders were already dropped before save; this guard mirrors the Swift order defensively.
        if (blockedSenders.isBlocked(fields.senderNodeName)) return

        if (channel != null) {
            val active = activeConversation.activeChannel()
            val isViewing = active == ActiveChannel(channel.radioID, channel.index)
            if (!isViewing) {
                store.incrementChannelUnread(channel.id)
                if (hasSelfMention) store.incrementChannelUnreadMention(channel.id)
            }
        }
        val postedChannel = channel?.takeIf { shouldPostChannelNotification(it) }
        if (postedChannel != null) {
            notifier.postChannelMessage(
                ChannelMessageNotice(
                    channel = postedChannel,
                    channelIndex = channelIndex,
                    radioID = radioID,
                    senderNodeName = fields.senderNodeName,
                    text = fields.text,
                    messageID = dto.id,
                    hasSelfMention = hasSelfMention,
                ),
            )
        } else {
            notifier.recordUnresolvedChannel(channelIndex, radioID, fields.senderTimestamp)
        }
        notifier.updateBadge()
        broadcaster.yield(IncomingMessageEvent.ChannelMessageReceived(dto, channelIndex))
    }

    companion object {
        /**
         * Splits a channel message's `"NodeName: text"` wire format on the first colon, trimming both
         * halves. A message with no colon, a leading colon, or nothing after the colon has no parseable
         * sender and returns a null sender with the whole text. Mirrors the Swift
         * `parseChannelMessage`, which omits empty split subsequences.
         */
        fun parseChannelMessage(text: String): Pair<String?, String> {
            val colon = text.indexOf(':')
            val after = if (colon > 0) text.substring(colon + 1) else ""
            return if (colon > 0 && after.isNotEmpty()) {
                text.substring(0, colon).trim() to after.trim()
            } else {
                null to text
            }
        }
    }
}

/** Corrected clock, path correlation, and mention flag for one inbound row. */
private data class IngestRowDraft(
    val finalTimestamp: Long,
    val timestampCorrected: Boolean,
    val receiveTime: Double,
    val sortDate: Double,
    val rx: RxLogCorrelation,
    val hasSelfMention: Boolean,
    val deduplicationKey: String,
)

/** Contact and text a saved direct message uses for unread and notification side effects. */
private data class DirectIngestNotice(
    val contact: ResolvedContact?,
    val text: String,
    val timestamp: Long,
    val hasSelfMention: Boolean,
    val receiveTime: Double,
)

/** The per-kind wire fields extracted at the top of the pipeline, before correction and correlation. */
private data class WireFields(
    val text: String,
    val senderNodeName: String?,
    val senderTimestamp: Long,
    val textTypeRaw: Int,
    val snr: Double?,
    val reportedPathLength: Int,
    val contactID: Uuid?,
    val channelIndex: Int?,
    val senderKeyPrefix: ByteArray?,
)

private fun extractFields(incoming: IncomingMessage): WireFields = when (incoming) {
    is IncomingMessage.Direct -> WireFields(
        text = incoming.message.text,
        senderNodeName = null,
        senderTimestamp = incoming.message.senderTimestamp,
        textTypeRaw = incoming.message.textType,
        snr = incoming.message.snr,
        reportedPathLength = incoming.message.pathLength,
        contactID = incoming.contact?.id,
        channelIndex = null,
        senderKeyPrefix = incoming.message.senderPublicKeyPrefix,
    )

    is IncomingMessage.Channel -> {
        val (senderName, body) = MessageIngestPipeline.parseChannelMessage(incoming.message.text)
        WireFields(
            text = body,
            senderNodeName = senderName,
            senderTimestamp = incoming.message.senderTimestamp,
            textTypeRaw = incoming.message.textType,
            snr = incoming.message.snr,
            reportedPathLength = incoming.message.pathLength,
            contactID = null,
            channelIndex = incoming.message.channelIndex,
            senderKeyPrefix = null,
        )
    }
}

private fun computeSelfMention(
    incoming: IncomingMessage,
    fields: WireFields,
    self: String,
    detector: SelfMentionDetector,
): Boolean {
    if (self.isEmpty()) return false
    return when (incoming) {
        is IncomingMessage.Direct -> detector.containsSelfMention(fields.text, self)
        is IncomingMessage.Channel ->
            fields.senderNodeName != self && detector.containsSelfMention(fields.text, self)
    }
}
