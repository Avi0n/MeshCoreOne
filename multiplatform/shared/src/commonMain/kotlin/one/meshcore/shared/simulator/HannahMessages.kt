package one.meshcore.shared.simulator

import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MessageEntity

internal fun MockDataProvider.hannahMessages(now: Double): List<MessageEntity> {
    val key = mockPublicKey(seed = HANNAH_KEY_SEED).copyOf(SENDER_KEY_PREFIX_LENGTH)
    val direct = PathEncoding.encodePathLen(1, 1)
    return listOf(
        MockMessageFactory.message(
            MockMessageFactory.Seed(
                id = Uuid.normalized("80000000-0000-0000-0000-000000000001"),
                createdAt = now - TWENTY_MINUTES_SECONDS,
                text = "Hi! This is Hannah from the trail club \uD83D\uDC4B",
                direction = MessageDirection.INCOMING,
                contactID = hannahLeeID,
                pathLength = direct,
                snr = HANNAH_HELLO_SNR,
                pathNodes = HANNAH_PATH_NODE,
                senderKeyPrefix = key,
            ),
        ),
        MockMessageFactory.message(
            MockMessageFactory.Seed(
                id = Uuid.normalized("80000000-0000-0000-0000-000000000002"),
                createdAt = now - FIFTEEN_MINUTES_SECONDS,
                text = "Hey Hannah! Welcome aboard.",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.DELIVERED,
                contactID = hannahLeeID,
                ackCode = HANNAH_WELCOME_ACK,
                pathLength = direct,
            ),
        ),
        MockMessageFactory.message(
            MockMessageFactory.Seed(
                id = Uuid.normalized("80000000-0000-0000-0000-000000000003"),
                createdAt = now - TEN_MINUTES_SECONDS,
                text = "Meet me at the trailhead: 37.8651, -119.5383",
                direction = MessageDirection.INCOMING,
                contactID = hannahLeeID,
                pathLength = direct,
                snr = HANNAH_TRAILHEAD_SNR,
                pathNodes = HANNAH_PATH_NODE,
                senderKeyPrefix = key,
            ),
        ),
    )
}
