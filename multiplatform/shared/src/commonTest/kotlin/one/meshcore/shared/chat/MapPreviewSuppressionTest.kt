package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wardriving channel never renders map thumbnails, whatever the global toggle says, so the app cannot
 * flood the tile API on the channel that carries a coordinate in nearly every message. Mirrors the Swift
 * `ChatConversationType.suppressesMapPreviews`.
 */
class MapPreviewSuppressionTest {
    private fun channel(name: String) = ChatFixtures.channel(index = 3, name = name)

    @Test
    fun wardrivingChannelSuppressesInEveryWrittenForm() {
        assertTrue(suppressesMapPreviews(channel("wardriving")))
        assertTrue(suppressesMapPreviews(channel("#wardriving")))
        assertTrue(suppressesMapPreviews(channel("WarDriving")))
        assertTrue(suppressesMapPreviews(channel("  #WARDRIVING  ")))
    }

    @Test
    fun otherChannelsAndDirectMessagesNeverSuppress() {
        assertFalse(suppressesMapPreviews(channel("wardriving-north")))
        assertFalse(suppressesMapPreviews(channel("Public")))
        assertFalse(suppressesMapPreviews(null))
    }
}
