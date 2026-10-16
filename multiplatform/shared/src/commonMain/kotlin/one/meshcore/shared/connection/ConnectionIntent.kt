package one.meshcore.shared.connection

/**
 * The user's connection intent, ported from the iOS `ConnectionIntent`. It replaces the older trio of
 * `shouldBeConnected` / `userExplicitlyDisconnected` / `pendingForceFullSync` flags.
 *
 * Persistence is deliberately one bit: only [UserDisconnected] is written (as the
 * `userExplicitlyDisconnected` boolean). [None] and [WantsConnection] clear it, so a fresh launch
 * restores to [UserDisconnected] only when the user last disconnected on purpose — which is what
 * suppresses auto-reconnect and the disconnected pill.
 */
sealed interface ConnectionIntent {
    /** Initial state; no active intent. Not persisted. */
    data object None : ConnectionIntent

    /** The user explicitly disconnected. The only state persisted, suppressing auto-reconnect. */
    data object UserDisconnected : ConnectionIntent

    /** The user wants to be connected. Not persisted. */
    data class WantsConnection(val forceFullSync: Boolean = false) : ConnectionIntent

    /** Whether the user wants to be connected. */
    val wantsConnection: Boolean
        get() = this is WantsConnection

    /** Whether the user explicitly disconnected. */
    val isUserDisconnected: Boolean
        get() = this is UserDisconnected

    /**
     * The value to store under `userExplicitlyDisconnected`: `true` only for [UserDisconnected], so
     * persisting any other state clears the bit.
     */
    val persistedDisconnectFlag: Boolean
        get() = this is UserDisconnected

    companion object {
        /** Restores intent from the persisted bit: [UserDisconnected] if set, else [None]. */
        fun restored(userExplicitlyDisconnected: Boolean): ConnectionIntent =
            if (userExplicitlyDisconnected) UserDisconnected else None
    }
}
