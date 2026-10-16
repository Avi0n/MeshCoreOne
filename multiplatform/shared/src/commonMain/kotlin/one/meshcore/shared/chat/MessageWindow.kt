package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto

/**
 * A newest-end timeline fetch: at least [floorLimit] rows, widened to every row at or newer than an
 * optional oldest-loaded [anchorSortDate]. [hasMore] is whether older rows remain. Mirrors iOS
 * `PersistenceStore.fetchMessageWindow`.
 */
data class MessageWindow(val messages: List<MessageDto>, val hasMore: Boolean)
