package one.meshcore.shared.contact

/**
 * UI-refresh notifications [ContactService] emits after a store mutation, so the contacts and
 * conversations lists reload. Kotlin port of the subset of the Swift `SyncCoordinator` the contact
 * service calls. The production impl bumps the observable version counters the view models watch.
 */
interface ContactSyncCoordinator {
    /** A contact row changed; the contacts list should reload. */
    suspend fun notifyContactsChanged()

    /** A conversation's messages or unread state changed; the chats list should reload. */
    suspend fun notifyConversationsChanged()
}

/** A coordinator that does nothing; the stand-in for tests and until the container wires the real one. */
object NoOpContactSyncCoordinator : ContactSyncCoordinator {
    override suspend fun notifyContactsChanged() = Unit
    override suspend fun notifyConversationsChanged() = Unit
}

/**
 * Production [ContactSyncCoordinator]: invokes app-level list invalidation hooks (SharedFlow emitters
 * in AppContainer). Mirrors iOS `SyncCoordinator.notifyContactsChanged` version bumps without owning UI.
 */
class CallbackContactSyncCoordinator(
    private val onContactsChanged: suspend () -> Unit = {},
    private val onConversationsChanged: suspend () -> Unit = {},
) : ContactSyncCoordinator {
    override suspend fun notifyContactsChanged() = onContactsChanged()
    override suspend fun notifyConversationsChanged() = onConversationsChanged()
}
