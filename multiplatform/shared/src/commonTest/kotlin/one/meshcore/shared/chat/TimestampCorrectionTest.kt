package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mirrors the iOS `SyncCoordinatorTimestampTests`: valid range, future/past boundaries, and underflow. */
class TimestampCorrectionTest {
    private val now = 1_700_000_000.0
    private val nowSeconds = now.toLong()

    private val oneMinute = 60L
    private val fiveMinutes = 5 * 60L
    private val sixMinutes = 6 * 60L
    private val oneWeek = 7 * 24 * 60 * 60L
    private val threeMonths = 3 * 30 * 24 * 60 * 60L
    private val sixMonths = 6 * 30 * 24 * 60 * 60L
    private val sevenMonths = 7 * 30 * 24 * 60 * 60L

    @Test
    fun withinValidRangeIsNotCorrected() {
        val result = correctTimestampIfNeeded(nowSeconds, now)
        assertFalse(result.wasCorrected)
        assertEquals(nowSeconds, result.correctedTimestamp)
    }

    @Test
    fun oneMinuteInFutureIsNotCorrected() {
        val result = correctTimestampIfNeeded(nowSeconds + oneMinute, now)
        assertFalse(result.wasCorrected)
        assertEquals(nowSeconds + oneMinute, result.correctedTimestamp)
    }

    @Test
    fun exactlyFiveMinutesInFutureIsNotCorrected() {
        val result = correctTimestampIfNeeded(nowSeconds + fiveMinutes, now)
        assertFalse(result.wasCorrected)
    }

    @Test
    fun sixMinutesInFutureIsCorrected() {
        val result = correctTimestampIfNeeded(nowSeconds + sixMinutes, now)
        assertTrue(result.wasCorrected)
        assertEquals(nowSeconds, result.correctedTimestamp)
    }

    @Test
    fun oneWeekAgoIsNotCorrected() {
        val result = correctTimestampIfNeeded(nowSeconds - oneWeek, now)
        assertFalse(result.wasCorrected)
    }

    @Test
    fun threeMonthsAgoIsNotCorrected() {
        val result = correctTimestampIfNeeded(nowSeconds - threeMonths, now)
        assertFalse(result.wasCorrected)
    }

    @Test
    fun exactlySixMonthsInPastIsNotCorrected() {
        val result = correctTimestampIfNeeded(nowSeconds - sixMonths, now)
        assertFalse(result.wasCorrected)
    }

    @Test
    fun sevenMonthsAgoIsCorrected() {
        val result = correctTimestampIfNeeded(nowSeconds - sevenMonths, now)
        assertTrue(result.wasCorrected)
        assertEquals(nowSeconds, result.correctedTimestamp)
    }

    @Test
    fun zeroTimestampIsCorrected() {
        val result = correctTimestampIfNeeded(0, now)
        assertTrue(result.wasCorrected)
        assertEquals(nowSeconds, result.correctedTimestamp)
    }

    @Test
    fun correctedTimestampDiffersFromInvalidOriginal() {
        val farFuture = nowSeconds + 365 * 24 * 60 * 60L
        val result = correctTimestampIfNeeded(farFuture, now)
        assertTrue(result.wasCorrected)
        assertEquals(nowSeconds, result.correctedTimestamp)
        assertTrue(result.correctedTimestamp != farFuture)
    }

    @Test
    fun receiveTimeNearUnixEpochDoesNotUnderflow() {
        val result = correctTimestampIfNeeded(500, 1000.0)
        assertFalse(result.wasCorrected)
        assertEquals(500, result.correctedTimestamp)
    }

    @Test
    fun receiveTimeAtUnixEpochCorrectsFutureTimestamp() {
        val result = correctTimestampIfNeeded(1_000_000, 0.0)
        assertTrue(result.wasCorrected)
        assertEquals(0, result.correctedTimestamp)
    }
}
