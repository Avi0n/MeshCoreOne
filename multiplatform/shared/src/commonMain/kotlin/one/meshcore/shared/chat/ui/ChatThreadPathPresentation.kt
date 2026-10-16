package one.meshcore.shared.chat.ui

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.pathdetail.MessagePathArrivals
import one.meshcore.shared.chat.pathdetail.MessagePathDisplay
import one.meshcore.shared.chat.pathdetail.MessagePathNameResolution
import one.meshcore.shared.chat.pathdetail.MessagePathResolution
import one.meshcore.shared.chat.pathdetail.loadContacts
import one.meshcore.shared.chat.pathdetail.loadDiscoveredRepeaters
import one.meshcore.shared.chat.pathdetail.loadMessageForPath
import one.meshcore.shared.chat.pathdetail.loadRepeatDetails
import one.meshcore.shared.chat.pathdetail.pathReceiverLocation
import one.meshcore.shared.chat.pathdetail.repeaterContacts
import one.meshcore.shared.chat.pathdetail.resolveSenderLocation
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.remotenode.ui.NeighborPrefixResolver

internal suspend fun loadThreadPathPresentation(
    fetches: ThreadPathFetches,
    place: ThreadPathPlace,
): MessagePathPresentation {
    val dto = loadMessageForPath { fetches.fetchMessage(fetches.messageId) }
    if (dto == null) {
        return MessagePathPresentation(
            initialDisplay = MessagePathDisplay(
                available = false,
                rows = emptyList(),
                hasIntermediateHops = false,
                pathString = "",
                pathStringForClipboard = "",
            ),
            arrivals = emptyList(),
        )
    }
    val contacts = loadContacts(fetches.contactService, fetches.radioId)
    val repeaters = repeaterContacts(contacts)
    val discovered = loadDiscoveredRepeaters(fetches.radioId, fetches.fetchDiscoveredRepeaters)
    val senderPrefix = dto.senderKeyPrefix?.takeIf { prefix -> prefix.isNotEmpty() }
    val sender = MessagePathNameResolution.sender(dto, contacts, place.unknownHopLabel)
    fun hop(raw: ByteArray) = NeighborPrefixResolver.resolveLocated(raw, repeaters, discovered, place.userLocation)
    fun hopName(raw: ByteArray) =
        MessagePathNameResolution.repeater(raw, repeaters, discovered, place.userLocation, place.unknownHopLabel)
    fun displayFor(message: MessageDto): MessagePathDisplay = MessagePathDisplay.build(
        message = message,
        resolution = MessagePathResolution(
            senderName = sender.displayName,
            senderNodeID = senderPrefix?.let { prefix -> prefix.copyOf(1).toUppercaseHex() },
            receiverName = place.localNodeName?.takeIf { it.isNotEmpty() } ?: place.youLabel,
            resolveRepeaterName = { raw -> hopName(raw).displayName },
            senderLocation = resolveSenderLocation(
                senderPrefix,
                contacts,
                senderNodeName = dto.senderNodeName,
                isChannelMessage = dto.isChannelMessage,
            ),
            receiverLocation = pathReceiverLocation(place.nodeCoordinate, place.currentLocation),
            resolveRepeaterLocation = loc@{ raw ->
                val resolved = hop(raw) ?: return@loc null
                if (resolved.matchKind != one.meshcore.shared.chat.NodeNameMatchKind.EXACT) return@loc null
                val latitude = resolved.latitude
                val longitude = resolved.longitude
                if (latitude != null && longitude != null) LatLng(latitude, longitude) else null
            },
            resolveRepeaterMatchKind = { raw -> hopName(raw).matchKind },
            senderMatchKind = sender.matchKind,
        ),
    )
    val arrivals = MessagePathArrivals.assemble(dto, loadRepeatDetails { fetches.fetchRepeats(fetches.messageId) })
        .map { arrival ->
            val pathNodes = if (arrival.isFirst && dto.pathNodes == null) null else arrival.pathNodes
            ArrivalPathDisplay(
                arrival,
                displayFor(dto.copy(pathNodes = pathNodes, pathLength = arrival.pathLength, snr = arrival.snr)),
            )
        }
    return MessagePathPresentation(
        initialDisplay = arrivals.firstOrNull()?.display ?: displayFor(dto),
        arrivals = arrivals,
    )
}
