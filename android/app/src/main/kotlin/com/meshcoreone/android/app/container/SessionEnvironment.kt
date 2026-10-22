// AndroidOnly: WP-303 Process-owned collaborators every per-connection service graph is built from.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import com.meshcoreone.android.core.contracts.domain.MessagingIssueReporter
import com.meshcoreone.android.core.contracts.domain.NotificationPreferencesPort
import com.meshcoreone.android.core.contracts.domain.NotificationStringProvider
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.notifications.NotificationDeliveryPort
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.session.SystemSessionClock
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.contacts.ContactPreferenceFlags
import com.meshcoreone.android.core.services.diagnostics.DebugLogBuffer
import com.meshcoreone.android.core.services.messaging.ChatSendQueueProgress
import com.meshcoreone.android.core.services.messaging.LoggingMessagingIssueReporter
import com.meshcoreone.android.core.services.messaging.ChatSendQueueConfig
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import com.meshcoreone.android.core.services.sync.SyncClock
import com.meshcoreone.android.core.services.sync.SyncLogSink
import java.time.Instant
import kotlinx.coroutines.CoroutineScope

/** Runtime state the sync retry policy reads from the process connection manager. */
interface RuntimeSyncState {
    val detectedPlatform: DevicePlatform
    val lastCleanChannelSync: Pair<RadioId, Instant>?
    val lastAttemptedChannelSync: Pair<RadioId, Instant>?

    /** Disconnects the live radio from outside the connection operation (the resync-failure path). */
    fun requestDisconnect(reason: RuntimeDisconnectReason)
}

/** Fires the UI's "Sync Failed" hook when the resync loop is exhausted. */
fun interface ResyncFailureSignal {
    fun resyncFailed()
}

/**
 * Everything a [RadioSessionContainer] needs that outlives the connection. Handed to the runtime service
 * factory once per process; the container never closes any of it (the process store, scopes and platform
 * adapters belong to the [AppContainer]).
 */
class SessionEnvironment(
    val store: PersistenceStoreProtocol,
    val processScope: CoroutineScope,
    val notificationDelivery: NotificationDeliveryPort,
    val notificationPreferences: NotificationPreferencesPort,
    val notificationStrings: NotificationStringProvider?,
    val appState: AppStateProvider,
    val passwords: NodePasswordVault,
    val contactPreferences: ContactPreferenceFlags,
    val runtimeState: RuntimeSyncState,
    val resyncFailure: ResyncFailureSignal,
    val chatSendQueueProgress: ChatSendQueueProgress,
    val syncClock: SyncClock = SyncClock.SYSTEM,
    val sessionClock: SessionClock = SystemSessionClock(),
    val logSink: SyncLogSink = SyncLogSink.NONE,
    val messagingReporter: MessagingIssueReporter = LoggingMessagingIssueReporter,
    val messageServiceConfig: MessageServiceConfig = MessageServiceConfig(),
    val chatSendQueueConfig: ChatSendQueueConfig = ChatSendQueueConfig(),
    val onSessionLifecycle: SessionLifecycleListener = SessionLifecycleListener.NONE,
    /**
     * The process bootstrap debug-log buffer: a session replaces the process-global buffer on construction and
     * restores this one when it tears down, so a stale graph never keeps the global pointing at itself.
     */
    val bootstrapDebugLog: DebugLogBuffer? = null,
)

/** Observes container creation and teardown so the process owner can prove no graph leaks. */
interface SessionLifecycleListener {
    fun created(container: RadioSessionContainer) {}
    fun tornDown(container: RadioSessionContainer) {}

    companion object { val NONE: SessionLifecycleListener = object : SessionLifecycleListener {} }
}
