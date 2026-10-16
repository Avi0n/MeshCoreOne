package one.meshcore.shared.prefs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The key/value persistence seam the connection and app-state layers read and write through — the
 * Kotlin analogue of iOS `UserDefaults`. Keys are the pinned strings in [PersistenceKeys]; the two
 * reachability axes (which radio vs. how to reach it) are kept apart by the callers, not here.
 *
 * The production actual is DataStore-backed (androidMain + desktopMain via the shared JVM source set);
 * an [InMemoryPreferencesStore] fake stands in for unit tests. Absent keys return `null`, so a caller
 * distinguishes "never written" from "written false" — load-bearing for the onboarding and connection
 * intent bits, where a missing key and an explicit `false` mean different things.
 */
interface PreferencesStore {
    suspend fun getString(key: String): String?

    suspend fun getBoolean(key: String): Boolean?

    suspend fun getInt(key: String): Int?

    suspend fun getDouble(key: String): Double?

    suspend fun putString(key: String, value: String)

    suspend fun putBoolean(key: String, value: Boolean)

    suspend fun putInt(key: String, value: Int)

    suspend fun putDouble(key: String, value: Double)

    suspend fun remove(key: String)
}

/**
 * An in-memory [PreferencesStore] backed by a plain map behind a [Mutex]. It is the unit-test double
 * for every layer that persists through the store, and also serves as an ephemeral fallback before a
 * disk-backed store is available. Values are held as their natural types; a wrong-typed read returns
 * `null` rather than throwing, matching the tolerant `UserDefaults`/DataStore behaviour.
 */
class InMemoryPreferencesStore(initial: Map<String, Any> = emptyMap()) : PreferencesStore {
    private val mutex = Mutex()
    private val values = initial.toMutableMap()

    /** A snapshot of the backing map, for test assertions. */
    suspend fun snapshot(): Map<String, Any> = mutex.withLock { values.toMap() }

    override suspend fun getString(key: String): String? = mutex.withLock { values[key] as? String }

    override suspend fun getBoolean(key: String): Boolean? = mutex.withLock { values[key] as? Boolean }

    override suspend fun getInt(key: String): Int? = mutex.withLock { values[key] as? Int }

    override suspend fun getDouble(key: String): Double? = mutex.withLock { values[key] as? Double }

    override suspend fun putString(key: String, value: String) = writePreference(mutex, values, key, value)

    override suspend fun putBoolean(key: String, value: Boolean) = writePreference(mutex, values, key, value)

    override suspend fun putInt(key: String, value: Int) = writePreference(mutex, values, key, value)

    override suspend fun putDouble(key: String, value: Double) = writePreference(mutex, values, key, value)

    override suspend fun remove(key: String) {
        mutex.withLock { values.remove(key) }
    }
}

private suspend fun writePreference(mutex: Mutex, values: MutableMap<String, Any>, key: String, value: Any) {
    mutex.withLock { values[key] = value }
}
