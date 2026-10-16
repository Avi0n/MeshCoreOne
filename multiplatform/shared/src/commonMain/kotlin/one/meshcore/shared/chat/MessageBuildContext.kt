package one.meshcore.shared.chat

import one.meshcore.shared.chat.linkpreview.LinkPreviewController
import one.meshcore.shared.map.MapSnapshotStore

/**
 * Stores and display settings a message-row build reads. Defaults match a plain row with no map, preview,
 * or avatar table, so a caller that only has the message and its flags still builds the same inputs.
 */
data class MessageBuildContext(
    val currentUserName: String? = null,
    val mapSnapshotStore: MapSnapshotStore? = null,
    val inlineImageDimensions: InlineImageDimensionsStore? = null,
    val envInputs: EnvInputs = EnvInputs.DEFAULT,
    val linkPreviewController: LinkPreviewController? = null,
    val incomingAvatarTable: () -> Map<String, IncomingAvatarIdentity> = { emptyMap() },
)
