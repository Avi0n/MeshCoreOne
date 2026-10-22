// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionIntentTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.DataStoreConnectionPreferences
import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.datastore.PreferenceKey
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.runtime.LastConnectionStore
import com.meshcoreone.android.core.runtime.SystemRuntimeClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ConnectionIntentStoreOriginalPredicateTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var storage: MeshCoreStorage
    private lateinit var store: LastConnectionStore
    private val key = PreferenceKey.BooleanKey(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED, null)

    @Before fun openStore() = runBlocking {
        val directory = temporary.newFolder()
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = directory
        }
        open()
        assertTrue(storage.preferences.snapshot().storedValues.isEmpty())
    }

    @After fun closeStore() = runBlocking { storage.close() }

    @Test fun originalUserDisconnectedPersistsAndRestoresThroughActualAppAdapter() = runBlocking {
        store.persistIntent(ConnectionIntent.UserDisconnected)

        assertEquals(ConnectionIntent.UserDisconnected, store.restoredIntent())
        assertEquals(true, storage.preferences.snapshot().stored(key))
        storage.close()
        open()
        assertEquals(ConnectionIntent.UserDisconnected, store.restoredIntent())
        assertEquals(true, storage.preferences.snapshot().stored(key))
    }

    @Test fun originalNoneClearRequiresThePreviouslyRestoredUserDisconnected() = runBlocking {
        store.persistIntent(ConnectionIntent.UserDisconnected)
        assertEquals(ConnectionIntent.UserDisconnected, store.restoredIntent())
        assertEquals(true, storage.preferences.snapshot().stored(key))

        store.persistIntent(ConnectionIntent.None)

        assertEquals(ConnectionIntent.None, store.restoredIntent())
        assertFalse(storage.preferences.snapshot().contains(key))
    }

    @Test fun originalDefaultWantsConnectionClearsThePreviouslyRestoredUserDisconnected() = runBlocking {
        store.persistIntent(ConnectionIntent.UserDisconnected)
        assertEquals(ConnectionIntent.UserDisconnected, store.restoredIntent())
        assertEquals(true, storage.preferences.snapshot().stored(key))
        val intent = ConnectionIntent.WantsConnection()
        assertFalse(intent.forceFullSync)

        store.persistIntent(intent)

        assertEquals(ConnectionIntent.None, store.restoredIntent())
        assertFalse(storage.preferences.snapshot().contains(key))
    }

    @Test fun originalUnpersistedIntentRestoresNoneWithoutWritingADefault() = runBlocking {
        assertEquals(ConnectionIntent.None, store.restoredIntent())
        assertTrue(storage.preferences.snapshot().storedValues.isEmpty())
    }

    private fun open() {
        storage = MeshCoreStorage.get(context)
        store = LastConnectionStore(DataStoreConnectionPreferences(storage.preferences), SystemRuntimeClock())
    }
}
