package one.meshcore.shared.simulator

import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MessageEntity

internal fun MockDataProvider.publicChannelMessages(now: Double): List<MessageEntity> {
    val path = PathEncoding.encodePathLen(1, 2)
    return earlierPublicChannelMessages(now, path) + laterPublicChannelMessages(now, path)
}

private fun MockDataProvider.earlierPublicChannelMessages(now: Double, path: Int): List<MessageEntity> = listOf(
    channelIncoming(
        ChannelIncoming(
            id = "C0000000-0000-0000-0000-000000000001",
            createdAt = now - PUBLIC_NORTH_REPEATER_AGE_SECONDS,
            text = "Anyone monitoring the north repeater today?",
            sender = "Alice Chen",
            snr = PUBLIC_NORTH_REPEATER_SNR,
            path = path,
        ),
    ),
    channelIncoming(
        ChannelIncoming(
            id = "C0000000-0000-0000-0000-000000000002",
            createdAt = now - PUBLIC_SIGNAL_SOLID_AGE_SECONDS,
            text = "Signal's been solid on my end all morning.",
            sender = "Alice Chen",
            snr = PUBLIC_SIGNAL_SOLID_SNR,
            path = path,
        ),
    ),
    channelIncoming(
        ChannelIncoming(
            id = "C0000000-0000-0000-0000-000000000003",
            createdAt = now - PUBLIC_SOUTH_RIDGE_AGE_SECONDS,
            text = "Same here, clear copy from the south ridge.",
            sender = "Bob Martinez",
            snr = PUBLIC_SOUTH_RIDGE_SNR,
            path = path,
        ),
    ),
)

private fun MockDataProvider.laterPublicChannelMessages(now: Double, path: Int): List<MessageEntity> = listOf(
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized("C0000000-0000-0000-0000-000000000004"),
            createdAt = now - TWENTY_MINUTES_SECONDS,
            text = "Good to hear. I'll run a trace this afternoon.",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENT,
            channelIndex = PUBLIC_CHANNEL_INDEX,
            ackCode = PUBLIC_TRACE_ACK,
            pathLength = path,
        ),
    ),
    MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = publicAmbiguousRegionMessageID,
            createdAt = now - TEN_MINUTES_SECONDS,
            text = "Flood from a region that matches two names on this radio",
            direction = MessageDirection.INCOMING,
            channelIndex = PUBLIC_CHANNEL_INDEX,
            pathLength = path,
            snr = PUBLIC_AMBIGUOUS_FLOOD_SNR,
            pathNodes = PUBLIC_REGION_PATH_NODES,
            senderNodeName = "Alice Chen",
            routeType = RouteType.TC_FLOOD,
            regionScope = null,
            regionScopeMatches = ambiguousRegionNames,
        ),
    ),
)

internal fun MockDataProvider.bayAreaChannelMessages(now: Double): List<MessageEntity> {
    val path = PathEncoding.encodePathLen(1, 1)
    return listOf(
        channelIncoming(
            ChannelIncoming(
                id = "C1000000-0000-0000-0000-000000000001",
                createdAt = now - TWO_HOURS_SECONDS,
                text = "Welcome to all the new members this week!",
                sender = "Carol Diaz",
                snr = BAY_WELCOME_SNR,
                path = path,
            ),
        ),
        channelIncoming(
            ChannelIncoming(
                id = "C1000000-0000-0000-0000-000000000002",
                createdAt = now - NINETY_MINUTES_SECONDS,
                text = "We just passed 50 active nodes in the area \uD83C\uDF89",
                sender = "Carol Diaz",
                snr = BAY_ACTIVE_NODES_SNR,
                path = path,
            ),
        ),
        channelIncoming(
            ChannelIncoming(
                id = bayAreaMentionMessageID.value,
                createdAt = now - ONE_HOUR_SECONDS,
                text = "@[Sim] can you cover the cleanup this Saturday?",
                sender = "Carol Diaz",
                snr = BAY_MENTION_SNR,
                path = path,
                isRead = false,
                containsSelfMention = true,
            ),
        ),
    )
}

internal fun MockDataProvider.trailCrewChannelMessages(now: Double): List<MessageEntity> {
    val path = PathEncoding.encodePathLen(1, 2)
    return listOf(
        channelIncoming(
            ChannelIncoming(
                id = "C2000000-0000-0000-0000-000000000001",
                createdAt = now - TRAIL_BRIDGE_OPEN_AGE_SECONDS,
                text = "Bridge repair is done. Trail's open again.",
                sender = "Frank Wilson",
                snr = TRAIL_BRIDGE_SNR,
                path = path,
            ),
        ),
        MockMessageFactory.message(
            MockMessageFactory.Seed(
                id = Uuid.normalized("C2000000-0000-0000-0000-000000000002"),
                createdAt = now - TWO_HOURS_SECONDS,
                text = "Nice work everyone.",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.SENT,
                channelIndex = TRAIL_CREW_CHANNEL_INDEX,
                ackCode = TRAIL_NICE_WORK_ACK,
                pathLength = path,
            ),
        ),
    )
}

internal fun MockDataProvider.meshHQChannelMessages(now: Double): List<MessageEntity> {
    val path = PathEncoding.encodePathLen(1, 2)
    val senders = listOf("Alice Chen", "Bob Martinez", "Carol Diaz", "Frank Wilson", "Hannah Lee")
    val lines = listOf(
        "Morning net check — who's on frequency?",
        "Copy, strong signal from the east ridge.",
        "Repeater 3 is back online after the firmware push.",
        "Battery bank held through the night, 82% remaining.",
        "Anyone have eyes on the weather coming over the pass?",
        "Rain expected this afternoon, plan accordingly.",
        "Trace route to the summit node looks clean, 3 hops.",
        "Lost the link to node 7 for a bit, back now.",
        "New antenna mount is up, gaining about 4 dB.",
        "Field team checking in from the trailhead.",
        "Packet loss down to under 2% since the reroute.",
        "Reminder: monthly maintenance window is Sunday.",
        "Great turnout on the group trace test today.",
    )
    return (0 until MESH_HQ_TOTAL_MESSAGES).map { i ->
        val createdAt = now - ((MESH_HQ_TOTAL_MESSAGES - i) * MESH_HQ_SECONDS_BETWEEN_MESSAGES)
        val isRead = i < (MESH_HQ_TOTAL_MESSAGES - MESH_HQ_UNREAD_COUNT)
        if (i % MESH_HQ_OUTGOING_EVERY == MESH_HQ_OUTGOING_REMAINDER) {
            MockMessageFactory.message(
                MockMessageFactory.Seed(
                    id = meshHQMessageID(i),
                    createdAt = createdAt.toDouble(),
                    text = "Copy that, thanks for the update.",
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.SENT,
                    channelIndex = MESH_HQ_CHANNEL_INDEX,
                    ackCode = MESH_HQ_ACK_CODE_BASE + i,
                    pathLength = path,
                    isRead = isRead,
                ),
            )
        } else {
            MockMessageFactory.message(
                MockMessageFactory.Seed(
                    id = meshHQMessageID(i),
                    createdAt = createdAt.toDouble(),
                    text = lines[i % lines.size],
                    direction = MessageDirection.INCOMING,
                    channelIndex = MESH_HQ_CHANNEL_INDEX,
                    pathLength = path,
                    snr = MESH_HQ_SNR_BASE + (i % MESH_HQ_SNR_BUCKETS) * MESH_HQ_SNR_STEP,
                    senderNodeName = senders[i % senders.size],
                    isRead = isRead,
                ),
            )
        }
    }
}

private fun MockDataProvider.channelIncoming(incoming: ChannelIncoming): MessageEntity {
    val index = when {
        incoming.id.startsWith("C1", ignoreCase = true) -> BAY_AREA_CHANNEL_INDEX
        incoming.id.startsWith("C2", ignoreCase = true) -> TRAIL_CREW_CHANNEL_INDEX
        else -> PUBLIC_CHANNEL_INDEX
    }
    return MockMessageFactory.message(
        MockMessageFactory.Seed(
            id = Uuid.normalized(incoming.id),
            createdAt = incoming.createdAt,
            text = incoming.text,
            direction = MessageDirection.INCOMING,
            channelIndex = index,
            pathLength = incoming.path,
            snr = incoming.snr,
            senderNodeName = incoming.sender,
            isRead = incoming.isRead,
            containsSelfMention = incoming.containsSelfMention,
            mentionSeen = false,
        ),
    )
}

private data class ChannelIncoming(
    val id: String,
    val createdAt: Double,
    val text: String,
    val sender: String,
    val snr: Double,
    val path: Int,
    val isRead: Boolean = true,
    val containsSelfMention: Boolean = false,
)

private fun meshHQMessageID(index: Int): Uuid {
    val hex = index.toString(MESH_HQ_ID_RADIX).uppercase().padStart(MESH_HQ_ID_PAD_WIDTH, '0')
    return Uuid.normalized("C3000000-0000-0000-0000-$hex")
}
