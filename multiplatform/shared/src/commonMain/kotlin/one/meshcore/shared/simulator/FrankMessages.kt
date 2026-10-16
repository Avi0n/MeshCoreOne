package one.meshcore.shared.simulator

import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MessageEntity

internal fun MockDataProvider.frankMessages(now: Double): List<MessageEntity> {
    val key = mockPublicKey(seed = FRANK_KEY_SEED).copyOf(SENDER_KEY_PREFIX_LENGTH)
    return earlierFrankMessages(now, key) + laterFrankMessages(now, key)
}

private fun MockDataProvider.earlierFrankMessages(now: Double, key: ByteArray): List<MessageEntity> = listOf(
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("60000000-0000-0000-0000-000000000001"),
            createdAt = now - THREE_DAYS_SECONDS,
            text = "Hey kiddo, how are you?",
            direction = MessageDirection.INCOMING,
            contactID = frankWilsonID,
            pathLength = PathEncoding.encodePathLen(2, 2),
            snr = FRANK_GREETING_SNR,
            pathNodes = FRANK_FAMILY_PATH_NODES,
            senderKeyPrefix = key,
        ),
    ),
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = frankRepeatMessageID,
            createdAt = now - FRANK_REPLY_AGE_SECONDS,
            text = "Doing great Dad! How about you?",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.DELIVERED,
            contactID = frankWilsonID,
            ackCode = FRANK_REPLY_ACK,
            pathLength = PathEncoding.encodePathLen(1, 2),
            roundTripTime = FRANK_REPLY_ROUND_TRIP_MILLIS,
            heardRepeats = FRANK_REPLY_HEARD_REPEATS,
        ),
    ),
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("60000000-0000-0000-0000-000000000003"),
            createdAt = now - THREE_HOURS_SECONDS,
            text = "Good! Talk soon.",
            direction = MessageDirection.INCOMING,
            contactID = frankWilsonID,
            pathLength = PathEncoding.encodePathLen(2, 2),
            snr = FRANK_TALK_SOON_SNR,
            pathNodes = FRANK_FAMILY_PATH_NODES,
            senderKeyPrefix = key,
        ),
    ),
)

private fun MockDataProvider.laterFrankMessages(now: Double, key: ByteArray): List<MessageEntity> = listOf(
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = frankFloodUniqueMessageID,
            createdAt = now - ONE_HOUR_SECONDS,
            text = "Storm warning for the ridge tonight \u26C8\uFE0F",
            direction = MessageDirection.INCOMING,
            contactID = frankWilsonID,
            pathLength = FRANK_FLOOD_PATH_LENGTH,
            snr = FRANK_STORM_SNR,
            pathNodes = FRANK_FLOOD_PATH_NODES,
            senderKeyPrefix = key,
            routeType = RouteType.TC_FLOOD,
            regionScope = UNIQUE_REGION_NAME,
            regionScopeMatches = listOf(UNIQUE_REGION_NAME),
        ),
    ),
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = frankFloodAmbiguousMessageID,
            createdAt = now - THIRTY_MINUTES_SECONDS,
            text = "Same storm, heard under two regions",
            direction = MessageDirection.INCOMING,
            contactID = frankWilsonID,
            pathLength = FRANK_FLOOD_PATH_LENGTH,
            snr = FRANK_AMBIGUOUS_STORM_SNR,
            pathNodes = FRANK_FLOOD_PATH_NODES,
            senderKeyPrefix = key,
            routeType = RouteType.TC_FLOOD,
            regionScope = null,
            regionScopeMatches = ambiguousRegionNames,
        ),
    ),
)
