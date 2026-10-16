package one.meshcore.shared.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomContactDeleteAtomicityInstrumentedTest {
    @get:Rule
    val testName = TestName()
    private lateinit var fixture: RoomContactDeleteAtomicityNativeFixture

    @Before
    fun setUp() {
        fixture = RoomContactDeleteAtomicityNativeFixture()
    }

    @After
    fun tearDown() {
        if (::fixture.isInitialized) fixture.close(testName.methodName)
    }

    @Test
    fun lateSingleContactFaultRestoresAllPersistedRows() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            fixture.seed()
            val before = fixture.snapshot()
            val fault = RoomContactDeleteAtomicityNativeFixture.FinalDeleteFailure()
            fixture.failFinalDelete(fixture.firstContact, fault)

            val actual = runCatching { fixture.store.deleteContact(fixture.firstContact) }.exceptionOrNull()
            val after = fixture.snapshot()
            fixture.record(testName.methodName, before, after, actual)

            assertOriginalFault(fault, actual)
            assertTrue(fixture.childRowsClearedAtFault)
            assertEquals(before, after)
        }
    }

    @Test
    fun secondContactFaultRestoresTheWholeBatch() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            fixture.seed()
            val before = fixture.snapshot()
            val fault = RoomContactDeleteAtomicityNativeFixture.FinalDeleteFailure()
            fixture.failFinalDelete(fixture.secondContact, fault)

            val actual = runCatching {
                fixture.store.deleteContacts(
                    fixture.radioID,
                    listOf(fixture.firstKey, fixture.secondKey),
                    skippingPublicKeys = { emptySet() },
                )
            }.exceptionOrNull()
            val after = fixture.snapshot()
            fixture.record(testName.methodName, before, after, actual)

            assertOriginalFault(fault, actual)
            assertTrue(fixture.childRowsClearedAtFault)
            assertTrue(fixture.earlierContactRemovedAtFault)
            assertEquals(before, after)
        }
    }

    @Test
    fun referencedContactStaysAndMissingContactOrphansArePurged() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            fixture.seed()
            val before = fixture.snapshot()
            fixture.store.deleteContactIfUnreferenced(fixture.firstContact)
            val unchanged = fixture.snapshot()
            fixture.record(testName.methodName + "-referenced", before, unchanged, null)
            assertEquals(before, unchanged)

            val survivors = fixture.survivors()
            fixture.database.contactDao().deleteById(fixture.firstContact)
            assertTrue(fixture.childRowCount(fixture.firstContact) > 0)
            fixture.store.deleteContact(fixture.firstContact)
            val after = fixture.snapshot()
            fixture.record(testName.methodName + "-orphans", before, after, null)

            assertNull(fixture.database.contactDao().getById(fixture.firstContact))
            assertEquals(0, fixture.childRowCount(fixture.firstContact))
            assertNotNull(fixture.database.contactDao().getById(fixture.secondContact))
            assertEquals(survivors, fixture.survivors())
        }
    }

    private fun assertOriginalFault(expected: Throwable, actual: Throwable?) {
        if (actual === expected) return
        assertNotNull(actual)
        val recovered = requireNotNull(actual)
        assertEquals(expected.javaClass, recovered.javaClass)
        assertSame(expected, recovered.cause)
        assertTrue(recovered.stackTrace.any { it.className.startsWith(COROUTINE_RECOVERY_FRAME_PREFIX) })
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val COROUTINE_RECOVERY_FRAME_PREFIX = "_COROUTINE."
    }
}
