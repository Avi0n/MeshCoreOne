package one.meshcore.shared.pathedit

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `RecentHopsStore` recents behavior: move-to-front LRU, trim to the cap, persistence
 * across store instances (shared backing), per-radio scoping, and lowercase-hex storage.
 */
class RecentHopsStoreTest {
    private val radioX = PathEditFixtures.uuid(10)
    private val radioY = PathEditFixtures.uuid(11)

    private fun key(byte: Int): ByteArray = ByteArray(PathEditFixtures.PUBLIC_KEY_LENGTH) { byte.toByte() }

    @Test
    fun recordMovesToFront() = runTest {
        val store = RecentHopsStore(InMemoryPreferencesStore())
        val pk1 = key(0x01)
        val pk2 = key(0x02)
        var recents = store.record(pk1, emptyList(), radioX)
        recents = store.record(pk2, recents, radioX)
        recents = store.record(pk1, recents, radioX)
        assertEquals(2, recents.size)
        assertContentEquals(pk1, recents[0])
        assertContentEquals(pk2, recents[1])
    }

    @Test
    fun recordTrimsToLimit() = runTest {
        val store = RecentHopsStore(InMemoryPreferencesStore())
        var recents = emptyList<ByteArray>()
        for (i in 0 until 10) {
            recents = store.record(key(i), recents, radioX)
        }
        assertEquals(RecentHopsStore.LIMIT, recents.size)
        assertContentEquals(key(9), recents.first())
        assertContentEquals(key(2), recents.last())
    }

    @Test
    fun recentKeysPersistAcrossStoreInstances() = runTest {
        val preferences = InMemoryPreferencesStore()
        val pk = key(0x0A)
        RecentHopsStore(preferences).record(pk, emptyList(), radioX)

        val reloaded = RecentHopsStore(preferences).load(radioX)
        assertEquals(1, reloaded.size)
        assertContentEquals(pk, reloaded[0])
    }

    @Test
    fun recentKeysScopedPerRadio() = runTest {
        val preferences = InMemoryPreferencesStore()
        RecentHopsStore(preferences).record(key(0x0A), emptyList(), radioX)
        assertTrue(RecentHopsStore(preferences).load(radioY).isEmpty())
    }

    @Test
    fun recentKeysStoredLowercase() = runTest {
        val preferences = InMemoryPreferencesStore()
        val pk = PathEditFixtures.key(0xAB, 0xCD)
        RecentHopsStore(preferences).record(pk, emptyList(), radioX)

        val stored = preferences.getString(RecentHopsStore.storageKey(radioX)) ?: ""
        assertEquals(stored, stored.lowercase())
        assertTrue(stored.startsWith("abcd"), "expected lowercase hex prefix, got: $stored")
    }
}
