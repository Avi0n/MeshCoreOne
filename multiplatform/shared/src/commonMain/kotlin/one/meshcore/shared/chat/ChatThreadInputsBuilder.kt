package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.linkpreview.LinkPreviewController
import one.meshcore.shared.chat.linkpreview.LinkPreviewUrlExtractor
import one.meshcore.shared.chat.linkpreview.detectUrl
import one.meshcore.shared.chat.linkpreview.inlineImageBytes
import one.meshcore.shared.chat.linkpreview.inlineImageIsGIF
import one.meshcore.shared.chat.linkpreview.loadedPreview
import one.meshcore.shared.chat.linkpreview.previewIconBytes
import one.meshcore.shared.chat.linkpreview.previewImageBytes
import one.meshcore.shared.chat.linkpreview.previewState
import one.meshcore.shared.chat.linkpreview.routesToInlineImage
import one.meshcore.shared.chat.text.MessageTextLinkifier
import one.meshcore.shared.map.MapSnapshotRequest
import one.meshcore.shared.map.MapSnapshotStore
import one.meshcore.shared.model.Uuid

/**
 * Assembles the per-message [MessageBuildInputs] the coordinator's off-thread build consumes, walking the
 * canonical [messages] in order so each row's grouping flags come from [computeDisplayFlags] against its
 * chronological predecessor. Pure — no I/O, no coordinator state — so it is unit-testable and safe to run
 * on the confinement dispatcher before handing the result to [ChatCoordinator.rebuildItems]. This is the
 * Kotlin analogue of the input half of the Swift `ChatViewModel.buildItems`.
 *
 * [dividerMessageID] tags exactly one row with the positional new-messages divider (spec §9.4); the
 * pair-wise day/timestamp/sender/direction flags come from [computeDisplayFlags].
 */
fun buildThreadInputs(
    messages: List<MessageDto>,
    dividerMessageID: Uuid?,
    resolveSender: SenderNameResolver,
    context: MessageBuildContext = MessageBuildContext(),
): List<Pair<MessageDto, MessageBuildInputs>> = messages.mapIndexed { index, message ->
    val previous = messages.getOrNull(index - 1)
    val next = messages.getOrNull(index + 1)
    val flags = computeDisplayFlags(message, previous, next)
    message to buildInputsForMessage(
        message = message,
        flags = flags,
        dividerMessageID = dividerMessageID,
        resolveSender = resolveSender,
        context = context,
    )
}

/**
 * Builds the inputs for one message given its already-computed grouping [flags]. Shared by the full-list
 * [buildThreadInputs] and the single-row append/rebuild paths so a live append renders identically to a
 * full rebuild.
 */
fun buildInputsForMessage(
    message: MessageDto,
    flags: DisplayFlags,
    dividerMessageID: Uuid?,
    resolveSender: SenderNameResolver,
    context: MessageBuildContext = MessageBuildContext(),
): MessageBuildInputs {
    // Linkify off the main thread here so the item build stays pure; a body with no detected span carries
    // null (the renderer uses the raw string), which also keeps a plain message's value-equality trivial.
    val linkify = MessageTextLinkifier.linkify(message.text, context.currentUserName)
    val formatted = linkify.formatted.takeIf { it.spans.isNotEmpty() }
    val mapCoordinate = linkify.firstCoordinate
    val preview = messagePreviewInputs(message, context.linkPreviewController, context.inlineImageDimensions)
    // Compact inline path label, gated the same way as iOS: only shown on an incoming message when the
    // "show incoming path" setting is on.
    val pathLabels = context.envInputs.incomingPathLabels
    val formattedPath = if (pathLabels != null && !message.isOutgoing) {
        MessagePathFormatter.format(message, pathLabels.direct, pathLabels.flood)
    } else {
        null
    }
    val senderResolution = resolveSender.resolve(message)
    val incomingAvatar = if (flags.isClusterEnd) {
        IncomingAvatarIdentity.resolve(
            senderNodeName = message.senderNodeName,
            displayName = senderResolution.displayName,
            table = context.incomingAvatarTable(),
        )
    } else {
        null
    }
    return MessageBuildInputs(
        messageID = message.id,
        baseColor = if (message.isOutgoing) BaseColorSlot.OUTGOING else BaseColorSlot.INCOMING,
        senderResolution = senderResolution,
        showTimestamp = flags.showTimestamp,
        showDirectionGap = flags.showDirectionGap,
        showSenderName = flags.showSenderName,
        showNewMessagesDivider = message.id == dividerMessageID,
        showDayDivider = flags.showDayDivider,
        isClusterEnd = flags.isClusterEnd,
        incomingAvatar = incomingAvatar,
        formattedPath = formattedPath,
        formattedText = formatted,
        cachedURL = preview.cachedURL,
        isInlineImageURL = preview.isInlineImage,
        previewState = preview.previewState,
        loadedPreview = preview.loadedPreview,
        hasPreviewImageRef = preview.hasPreviewImage,
        hasPreviewIconRef = preview.hasPreviewIcon,
        hasInlineImageRef = preview.hasInlineImage,
        imageIsGIF = preview.imageIsGIF,
        inlineImageAspect = preview.imageAspect,
        mapPreviewLatitude = mapCoordinate?.latitude,
        mapPreviewLongitude = mapCoordinate?.longitude,
        isMapPreviewReady = resolveMapPreviewReady(
            latitude = mapCoordinate?.latitude,
            longitude = mapCoordinate?.longitude,
            envInputs = context.envInputs,
            store = context.mapSnapshotStore,
        ),
    )
}

/**
 * Whether the map thumbnail for a coordinate is resolved (ready or failed). Mirrors Swift
 * `MapSnapshotStore.isResolved`: when previews are on and a store is wired, enqueue a snapshot
 * request and report its resolution; without a store, a coordinate alone counts as ready so
 * callers without snapshot support can still show the card.
 */
fun resolveMapPreviewReady(
    latitude: Double?,
    longitude: Double?,
    envInputs: EnvInputs,
    store: MapSnapshotStore?,
): Boolean {
    if (!envInputs.showMapPreviews || latitude == null || longitude == null) return false
    if (store == null) return true
    val request = MapSnapshotRequest.rounded(
        latitude = latitude,
        longitude = longitude,
        isDark = envInputs.mapBasemapIsDark,
        isOffline = envInputs.isOffline,
    )
    store.request(request)
    return store.isResolved(request)
}

/**
 * Resolves the display name (and match confidence) for a message's sender. Injected into the thread so the
 * live wiring can fold in contact matching while tests and the base build stay simple. Mirrors the Swift
 * sender-name resolution the item builder consumes.
 */
fun interface SenderNameResolver {
    fun resolve(message: MessageDto): NodeNameResolution
}

// First URL drives the single image/link-preview slot. Extension classification still drops a URL
// the fetch path has since discovered serves an HTML page.
private fun messagePreviewInputs(
    message: MessageDto,
    linkPreviewController: LinkPreviewController?,
    inlineImageDimensions: InlineImageDimensionsStore?,
): MessagePreviewInputs {
    val cachedURL = linkPreviewController?.detectUrl(message)
        ?: LinkPreviewUrlExtractor.extractFirst(message.text)
    val isInlineImage = cachedURL != null &&
        (linkPreviewController?.routesToInlineImage(cachedURL) ?: ImageURLClassifier.isImageURL(cachedURL))
    val imageUrl = cachedURL?.let { ImageURLClassifier.directImageURL(it) }
    val imageAspect = if (isInlineImage && imageUrl != null) {
        inlineImageDimensions?.aspect(imageUrl)
    } else {
        null
    }
    return MessagePreviewInputs(
        cachedURL = cachedURL,
        isInlineImage = isInlineImage,
        imageAspect = imageAspect,
        previewState = linkPreviewController?.previewState(message.id) ?: PreviewLoadState.IDLE,
        loadedPreview = linkPreviewController?.loadedPreview(message.id),
        hasPreviewImage = linkPreviewController?.previewImageBytes(message.id) != null,
        hasPreviewIcon = linkPreviewController?.previewIconBytes(message.id) != null,
        hasInlineImage = linkPreviewController?.inlineImageBytes(message.id) != null,
        imageIsGIF = linkPreviewController?.inlineImageIsGIF(message.id) == true,
    )
}

private data class MessagePreviewInputs(
    val cachedURL: String?,
    val isInlineImage: Boolean,
    val imageAspect: Double?,
    val previewState: PreviewLoadState,
    val loadedPreview: LinkPreviewDataDto?,
    val hasPreviewImage: Boolean,
    val hasPreviewIcon: Boolean,
    val hasInlineImage: Boolean,
    val imageIsGIF: Boolean,
)
