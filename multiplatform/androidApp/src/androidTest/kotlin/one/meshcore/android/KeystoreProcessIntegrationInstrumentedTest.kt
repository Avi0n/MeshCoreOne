package one.meshcore.android

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.prefs.DataStorePreferencesStore
import one.meshcore.shared.remotenode.KeystoreNodePasswordStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class KeystoreProcessIntegrationInstrumentedTest {
    private lateinit var application: MeshCoreApplication
    private lateinit var container: AppContainer
    private lateinit var directory: File
    private lateinit var nonce: String

    @Test
    fun seedPasswords() = phase(SEED) {
        assertFalse(directory.exists())
        assertAbsent()
        assertFalse(keyPresent())
        for (index in KEYS.indices) container.nodePasswordStore.storePassword(KEYS[index], PASSWORDS[index])
        for (index in KEYS.indices) {
            assertTrue(
                "Synthetic password round trip",
                container.nodePasswordStore.retrievePassword(KEYS[index]) == PASSWORDS[index],
            )
            assertTrue(container.nodePasswordStore.hasPassword(KEYS[index]))
        }
        val records = records()
        assertTrue(records.all { value -> PASSWORDS.none { value.contains(it) } })
        assertTrue(keyPresent())
        assertTrue(File(application.filesDir, PREFERENCES_FILE).isFile)
        writeReceipt(SEED, records, "saved")
    }

    @Test
    fun readPasswordsThenRemoveKey() = phase(READ) {
        val before = assertOriginalRecords()
        assertTrue(keyPresent())
        for (index in KEYS.indices) {
            assertTrue(
                "Restart retrieves synthetic password",
                container.nodePasswordStore.retrievePassword(KEYS[index]) == PASSWORDS[index],
            )
        }
        assertTrue(before == records())
        deleteAlias()
        assertFalse(keyPresent())
        assertTrue(before == records())
        writeReceipt(READ, before, "keyAbsentWithRecords")
    }

    @Test
    fun discardAfterMissingKeyRestart() = phase(DISCARD) {
        val before = assertOriginalRecords()
        assertFalse(keyPresent())
        for (key in KEYS) {
            assertTrue("No synthetic saved password", container.nodePasswordStore.retrievePassword(key) == null)
            assertFalse(container.nodePasswordStore.hasPassword(key))
        }
        assertAbsent()
        writeReceipt(DISCARD, before, "discarded")
    }

    @Test
    fun confirmDiscardAndRecoverAfterRestart() = phase(RECOVER) {
        assertAbsent()
        for (key in KEYS) {
            assertFalse(container.nodePasswordStore.hasPassword(key))
            assertTrue("No synthetic saved password", container.nodePasswordStore.retrievePassword(key) == null)
        }
        container.nodePasswordStore.storePassword(KEYS.first(), RECOVERED_PASSWORD)
        assertTrue(
            "Synthetic password recovers",
            container.nodePasswordStore.retrievePassword(KEYS.first()) == RECOVERED_PASSWORD,
        )
        assertTrue(container.nodePasswordStore.hasPassword(KEYS.first()))
        assertTrue(container.prefs.getString(prefKey(KEYS.first()))?.contains(RECOVERED_PASSWORD) == false)
        for (key in KEYS) container.nodePasswordStore.deletePassword(key)
        assertAbsent()
        deleteAlias()
        assertFalse(keyPresent())
        writeReceipt(RECOVER, emptyList(), "discardDurableAndRecoveredAndCleaned")
    }

    private fun phase(expected: Int, body: suspend () -> Unit) = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires the dedicated staged driver", args.containsKey(PHASE_ARGUMENT))
        assertTrue("Exact phase selector", args.getString(PHASE_ARGUMENT) == expected.toString())
        nonce = checkNotNull(args.getString(NONCE_ARGUMENT))
        assertTrue("Scoped run nonce", NONCE_PATTERN.matches(nonce))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(target.packageName == TARGET_PACKAGE)
        application = target.applicationContext as MeshCoreApplication
        assertTargetIdentity()
        container = application.container
        directory = File(application.filesDir, RECEIPT_DIRECTORY)
        withTimeout(TIMEOUT_MS) {
            qualifyGraph()
            if (expected != SEED) verifyPredecessors(expected)
            body()
        }
    }

    private suspend fun qualifyGraph() {
        assertTrue(container.nodePasswordStore is KeystoreNodePasswordStore)
        assertTrue(container.prefs is DataStorePreferencesStore)
        assertTrue(container.nodePasswordStoreProvider() === container.nodePasswordStore)
        assertTrue(container.appContext === application)
        withContext(Dispatchers.Main) {
            container.awaitInitialOfflineData()
            assertFalse(container.connectionManager.state.value.isConnected)
            assertNull(container.connectionManager.services)
            assertNull(container.lastConnectionStore.deviceId())
            assertNull(container.currentRadioId())
        }
        val rows = BackupPostcommitRows(container.database)
        rows.assertCounts(rows.snapshot(), copies = 0)
    }

    private fun verifyPredecessors(expected: Int) {
        for (index in SEED until expected) {
            val prior = readReceipt(index)
            assertTrue(prior.getString("nonce") == nonce)
            assertTrue(prior.getBoolean("passed"))
            assertTrue(prior.getInt("phase") == index)
            assertTrue(prior.getInt("uid") == Process.myUid())
            assertTrue(
                "New target process",
                prior.getInt("pid") != Process.myPid() ||
                    prior.getLong("processStartElapsed") != Process.getStartElapsedRealtime(),
            )
        }
    }

    private suspend fun assertOriginalRecords(): List<String> {
        val original = readReceipt(SEED).getJSONArray("ciphertextDigests")
        val current = records()
        for (index in KEYS.indices) assertTrue(digest(current[index]) == original.getString(index))
        return current
    }

    private suspend fun records(): List<String> = KEYS.map { checkNotNull(container.prefs.getString(prefKey(it))) }

    private suspend fun assertAbsent() {
        for (key in KEYS) assertTrue("No synthetic preference entry", container.prefs.getString(prefKey(key)) == null)
    }

    private fun assertTargetIdentity() {
        assertTrue(application.packageName == TARGET_PACKAGE)
        assertTrue(Process.myUid() == application.applicationInfo.uid)
    }

    private suspend fun keyPresent(): Boolean = withContext(Dispatchers.IO) {
        assertTargetIdentity()
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.containsAlias(KEY_ALIAS)
    }

    private suspend fun deleteAlias() = withContext(Dispatchers.IO) {
        assertTargetIdentity()
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
    }

    private fun writeReceipt(stage: Int, records: List<String>, result: String) {
        assertTargetIdentity()
        assertTrue(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "phase-$stage.json")
        assertFalse(file.exists())
        val json = JSONObject()
            .put("phase", stage).put("nonce", nonce).put("passed", true).put(result, true)
            .put("pid", Process.myPid()).put("uid", Process.myUid())
            .put("processStartElapsed", Process.getStartElapsedRealtime())
            .put("ciphertextDigests", JSONArray(records.map(::digest)))
            .put("applicationClass", application.javaClass.name)
            .put("containerClass", container.javaClass.name)
            .put("passwordStoreClass", container.nodePasswordStore.javaClass.name)
            .put("preferencesClass", container.prefs.javaClass.name)
        FileOutputStream(file).use {
            it.write(json.toString().encodeToByteArray())
            it.fd.sync()
        }
    }

    private fun readReceipt(stage: Int): JSONObject = JSONObject(File(directory, "phase-$stage.json").readText())

    private fun prefKey(key: ByteArray): String = PREF_PREFIX + key.toUppercaseHex()

    private fun digest(record: String): String =
        MessageDigest.getInstance("SHA-256").digest(record.encodeToByteArray()).toUppercaseHex()

    private companion object {
        const val TARGET_PACKAGE = "io.pocketmesh.app.debug"
        const val PHASE_ARGUMENT = "keystoreProcessPhase"
        const val NONCE_ARGUMENT = "keystoreProcessNonce"
        const val RECEIPT_DIRECTORY = "keystore-process-integration"
        const val PREFERENCES_FILE = "meshcore.preferences_pb"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "one.meshcore.node_password_key"
        const val PREF_PREFIX = "nodepw."
        const val TIMEOUT_MS = 20_000L
        const val SEED = 1
        const val READ = 2
        const val DISCARD = 3
        const val RECOVER = 4
        const val PUBLIC_KEY_LENGTH = 32
        const val FIRST_KEY_BYTE = 0x71
        const val SECOND_KEY_BYTE = 0x72
        const val RECOVERED_PASSWORD = "fixture-recovered-local-only"
        val NONCE_PATTERN = Regex("[0-9a-f]{32}")
        val KEYS =
            listOf(
                ByteArray(PUBLIC_KEY_LENGTH) {
                    FIRST_KEY_BYTE.toByte()
                },
                ByteArray(PUBLIC_KEY_LENGTH) { SECOND_KEY_BYTE.toByte() },
            )
        val PASSWORDS = listOf("fixture-alpha-local-only", "fixture-bravo-local-only")
    }
}
