package one.meshcore.shared.simulator

import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MessageEntity

internal fun MockDataProvider.aliceMessages(now: Double): List<MessageEntity> {
    val key = mockPublicKey(seed = ALICE_KEY_SEED).copyOf(SENDER_KEY_PREFIX_LENGTH)
    return earlierAliceMessages(now, key) + middleAliceMessages(now, key) + newestAliceMessages(now, key)
}

private fun MockDataProvider.earlierAliceMessages(now: Double, key: ByteArray): List<MessageEntity> = listOf(
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("10000000-0000-0000-0000-000000000001"),
            createdAt = now - ALICE_WEEKEND_ASK_AGE_SECONDS,
            text = "Hey Alice, are you free this weekend?",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.DELIVERED,
            contactID = aliceChenID,
            ackCode = ALICE_WEEKEND_ASK_ACK,
            pathLength = PathEncoding.encodePathLen(1, 2),
            roundTripTime = ALICE_WEEKEND_ASK_ROUND_TRIP_MILLIS,
            heardRepeats = 1,
        ),
    ),
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = aliceReactedMessageID,
            createdAt = now - ONE_DAY_SECONDS,
            text = "Yeah! Want to go hiking? \uD83E\uDEB6",
            direction = MessageDirection.INCOMING,
            contactID = aliceChenID,
            pathLength = PathEncoding.encodePathLen(2, 2),
            snr = ALICE_HIKE_REPLY_SNR,
            pathNodes = ALICE_TWO_HOP_PATH_NODES,
            senderKeyPrefix = key,
        ),
    ),
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("10000000-0000-0000-0000-000000000003"),
            createdAt = now - ALICE_TRAIL_PLAN_AGE_SECONDS,
            text = "Perfect! I know a great trail.",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENT,
            contactID = aliceChenID,
            ackCode = ALICE_TRAIL_PLAN_ACK,
            pathLength = PathEncoding.encodePathLen(1, 2),
            replyToID = aliceReactedMessageID,
        ),
    ),
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("10000000-0000-0000-0000-000000000009"),
            createdAt = now - ALICE_NINE_AM_AGE_SECONDS,
            text = "see you at 9am",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.DELIVERED,
            contactID = aliceChenID,
            textType = TextType.SIGNED_PLAIN,
            ackCode = ALICE_NINE_AM_ACK,
            pathLength = PathEncoding.encodePathLen(1, 2),
        ),
    ),
)

private fun MockDataProvider.middleAliceMessages(now: Double, key: ByteArray): List<MessageEntity> = listOf(
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("10000000-0000-0000-0000-00000000000C"),
            createdAt = now - TWO_HOURS_SECONDS,
            text = "Sorry, my clock was way off \uD83D\uDE05",
            direction = MessageDirection.INCOMING,
            contactID = aliceChenID,
            pathLength = PathEncoding.encodePathLen(1, 2),
            snr = ALICE_CLOCK_SKEW_SNR,
            pathNodes = ALICE_ONE_HOP_PATH_NODES,
            senderKeyPrefix = key,
            isRead = false,
            timestampCorrected = true,
            senderTimestamp = (now - FOUR_HOURS_SECONDS).toLong(),
        ),
    ),
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = aliceLinkPreviewMessageID,
            createdAt = now - NINETY_MINUTES_SECONDS,
            text = "Trail details here: https://meshcoreone.com/trails/skyline",
            direction = MessageDirection.INCOMING,
            contactID = aliceChenID,
            pathLength = PathEncoding.encodePathLen(1, 2),
            snr = ALICE_TRAIL_LINK_SNR,
            pathNodes = ALICE_ONE_HOP_PATH_NODES,
            senderKeyPrefix = key,
            isRead = false,
        ),
    ),
)

private fun MockDataProvider.newestAliceMessages(now: Double, key: ByteArray): List<MessageEntity> = listOf(
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("10000000-0000-0000-0000-00000000000B"),
            createdAt = now - ONE_HOUR_SECONDS,
            text = "Made it to the summit! $INLINE_IMAGE_URL",
            direction = MessageDirection.INCOMING,
            contactID = aliceChenID,
            pathLength = PathEncoding.encodePathLen(2, 2),
            snr = ALICE_SUMMIT_SNR,
            pathNodes = ALICE_TWO_HOP_PATH_NODES,
            senderKeyPrefix = key,
            isRead = false,
        ),
    ),
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("10000000-0000-0000-0000-0000000000FF"),
            createdAt = now - TEN_MINUTES_SECONDS,
            text = "Relayed all the way across the mesh to reach you",
            direction = MessageDirection.INCOMING,
            contactID = aliceChenID,
            pathLength = ALICE_LONG_RELAY_PATH_LENGTH,
            snr = ALICE_LONG_RELAY_SNR,
            pathNodes = ALICE_LONG_RELAY_PATH_NODES,
            senderKeyPrefix = key,
            isRead = false,
        ),
    ),
)
