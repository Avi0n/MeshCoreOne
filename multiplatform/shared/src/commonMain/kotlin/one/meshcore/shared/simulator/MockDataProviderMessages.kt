package one.meshcore.shared.simulator

import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MessageEntity

// Ages are seconds before the seed clock. Ack codes and round trips are the demo radio values.

internal const val ALICE_WEEKEND_ASK_AGE_SECONDS = 90_000.0
internal const val ONE_DAY_SECONDS = 86_400.0
internal const val ALICE_TRAIL_PLAN_AGE_SECONDS = 82_000.0
internal const val ALICE_NINE_AM_AGE_SECONDS = 78_000.0
internal const val FOUR_HOURS_SECONDS = 14_400.0
internal const val THREE_HOURS_SECONDS = 10_800.0
internal const val TRAIL_BRIDGE_OPEN_AGE_SECONDS = 9_600.0
internal const val PUBLIC_NORTH_REPEATER_AGE_SECONDS = 9_000.0
internal const val PUBLIC_SIGNAL_SOLID_AGE_SECONDS = 8_940.0
internal const val TWO_HOURS_SECONDS = 7_200.0
internal const val PUBLIC_SOUTH_RIDGE_AGE_SECONDS = 6_000.0
internal const val NINETY_MINUTES_SECONDS = 5_400.0
internal const val ONE_HOUR_SECONDS = 3_600.0
internal const val THIRTY_MINUTES_SECONDS = 1_800.0
internal const val TWENTY_MINUTES_SECONDS = 1_200.0
internal const val FIFTEEN_MINUTES_SECONDS = 900.0
internal const val TWELVE_MINUTES_SECONDS = 720.0
internal const val TEN_MINUTES_SECONDS = 600.0
internal const val TWO_DAYS_SECONDS = 172_800.0
internal const val THREE_DAYS_SECONDS = 259_200.0
internal const val FRANK_REPLY_AGE_SECONDS = 255_600.0

internal const val ALICE_WEEKEND_ASK_ACK = 12_345L
internal const val ALICE_TRAIL_PLAN_ACK = 12_346L
internal const val ALICE_NINE_AM_ACK = 12_347L
internal const val BOB_WEATHER_ACK = 23_456L
internal const val BOB_FAILED_SEND_ACK = 23_457L
internal const val BOB_RETRYING_ACK = 23_458L
internal const val BOB_PENDING_ACK = 23_459L
internal const val BOB_SENDING_ACK = 23_460L
internal const val FRANK_REPLY_ACK = 34_567L
internal const val HANNAH_WELCOME_ACK = 45_678L
internal const val PUBLIC_TRACE_ACK = 50_001L
internal const val TRAIL_NICE_WORK_ACK = 50_002L
internal const val MESH_HQ_ACK_CODE_BASE = 52_000L

internal const val ALICE_WEEKEND_ASK_ROUND_TRIP_MILLIS = 2_500L
internal const val BOB_WEATHER_ROUND_TRIP_MILLIS = 850L
internal const val FRANK_REPLY_ROUND_TRIP_MILLIS = 3_200L

internal const val ALICE_HIKE_REPLY_SNR = 8.5
internal const val ALICE_CLOCK_SKEW_SNR = 6.5
internal const val ALICE_TRAIL_LINK_SNR = 7.9
internal const val ALICE_SUMMIT_SNR = 8.2
internal const val ALICE_LONG_RELAY_SNR = 3.2
internal const val BOB_HERE_SNR = 12.3
internal const val FRANK_GREETING_SNR = 2.1
internal const val FRANK_TALK_SOON_SNR = 0.8
internal const val FRANK_STORM_SNR = 3.2
internal const val FRANK_AMBIGUOUS_STORM_SNR = 3.0
internal const val HANNAH_HELLO_SNR = 9.0
internal const val HANNAH_TRAILHEAD_SNR = 8.7
internal const val PUBLIC_NORTH_REPEATER_SNR = 7.4
internal const val PUBLIC_SIGNAL_SOLID_SNR = 7.6
internal const val PUBLIC_SOUTH_RIDGE_SNR = 6.9
internal const val PUBLIC_AMBIGUOUS_FLOOD_SNR = 6.2
internal const val BAY_WELCOME_SNR = 9.1
internal const val BAY_ACTIVE_NODES_SNR = 9.0
internal const val BAY_MENTION_SNR = 8.8
internal const val TRAIL_BRIDGE_SNR = 5.2
internal const val MESH_HQ_SNR_BASE = 6.5
internal const val MESH_HQ_SNR_STEP = 0.4

internal const val ALICE_KEY_SEED = 10
internal const val BOB_KEY_SEED = 20
internal const val FRANK_KEY_SEED = 60
internal const val HANNAH_KEY_SEED = 80
internal const val SENDER_KEY_PREFIX_LENGTH = 6
internal const val BOB_EXHAUSTED_RETRY_ATTEMPT = 3
internal const val FRANK_REPLY_HEARD_REPEATS = 3

internal const val MESH_HQ_SECONDS_BETWEEN_MESSAGES = 120
internal const val MESH_HQ_OUTGOING_EVERY = 8
internal const val MESH_HQ_OUTGOING_REMAINDER = 7
internal const val MESH_HQ_SNR_BUCKETS = 5
internal const val MESH_HQ_ID_RADIX = 16
internal const val MESH_HQ_ID_PAD_WIDTH = 12

internal val ALICE_LONG_RELAY_PATH_LENGTH = PathEncoding.encodePathLen(3, 25)
internal val FRANK_FLOOD_PATH_LENGTH = PathEncoding.encodePathLen(1, 3)

// New hop-byte buffers on each read, so seeded messages do not share an array.
internal val ALICE_TWO_HOP_PATH_NODES: ByteArray
    get() = byteArrayOf(0x10, 0xA3.toByte(), 0x20, 0xB7.toByte())

internal val ALICE_ONE_HOP_PATH_NODES: ByteArray
    get() = byteArrayOf(0x10, 0x20)

internal val BOB_DIRECT_PATH_NODE: ByteArray
    get() = byteArrayOf(0x20)

internal val FRANK_FAMILY_PATH_NODES: ByteArray
    get() = byteArrayOf(0x10, 0x4F, 0x60, 0x9C.toByte())

internal val FRANK_FLOOD_PATH_NODES: ByteArray
    get() = byteArrayOf(0x10, 0x44, 0x60)

internal val HANNAH_PATH_NODE: ByteArray
    get() = byteArrayOf(0x80.toByte())

internal val PUBLIC_REGION_PATH_NODES: ByteArray
    get() = byteArrayOf(0x10, 0x20)

internal val ALICE_LONG_RELAY_PATH_NODES: ByteArray
    get() = ByteArray(75) { index ->
        when (index % 3) {
            0 -> MockDataProvider.wrappingByte(0x10 + index / 3)
            1 -> 0xA3.toByte()
            else -> 0xB7.toByte()
        }
    }

internal fun MockDataProvider.messagesForContact(contactID: Uuid, now: Double): List<MessageEntity> = when (contactID) {
    aliceChenID -> aliceMessages(now)
    bobMartinezID -> bobMessages(now)
    frankWilsonID -> frankMessages(now)
    hannahLeeID -> hannahMessages(now)
    else -> emptyList()
}

internal fun MockDataProvider.channelMessages(index: Int, now: Double): List<MessageEntity> = when (index) {
    PUBLIC_CHANNEL_INDEX -> publicChannelMessages(now)
    BAY_AREA_CHANNEL_INDEX -> bayAreaChannelMessages(now)
    TRAIL_CREW_CHANNEL_INDEX -> trailCrewChannelMessages(now)
    MESH_HQ_CHANNEL_INDEX -> meshHQChannelMessages(now)
    else -> emptyList()
}
