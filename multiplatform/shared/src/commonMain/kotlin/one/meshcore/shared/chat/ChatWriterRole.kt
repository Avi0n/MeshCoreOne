package one.meshcore.shared.chat

/**
 * Who holds [ChatTimelineWriter] access on a [ChatCoordinator]. `.interactive` is the open thread;
 * `.prime` is a closed-chat warm. Mirrors iOS `ChatWriterRole`.
 */
enum class ChatWriterRole {
    PRIME,
    INTERACTIVE,
}
