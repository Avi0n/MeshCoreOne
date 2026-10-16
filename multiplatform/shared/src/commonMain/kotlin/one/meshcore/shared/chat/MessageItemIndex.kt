package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/**
 * Builds the `id -> position` lookup [ChatRenderState] maintains alongside its item list. Last write wins
 * on a duplicate id, matching the array order the caller already deduplicated. Mirrors the Swift
 * `Sequence.indexByID()`.
 */
internal fun List<MessageItem>.indexByID(): Map<Uuid, Int> = buildMap {
    this@indexByID.forEachIndexed { index, item -> put(item.id, index) }
}
