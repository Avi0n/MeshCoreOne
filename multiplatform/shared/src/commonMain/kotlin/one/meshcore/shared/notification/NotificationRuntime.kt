package one.meshcore.shared.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import one.meshcore.shared.battery.BatteryThresholdMonitor
import one.meshcore.shared.settings.PersistentLogger
import kotlin.time.Duration

/**
 * State shared by the coordinator's posting, battery, reaction, and discovery siblings.
 * The coordinator exposes the fields callers already read; the siblings mutate the rest.
 */
internal class NotificationRuntime(
    internal val poster: PlatformNotificationPoster,
    internal val activeConversation: ActiveConversationState,
    internal val scope: CoroutineScope,
    internal val now: () -> Double,
    edges: NotificationCoordinatorEdges,
) {
    internal var isSuppressingNotifications: Boolean = false
    internal var isForeground: Boolean = true
    internal val badgeCountFlow = MutableStateFlow(0)
    internal val badgeCount: StateFlow<Int> = badgeCountFlow.asStateFlow()
    internal var badgeJob: Job? = null
    internal val unresolvedMutex = Mutex()
    internal val unresolvedLastEmit = mutableMapOf<Int, Double>()
    internal val unresolvedChannelIndices = mutableSetOf<Int>()
    internal val batteryMonitor = BatteryThresholdMonitor()
    internal val preferences = edges.preferences
    internal val drafts = edges.drafts
    internal val isAuthorized = edges.isAuthorized
    internal val unreadCounts = edges.unreadCounts
    internal val onConversationsChanged = edges.onConversationsChanged
    internal val diagnostic = edges.diagnostic
    internal val logger = edges.logger ?: PersistentLogger(scope, category = "NotificationCoordinator")
    internal var suppressionWatchdogJob: Job? = null
    internal var suppressionWatchdogTimeout: Duration = SyncNotificationSuppressionWatchdog.TIMEOUT

    internal val ingest = NotificationIngest(this)
    internal val signals = NotificationSignals(this)
}
