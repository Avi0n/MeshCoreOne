package one.meshcore.shared.persistence

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.PendingSendDto
import one.meshcore.shared.chat.PendingSendKind
import one.meshcore.shared.model.Uuid
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the Swift `PendingSendPersistenceTests` against the Room-backed [RoomPendingSendStore]: the
 * DTO round-trip, radio scoping, sequence-ordered fetch, atomic per-radio sequence assignment (including
 * concurrent inserts), the three-state `attemptCount` round-trip, delete-by-message, the unknown-kind
 * skip, and the legacy-null purge. Runs on the desktop JVM against the bundled SQLite engine.
 */
class PendingSendPersistenceTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: RoomPendingSendStore

    private val radioA = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val radioB = Uuid.normalized("B0000000-0000-0000-0000-000000000002")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("pending-send", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        store = RoomPendingSendStore(db.pendingSendDao())
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    private fun uid(seed: Int): Uuid = Uuid.normalized("00000000-0000-0000-0000-" + seed.toString().padStart(12, '0'))

    private fun dmFixture(
        radioID: Uuid,
        sequence: Int,
        messageID: Uuid = uid(1000 + sequence),
        attemptCount: Int? = 0,
        id: Uuid = uid(2000 + sequence),
    ): PendingSendDto = PendingSendDto(
        id = id,
        radioID = radioID,
        messageID = messageID,
        kind = PendingSendKind.DM,
        sequence = sequence,
        enqueuedAtEpochSeconds = 1_700_000_000.0,
        contactID = uid(3000 + sequence),
        isResend = false,
        messageText = "",
        messageTimestamp = 0,
        attemptCount = attemptCount,
    )

    @Test
    fun `channel DTO round-trips through the entity`() = runBlocking {
        val dto = PendingSendDto(
            id = uid(1),
            radioID = radioA,
            messageID = uid(2),
            kind = PendingSendKind.CHANNEL,
            sequence = 1,
            enqueuedAtEpochSeconds = 1_700_000_000.0,
            channelIndex = 3,
            isResend = true,
            messageText = "test",
            messageTimestamp = 1_700_000_000,
            localNodeName = "Alice",
        )
        store.upsertPendingSend(dto)

        val fetched = store.fetchPendingSends(radioA)
        assertEquals(1, fetched.size)
        assertEquals(dto, fetched.first())
    }

    @Test
    fun `pending sends are scoped by radioID`() = runBlocking {
        store.upsertPendingSend(dmFixture(radioA, sequence = 1))
        store.upsertPendingSend(dmFixture(radioB, sequence = 1, id = uid(9001)))

        assertEquals(radioA, store.fetchPendingSends(radioA).single().radioID)
        assertEquals(radioB, store.fetchPendingSends(radioB).single().radioID)
    }

    @Test
    fun `fetchPendingSends returns rows in sequence order`() = runBlocking {
        store.upsertPendingSend(dmFixture(radioA, sequence = 3, id = uid(11)))
        store.upsertPendingSend(dmFixture(radioA, sequence = 1, id = uid(12)))
        store.upsertPendingSend(dmFixture(radioA, sequence = 2, id = uid(13)))

        assertEquals(listOf(1, 2, 3), store.fetchPendingSends(radioA).map { it.sequence })
    }

    @Test
    fun `deletePendingSend removes the row`() = runBlocking {
        val dto = dmFixture(radioA, sequence = 1)
        store.upsertPendingSend(dto)
        store.deletePendingSend(dto.id)

        assertTrue(store.fetchPendingSends(radioA).isEmpty())
    }

    @Test
    fun `insertPendingSendAssigningSequence returns 1 on an empty table`() = runBlocking {
        val assigned = store.insertPendingSendAssigningSequence(dmFixture(radioA, sequence = 0))

        assertEquals(1, assigned)
        assertEquals(1, store.fetchPendingSends(radioA).single().sequence)
    }

    @Test
    fun `insertPendingSendAssigningSequence assigns per-radio monotonic sequences`() = runBlocking {
        val firstA = store.insertPendingSendAssigningSequence(dmFixture(radioA, 0, id = uid(21)))
        val secondA = store.insertPendingSendAssigningSequence(dmFixture(radioA, 0, id = uid(22)))
        val firstB = store.insertPendingSendAssigningSequence(dmFixture(radioB, 0, id = uid(23)))

        assertEquals(1, firstA)
        assertEquals(2, secondA)
        assertEquals(1, firstB, "sequence numbering is per-radio")
    }

    @Test
    fun `concurrent inserts on the same radio produce distinct sequences`() = runBlocking {
        val a = async { store.insertPendingSendAssigningSequence(dmFixture(radioA, 0, id = uid(31))) }
        val b = async { store.insertPendingSendAssigningSequence(dmFixture(radioA, 0, id = uid(32))) }
        val assigned = listOf(a.await(), b.await()).sorted()

        assertEquals(listOf(1, 2), assigned, "the sequence mutex must produce distinct values")
        assertEquals(setOf(1, 2), store.fetchPendingSends(radioA).map { it.sequence }.toSet())
    }

    @Test
    fun `attemptCount round-trips through insert and fetch for all three states`() = runBlocking {
        val legacy = uid(41)
        val raceWindow = uid(42)
        val drained = uid(43)
        store.insertPendingSendAssigningSequence(dmFixture(radioA, 0, legacy, attemptCount = null, id = uid(51)))
        store.insertPendingSendAssigningSequence(dmFixture(radioA, 0, raceWindow, attemptCount = 0, id = uid(52)))
        store.insertPendingSendAssigningSequence(dmFixture(radioA, 0, drained, attemptCount = 4, id = uid(53)))

        val rows = store.fetchPendingSends(radioA).associateBy { it.messageID }
        assertNull(rows.getValue(legacy).attemptCount, "pre-migration null stays null")
        assertEquals(0, rows.getValue(raceWindow).attemptCount)
        assertEquals(4, rows.getValue(drained).attemptCount, "positive value round-trips without normalization")
    }

    @Test
    fun `deletePendingSendsForMessage removes every row matching the messageID`() = runBlocking {
        val shared = uid(61)
        val other = uid(62)
        store.upsertPendingSend(dmFixture(radioA, 1, messageID = shared, id = uid(71)))
        store.upsertPendingSend(dmFixture(radioB, 1, messageID = shared, id = uid(72)))
        store.upsertPendingSend(dmFixture(radioA, 2, messageID = other, id = uid(73)))

        store.deletePendingSendsForMessage(shared)

        assertEquals(other, store.fetchPendingSends(radioA).single().messageID)
        assertTrue(store.fetchPendingSends(radioB).isEmpty())
    }

    @Test
    fun `deletePendingSendsForMessage is a no-op when no rows match`() = runBlocking {
        store.upsertPendingSend(dmFixture(radioA, 1, messageID = uid(81), id = uid(82)))
        store.deletePendingSendsForMessage(uid(999))

        assertEquals(1, store.fetchPendingSends(radioA).size)
    }

    @Test
    fun `fetchPendingSends skips rows whose kindRawValue is unknown`() = runBlocking {
        store.upsertPendingSend(dmFixture(radioA, sequence = 1, id = uid(91)))
        // A future-case discriminator inserted directly must be filtered out of the DTO fetch.
        db.pendingSendDao().upsert(
            PendingSendEntity(
                id = uid(92),
                radioID = radioA,
                messageID = uid(93),
                kindRawValue = 99,
                isResend = false,
                messageText = "",
                messageTimestamp = 0,
                sequence = 2,
                enqueuedAt = 1_700_000_000.0,
            ),
        )

        val rows = store.fetchPendingSends(radioA)
        assertEquals(1, rows.size, "unknown-kind row is filtered")
        assertEquals(1, rows.first().sequence)
    }

    @Test
    fun `incrementPendingSendAttemptCount bumps and returns the new count, null when absent`() = runBlocking {
        val messageID = uid(101)
        store.upsertPendingSend(dmFixture(radioA, 1, messageID = messageID, attemptCount = 0, id = uid(102)))

        assertEquals(1, store.incrementPendingSendAttemptCount(messageID))
        assertEquals(2, store.incrementPendingSendAttemptCount(messageID))
        assertNull(store.incrementPendingSendAttemptCount(uid(999)))
    }

    @Test
    fun `purgeLegacyAttemptCountRows deletes only the null-attemptCount rows`() = runBlocking {
        store.upsertPendingSend(dmFixture(radioA, 1, messageID = uid(111), attemptCount = null, id = uid(112)))
        store.upsertPendingSend(dmFixture(radioA, 2, messageID = uid(113), attemptCount = 0, id = uid(114)))

        assertEquals(1, store.purgeLegacyAttemptCountRows())
        assertEquals(1, store.fetchPendingSends(radioA).size, "the current-build row survives")
    }

    @Test
    fun `purgeOrphanPendingSends deletes rows whose radio has no Device`() = runBlocking {
        db.deviceDao().upsert(deviceEntity(radioA))
        store.upsertPendingSend(dmFixture(radioA, 1, messageID = uid(121), id = uid(122)))
        store.upsertPendingSend(dmFixture(radioB, 1, messageID = uid(123), id = uid(124)))

        assertEquals(1, store.purgeOrphanPendingSends())
        assertEquals(1, store.fetchPendingSends(radioA).size, "paired radio keeps its queue")
        assertTrue(store.fetchPendingSends(radioB).isEmpty(), "forgotten radio queue is dropped")
    }

    private fun deviceEntity(radioID: Uuid) = DeviceEntity(
        id = radioID,
        radioID = radioID,
        publicKey = ByteArray(PUBLIC_KEY_BYTES),
        nodeName = "seed",
        firmwareVersion = 0,
        firmwareVersionString = "",
        manufacturerName = "",
        buildDate = "",
        maxContacts = 0,
        maxChannels = 0,
        frequency = 0,
        bandwidth = 0,
        spreadingFactor = 0,
        codingRate = 0,
        txPower = 0,
        maxTxPower = 0,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0,
        clientRepeat = false,
        pathHashMode = 0,
        manualAddContacts = false,
        autoAddConfig = 0,
        autoAddMaxHops = 0,
        multiAcks = 0,
        telemetryModeBase = 0,
        telemetryModeLoc = 0,
        telemetryModeEnv = 0,
        advertLocationPolicy = 0,
        lastConnected = 0.0,
        lastContactSync = 0,
        isActive = true,
    )

    private companion object {
        const val PUBLIC_KEY_BYTES = 32
    }
}
