package one.meshcore.shared.chat.text

import one.meshcore.shared.channel.ChannelSecret
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `meshcoreone://hashtag/<name>` link, name resolution, and the W5 secret seam. */
class HashtagDeeplinkTest {
    @Test fun `url round-trips through channelNameFromUrl`() {
        val url = HashtagDeeplink.url("general")
        assertEquals("meshcoreone://hashtag/general", url)
        assertEquals("general", HashtagDeeplink.channelNameFromUrl(url!!))
    }

    @Test fun `channelNameFromUrl returns null for non-hashtag urls`() {
        assertNull(HashtagDeeplink.channelNameFromUrl("meshcoreone://mention/Bob"))
        assertNull(HashtagDeeplink.channelNameFromUrl("https://apple.com"))
    }

    @Test fun `fullChannelName normalizes and prefixes a valid name`() {
        assertEquals("#general", HashtagDeeplink.fullChannelName("General"))
        assertEquals("#weather-sf", HashtagDeeplink.fullChannelName("weather-sf"))
    }

    @Test fun `fullChannelName rejects an invalid name`() {
        assertNull(HashtagDeeplink.fullChannelName("Bad Name"))
        assertNull(HashtagDeeplink.fullChannelName("-leading"))
    }

    @Test fun `join seam derives the channel secret from the hashed hash-name`() {
        val secret = HashtagJoinSeam.deriveSecret("#general")
        assertEquals(ChannelSecret.SIZE_BYTES, secret.size)
        // The `#` is part of the hashed input: sha256("#general")[0:16].
        assertContentEquals(ChannelSecret.hashtagChannelSecret("general"), secret)
    }
}
