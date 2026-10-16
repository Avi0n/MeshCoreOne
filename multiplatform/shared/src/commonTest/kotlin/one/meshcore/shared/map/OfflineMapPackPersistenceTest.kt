package one.meshcore.shared.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OfflineMapPackPersistenceTest {
    @Test
    fun roundTripPacksThroughPrefs() = runTest {
        val prefs = InMemoryPreferencesStore()
        val registry = OfflineMapPackRegistry()
        registry.upsert(
            OfflineMapPack(
                id = "p1",
                name = "Home",
                layer = OfflineMapLayer.TOPO,
                createdAtEpochSeconds = 99,
                completed = true,
                sizeBytes = 1000,
                paused = true,
            ),
        )
        registry.saveToPrefs(prefs)
        val loaded = OfflineMapPackRegistry()
        loaded.loadFromPrefs(prefs)
        assertEquals(1, loaded.list().size)
        val pack = loaded.list().first()
        assertEquals("Home", pack.name)
        assertEquals(OfflineMapLayer.TOPO, pack.layer)
        assertTrue(pack.completed)
        assertEquals(1000L, pack.sizeBytes)
        assertTrue(pack.paused)
    }

    @Test
    fun loadPreservesPacksChangedWhilePreferencesAreSuspended() = runTest {
        val backing = InMemoryPreferencesStore()
        val stored = OfflineMapPackRegistry().apply {
            upsert(pack(id = "progress", progress = 0.1))
            upsert(pack(id = "stored"))
        }
        stored.saveToPrefs(backing)
        val prefs = GatedOfflinePackReadStore(backing)
        val registry = OfflineMapPackRegistry()

        val load = async { registry.loadFromPrefs(prefs) }
        prefs.readStarted.await()
        registry.upsert(pack(id = "progress", progress = 0.8))
        registry.upsert(pack(id = "new"))
        prefs.releaseRead.complete(Unit)
        load.await()

        val packs = registry.list().associateBy { it.id }
        assertEquals(setOf("progress", "stored", "new"), packs.keys)
        assertEquals(0.8, packs.getValue("progress").progress)
    }

    @Test
    fun persistFaultKeepsTheInMemoryPackList() = runTest {
        val prefs = object : PreferencesStore by InMemoryPreferencesStore() {
            override suspend fun putString(key: String, value: String) {
                error("datastore down")
            }
        }
        val registry = OfflineMapPackRegistry()
        registry.upsert(
            OfflineMapPack(
                id = "p1",
                name = "Home",
                layer = OfflineMapLayer.BASE,
                createdAtEpochSeconds = 1,
            ),
        )

        registry.saveToPrefs(prefs)

        assertEquals(1, registry.list().size)
        assertEquals("Home", registry.list().single().name)
    }

    @Test
    fun aCancelledSavePropagatesAndKeepsTheInMemoryPackList() = runTest {
        val cancelled = CancellationException("superseded")
        val prefs = object : PreferencesStore by InMemoryPreferencesStore() {
            override suspend fun putString(key: String, value: String): Unit = throw cancelled
        }
        val registry = homeRegistry()

        val thrown = assertFailsWith<CancellationException> { registry.saveToPrefs(prefs) }

        assertSame(cancelled, thrown)
        assertEquals("Home", registry.list().single().name)
    }

    @Test
    fun aSaveErrorPropagatesAndKeepsTheInMemoryPackList() = runTest {
        val fault = OfflinePackSaveProbeError()
        val prefs = object : PreferencesStore by InMemoryPreferencesStore() {
            override suspend fun putString(key: String, value: String): Unit = throw fault
        }
        val registry = homeRegistry()

        val thrown = assertFailsWith<OfflinePackSaveProbeError> { registry.saveToPrefs(prefs) }

        assertSame(fault, thrown)
        assertEquals("Home", registry.list().single().name)
    }

    private fun homeRegistry(): OfflineMapPackRegistry {
        val registry = OfflineMapPackRegistry()
        registry.upsert(
            OfflineMapPack(
                id = "p1",
                name = "Home",
                layer = OfflineMapLayer.BASE,
                createdAtEpochSeconds = 1,
            ),
        )
        return registry
    }

    private fun pack(id: String, progress: Double = 0.0) = OfflineMapPack(
        id = id,
        name = id,
        layer = OfflineMapLayer.BASE,
        createdAtEpochSeconds = 1,
        progress = progress,
    )
}

private class OfflinePackSaveProbeError : Error("probe")

private class GatedOfflinePackReadStore(private val backing: InMemoryPreferencesStore) : PreferencesStore by backing {
    val readStarted = CompletableDeferred<Unit>()
    val releaseRead = CompletableDeferred<Unit>()

    override suspend fun getString(key: String): String? {
        if (key == OFFLINE_MAP_PACKS_PREFS_KEY) {
            readStarted.complete(Unit)
            releaseRead.await()
        }
        return backing.getString(key)
    }
}
