package one.meshcore.shared.chat.ui

import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.meshcore.MeshSession

interface ChatThreadFloodScope {
    /** Live session for the channel-open flood-scope push; null when disconnected (no push). */
    val floodScopeSession: () -> MeshSession?

    /** The connected device's persisted default flood-scope name, for resolving `inherit`. */
    val deviceDefaultFloodScopeName: () -> String?

    /**
     * Live device default. When present the open inherit channel re-pushes after the Device-row
     * cache fills at Connected, so a null default is not latched as Disabled across Ready drain.
     */
    val defaultFloodScopeNameState: StateFlow<String?>?

    /** Whether the connected radio honors the un-scoped flood override (firmware v12+). */
    val supportsUnscopedFloodSend: () -> Boolean
}

/** Flood-scope session and the connected radio's default, read when a channel opens. */
data class ChatThreadFloodInputs(
    override val floodScopeSession: () -> MeshSession? = { null },
    override val deviceDefaultFloodScopeName: () -> String? = { null },
    override val defaultFloodScopeNameState: StateFlow<String?>? = null,
    override val supportsUnscopedFloodSend: () -> Boolean = { false },
    val floodScopePushWarning: (String) -> Unit = {},
) : ChatThreadFloodScope
