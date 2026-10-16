package one.meshcore.shared.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.chat.reactions.ReactionNotifier
import one.meshcore.shared.contact.ContactDiscoveryNotifier
import one.meshcore.shared.model.Uuid
import kotlin.time.Duration

/**
 * The shared notification brain: the [IngestNotifier] the incoming-ingest pipeline calls after a
 * message is saved. It applies the posting gates ([NotificationGate] plus OS authorization, the
 * preference toggles, and the sync-window suppression flag), composes the platform-neutral notification
 * content, and delegates the actual OS surfacing to a [PlatformNotificationPoster]. It also owns the
 * debounced, preference-aware app badge and the in-memory quick-reply drafts. The Kotlin port of Swift
 * `NotificationService`, split so the decision logic is platform-neutral and unit-testable while the
 * `NotificationManagerCompat` glue stays in `:androidApp`.
 *
 * The phantom-channel firmware gate (§13.3) is enforced upstream at the pipeline call site
 * (`shouldPostChannelNotification`), which routes an unresolved slot to [recordUnresolvedChannel] instead
 * of [postChannelMessage]; this coordinator additionally passes `channelResolved = true` so the gate is
 * self-contained if a caller ever posts directly.
 *
 * Posting, battery, reaction, and new-contact behavior live on sibling types over one runtime.
 * Active-conversation and quick-reply calls are extensions on this type.
 */
class NotificationCoordinator internal constructor(internal val runtime: NotificationRuntime) :
    IngestNotifier by runtime.ingest,
    BatteryNotifier by runtime.signals,
    ReactionNotifier by runtime.signals,
    ContactDiscoveryNotifier by runtime.signals {

    constructor(
        poster: PlatformNotificationPoster,
        activeConversation: ActiveConversationState,
        scope: CoroutineScope,
        now: () -> Double,
        edges: NotificationCoordinatorEdges = NotificationCoordinatorEdges(),
    ) : this(NotificationRuntime(poster, activeConversation, scope, now, edges))

    /** Whether message notifications are temporarily suppressed (the initial-sync window, §13.2). */
    var isSuppressingNotifications: Boolean
        get() = runtime.isSuppressingNotifications
        set(value) {
            runtime.isSuppressingNotifications = value
        }

    /**
     * Whether the app is on screen. The active-conversation gate below only suppresses while it is: iOS
     * applies that gate in `userNotificationCenter(_:willPresent:)`, which the system calls only for a
     * foregrounded app, so a thread left open behind a locked screen still raises its banners. The
     * conversation stays claimed across the cycle, so returning to the foreground suppresses again with
     * nothing having to re-claim it. `AppLifecycleController` drives both edges. It starts on screen
     * because a conversation can only be claimed by a screen that has already reported foreground.
     */
    var isForeground: Boolean
        get() = runtime.isForeground
        set(value) {
            runtime.isForeground = value
        }

    /**
     * The most recent badge value the coordinator computed. Android has no portable numeric app-icon
     * badge, so this is what the ongoing connection notification renders as its count; it must be a live
     * signal rather than a snapshot for that notification to follow the unread state.
     */
    val badgeCount: StateFlow<Int>
        get() = runtime.badgeCount

    /** How long suppression may stay on before the watchdog force-clears it. */
    internal var suppressionWatchdogTimeout: Duration
        get() = runtime.suppressionWatchdogTimeout
        set(value) {
            runtime.suppressionWatchdogTimeout = value
        }
}

/** iOS `Suppressing notification for unresolved channel … — no local channel for this slot`. */
internal fun suppressingUnresolvedChannelLine(channelIndex: Int, radioID: Uuid, senderTimestamp: Long): String =
    "Suppressing notification for unresolved channel $channelIndex on device ${radioID.value}, " +
        "senderTimestamp: $senderTimestamp — no local channel for this slot"

/** iOS `Unresolved channel summary: total=…, indices=…`. */
internal fun unresolvedChannelSummaryLine(indices: Collection<Int>): String {
    val sorted = indices.sorted()
    return "Unresolved channel summary: total=${sorted.size}, indices=$sorted"
}
