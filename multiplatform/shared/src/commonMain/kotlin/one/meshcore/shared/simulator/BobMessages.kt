package one.meshcore.shared.simulator

import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MessageEntity

internal fun MockDataProvider.bobMessages(now: Double): List<MessageEntity> {
    val key = mockPublicKey(seed = BOB_KEY_SEED).copyOf(SENDER_KEY_PREFIX_LENGTH)
    val direct = PathEncoding.encodePathLen(1, 1)
    return earlierBobMessages(now, direct) + laterBobMessages(now, direct, key)
}

private fun MockDataProvider.earlierBobMessages(now: Double, direct: Int): List<MessageEntity> = listOf(
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("20000000-0000-0000-0000-000000000001"),
            createdAt = now - TWO_DAYS_SECONDS,
            text = "Bob, can you check the weather?",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.DELIVERED,
            contactID = bobMartinezID,
            ackCode = BOB_WEATHER_ACK,
            pathLength = direct,
            roundTripTime = BOB_WEATHER_ROUND_TRIP_MILLIS,
        ),
    ),
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("20000000-0000-0000-0000-000000000002"),
            createdAt = now - TWO_HOURS_SECONDS,
            text = "This message failed to send",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.FAILED,
            contactID = bobMartinezID,
            ackCode = BOB_FAILED_SEND_ACK,
            pathLength = direct,
            retryAttempt = BOB_EXHAUSTED_RETRY_ATTEMPT,
        ),
    ),
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("20000000-0000-0000-0000-000000000003"),
            createdAt = now - ONE_HOUR_SECONDS,
            text = "Retrying this one...",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.RETRYING,
            contactID = bobMartinezID,
            ackCode = BOB_RETRYING_ACK,
            pathLength = direct,
            retryAttempt = 1,
        ),
    ),
)

private fun MockDataProvider.laterBobMessages(now: Double, direct: Int, key: ByteArray): List<MessageEntity> = listOf(
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("20000000-0000-0000-0000-000000000004"),
            createdAt = now - FIFTEEN_MINUTES_SECONDS,
            text = "Are you there?",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.PENDING,
            contactID = bobMartinezID,
            ackCode = BOB_PENDING_ACK,
            pathLength = direct,
        ),
    ),
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("20000000-0000-0000-0000-000000000005"),
            createdAt = now - TWELVE_MINUTES_SECONDS,
            text = "Testing connection...",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENDING,
            contactID = bobMartinezID,
            ackCode = BOB_SENDING_ACK,
            pathLength = direct,
        ),
    ),
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("20000000-0000-0000-0000-000000000006"),
            createdAt = now - TEN_MINUTES_SECONDS,
            text = "Yeah, I'm here!",
            direction = MessageDirection.INCOMING,
            contactID = bobMartinezID,
            pathLength = direct,
            snr = BOB_HERE_SNR,
            pathNodes = BOB_DIRECT_PATH_NODE,
            senderKeyPrefix = key,
        ),
    ),
)
