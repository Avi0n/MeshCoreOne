package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto

/**
 * Builds the immutable [MessageItem] for a message from value-typed inputs. A pure function — no I/O, no
 * suspension, no shared state — so it runs off the confinement dispatcher inside [ChatCoordinator.rebuildItems].
 * Mirrors the Swift `MessageFragmentBuilder`.
 *
 * [makeFragments] is the single seam that assembles the ordered fragment list: text, then reaction summary,
 * then — gated on state and toggles — the malware / inline-image / link-preview and map-preview fragments.
 * The footer's hop / path / region slots are added on [MessageFooter] by the path/repeat slice.
 */
object MessageFragmentBuilder {
    /**
     * Sentinel URL for an inline-image fragment that reaches a failure state without a real source URL. The
     * bubble renders it as the generic failure placeholder.
     */
    private const val BLANK_URL = "about:blank"

    fun makeItem(message: MessageDto, inputs: MessageBuildInputs, envInputs: EnvInputs): MessageItem = MessageItem(
        id = message.id,
        envelope = makeEnvelope(message, inputs),
        content = makeFragments(message, inputs, envInputs),
        footer = makeFooter(message, inputs, envInputs),
        grouping = makeGrouping(inputs),
        shouldRequestPreviewFetch = shouldRequestPreviewFetch(message, inputs),
        actionAvailability = MessageActionAvailability.of(message),
    )

    /**
     * The ordered content fragment list. Text is always first; the reaction summary follows when present. A
     * malware verdict short-circuits: it replaces the image/link fragment, still appends the map preview
     * (the location is not the flagged link), and returns. Otherwise the master preview toggle gates the
     * inline-image-vs-link-preview split, and the map preview is appended last.
     */
    fun makeFragments(message: MessageDto, inputs: MessageBuildInputs, envInputs: EnvInputs): List<MessageFragment> {
        val fragments = mutableListOf<MessageFragment>()
        fragments += MessageFragment.Text(makeText(message, inputs, envInputs))

        val summary = message.reactionSummary
        if (!summary.isNullOrEmpty()) {
            fragments += MessageFragment.ReactionSummary(summary)
        }

        val url = inputs.cachedURL
        if (inputs.previewState == PreviewLoadState.MALWARE_WARNING && url != null) {
            fragments += MessageFragment.MalwareWarning(url)
            appendMapPreviewIfPresent(fragments, inputs, envInputs)
            return fragments
        }

        if (envInputs.previewsEnabled) {
            if (inputs.isInlineImageURL) {
                fragments += MessageFragment.InlineImage(makeInlineImage(url, inputs, envInputs))
            } else {
                fragments += MessageFragment.LinkPreview(makeLinkPreview(message, url, inputs))
            }
        }

        appendMapPreviewIfPresent(fragments, inputs, envInputs)
        return fragments
    }

    /**
     * Appends a map-preview fragment for the first linkified coordinate, if any. The privacy gate returns
     * before appending when chat map thumbnails are disabled, so the map view never fires the third-party
     * tile request; the coordinate text in the body stays tappable through the formatted-text path.
     */
    private fun appendMapPreviewIfPresent(
        fragments: MutableList<MessageFragment>,
        inputs: MessageBuildInputs,
        envInputs: EnvInputs,
    ) {
        if (!envInputs.showMapPreviews) return
        val latitude = inputs.mapPreviewLatitude ?: return
        val longitude = inputs.mapPreviewLongitude ?: return
        fragments += MessageFragment.MapPreview(
            MapPreviewFragmentState(
                latitude = latitude,
                longitude = longitude,
                isDark = envInputs.mapBasemapIsDark,
                isOffline = envInputs.isOffline,
                isReady = inputs.isMapPreviewReady,
            ),
        )
    }

    /**
     * Maps the message's preview state onto the inline-image state. Only [PreviewLoadState.LOADING] and a
     * byte-less [PreviewLoadState.LOADED] carry a URL the view fetches, and the controller reaches LOADING
     * only after the malware blocklist and the SSRF gate have both cleared that URL. An idle row therefore
     * renders [InlineImage.LoadState.Pending] — a reserved box, no request — rather than racing the checks.
     */
    private fun makeInlineImage(url: String?, inputs: MessageBuildInputs, envInputs: EnvInputs): InlineImage {
        val loadState: InlineImage.LoadState = when (inputs.previewState) {
            PreviewLoadState.LOADED ->
                if (inputs.hasInlineImageRef) {
                    InlineImage.LoadState.Loaded(
                        ImageReference(inputs.messageID, ImageReference.Role.INLINE),
                        isGIF = inputs.imageIsGIF,
                    )
                } else if (url != null) {
                    InlineImage.LoadState.Loading(url)
                } else {
                    InlineImage.LoadState.Failed(BLANK_URL)
                }

            PreviewLoadState.LOADING ->
                if (url != null) InlineImage.LoadState.Loading(url) else InlineImage.LoadState.Failed(BLANK_URL)

            PreviewLoadState.NO_PREVIEW ->
                if (url != null) InlineImage.LoadState.Failed(url) else InlineImage.LoadState.Failed(BLANK_URL)

            PreviewLoadState.DISABLED ->
                if (url != null) InlineImage.LoadState.Disabled(url) else InlineImage.LoadState.Failed(BLANK_URL)

            PreviewLoadState.IDLE, PreviewLoadState.MALWARE_WARNING ->
                if (url != null) InlineImage.LoadState.Pending else InlineImage.LoadState.Failed(BLANK_URL)
        }
        return InlineImage(
            state = loadState,
            autoPlayGIFs = envInputs.autoPlayGIFs,
            cachedAspect = inputs.inlineImageAspect,
        )
    }

    private fun makeLinkPreview(
        message: MessageDto,
        url: String?,
        inputs: MessageBuildInputs,
    ): LinkPreviewFragmentState {
        val imageRef = if (inputs.hasPreviewImageRef) {
            ImageReference(inputs.messageID, ImageReference.Role.LINK_PREVIEW_IMAGE)
        } else {
            null
        }
        val iconRef = if (inputs.hasPreviewIconRef) {
            ImageReference(inputs.messageID, ImageReference.Role.LINK_PREVIEW_ICON)
        } else {
            null
        }

        val mode: LinkPreviewFragmentState.Mode = when (inputs.previewState) {
            PreviewLoadState.LOADED -> {
                val preview = inputs.loadedPreview
                if (preview != null) {
                    LinkPreviewFragmentState.Mode.Loaded(preview, image = imageRef, icon = iconRef)
                } else {
                    LinkPreviewFragmentState.Mode.NoPreview
                }
            }

            PreviewLoadState.LOADING ->
                if (url != null) LinkPreviewFragmentState.Mode.Loading(url) else LinkPreviewFragmentState.Mode.Idle

            PreviewLoadState.NO_PREVIEW -> LinkPreviewFragmentState.Mode.NoPreview

            PreviewLoadState.DISABLED ->
                if (url != null) LinkPreviewFragmentState.Mode.Disabled(url) else LinkPreviewFragmentState.Mode.Idle

            PreviewLoadState.IDLE -> {
                val legacyURL = message.linkPreviewURL
                if (legacyURL != null) {
                    LinkPreviewFragmentState.Mode.Legacy(
                        url = legacyURL,
                        title = message.linkPreviewTitle,
                        image = imageRef,
                        icon = iconRef,
                    )
                } else if (url != null) {
                    LinkPreviewFragmentState.Mode.Loading(url)
                } else {
                    LinkPreviewFragmentState.Mode.Idle
                }
            }

            PreviewLoadState.MALWARE_WARNING -> LinkPreviewFragmentState.Mode.Idle
        }
        return LinkPreviewFragmentState(mode)
    }

    /**
     * Mirrors the predicate the bubble evaluated to admit a preview fetch: an idle URL with no persisted
     * legacy preview. A legacy message never re-fetches — its stored card wins.
     */
    private fun shouldRequestPreviewFetch(message: MessageDto, inputs: MessageBuildInputs): Boolean =
        inputs.previewState == PreviewLoadState.IDLE &&
            inputs.cachedURL != null &&
            message.linkPreviewURL == null

    private fun makeText(message: MessageDto, inputs: MessageBuildInputs, envInputs: EnvInputs): MessageTextPayload =
        MessageTextPayload(
            raw = message.text,
            formattedText = inputs.formattedText,
            baseColor = inputs.baseColor,
            isOutgoing = message.isOutgoing,
            currentUserName = envInputs.currentUserName,
        )

    private fun makeEnvelope(message: MessageDto, inputs: MessageBuildInputs): MessageEnvelope = MessageEnvelope(
        messageID = message.id,
        isOutgoing = message.isOutgoing,
        senderName = inputs.senderResolution.displayName,
        senderResolution = inputs.senderResolution,
        status = message.status,
        // Send time, not drain time: the centered divider is the sole time surface, so a days-old
        // drained message must not be relabelled at its delivery time.
        dateEpochSeconds = message.senderDateEpochSeconds,
        containsSelfMention = message.containsSelfMention,
        mentionSeen = message.mentionSeen,
        incomingAvatar = inputs.incomingAvatar,
    )

    private fun makeFooter(message: MessageDto, inputs: MessageBuildInputs, envInputs: EnvInputs): MessageFooter {
        // Send time shows inside every bubble — incoming and outgoing, DM and channel. It is the sole time
        // surface now that the centered cluster marker is gone, so it is unconditional (no toggle, not gated
        // on flood routing). It carries the clock-corrected send time so a skewed sender clock never surfaces
        // a misleading value; the raw wire value stays available in the info sheet.
        val sendTime = message.senderDateEpochSeconds
        // Hop count is incoming flood only: an outgoing row has no inbound path to report, matching
        // Swift `makeFooter`. Region still follows flood routing on both directions.
        val showHop = envInputs.showIncomingHopCount && message.isFloodRouted && !message.isOutgoing
        val regionMatch = if (envInputs.showIncomingRegion && message.isFloodRouted) {
            RegionScopeSemantics.coalesce(message.regionScope, message.regionScopeMatches)
        } else {
            null
        }
        val region = regionMatch?.let { RegionScopeSemantics.chipLabel(it) }
        return MessageFooter(
            showStatusRow = message.isOutgoing,
            status = message.status,
            isChannelMessage = message.isChannelMessage,
            heardRepeats = message.heardRepeats,
            showHeardCount = envInputs.showIncomingHeardCount && !message.isOutgoing && message.heardRepeats > 0,
            retryAttempt = message.retryAttempt,
            maxRetryAttempts = message.maxRetryAttempts,
            sendCount = message.sendCount,
            sendTimeToShowEpochSeconds = sendTime,
            sendTimeWasCorrected = message.timestampCorrected,
            showHop = showHop,
            hopCount = message.hopCount,
            formattedPath = inputs.formattedPath,
            regionToShow = region,
            regionMatchNames = regionMatch?.let { RegionScopeSemantics.matchNames(it) }.orEmpty(),
        )
    }

    private fun makeGrouping(inputs: MessageBuildInputs): GroupingFlags = GroupingFlags(
        showTimestamp = inputs.showTimestamp,
        showDirectionGap = inputs.showDirectionGap,
        showSenderName = inputs.showSenderName,
        showNewMessagesDivider = inputs.showNewMessagesDivider,
        showDayDivider = inputs.showDayDivider,
        isClusterEnd = inputs.isClusterEnd,
    )
}
