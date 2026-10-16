package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.linkpreview.LinkPreviewController
import one.meshcore.shared.chat.linkpreview.previewState
import one.meshcore.shared.chat.linkpreview.rehydrate
import one.meshcore.shared.chat.reactions.ReactionParser
import one.meshcore.shared.map.shouldClearMapSnapshotFailures
import one.meshcore.shared.model.Uuid

/**
 * Whether [message]'s text is a reaction on the wire, so a sent outgoing one renders as a badge rather
 * than a raw-text bubble. Reads the PocketMesh grammar directly, exactly as the Swift
 * `isHiddenOutgoingReaction` does, and picks the DM or channel form from the conversation key.
 */
internal fun ChatThreadViewModel.isReactionText(message: MessageDto): Boolean = if (isDM) {
    ReactionParser.parseDM(message.text) != null
} else {
    ReactionParser.parse(message.text) != null
}

internal fun ChatThreadViewModel.currentEnvInputs(): EnvInputs {
    val env = dependencies.envInputs()
    if (shouldClearMapSnapshotFailures(lastEnvWasOffline == true, env.isOffline)) {
        dependencies.mapSnapshotStore()?.clearFailures()
    }
    lastEnvWasOffline = env.isOffline
    lastAppliedEnv = env
    return env
}

internal fun ChatThreadViewModel.buildItems() {
    buildInFlight = true
    val env = currentEnvInputs()
    val store = dependencies.mapSnapshotStore()
    val preview = linkPreviewController
    // Seed controller from row-stored metadata without forcing rebuilds (already LOADED skips).
    if (preview != null) {
        rehydrateStoredPreviews(coordinator.messages, preview)
    }
    val inputs = buildThreadInputs(
        messages = coordinator.messages,
        dividerMessageID = dividerMessageID,
        resolveSender = dependencies.resolveSender,
        context = MessageBuildContext(
            currentUserName = env.currentUserName,
            mapSnapshotStore = store,
            inlineImageDimensions = dependencies.inlineImageDimensions(),
            envInputs = env,
            linkPreviewController = preview,
            incomingAvatarTable = dependencies.incomingAvatarTable,
        ),
    )
    writer?.rebuildItems(inputs, env) {
        buildInFlight = false
        applyInitialScrollIfReady()
        publish()
    }
}

internal fun ChatThreadViewModel.rebuildRenderItem(id: Uuid) {
    val message = coordinator.messagesByID[id] ?: return
    val index = coordinator.messages.indexOfFirst { it.id == id }
    val previous = if (index > 0) coordinator.messages[index - 1] else null
    val next = if (index >= 0 && index < coordinator.messages.lastIndex) {
        coordinator.messages[index + 1]
    } else {
        null
    }
    val flags = computeDisplayFlags(message, previous, next)
    val env = dependencies.envInputs()
    val inputs = buildInputsForMessage(
        message = message,
        flags = flags,
        dividerMessageID = dividerMessageID,
        resolveSender = dependencies.resolveSender,
        context = MessageBuildContext(
            currentUserName = env.currentUserName,
            mapSnapshotStore = dependencies.mapSnapshotStore(),
            inlineImageDimensions = dependencies.inlineImageDimensions(),
            envInputs = env,
            linkPreviewController = linkPreviewController,
            incomingAvatarTable = dependencies.incomingAvatarTable,
        ),
    )
    val item = MessageFragmentBuilder.makeItem(message, inputs, env)
    writer?.updateRenderItem(id) { item }
    publish()
}

/** Paints message-row-stored preview metadata into the controller before the fragment build. */
internal fun ChatThreadViewModel.rehydrateStoredPreviews(messages: List<MessageDto>, preview: LinkPreviewController) {
    messages.mapNotNull { message -> storedPreviewData(message)?.let { message.id to it } }
        .filter { (id, _) -> preview.previewState(id) != PreviewLoadState.LOADED }
        .forEach { (id, data) -> preview.rehydrate(id, data) }
}

/** The stored row for [id], or null when it is not on this thread's timeline. */
fun ChatThreadViewModel.messageByID(id: Uuid): MessageDto? = coordinator.messagesByID[id]

internal fun ChatThreadViewModel.publish() {
    val coordinatorState = coordinator.renderState
    val phase = if (buildInFlight && coordinatorState.items.isEmpty()) {
        ChatRenderState.LoadPhase.LOADING
    } else {
        coordinatorState.phase
    }
    threadRenderState.value = ChatThreadRenderState(
        items = coordinatorState.items,
        phase = phase,
        hasMoreMessages = coordinatorState.hasMoreMessages,
        isLoadingOlder = coordinatorState.isLoadingOlder,
        newMessagesDividerMessageID = dividerMessageID,
        isAtBottom = isAtBottom,
        unreadWhileScrolledUp = unreadWhileScrolledUp,
        scrollCommand = scrollCommand,
        contactRefreshSignal = contactRefreshSignal,
        completedInitialLoadGeneration = completedInitialLoadGeneration,
        errorMessage = errorMessage,
        sendErrorMessage = sendErrorMessage,
        errorBannerMessage = errorBannerMessage,
        channelSenders = channelSenderIndex.senders,
        channelSenderOrder = channelSenderIndex.order,
        firstSnapshot = currentFirstSnapshot(),
    )
}

/**
 * Re-runs the item build so newly loaded contacts repaint every sender name (and its nickname / match
 * badge) in place. The resolver reads its tables live, so this only has to force the rebuild.
 */
fun ChatThreadViewModel.refreshSenderNames() {
    buildItems()
}

/**
 * Rebakes every row when chat display prefs or network availability change while the thread stays
 * composed. No-op when [EnvInputs] is unchanged. Offline→online also drops sticky map-snapshot
 * failures so a pack miss can retry, matching iOS `ChatTimeline.applyEnvInputs`.
 */
fun ChatThreadViewModel.applyEnvInputs() {
    val env = dependencies.envInputs()
    if (env == lastAppliedEnv) return
    buildItems()
}
