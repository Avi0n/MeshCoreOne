package one.meshcore.shared.remotenode

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for [KeystoreNodePasswordStore] against the real Android Keystore: the encrypt/decrypt
 * round-trip, overwrite, delete, and — the failure path the ruling calls out — an undecryptable stored
 * entry treated as absent (deleted, `null` returned, never thrown). The Keystore-unavailable store branch
 * is the structural mirror of the decrypt-failure branch (both degrade through the same `runCatching`),
 * and cannot be forced on a device whose Keystore works.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreNodePasswordStoreInstrumentedTest {

    private val publicKey = ByteArray(32) { 0xAB.toByte() }

    @Test
    fun roundTripStoresAndRetrieves() = runBlocking {
        val store = KeystoreNodePasswordStore(InMemoryPreferencesStore(), Dispatchers.IO)
        store.storePassword(publicKey, "hunter2")
        assertTrue(store.hasPassword(publicKey))
        assertEquals("hunter2", store.retrievePassword(publicKey))
    }

    @Test
    fun overwriteKeepsLatest() = runBlocking {
        val store = KeystoreNodePasswordStore(InMemoryPreferencesStore(), Dispatchers.IO)
        store.storePassword(publicKey, "first")
        store.storePassword(publicKey, "second")
        assertEquals("second", store.retrievePassword(publicKey))
    }

    @Test
    fun deleteRemovesEntry() = runBlocking {
        val store = KeystoreNodePasswordStore(InMemoryPreferencesStore(), Dispatchers.IO)
        store.storePassword(publicKey, "gone")
        store.deletePassword(publicKey)
        assertFalse(store.hasPassword(publicKey))
        assertNull(store.retrievePassword(publicKey))
    }

    @Test
    fun undecryptableEntryTreatedAsAbsentAndDeleted() = runBlocking {
        val prefs = InMemoryPreferencesStore()
        val store = KeystoreNodePasswordStore(prefs, Dispatchers.IO)
        store.storePassword(publicKey, "valid")
        // Corrupt the stored ciphertext (as a key rotation or a restore to a new device would).
        prefs.putString("nodepw." + publicKey.toUppercaseHex(), "!!!not-decryptable!!!")

        assertNull(store.retrievePassword(publicKey))
        // The bad entry is discarded, so it no longer reports as present.
        assertFalse(store.hasPassword(publicKey))
    }
}
