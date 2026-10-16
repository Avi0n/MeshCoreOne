package one.meshcore.shared.channel

/**
 * Slot arithmetic shared by every create/join flow, ported from the repeated `loadAvailableSlots` /
 * `loadChannelState` logic in the iOS channel sheets. Slot 0 is reserved for the public channel, so user
 * channels only ever occupy `1 until maxChannels`; a device reporting `maxChannels <= 1` has no room for a
 * user channel. Pure so the hub and every sheet compute the same free-slot set.
 */
object ChannelSlots {
    /** First slot a user channel may occupy; slot 0 is the reserved public channel. */
    const val USER_CHANNEL_MIN_SLOT: Int = ChannelSecret.PUBLIC_CHANNEL_SLOT + 1

    /** The free user slots given the occupied [usedSlots] and the device-reported [maxChannels]. */
    fun availableSlots(usedSlots: Set<Int>, maxChannels: Int): List<Int> = if (maxChannels > USER_CHANNEL_MIN_SLOT) {
        (USER_CHANNEL_MIN_SLOT until maxChannels).filter { it !in usedSlots }
    } else {
        emptyList()
    }

    /** Whether the reserved public channel (slot 0) is already occupied. */
    fun hasPublicChannel(usedSlots: Set<Int>): Boolean = ChannelSecret.PUBLIC_CHANNEL_SLOT in usedSlots
}
