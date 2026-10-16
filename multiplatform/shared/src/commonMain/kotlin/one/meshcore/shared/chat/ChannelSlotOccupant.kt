package one.meshcore.shared.chat

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.ChatRoute

/**
 * Drops per-slot drafts and cached coordinators, then bumps the generation the open thread watches.
 * Mirrors iOS `AppState.handleChannelSlotOccupantChanged` without the navigation pop — the host closes
 * the chat from [shouldCloseOpenChannelChat] because Kotlin routes by channel id, not slot index.
 */
suspend fun applyChannelSlotOccupantChanged(
    radioID: Uuid,
    indices: Set<Int>,
    draftEditors: ChatDraftEditors,
    registry: ChatCoordinatorRegistry?,
    generations: ChannelSlotGenerationTracker,
) {
    draftEditors.retireChannelDrafts(radioID, indices)
    for (index in indices) {
        registry?.remove(ChatConversationID.channel(radioID, index))
    }
    generations.bump(radioID, indices)
}

/**
 * Whether the open Chats detail is a channel whose slot just changed occupants. A missing row is a
 * vacated slot: close it. A live row matches by radio and index so a secret rewrite of the same id
 * still leaves.
 */
suspend fun shouldCloseOpenChannelChat(
    selected: ChatRoute?,
    radioID: Uuid,
    changedIndices: Set<Int>,
    resolveChannel: suspend (Uuid) -> ChannelDto?,
): Boolean {
    val route = selected as? ChatRoute.Channel ?: return false
    val channel = resolveChannel(Uuid.normalized(route.channelId)) ?: return true
    return channel.radioID == radioID && channel.index in changedIndices
}

/**
 * Indices whose configured secret differs from the locally stored row. Slots with no prior row are
 * first sightings, not occupant changes. Mirrors Swift `ChannelService.secretChangedIndices`.
 */
fun secretChangedIndices(prior: List<ChannelDto>, configured: List<ChannelInfo>): Set<Int> {
    val priorSecrets = LinkedHashMap<Int, ByteArray>()
    for (row in prior) {
        priorSecrets.putIfAbsent(row.index, row.secret)
    }
    return configured.mapNotNull { info ->
        val priorSecret = priorSecrets[info.index] ?: return@mapNotNull null
        if (priorSecret.contentEquals(info.secret)) null else info.index
    }.toSet()
}
