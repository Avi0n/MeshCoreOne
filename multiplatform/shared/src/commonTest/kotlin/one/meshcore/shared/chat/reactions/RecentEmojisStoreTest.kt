package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentEmojisStoreTest {
    @Test
    fun defaultsWhenEmpty() = runTest {
        val store = RecentEmojisStore()
        assertEquals(RecentEmojisStore.DEFAULT_EMOJIS, store.recentEmojis)
    }

    @Test
    fun recordUsageMovesToFrontAndCaps() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = RecentEmojisStore(prefs)
        store.recordUsage("🔥")
        assertEquals("🔥", store.recentEmojis.first())
        assertEquals(RecentEmojisStore.MAX_RECENT, store.recentEmojis.size)

        store.recordUsage("🎉")
        assertEquals(listOf("🎉", "🔥") + RecentEmojisStore.DEFAULT_EMOJIS.take(4), store.recentEmojis)

        val reloaded = RecentEmojisStore(prefs)
        reloaded.load()
        assertEquals(store.recentEmojis, reloaded.recentEmojis)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val encoded = RecentEmojisStore.encode(listOf("👍", "❤️"))
        assertEquals(listOf("👍", "❤️"), RecentEmojisStore.decode(encoded))
        assertEquals(RecentEmojisStore.DEFAULT_EMOJIS, RecentEmojisStore.decode(null))
        assertEquals(RecentEmojisStore.DEFAULT_EMOJIS, RecentEmojisStore.decode(""))
        assertEquals("recentReactionEmojis", RecentEmojisStore.PREFS_KEY)
    }

    @Test
    fun frequentStoreStartsEmptyUntilUsed() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = RecentEmojisStore(
            prefs,
            maxRecent = RecentEmojisStore.MAX_FREQUENT,
            prefsKey = RecentEmojisStore.FREQUENT_PREFS_KEY,
            emptyFallback = emptyList(),
        )
        store.load()
        assertEquals(emptyList(), store.recentEmojis)
        store.recordUsage("😀")
        assertEquals(listOf("😀"), store.recentEmojis)
    }
}
