package one.meshcore.shared.chat

/** Reads a channel from the device to disambiguate a NOT_FOUND. Throwing signals a fetch fault (park). */
fun interface ChannelResolver {
    /** Whether the channel at [index] still exists on the device. */
    suspend fun channelExists(index: Int): Boolean
}
