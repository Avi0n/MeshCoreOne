package one.meshcore.shared.simulator

import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MessageEntity

/** Builds a seeded [MessageEntity] so each catalog row names only what differs from a plain hop. */
internal object MockMessageFactory {
    data class Seed(
        val id: Uuid,
        val createdAt: Double,
        val text: String,
        val direction: MessageDirection,
        val status: MessageStatus = MessageStatus.DELIVERED,
        val contactID: Uuid? = null,
        val channelIndex: Int? = null,
        val textType: TextType = TextType.PLAIN,
        val ackCode: Long? = null,
        val pathLength: Int = 1,
        val snr: Double? = null,
        val pathNodes: ByteArray? = null,
        val senderKeyPrefix: ByteArray? = null,
        val senderNodeName: String? = null,
        val isRead: Boolean = true,
        val replyToID: Uuid? = null,
        val roundTripTime: Long? = null,
        val heardRepeats: Int = 0,
        val retryAttempt: Int = 0,
        val maxRetryAttempts: Int = 3,
        val containsSelfMention: Boolean = false,
        val mentionSeen: Boolean = false,
        val timestampCorrected: Boolean = false,
        val senderTimestamp: Long? = null,
        val routeType: RouteType? = null,
        val regionScope: String? = null,
        val regionScopeMatches: List<String> = emptyList(),
        val linkPreviewFetched: Boolean = false,
        val linkPreviewURL: String? = null,
        val linkPreviewTitle: String? = null,
        val linkPreviewImageData: ByteArray? = null,
        val reactionSummary: String? = null,
    )

    fun message(seed: Seed): MessageEntity = MessageEntity(
        id = seed.id,
        radioID = MockDataProvider.simulatorDeviceID,
        text = seed.text,
        timestamp = seed.createdAt.toLong(),
        createdAt = seed.createdAt,
        sortDate = seed.createdAt,
        directionRawValue = seed.direction.raw,
        statusRawValue = seed.status.raw,
        textTypeRawValue = seed.textType.raw,
        pathLength = seed.pathLength,
        isRead = seed.isRead,
        heardRepeats = seed.heardRepeats,
        sendCount = 0,
        retryAttempt = seed.retryAttempt,
        maxRetryAttempts = seed.maxRetryAttempts,
        linkPreviewFetched = seed.linkPreviewFetched,
        containsSelfMention = seed.containsSelfMention,
        mentionSeen = seed.mentionSeen,
        timestampCorrected = seed.timestampCorrected,
        routeTypeRawValue = seed.routeType?.raw ?: -1,
        contactID = seed.contactID,
        channelIndex = seed.channelIndex,
        ackCode = seed.ackCode,
        snr = seed.snr,
        pathNodes = seed.pathNodes,
        senderKeyPrefix = seed.senderKeyPrefix,
        senderNodeName = seed.senderNodeName,
        replyToID = seed.replyToID,
        roundTripTime = seed.roundTripTime,
        senderTimestamp = seed.senderTimestamp,
        reactionSummary = seed.reactionSummary,
        regionScope = seed.regionScope,
        regionScopeMatches = seed.regionScopeMatches,
        linkPreviewURL = seed.linkPreviewURL,
        linkPreviewTitle = seed.linkPreviewTitle,
        linkPreviewImageData = seed.linkPreviewImageData,
    )
}
