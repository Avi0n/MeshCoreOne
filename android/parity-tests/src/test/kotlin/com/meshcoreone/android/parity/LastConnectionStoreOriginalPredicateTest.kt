// PortedFrom: MC1Services/Tests/MC1ServicesTests/Connection/LastConnectionStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.DataStoreConnectionPreferences
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.datastore.PreferenceKey
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.runtime.LastConnectionStore
import com.meshcoreone.android.core.runtime.RuntimeClock
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.delay
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
class LastConnectionStoreOriginalPredicateTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var storage: MeshCoreStorage
    private lateinit var store: LastConnectionStore
    private val clock = object : RuntimeClock {
        override val elapsed = Duration.ZERO
        override val instant = Instant.ofEpochMilli(1_704_067_200_125)
        override suspend fun sleep(duration: Duration) = delay(duration)
    }

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

    @Test fun originalTestRadioRoundTripUsesActualStoredKeysAndReopen() = runBlocking {
        val device = UUID.randomUUID()
        val radio = RadioId(UUID.randomUUID())
        store.persist(device, radio, "Test Radio")
        assertEquals(device, store.read().deviceId)
        assertEquals(radio, store.read().radioId)
        assertEquals("Test Radio", text(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME))
        assertEquals(device.canonicalString(), text(PersistenceKeys.LAST_CONNECTED_DEVICE_ID))
        assertEquals(radio.canonicalString, text(PersistenceKeys.LAST_CONNECTED_RADIO_ID))

        storage.close()
        open()

        assertEquals(device, store.read().deviceId)
        assertEquals(radio, store.read().radioId)
        assertEquals("Test Radio", text(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME))
    }

    @Test fun originalHolderClearRemovesAllThreeActualPersistedValues() = runBlocking {
        val device = UUID.randomUUID()
        val radio = RadioId(UUID.randomUUID())
        store.persist(device, radio, "Test Radio")
        assertEquals(device, store.read().deviceId)
        assertEquals(radio, store.read().radioId)
        assertEquals("Test Radio", text(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME))

        store.clear(device)

        assertNull(store.read().deviceId)
        assertNull(store.read().radioId)
        assertNull(text(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME))
        assertNull(text(PersistenceKeys.LAST_CONNECTED_DEVICE_ID))
        assertNull(text(PersistenceKeys.LAST_CONNECTED_RADIO_ID))
    }

    @Test fun originalNonHolderClearPreservesHolderRadioAndName() = runBlocking {
        val holder = UUID.randomUUID()
        val radio = RadioId(UUID.randomUUID())
        store.persist(holder, radio, "Holder Radio")
        val before = storage.preferences.snapshot()
        assertEquals(holder, store.read().deviceId)
        assertEquals("Holder Radio", text(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME))

        store.clear(UUID.randomUUID())

        assertEquals(holder, store.read().deviceId)
        assertEquals("Holder Radio", text(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME))
        assertEquals(radio, store.read().radioId)
        assertEquals(before, storage.preferences.snapshot())
    }

    @Test fun originalEmptyDefaultsExposeThreeIndependentNilReads() = runBlocking {
        assertNull(store.read().deviceId)
        assertNull(store.read().radioId)
        assertNull(store.read().diagnostic)
        assertTrue(storage.preferences.snapshot().storedValues.isEmpty())
    }

    @Test fun originalMalformedUuidStringsStayStoredButReadAsNil() = runBlocking {
        storage.preferences.set(PreferenceKey.StringKey(PersistenceKeys.LAST_CONNECTED_DEVICE_ID, null), "not-a-uuid")
        storage.preferences.set(PreferenceKey.StringKey(PersistenceKeys.LAST_CONNECTED_RADIO_ID, null), "also-not-a-uuid")

        assertNull(store.read().deviceId)
        assertNull(store.read().radioId)
        assertEquals("not-a-uuid", text(PersistenceKeys.LAST_CONNECTED_DEVICE_ID))
        assertEquals("also-not-a-uuid", text(PersistenceKeys.LAST_CONNECTED_RADIO_ID))
    }

    @Test fun originalBondStampHasBothSourceTimeBoundsAndNoOtherHolder() = runBlocking {
        val device = UUID.randomUUID()
        assertNull(store.bondVerificationDate(device))
        val before = clock.instant

        store.persistBondVerification(device)

        val stamped = requireNotNull(store.bondVerificationDate(device))
        assertTrue(stamped >= before && stamped <= clock.instant)
        assertEquals(clock.instant, stamped)
        assertNull(store.bondVerificationDate(UUID.randomUUID()))
        assertEquals(device.canonicalString(), text(PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID))
        assertEquals(clock.instant.toEpochMilli(), storage.preferences.snapshot().stored(
            PreferenceKey.IntegerKey(PersistenceKeys.LAST_BOND_VERIFIED_DATE, 0L)))
    }

    @Test fun originalSecondBondVerificationReplacesTheActualSingleSlot() = runBlocking {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        store.persistBondVerification(first)
        assertEquals(clock.instant, store.bondVerificationDate(first))

        store.persistBondVerification(second)

        assertNull(store.bondVerificationDate(first))
        assertNotNull(store.bondVerificationDate(second))
        assertEquals(second.canonicalString(), text(PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID))
    }

    @Test fun originalBondHolderAndNonHolderClearRemainIndependent() = runBlocking {
        val holder = UUID.randomUUID()
        val other = UUID.randomUUID()
        store.persistBondVerification(holder)
        assertNotNull(store.bondVerificationDate(holder))

        store.clear(other)
        assertNotNull(store.bondVerificationDate(holder))
        store.clear(holder)

        assertNull(store.bondVerificationDate(holder))
        assertNull(text(PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID))
        assertNull(storage.preferences.snapshot().storedValues[PersistenceKeys.LAST_BOND_VERIFIED_DATE])
    }

    @Test fun originalDisconnectDiagnosticHasParseablePrefixAndExactSuffix() = runBlocking {
        val summary = "source=unitTest, reason=verifyFormat"

        store.persistDisconnectDiagnostic(summary)

        val stored = requireNotNull(store.read().diagnostic)
        val separator = stored.indexOf(' ')
        assertTrue(separator >= 0)
        assertEquals(clock.instant, Instant.parse(stored.substring(0, separator)))
        assertEquals(summary, stored.substring(separator + 1))
        assertEquals(stored, text(PersistenceKeys.LAST_DISCONNECT_DIAGNOSTIC))
    }

    private fun open() {
        storage = MeshCoreStorage.get(context)
        store = LastConnectionStore(DataStoreConnectionPreferences(storage.preferences), clock)
    }

    private suspend fun text(name: String) =
        storage.preferences.snapshot().stored(PreferenceKey.StringKey(name, null))
}
