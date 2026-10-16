package one.meshcore.shared.remotenode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.security.KeyStore

/**
 * A restored node-password ciphertext whose Android Keystore key is absent.
 * The alias delete stays in this test package. The shipping apps are a different UID.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreBackupRestoreInstrumentedTest {
    @Test
    fun restoredCiphertextWithoutTheKeystoreKeyIsDiscarded() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(SHARED_TEST_PACKAGE, target.packageName)
        val prefs = InMemoryPreferencesStore()
        val store = KeystoreNodePasswordStore(prefs, Dispatchers.IO)
        store.storePassword(PUBLIC_KEY, PASSWORD)
        assertEquals(PASSWORD, store.retrievePassword(PUBLIC_KEY))
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        assertTrue(keyStore.containsAlias(KEY_ALIAS))
        keyStore.deleteEntry(KEY_ALIAS)
        assertFalse(keyStore.containsAlias(KEY_ALIAS))
        assertNull(store.retrievePassword(PUBLIC_KEY))
        assertFalse(store.hasPassword(PUBLIC_KEY))
        assertNull(prefs.getString(PREF_KEY))
    }

    private companion object {
        const val SHARED_TEST_PACKAGE = "one.meshcore.shared.test"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "one.meshcore.node_password_key"
        const val PASSWORD = "mesh-node-secret"
        const val PUBLIC_KEY_LENGTH = 32
        const val PUBLIC_KEY_FILL = 0x5A
        const val PREF_PREFIX = "nodepw."

        val PUBLIC_KEY = ByteArray(PUBLIC_KEY_LENGTH) { PUBLIC_KEY_FILL.toByte() }
        val PREF_KEY = PREF_PREFIX + PUBLIC_KEY.toUppercaseHex()
    }
}
