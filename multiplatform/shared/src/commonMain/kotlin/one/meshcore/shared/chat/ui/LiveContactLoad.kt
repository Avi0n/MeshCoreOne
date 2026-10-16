package one.meshcore.shared.chat.ui

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import one.meshcore.shared.model.Uuid

/** The contact row the thread is following, and how to reload it when the list changes. */
internal data class LiveContactLoad<T>(
    val contact: T?,
    val id: Uuid?,
    val current: () -> T?,
    val load: suspend (Uuid) -> T?,
    val invalidations: Flow<Unit>,
    val assign: (T?) -> Unit,
)

/**
 * No id means the route has no contact. An empty row takes the route's contact first, then each
 * reload keeps the previous row when the store misses. List invalidations reload again.
 */
internal suspend fun <T> collectLiveContact(load: LiveContactLoad<T>) {
    val id = load.id ?: return
    suspend fun reload() {
        val refreshed = load.load(id)
        currentCoroutineContext().ensureActive()
        load.assign(refreshed ?: load.current())
    }
    if (load.current() == null) load.assign(load.contact)
    reload()
    load.invalidations.collect { reload() }
}
