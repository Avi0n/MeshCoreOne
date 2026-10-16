package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Value-equality completeness for the W3 fragment payloads. Each payload must be structurally equal iff
 * every rendering-affecting field matches, because that equality is what tells the timeline a row changed.
 * These guard against a field being dropped from a payload's equality (or a `ByteArray` compared by
 * reference), which would leave a visibly changed row reporting "unchanged".
 */
class MessageFragmentPayloadEqualityTest {
    private val messageID = ChatFixtures.freshUuid()

    @Test
    fun `image reference equality covers cache key and role`() {
        val ref = ImageReference(messageID, ImageReference.Role.INLINE)
        assertEquals(ref, ImageReference(messageID, ImageReference.Role.INLINE))
        assertNotEquals(ref, ImageReference(messageID, ImageReference.Role.LINK_PREVIEW_IMAGE))
        assertNotEquals(ref, ImageReference(ChatFixtures.freshUuid(), ImageReference.Role.INLINE))
    }

    @Test
    fun `inline image equality covers state auto-play and aspect`() {
        val base = InlineImage(InlineImage.LoadState.Pending, autoPlayGIFs = true, cachedAspect = 1.5)
        assertEquals(base, InlineImage(InlineImage.LoadState.Pending, autoPlayGIFs = true, cachedAspect = 1.5))
        assertNotEquals(base, base.copy(state = InlineImage.LoadState.Loading("u")))
        assertNotEquals(base, base.copy(autoPlayGIFs = false))
        assertNotEquals(base, base.copy(cachedAspect = 2.0))
    }

    @Test
    fun `inline image loaded state distinguishes the gif flag`() {
        val ref = ImageReference(messageID, ImageReference.Role.INLINE)
        assertNotEquals(
            InlineImage.LoadState.Loaded(ref, isGIF = true),
            InlineImage.LoadState.Loaded(ref, isGIF = false),
        )
    }

    @Test
    fun `map preview equality covers every field`() {
        val base = MapPreviewFragmentState(1.0, 2.0, isDark = false, isOffline = false, isReady = false)
        assertEquals(base, MapPreviewFragmentState(1.0, 2.0, isDark = false, isOffline = false, isReady = false))
        assertNotEquals(base, base.copy(latitude = 1.5))
        assertNotEquals(base, base.copy(longitude = 2.5))
        assertNotEquals(base, base.copy(isDark = true))
        assertNotEquals(base, base.copy(isOffline = true))
        assertNotEquals(base, base.copy(isReady = true))
    }

    @Test
    fun `link preview primary url resolves for loaded and legacy only`() {
        val loaded = LinkPreviewFragmentState(
            LinkPreviewFragmentState.Mode.Loaded(LinkPreviewDataDto(url = "https://a"), image = null, icon = null),
        )
        val legacy = LinkPreviewFragmentState(
            LinkPreviewFragmentState.Mode.Legacy("https://b", title = null, image = null, icon = null),
        )
        assertEquals("https://a", loaded.primaryURL)
        assertEquals("https://b", legacy.primaryURL)
        assertNull(LinkPreviewFragmentState(LinkPreviewFragmentState.Mode.Idle).primaryURL)
        assertNull(LinkPreviewFragmentState(LinkPreviewFragmentState.Mode.Loading("https://c")).primaryURL)
        assertNull(LinkPreviewFragmentState(LinkPreviewFragmentState.Mode.NoPreview).primaryURL)
        assertNull(LinkPreviewFragmentState(LinkPreviewFragmentState.Mode.Disabled("https://d")).primaryURL)
    }

    @Test
    fun `link preview data dto distinguishes title dimensions and fetch time`() {
        val base = LinkPreviewDataDto(
            url = "https://a",
            title = "t",
            imageWidth = 10,
            imageHeight = 20,
            fetchedAtEpochSeconds = 1.0,
        )
        assertEquals(base, base.copy())
        assertNotEquals(base, base.copy(url = "https://b"))
        assertNotEquals(base, base.copy(title = "other"))
        assertNotEquals(base, base.copy(imageWidth = 11))
        assertNotEquals(base, base.copy(imageHeight = 21))
        assertNotEquals(base, base.copy(fetchedAtEpochSeconds = 2.0))
    }
}
