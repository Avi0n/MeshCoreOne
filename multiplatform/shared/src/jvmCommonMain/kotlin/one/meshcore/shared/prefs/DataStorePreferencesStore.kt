package one.meshcore.shared.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath

/**
 * The disk-backed [PreferencesStore], a thin facade over Jetpack DataStore Preferences. One
 * implementation serves both JVM targets (Android and the desktop/Windows build) from the shared JVM
 * source set — the same rationale as the TCP socket adapter — because DataStore Preferences is itself
 * multiplatform and file-backed. The Android and desktop apps differ only in the path they hand in.
 *
 * DataStore permits exactly one active instance per file, so construct this once per process at the app
 * layer and inject it. [path] must end in `.preferences_pb` (DataStore's required extension).
 */
class DataStorePreferencesStore private constructor(private val dataStore: DataStore<Preferences>) : PreferencesStore {
    override suspend fun getString(key: String): String? = dataStore.data.first()[stringPreferencesKey(key)]

    override suspend fun getBoolean(key: String): Boolean? = dataStore.data.first()[booleanPreferencesKey(key)]

    override suspend fun getInt(key: String): Int? = dataStore.data.first()[intPreferencesKey(key)]

    override suspend fun getDouble(key: String): Double? = dataStore.data.first()[doublePreferencesKey(key)]

    override suspend fun putString(key: String, value: String) {
        dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun putBoolean(key: String, value: Boolean) {
        dataStore.edit { it[booleanPreferencesKey(key)] = value }
    }

    override suspend fun putInt(key: String, value: Int) {
        dataStore.edit { it[intPreferencesKey(key)] = value }
    }

    override suspend fun putDouble(key: String, value: Double) {
        dataStore.edit { it[doublePreferencesKey(key)] = value }
    }

    override suspend fun remove(key: String) {
        // A logical key is written with one type, but clear every typed variant so a value can never
        // survive a remove because it was stored under a different primitive than the remover assumes.
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(key))
            prefs.remove(booleanPreferencesKey(key))
            prefs.remove(intPreferencesKey(key))
            prefs.remove(doublePreferencesKey(key))
        }
    }

    companion object {
        /** Opens (or creates) the DataStore file at [path], which must end in `.preferences_pb`. */
        fun atPath(path: String): DataStorePreferencesStore =
            DataStorePreferencesStore(PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() }))
    }
}
