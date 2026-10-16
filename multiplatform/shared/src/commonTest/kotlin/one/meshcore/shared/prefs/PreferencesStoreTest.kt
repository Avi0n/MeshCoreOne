package one.meshcore.shared.prefs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round-trip and tolerance behaviour of the [InMemoryPreferencesStore] fake that stands in for the
 * DataStore-backed production store in every unit test. Absent keys read `null`, a removed key is gone,
 * and a wrong-typed read returns `null` rather than throwing — the tolerant contract the DataStore
 * actual also honours.
 */
class PreferencesStoreTest {
    @Test
    fun roundTripsEachPrimitiveType() = runTest {
        val store = InMemoryPreferencesStore()
        store.putString("s", "hello")
        store.putBoolean("b", true)
        store.putInt("i", 42)
        store.putDouble("d", 3.5)

        assertEquals("hello", store.getString("s"))
        assertEquals(true, store.getBoolean("b"))
        assertEquals(42, store.getInt("i"))
        assertEquals(3.5, store.getDouble("d"))
    }

    @Test
    fun absentKeyReadsNull() = runTest {
        val store = InMemoryPreferencesStore()
        assertNull(store.getString("missing"))
        assertNull(store.getBoolean("missing"))
        assertNull(store.getInt("missing"))
        assertNull(store.getDouble("missing"))
    }

    @Test
    fun removeClearsTheKey() = runTest {
        val store = InMemoryPreferencesStore(mapOf("s" to "value"))
        assertEquals("value", store.getString("s"))
        store.remove("s")
        assertNull(store.getString("s"))
    }

    @Test
    fun wrongTypedReadReturnsNullNotThrows() = runTest {
        val store = InMemoryPreferencesStore()
        store.putString("s", "not a number")
        assertNull(store.getInt("s"))
        assertEquals("not a number", store.getString("s"))
    }
}
