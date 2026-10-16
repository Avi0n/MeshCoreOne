package one.meshcore.shared.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackupUserDefaultsRestoreTest {
    @Test
    fun aPersistFaultOnRestoreDoesNotThrow() = runTest {
        val warnings = mutableListOf<String>()
        val written = BackupUserDefaults(
            hasCompletedOnboarding = true,
            replyWithQuote = true,
            autoDeleteStaleNodesDays = 30,
        ).restoreInto(ThrowingPreferencesStore(), warnings::add)

        assertTrue(written.isEmpty())
        assertEquals(
            listOf(
                "Preferences restore failed: disk full",
                "Preferences restore failed: disk full",
                "Preferences restore failed: disk full",
            ),
            warnings,
        )
    }

    @Test
    fun aCancelledRestorePropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            BackupUserDefaults(hasCompletedOnboarding = true).restoreInto(
                CancelingPreferencesStore(cancelled),
                warnings::add,
            )
        }
        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aRestoreErrorPropagates() = runTest {
        val probe = RestoreProbeError()
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<RestoreProbeError> {
            BackupUserDefaults(hasCompletedOnboarding = true).restoreInto(
                ErrorPreferencesStore(probe),
                warnings::add,
            )
        }
        assertSame(probe, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun restoreWritesMapFilterKeysWhenAbsent() = runTest {
        val store = InMemoryPreferencesStore()
        val written = BackupUserDefaults(
            mapFilterMainMap = MAIN,
            mapFilterTracePath = TRACE,
            mapFilterNeighborSNR = SNR,
        ).restoreInto(store) {}

        assertEquals(MAIN, store.getString(PersistenceKeys.MAP_FILTER_MAIN_MAP))
        assertEquals(TRACE, store.getString(PersistenceKeys.MAP_FILTER_TRACE_PATH))
        assertEquals(SNR, store.getString(PersistenceKeys.MAP_FILTER_NEIGHBOR_SNR))
        assertEquals(
            listOf(
                PersistenceKeys.MAP_FILTER_MAIN_MAP,
                PersistenceKeys.MAP_FILTER_TRACE_PATH,
                PersistenceKeys.MAP_FILTER_NEIGHBOR_SNR,
            ),
            written,
        )
    }

    @Test
    fun restoreWritesMapClusteringWhenAbsent() = runTest {
        val store = InMemoryPreferencesStore()
        val written = BackupUserDefaults(mapClusteringEnabled = false).restoreInto(store) {}

        assertEquals(false, store.getBoolean(PersistenceKeys.MAP_CLUSTERING_ENABLED))
        assertEquals(listOf(PersistenceKeys.MAP_CLUSTERING_ENABLED), written)
    }

    @Test
    fun incomingHeardCountBacksUpAndRestoresOnlyWhenUnset() = runTest {
        val source = InMemoryPreferencesStore()
        source.putBoolean(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT, true)
        val snapshot = source.backupUserDefaultsSnapshot()
        assertEquals(true, snapshot.showIncomingHeardCount)

        val destination = InMemoryPreferencesStore()
        assertEquals(
            listOf(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT),
            snapshot.restoreInto(destination) {},
        )
        assertEquals(true, destination.getBoolean(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT))
        destination.putBoolean(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT, false)
        assertTrue(snapshot.restoreInto(destination) {}.isEmpty())
        assertEquals(false, destination.getBoolean(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT))
    }

    @Test
    fun restoreWritesTranslationTargetKeysWhenAbsent() = runTest {
        val store = InMemoryPreferencesStore()
        val written = BackupUserDefaults(
            translationTargetLanguage = "da",
            useDefaultTranslationApp = true,
            translationOffersEnabled = false,
        ).restoreInto(store) {}

        assertEquals("da", store.getString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE))
        assertEquals(true, store.getBoolean(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP))
        assertEquals(false, store.getBoolean(PersistenceKeys.TRANSLATION_OFFERS_ENABLED))
        assertEquals(
            listOf(
                PersistenceKeys.USE_DEFAULT_TRANSLATION_APP,
                PersistenceKeys.TRANSLATION_OFFERS_ENABLED,
                PersistenceKeys.TRANSLATION_TARGET_LANGUAGE,
            ),
            written,
        )
    }
}

private const val MAIN = """{"contacts":true}"""
private const val TRACE = """{"repeaters":false}"""
private const val SNR = """{"rooms":true}"""

private class ThrowingPreferencesStore : PreferencesStore {
    override suspend fun getString(key: String): String? = null
    override suspend fun getBoolean(key: String): Boolean? = null
    override suspend fun getInt(key: String): Int? = null
    override suspend fun getDouble(key: String): Double? = null
    override suspend fun putString(key: String, value: String) = error("disk full")
    override suspend fun putBoolean(key: String, value: Boolean) = error("disk full")
    override suspend fun putInt(key: String, value: Int) = error("disk full")
    override suspend fun putDouble(key: String, value: Double) = error("disk full")
    override suspend fun remove(key: String) = error("disk full")
}

private class CancelingPreferencesStore(private val cancelled: CancellationException) : PreferencesStore {
    override suspend fun getString(key: String): String? = null
    override suspend fun getBoolean(key: String): Boolean? = null
    override suspend fun getInt(key: String): Int? = null
    override suspend fun getDouble(key: String): Double? = null
    override suspend fun putString(key: String, value: String) = throw cancelled
    override suspend fun putBoolean(key: String, value: Boolean) = throw cancelled
    override suspend fun putInt(key: String, value: Int) = throw cancelled
    override suspend fun putDouble(key: String, value: Double) = throw cancelled
    override suspend fun remove(key: String) = throw cancelled
}

private class ErrorPreferencesStore(private val probe: RestoreProbeError) : PreferencesStore {
    override suspend fun getString(key: String): String? = null
    override suspend fun getBoolean(key: String): Boolean? = null
    override suspend fun getInt(key: String): Int? = null
    override suspend fun getDouble(key: String): Double? = null
    override suspend fun putString(key: String, value: String): Unit = throw probe
    override suspend fun putBoolean(key: String, value: Boolean): Unit = throw probe
    override suspend fun putInt(key: String, value: Int): Unit = throw probe
    override suspend fun putDouble(key: String, value: Double): Unit = throw probe
    override suspend fun remove(key: String): Unit = throw probe
}

private class RestoreProbeError : Error("probe")
