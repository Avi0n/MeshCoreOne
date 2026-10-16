package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the W2 (`.text`-only) slice of the Swift `MessageFragmentBuilderTests`: the single text fragment,
 * the envelope/footer/grouping capture from the message, the incoming-send-time footer, and the "a changed
 * message field changes the item" hash-flip family. The reaction / preview / image / map / malware cases
 * are W3.
 */
class MessageFragmentBuilderTest {
    private fun message(
        text: String = "hello",
        direction: MessageDirection = MessageDirection.OUTGOING,
        status: MessageStatus = MessageStatus.SENT,
    ): MessageDto = ChatFixtures.message(text = text, direction = direction, status = status)

    private fun inputs(id: Uuid): MessageBuildInputs = buildInputs(id)

    /**
     * Rich build inputs for the W3 fragment cases. Mirrors the Swift `makeInputs`: everything defaults to the
     * "nothing detected / idle" values so each test states only the fields it exercises.
     */
    @Suppress("LongParameterList")
    private fun richInputs(
        id: Uuid,
        previewState: PreviewLoadState = PreviewLoadState.IDLE,
        loadedPreview: LinkPreviewDataDto? = null,
        cachedURL: String? = null,
        isInlineImageURL: Boolean = false,
        hasInlineImageRef: Boolean = false,
        hasPreviewImageRef: Boolean = false,
        hasPreviewIconRef: Boolean = false,
        imageIsGIF: Boolean = false,
        mapPreviewLatitude: Double? = null,
        mapPreviewLongitude: Double? = null,
        isMapPreviewReady: Boolean = false,
    ): MessageBuildInputs = buildInputs(id).copy(
        previewState = previewState,
        loadedPreview = loadedPreview,
        cachedURL = cachedURL,
        isInlineImageURL = isInlineImageURL,
        hasInlineImageRef = hasInlineImageRef,
        hasPreviewImageRef = hasPreviewImageRef,
        hasPreviewIconRef = hasPreviewIconRef,
        imageIsGIF = imageIsGIF,
        mapPreviewLatitude = mapPreviewLatitude,
        mapPreviewLongitude = mapPreviewLongitude,
        isMapPreviewReady = isMapPreviewReady,
    )

    private fun kinds(item: MessageItem): List<FragmentKind> = item.content.map(::kindOf)

    private enum class FragmentKind { TEXT, INLINE_IMAGE, LINK_PREVIEW, MAP_PREVIEW, MALWARE_WARNING, REACTION_SUMMARY }

    private fun kindOf(fragment: MessageFragment): FragmentKind = when (fragment) {
        is MessageFragment.Text -> FragmentKind.TEXT
        is MessageFragment.InlineImage -> FragmentKind.INLINE_IMAGE
        is MessageFragment.LinkPreview -> FragmentKind.LINK_PREVIEW
        is MessageFragment.MapPreview -> FragmentKind.MAP_PREVIEW
        is MessageFragment.MalwareWarning -> FragmentKind.MALWARE_WARNING
        is MessageFragment.ReactionSummary -> FragmentKind.REACTION_SUMMARY
    }

    @Suppress("LongParameterList")
    private fun env(
        currentUserName: String = "Me",
        showIncomingSendTime: Boolean = false,
        previewsEnabled: Boolean = false,
        showMapPreviews: Boolean = true,
        isDark: Boolean = false,
        autoPlayGIFs: Boolean = true,
    ): EnvInputs = EnvInputs(
        currentUserName = currentUserName,
        showIncomingSendTime = showIncomingSendTime,
        previewsEnabled = previewsEnabled,
        showMapPreviews = showMapPreviews,
        isDark = isDark,
        autoPlayGIFs = autoPlayGIFs,
    )

    private companion object {
        const val IMAGE_URL = "https://example.com/cat.jpg"
        const val PAGE_URL = "https://example.com/page"
        const val LEGACY_URL = "https://example.com"
    }

    @Test
    fun `plain text produces a single text fragment`() {
        val message = message(text = "hello")
        val item = MessageFragmentBuilder.makeItem(message, inputs(message.id), env())

        assertEquals(1, item.content.size)
        val fragment = item.content[0]
        assertTrue(fragment is MessageFragment.Text)
        assertEquals("hello", (fragment as MessageFragment.Text).payload.raw)
        assertNull(fragment.payload.formattedText)
    }

    @Test
    fun `text payload carries direction color and current user name`() {
        val message = message(direction = MessageDirection.OUTGOING)
        val custom = buildInputs(message.id).copy(baseColor = BaseColorSlot.OUTGOING)
        val item = MessageFragmentBuilder.makeItem(message, custom, env(currentUserName = "Alice"))

        val payload = (item.content[0] as MessageFragment.Text).payload
        assertEquals(BaseColorSlot.OUTGOING, payload.baseColor)
        assertTrue(payload.isOutgoing)
        assertEquals("Alice", payload.currentUserName)
    }

    @Test
    fun `same inputs produce equal items and equal hashes`() {
        val message = message()
        val a = MessageFragmentBuilder.makeItem(message, inputs(message.id), env())
        val b = MessageFragmentBuilder.makeItem(message, inputs(message.id), env())

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `envelope captures identity direction and mention flags`() {
        val message = message().copy(containsSelfMention = true, mentionSeen = true)
        val item = MessageFragmentBuilder.makeItem(message, inputs(message.id), env())

        assertEquals(message.id, item.envelope.messageID)
        assertTrue(item.envelope.isOutgoing)
        assertTrue(item.envelope.containsSelfMention)
        assertTrue(item.envelope.mentionSeen)
    }

    @Test
    fun `envelope senderName comes from the resolution display name`() {
        val message = message()
        val resolved = buildInputs(message.id).copy(
            senderResolution = NodeNameResolution(displayName = "Bravo", matchKind = NodeNameMatchKind.EXACT),
        )
        val item = MessageFragmentBuilder.makeItem(message, resolved, env())

        assertEquals("Bravo", item.envelope.senderName)
        assertEquals(NodeNameMatchKind.EXACT, item.envelope.senderResolution.matchKind)
    }

    @Test
    fun `envelope date is the send time not the drain time`() {
        val sendTime = 1_700_000_000L
        val drainTime = sendTime + 3 * 24 * 60 * 60.0
        val message = ChatFixtures.message(timestamp = sendTime, createdAt = drainTime)
        val item = MessageFragmentBuilder.makeItem(message, inputs(message.id), env())

        assertEquals(sendTime.toDouble(), item.envelope.dateEpochSeconds)
        assertNotEquals(message.createdAt, item.envelope.dateEpochSeconds)
    }

    @Test
    fun `envelope hasFailed reflects failed status only`() {
        val failed = MessageFragmentBuilder.makeItem(
            message(status = MessageStatus.FAILED),
            inputs(ChatFixtures.freshUuid()),
            env(),
        )
        val sentMessage = message(status = MessageStatus.SENT)
        val sent = MessageFragmentBuilder.makeItem(sentMessage, inputs(sentMessage.id), env())

        assertTrue(failed.envelope.hasFailed)
        assertTrue(!sent.envelope.hasFailed)
    }

    @Test
    fun `footer captures repeat and retry counters and outgoing status row`() {
        val message = message().copy(heardRepeats = 3, retryAttempt = 2, maxRetryAttempts = 5, sendCount = 4)
        val item = MessageFragmentBuilder.makeItem(message, inputs(message.id), env())

        assertEquals(3, item.footer.heardRepeats)
        assertEquals(2, item.footer.retryAttempt)
        assertEquals(5, item.footer.maxRetryAttempts)
        assertEquals(4, item.footer.sendCount)
        assertTrue(item.footer.showStatusRow) // outgoing
        assertTrue(!item.footer.isChannelMessage)
    }

    @Test
    fun `footer shows corrected send time on incoming message`() {
        val corrected = 1_700_000_000L
        val message = ChatFixtures.message(
            timestamp = corrected,
            direction = MessageDirection.INCOMING,
            senderTimestamp = 100L,
            timestampCorrected = true,
        )
        val item = MessageFragmentBuilder.makeItem(message, inputs(message.id), env())

        assertEquals(corrected.toDouble(), item.footer.sendTimeToShowEpochSeconds)
        assertTrue(item.footer.sendTimeWasCorrected)
        assertTrue(!item.footer.showStatusRow) // incoming
    }

    @Test
    fun `footer shows send time regardless of the legacy showIncomingSendTime flag`() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING)
        val on = MessageFragmentBuilder.makeItem(message, inputs(message.id), env(showIncomingSendTime = true))
        val off = MessageFragmentBuilder.makeItem(message, inputs(message.id), env(showIncomingSendTime = false))
        assertEquals(message.senderDateEpochSeconds, on.footer.sendTimeToShowEpochSeconds)
        assertEquals(message.senderDateEpochSeconds, off.footer.sendTimeToShowEpochSeconds)
    }

    @Test
    fun `footer shows send time on outgoing messages`() {
        val message = message(direction = MessageDirection.OUTGOING)
        val item = MessageFragmentBuilder.makeItem(message, inputs(message.id), env())
        assertEquals(message.senderDateEpochSeconds, item.footer.sendTimeToShowEpochSeconds)
    }

    @Test
    fun `grouping flags are carried from the build inputs`() {
        val message = message()
        val grouping = buildInputs(message.id).copy(
            showTimestamp = true,
            showDirectionGap = true,
            showSenderName = true,
            showNewMessagesDivider = true,
            showDayDivider = true,
        )
        val item = MessageFragmentBuilder.makeItem(message, grouping, env())

        assertEquals(GroupingFlags(true, true, true, true, true), item.grouping)
    }

    @Test
    fun `shouldRequestPreviewFetch is false and previewFetchTaskID null in W2`() {
        val message = message()
        val item = MessageFragmentBuilder.makeItem(message, inputs(message.id), env())
        assertTrue(!item.shouldRequestPreviewFetch)
        assertNull(item.previewFetchTaskID)
    }

    @Test
    fun `a changed message field changes the item`() {
        val id = ChatFixtures.freshUuid()
        val base = ChatFixtures.message(id = id, status = MessageStatus.SENT)
        val variants = listOf(
            base.copy(heardRepeats = 3),
            base.copy(retryAttempt = 1),
            base.copy(maxRetryAttempts = 5),
            base.copy(status = MessageStatus.DELIVERED),
            base.copy(containsSelfMention = true),
            base.copy(containsSelfMention = true, mentionSeen = true),
            base.copy(sendCount = 9),
            base.copy(text = "changed"),
        )
        val baseItem = MessageFragmentBuilder.makeItem(base, inputs(id), env())
        for (variant in variants) {
            val item = MessageFragmentBuilder.makeItem(variant, inputs(id), env())
            assertNotEquals(baseItem, item, "changing a message field must change the item")
            assertNotEquals(baseItem.hashCode(), item.hashCode())
        }
    }

    // MARK: - Reaction summary

    @Test
    fun `reaction summary appears after the text fragment`() {
        val message = message(text = "hi").copy(reactionSummary = "👍:1")
        val item = MessageFragmentBuilder.makeItem(message, inputs(message.id), env())

        assertEquals(2, item.content.size)
        assertTrue(item.content[0] is MessageFragment.Text)
        val reaction = item.content[1]
        assertTrue(reaction is MessageFragment.ReactionSummary)
        assertEquals("👍:1", (reaction as MessageFragment.ReactionSummary).summary)
    }

    @Test
    fun `an empty reaction summary produces no reaction fragment`() {
        val message = message(text = "hi").copy(reactionSummary = "")
        val item = MessageFragmentBuilder.makeItem(message, inputs(message.id), env())
        assertEquals(listOf(FragmentKind.TEXT), kinds(item))
    }

    @Test
    fun `adding a reaction flips the item hash`() {
        val id = ChatFixtures.freshUuid()
        val without = message(text = "hi").copy(id = id)
        val with = message(text = "hi").copy(id = id, reactionSummary = "👍:1")
        val a = MessageFragmentBuilder.makeItem(without, inputs(id), env())
        val b = MessageFragmentBuilder.makeItem(with, inputs(id), env())

        assertNotEquals(a, b)
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    // MARK: - Malware short-circuit

    @Test
    fun `malware warning replaces preview and inline image fragments`() {
        val message = message(text = "click me")
        val richInputs = richInputs(
            message.id,
            previewState = PreviewLoadState.MALWARE_WARNING,
            cachedURL = "https://bad.example",
            isInlineImageURL = true,
        )
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env(previewsEnabled = true))
        assertEquals(listOf(FragmentKind.TEXT, FragmentKind.MALWARE_WARNING), kinds(item))
    }

    @Test
    fun `malware warning still appends the map preview for a located message`() {
        val message = message(text = "click me at 37.7749, -122.4194")
        val richInputs = richInputs(
            message.id,
            previewState = PreviewLoadState.MALWARE_WARNING,
            cachedURL = "https://bad.example",
            mapPreviewLatitude = 37.7749,
            mapPreviewLongitude = -122.4194,
        )
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env())
        assertEquals(listOf(FragmentKind.TEXT, FragmentKind.MALWARE_WARNING, FragmentKind.MAP_PREVIEW), kinds(item))
    }

    // MARK: - Inline image vs link preview split

    @Test
    fun `image url with isInlineImageURL true routes to inline image`() {
        val message = message(text = "see")
        val richInputs = richInputs(message.id, cachedURL = IMAGE_URL, isInlineImageURL = true)
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env(previewsEnabled = true))

        assertTrue(kinds(item).contains(FragmentKind.INLINE_IMAGE))
        assertTrue(!kinds(item).contains(FragmentKind.LINK_PREVIEW))
    }

    @Test
    fun `link content off suppresses inline image and link preview for image url`() {
        val message = message(text = "see")
        val richInputs = richInputs(message.id, cachedURL = IMAGE_URL, isInlineImageURL = true)
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env(previewsEnabled = false))

        assertTrue(!kinds(item).contains(FragmentKind.INLINE_IMAGE))
        assertTrue(!kinds(item).contains(FragmentKind.LINK_PREVIEW))
    }

    @Test
    fun `link content off suppresses link preview for non image url`() {
        val message = message(text = "see")
        val richInputs = richInputs(message.id, cachedURL = PAGE_URL, isInlineImageURL = false)
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env(previewsEnabled = false))
        assertTrue(!kinds(item).contains(FragmentKind.LINK_PREVIEW))
    }

    @Test
    fun `link content on routes non image url to link preview`() {
        val message = message(text = "see")
        val richInputs = richInputs(message.id, cachedURL = PAGE_URL, isInlineImageURL = false)
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env(previewsEnabled = true))

        assertTrue(kinds(item).contains(FragmentKind.LINK_PREVIEW))
        assertTrue(!kinds(item).contains(FragmentKind.INLINE_IMAGE))
    }

    @Test
    fun `disabled preview state yields disabled inline image`() {
        val message = message(text = "see")
        val richInputs = richInputs(
            message.id,
            previewState = PreviewLoadState.DISABLED,
            cachedURL = IMAGE_URL,
            isInlineImageURL = true,
        )
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env(previewsEnabled = true))

        val fragment = item.content.first { it is MessageFragment.InlineImage } as MessageFragment.InlineImage
        assertEquals(InlineImage.LoadState.Disabled(IMAGE_URL), fragment.image.state)
    }

    @Test
    fun `page serving image url reroutes to link preview`() {
        val message = message(text = "see")
        val richInputs = richInputs(message.id, cachedURL = IMAGE_URL, isInlineImageURL = false)
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env(previewsEnabled = true))

        assertTrue(kinds(item).contains(FragmentKind.LINK_PREVIEW))
        assertTrue(!kinds(item).contains(FragmentKind.INLINE_IMAGE))
    }

    // MARK: - Legacy link preview

    @Test
    fun `legacy link preview surfaces with persisted fields when state is idle`() {
        val message = message(text = "see").copy(linkPreviewURL = LEGACY_URL, linkPreviewTitle = "Example")
        val richInputs = richInputs(message.id, previewState = PreviewLoadState.IDLE)
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env(previewsEnabled = true))

        val state = (item.content.last() as MessageFragment.LinkPreview).state
        val mode = state.mode
        assertTrue(mode is LinkPreviewFragmentState.Mode.Legacy)
        mode as LinkPreviewFragmentState.Mode.Legacy
        assertEquals(LEGACY_URL, mode.url)
        assertEquals("Example", mode.title)
    }

    @Test
    fun `legacy link preview carries image reference when inputs say so`() {
        val message = message(text = "see").copy(linkPreviewURL = LEGACY_URL, linkPreviewTitle = "Example")
        val richInputs = richInputs(message.id, previewState = PreviewLoadState.IDLE, hasPreviewImageRef = true)
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env(previewsEnabled = true))

        val state = (item.content.last() as MessageFragment.LinkPreview).state
        val mode = state.mode as LinkPreviewFragmentState.Mode.Legacy
        assertEquals(ImageReference(message.id, ImageReference.Role.LINK_PREVIEW_IMAGE), mode.image)
    }

    // MARK: - Preview fetch admission

    @Test
    fun `shouldRequestPreviewFetch is true on idle with URL and no legacy fields`() {
        val message = message(text = "hi")
        val richInputs = richInputs(message.id, previewState = PreviewLoadState.IDLE, cachedURL = LEGACY_URL)
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env())
        assertTrue(item.shouldRequestPreviewFetch)
    }

    @Test
    fun `shouldRequestPreviewFetch is false on legacy message`() {
        val message = message(text = "hi").copy(linkPreviewURL = LEGACY_URL)
        val richInputs = richInputs(message.id, previewState = PreviewLoadState.IDLE, cachedURL = LEGACY_URL)
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env())
        assertTrue(!item.shouldRequestPreviewFetch)
    }

    @Test
    fun `preview fetch task id is distinct per fetch-wanting message`() {
        val messageA = message(text = "see $LEGACY_URL")
        val messageB = message(text = "also $LEGACY_URL")
        val itemA = MessageFragmentBuilder.makeItem(
            messageA,
            richInputs(messageA.id, previewState = PreviewLoadState.IDLE, cachedURL = LEGACY_URL),
            env(previewsEnabled = true),
        )
        val itemB = MessageFragmentBuilder.makeItem(
            messageB,
            richInputs(messageB.id, previewState = PreviewLoadState.IDLE, cachedURL = LEGACY_URL),
            env(previewsEnabled = true),
        )

        assertTrue(itemA.shouldRequestPreviewFetch)
        assertTrue(itemB.shouldRequestPreviewFetch)
        assertEquals(itemA.id, itemA.previewFetchTaskID)
        assertEquals(itemB.id, itemB.previewFetchTaskID)
        assertNotEquals(itemA.previewFetchTaskID, itemB.previewFetchTaskID)
    }

    @Test
    fun `preview fetch task id is null when no fetch is wanted`() {
        val message = message(text = "hi")
        val idle = MessageFragmentBuilder.makeItem(
            message,
            richInputs(message.id, previewState = PreviewLoadState.IDLE, cachedURL = LEGACY_URL),
            env(previewsEnabled = true),
        )
        val loading = MessageFragmentBuilder.makeItem(
            message,
            richInputs(message.id, previewState = PreviewLoadState.LOADING, cachedURL = LEGACY_URL),
            env(previewsEnabled = true),
        )
        val noURL = MessageFragmentBuilder.makeItem(
            message,
            richInputs(message.id, previewState = PreviewLoadState.IDLE, cachedURL = null),
            env(previewsEnabled = true),
        )

        assertEquals(message.id, idle.previewFetchTaskID)
        assertNull(loading.previewFetchTaskID)
        assertNull(noURL.previewFetchTaskID)
    }

    // MARK: - Preview hash propagation

    @Test
    fun `previewState change flips the item hash`() {
        val message = message(text = "hi")
        val idle = MessageFragmentBuilder.makeItem(
            message,
            richInputs(message.id, previewState = PreviewLoadState.IDLE, cachedURL = LEGACY_URL),
            env(previewsEnabled = true),
        )
        val loaded = MessageFragmentBuilder.makeItem(
            message,
            richInputs(message.id, previewState = PreviewLoadState.LOADED, cachedURL = LEGACY_URL),
            env(previewsEnabled = true),
        )
        assertNotEquals(idle.hashCode(), loaded.hashCode())
    }

    @Test
    fun `loadedPreview change flips the item hash`() {
        val message = message(text = "hi")
        val preview = LinkPreviewDataDto(url = LEGACY_URL, title = "Example", fetchedAtEpochSeconds = 1_700_000_000.0)
        val without = MessageFragmentBuilder.makeItem(
            message,
            richInputs(message.id, previewState = PreviewLoadState.LOADED, cachedURL = LEGACY_URL),
            env(previewsEnabled = true),
        )
        val withInputs = richInputs(
            message.id,
            previewState = PreviewLoadState.LOADED,
            cachedURL = LEGACY_URL,
            loadedPreview = preview,
        )
        val with = MessageFragmentBuilder.makeItem(message, withInputs, env(previewsEnabled = true))
        assertNotEquals(without.hashCode(), with.hashCode())
    }

    // MARK: - Map preview

    @Test
    fun `a coordinate in build inputs emits a mapPreview fragment`() {
        val message = message(text = "Meet at 37.7749, -122.4194")
        val richInputs = richInputs(
            message.id,
            mapPreviewLatitude = 37.7749,
            mapPreviewLongitude = -122.4194,
            isMapPreviewReady = true,
        )
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env(isDark = true))

        val state = (item.content.last() as MessageFragment.MapPreview).state
        assertEquals(37.7749, state.latitude)
        assertEquals(-122.4194, state.longitude)
        assertTrue(state.isDark)
        assertTrue(state.isReady)
    }

    @Test
    fun `map preview appearance can oppose message chrome`() {
        val message = message(text = "Meet at 37.7749, -122.4194")
        val inputs = richInputs(
            message.id,
            mapPreviewLatitude = 37.7749,
            mapPreviewLongitude = -122.4194,
            isMapPreviewReady = true,
        )
        for (mapDark in listOf(false, true)) {
            val environment = env(isDark = !mapDark).copy(mapBasemapIsDark = mapDark)
            val item = MessageFragmentBuilder.makeItem(message, inputs, environment)
            val state = (item.content.last() as MessageFragment.MapPreview).state
            assertEquals(mapDark, state.isDark)
            assertEquals(!mapDark, environment.isDark)
        }
    }

    @Test
    fun `no coordinate means no mapPreview fragment`() {
        val message = message(text = "no coordinates here")
        val item = MessageFragmentBuilder.makeItem(message, richInputs(message.id), env())
        assertTrue(item.content.none { it is MessageFragment.MapPreview })
    }

    @Test
    fun `map preview is skipped when the privacy gate is off`() {
        val message = message(text = "Meet at 37.7749, -122.4194")
        val richInputs = richInputs(
            message.id,
            mapPreviewLatitude = 37.7749,
            mapPreviewLongitude = -122.4194,
        )
        val item = MessageFragmentBuilder.makeItem(message, richInputs, env(showMapPreviews = false))
        assertTrue(item.content.none { it is MessageFragment.MapPreview })
    }
}
