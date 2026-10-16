package one.meshcore.shared.chat

/**
 * How a conversation load fills the timeline. Mirrors iOS `ChatPopulateMode`.
 *
 * [REFRESH_WINDOW] refetchs the already-loaded window in place so a reconnect keeps paged-in
 * history. Falls back to [REPLACE] sizing when this conversation is not already presenting.
 */
enum class ChatPopulateMode {
    /** Fetch the first page and replace the loaded window. */
    REPLACE,

    /**
     * Refetch the already-loaded window in place. Falls back to [REPLACE] sizing when this
     * conversation is not already presenting.
     */
    REFRESH_WINDOW,
}
