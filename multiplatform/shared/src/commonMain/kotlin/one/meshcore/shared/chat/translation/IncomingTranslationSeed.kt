package one.meshcore.shared.chat.translation

import one.meshcore.shared.model.Uuid

/**
 * Offers that are off seed nothing. A null incoming row is the sender's own message.
 * Blank text is not a translation source. Other people's text is seeded in list order.
 */
internal suspend fun <T> seedIncomingTranslations(
    offersEnabled: Boolean,
    rows: List<T>,
    incoming: (T) -> Pair<Uuid, String>?,
    seed: suspend (Uuid, String) -> Unit,
) {
    if (!offersEnabled) return
    for (row in rows) {
        val (id, body) = incoming(row) ?: continue
        if (body.isNotEmpty()) seed(id, body)
    }
}
