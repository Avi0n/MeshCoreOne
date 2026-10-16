package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.notification.ActiveConversationState
import one.meshcore.shared.notification.NotificationCoordinator
import one.meshcore.shared.notification.NotificationFixtures
import one.meshcore.shared.notification.RecordingPoster
import kotlin.time.Duration.Companion.hours

internal class ConnectionSyncSuppressionNotifier private constructor(
    val coordinator: NotificationCoordinator,
    val poster: RecordingPoster,
) : IngestNotifier by coordinator {
    val history = mutableListOf<Boolean>()

    override fun setSyncNotificationSuppression(suppress: Boolean) {
        history += suppress
        coordinator.setSyncNotificationSuppression(suppress)
    }

    suspend fun probe() = coordinator.postDirectMessage(
        NotificationFixtures.contact(),
        PROBE_TEXT,
        NotificationFixtures.CONTACT,
        hasSelfMention = false,
    )

    companion object {
        const val PROBE_TEXT = "synthetic suppression probe"
        private val PARKED_WATCHDOG = 1.hours

        fun create(scope: CoroutineScope, now: () -> Double): ConnectionSyncSuppressionNotifier {
            val poster = RecordingPoster()
            val coordinator = NotificationCoordinator(poster, ActiveConversationState(), scope, now).also {
                it.suppressionWatchdogTimeout = PARKED_WATCHDOG
            }
            return ConnectionSyncSuppressionNotifier(coordinator, poster)
        }
    }
}
