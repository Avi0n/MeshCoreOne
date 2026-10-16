package one.meshcore.shared.chat.reactions

/** Reaction-handler edges supplied by the owning UI or connection graph. */
data class ReactionHandlerEdges(
    val unknownSenderLabel: suspend () -> String,
    val notifier: ReactionNotifier = ReactionNotifier.NoOp,
    val localNodeName: () -> String? = { null },
)
