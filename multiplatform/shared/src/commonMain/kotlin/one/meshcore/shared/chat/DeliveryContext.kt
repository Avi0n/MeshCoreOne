package one.meshcore.shared.chat

/**
 * How an incoming message reached the app, used to derive its persisted sort order. Ephemeral: never
 * stored on the message. Mirrors Swift `DeliveryContext`.
 */
sealed interface DeliveryContext {
    /** Pushed in real time while connected. */
    data object Live : DeliveryContext

    /**
     * Drained from the device's stored backlog. [anchorEpochSeconds] is captured once per drain so the
     * whole batch shares a sort date and lands as one contiguous block at delivery time.
     */
    data class InitialSync(val anchorEpochSeconds: Double) : DeliveryContext
}

/**
 * The sort date (epoch seconds) to persist for an incoming message. Live messages sort by receive time
 * so a just-arrived message stays at the transcript bottom; a backlog batch sorts by the shared drain
 * anchor so it forms one block rather than scattering through scrollback by send time. Grouping and
 * day-dividers key on send time separately (see [computeDisplayFlags]). Mirrors Swift
 * `SyncCoordinator.sortDate(for:receiveTime:)`.
 */
fun sortDate(context: DeliveryContext, receiveTimeEpochSeconds: Double): Double = when (context) {
    is DeliveryContext.Live -> receiveTimeEpochSeconds
    is DeliveryContext.InitialSync -> context.anchorEpochSeconds
}
