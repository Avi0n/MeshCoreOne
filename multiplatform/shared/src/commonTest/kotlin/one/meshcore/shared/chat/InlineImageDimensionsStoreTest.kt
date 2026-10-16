package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InlineImageDimensionsStoreTest {
    @Test
    fun saveAndAspectRoundTrip() {
        val store = InlineImageDimensionsStore()
        assertNull(store.aspect("https://ex/a.png"))
        store.save("https://ex/a.png", width = 800, height = 400)
        assertEquals(2.0, store.aspect("https://ex/a.png"))
    }

    @Test
    fun rejectsNonPositiveSizes() {
        val store = InlineImageDimensionsStore()
        store.save("https://ex/bad.png", width = 0, height = 100)
        store.save("https://ex/bad2.png", width = 100, height = -1)
        assertNull(store.aspect("https://ex/bad.png"))
        assertNull(store.aspect("https://ex/bad2.png"))
    }

    @Test
    fun reloadsAspectsFromThePersister() {
        val persister = MemoryPersister(mapOf("https://ex/a.png" to 2.0))
        val store = InlineImageDimensionsStore(persister)
        assertEquals(2.0, store.aspect("https://ex/a.png"))
        store.save("https://ex/b.png", width = 100, height = 400)
        assertEquals(0.25, persister.snapshot["https://ex/b.png"])
        assertEquals(2.0, persister.snapshot["https://ex/a.png"])
    }

    @Test
    fun emptyPersisterStartsEmpty() {
        val store = InlineImageDimensionsStore(MemoryPersister())
        assertNull(store.aspect("https://ex/a.png"))
    }

    private class MemoryPersister(initial: Map<String, Double> = emptyMap()) : InlineImageDimensionsPersister {
        var snapshot: Map<String, Double> = initial
        override fun load(): Map<String, Double> = snapshot
        override fun save(aspects: Map<String, Double>) {
            snapshot = aspects
        }
    }
}
