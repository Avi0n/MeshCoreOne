package one.meshcore.shared.remotenode

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.prefs.PreferencesStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Android production [NodePasswordStore], the analogue of the iOS Keychain. Node passwords are
 * encrypted under an AES-256-GCM key held in the Android Keystore (never exportable) and the
 * ciphertext is persisted in the app's [PreferencesStore] (DataStore), keyed by the node's uppercase public
 * key hex — the reconciliation axis, never the volatile connection handle. The keystore key is generated
 * without user-authentication gating so the background keep-alive and BLE-reconnect re-auth can decrypt
 * headless, matching the iOS keychain's after-first-unlock accessibility.
 *
 * Each record is `base64(iv ‖ ciphertext+tag)`: a **fresh random 12-byte IV per write** (GCM must never
 * reuse an IV under one key — tag forgery is the failure mode a review looks for first), the 16-byte GCM
 * tag appended by the cipher.
 *
 * Degrades, never crashes: any Keystore failure (creation, OEM quirk, absent StrongBox) makes a store a
 * no-op ("password not remembered") and a retrieve return `null` — an emergency responder on odd hardware
 * must still connect, only without the saved-password convenience. A stored entry that no longer decrypts
 * (key rotated, backup restored to a new device) is treated as absent: it is deleted and `null` returned,
 * never thrown. App-lifetime and shared across connections; construct once at the app layer and inject.
 */
class KeystoreNodePasswordStore(private val prefs: PreferencesStore, private val ioDispatcher: CoroutineDispatcher) :
    NodePasswordStore {

    override suspend fun storePassword(publicKey: ByteArray, password: String) {
        val record = withContext(ioDispatcher) {
            runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
                val ciphertext = cipher.doFinal(password.encodeToByteArray())
                Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
            }.getOrElse {
                // Keystore unavailable/failed: degrade to "not remembered" rather than block the login.
                Log.w(LOG_TAG, "node password not stored (keystore unavailable): ${it::class.simpleName}")
                null
            }
        } ?: return
        prefs.putString(prefKey(publicKey), record)
    }

    override suspend fun retrievePassword(publicKey: ByteArray): String? {
        val stored = prefs.getString(prefKey(publicKey)) ?: return null
        val decrypted = withContext(ioDispatcher) {
            runCatching {
                val record = Base64.decode(stored, Base64.NO_WRAP)
                require(record.size > IV_BYTES)
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, record.copyOf(IV_BYTES)))
                }
                cipher.doFinal(record.copyOfRange(IV_BYTES, record.size)).decodeToString()
            }.getOrNull()
        }
        if (decrypted == null) {
            // Undecryptable (key rotated, corrupted, restored to a new device) — treat as absent, not an error.
            Log.w(LOG_TAG, "node password entry undecryptable; discarding")
            deletePassword(publicKey)
        }
        return decrypted
    }

    override suspend fun deletePassword(publicKey: ByteArray) {
        prefs.remove(prefKey(publicKey))
    }

    override suspend fun hasPassword(publicKey: ByteArray): Boolean = prefs.getString(prefKey(publicKey)) != null

    private fun prefKey(publicKey: ByteArray): String = KEY_PREFIX + publicKey.toUppercaseHex()

    // Returns the persistent keystore key, generating it on first use. The key is confined to the Android
    // Keystore, so the plaintext key material never enters app memory. Throws on Keystore failure; the
    // callers catch and degrade.
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val LOG_TAG = "NodePasswordStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "one.meshcore.node_password_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_PREFIX = "nodepw."
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
