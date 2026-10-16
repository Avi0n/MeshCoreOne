package one.meshcore.shared.persistence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.contact.ContactServiceError
import one.meshcore.shared.persistence.RoomContactPreferencePeerCase.AVATAR
import one.meshcore.shared.persistence.RoomContactPreferencePeerCase.BLOCK
import one.meshcore.shared.persistence.RoomContactPreferencePeerCase.FAVORITE
import one.meshcore.shared.persistence.RoomContactPreferencePeerCase.LOCAL_FAVORITE
import one.meshcore.shared.persistence.RoomContactPreferencePeerCase.NICKNAME
import one.meshcore.shared.persistence.RoomContactPreferencePeerCase.OCV
import one.meshcore.shared.persistence.RoomContactPreferencePeerCase.TELEMETRY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomContactPreferencePeerOwnershipTest {
    @Test fun activeBlockWriterPreservesConcurrentEdit() = active(BLOCK)

    @Test fun activeLocalFavoriteWriterPreservesConcurrentEdit() = active(LOCAL_FAVORITE)

    @Test fun activeAvatarWriterPreservesConcurrentEdit() = active(AVATAR)

    @Test fun activeOcvWriterPreservesConcurrentEdit() = active(OCV)

    @Test fun activeFavoriteWriterPreservesConcurrentEdit() = active(FAVORITE)

    @Test fun activeTelemetryWriterPreservesConcurrentEdit() = active(TELEMETRY)

    @Test fun ordinaryBlockWriterPreservesOtherFields() = ordinary(BLOCK)

    @Test fun ordinaryLocalFavoriteWriterPreservesOtherFields() = ordinary(LOCAL_FAVORITE)

    @Test fun ordinaryAvatarWriterPreservesOtherFields() = ordinary(AVATAR)

    @Test fun ordinaryOcvWriterPreservesOtherFields() = ordinary(OCV)

    @Test fun ordinaryFavoriteWriterPreservesOtherFields() = ordinary(FAVORITE)

    @Test fun ordinaryTelemetryWriterPreservesOtherFields() = ordinary(TELEMETRY)

    @Test fun missingBlockTargetDoesNotInsertOrNotify() = missing(BLOCK)

    @Test fun missingLocalFavoriteTargetDoesNotInsertOrNotify() = missing(LOCAL_FAVORITE)

    @Test fun missingAvatarTargetDoesNotInsertOrNotify() = missing(AVATAR)

    @Test fun missingOcvTargetDoesNotInsertOrNotify() = missing(OCV)

    @Test fun missingFavoriteTargetDoesNotInsertOrNotify() = missing(FAVORITE)

    @Test fun missingTelemetryTargetDoesNotInsertOrNotify() = missing(TELEMETRY)

    @Test fun canceledNicknameWriterPreservesFreshRows() = canceled(NICKNAME)

    @Test fun canceledBlockWriterPreservesFreshRows() = canceled(BLOCK)

    @Test fun canceledLocalFavoriteWriterPreservesFreshRows() = canceled(LOCAL_FAVORITE)

    @Test fun canceledAvatarWriterPreservesFreshRows() = canceled(AVATAR)

    @Test fun canceledOcvWriterPreservesFreshRows() = canceled(OCV)

    @Test fun canceledFavoriteWriterPreservesFreshRows() = canceled(FAVORITE)

    @Test fun canceledTelemetryWriterPreservesFreshRows() = canceled(TELEMETRY)

    @Test fun deletedFavoriteTargetAfterCommandDoesNotReappear() = deleted(FAVORITE)

    @Test fun deletedTelemetryTargetAfterCommandDoesNotReappear() = deleted(TELEMETRY)

    private fun active(kind: RoomContactPreferencePeerCase) = exercise(kind) {
        seed()
        val unaffected = rows.survivors()
        start()
        editCurrent()
        val expected = expectedSnapshot()
        assertNull(releaseAndJoin())
        assertEffects(1 + kind.notifications, if (kind == BLOCK) 1 else 0)
        assertEquals(unaffected, rows.survivors())
        assertEquals(expected, rows.snapshot(), "LAST: active preference writer replayed unrelated fields")
    }

    private fun ordinary(kind: RoomContactPreferencePeerCase) = exercise(kind) {
        seed()
        val unaffected = rows.survivors()
        val expected = expectedSnapshot()
        start()
        assertNull(releaseAndJoin())
        assertEffects(kind.notifications, if (kind == BLOCK) 1 else 0)
        assertEquals(unaffected, rows.survivors())
        assertEquals(expected, rows.snapshot())
    }

    private fun missing(kind: RoomContactPreferencePeerCase) = exercise(kind) {
        seed()
        val before = rows.snapshot()
        missingTarget()
        assertEquals(before, rows.snapshot())
    }

    private fun canceled(kind: RoomContactPreferencePeerCase) = exercise(kind) {
        seed()
        val unaffected = rows.survivors()
        start()
        editCurrent()
        val expected = if (kind.isCommand) expectedSnapshot() else rows.snapshot()
        cancelOrigin()
        assertTrue(releaseAndJoin() is CancellationException)
        assertEffects(1, 0)
        assertEquals(unaffected, rows.survivors())
        assertEquals(expected, rows.snapshot(), "LAST: canceled preference writer committed stale rows")
    }

    private fun deleted(kind: RoomContactPreferencePeerCase) = exercise(kind) {
        seed()
        val unaffected = rows.survivors()
        start()
        deleteAfterCommand()
        val expected = rows.snapshot()
        val result = releaseAndJoin()
        assertEffects(0, 0)
        assertEquals(unaffected, rows.survivors())
        assertEquals(
            expected to true,
            rows.snapshot() to (result is ContactServiceError.ContactNotFound),
            "LAST: completed command recreated deleted target or omitted missing result",
        )
    }

    private fun exercise(
        kind: RoomContactPreferencePeerCase,
        block: suspend RoomContactPreferencePeerFixture.() -> Unit,
    ) {
        val fixture = RoomContactPreferencePeerFixture(kind)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomContactPreferencePeerFixture.TIMEOUT_MS) { fixture.block() }
            }
        } finally {
            fixture.close()
        }
    }
}
