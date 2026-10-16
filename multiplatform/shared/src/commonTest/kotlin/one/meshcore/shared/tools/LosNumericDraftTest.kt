package one.meshcore.shared.tools

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Line-of-Sight numeric fields commit on every keystroke and the committed value flows straight
 * back to the field. Each test below runs that full round trip — type, commit, sync — because the
 * defect only appears on the way back: a field that re-seeds from its own commit leaves "1.0" in the
 * box for a typed "10" and analyzes a 1 m mast.
 */
class LosNumericDraftTest {
    /** One keystroke: type it, then feed the committed value back the way the screen's effect does. */
    private fun LosNumericDraft.keystroke(text: String, isAcceptable: (Double) -> Boolean = { true }) =
        typed(text, isAcceptable).let { it.synced(it.committed) }

    @Test
    fun typingAMultiDigitHeightCommitsTheNumberTyped() {
        var draft = LosNumericDraft.forValue(7.0)
        for (text in listOf("1", "10")) draft = draft.keystroke(text)
        assertEquals("10", draft.text)
        assertEquals(10.0, draft.committed)
    }

    @Test
    fun typingAFrequencyCommitsTheBandTyped() {
        var draft = LosNumericDraft.forValue(868.0)
        for (text in listOf("9", "91", "915")) draft = draft.keystroke(text) { it > 0 }
        assertEquals("915", draft.text)
        assertEquals(915.0, draft.committed)
    }

    @Test
    fun aCommittedLargeFrequencyCanBeReopenedForEditing() = runTest {
        val viewModel = LineOfSightViewModel(this)
        val typed = "99999999999999999999"
        val draft = LosNumericDraft.forValue(viewModel.frequencyMHz).typed(typed) { it > 0 }

        viewModel.setFrequencyMHz(draft.committed)

        assertEquals(typed, draft.synced(viewModel.frequencyMHz).text)
        assertEquals(1e20, viewModel.frequencyMHz)
        val reopened = LosNumericDraft.forValue(viewModel.frequencyMHz)
        assertEquals(viewModel.frequencyMHz, parseLosNumber(reopened.text))
    }

    @Test
    fun frequenciesAtAndBeyondTheIntegerBoundaryRoundTripThroughTheField() = runTest {
        val boundary = Long.MAX_VALUE.toDouble()
        val values = listOf(
            Double.fromBits(boundary.toBits() - 1),
            boundary,
            Double.fromBits(boundary.toBits() + 1),
            Double.MAX_VALUE,
        )
        val viewModel = LineOfSightViewModel(this)
        for (value in values) {
            viewModel.setFrequencyMHz(value)
            val reopened = LosNumericDraft.forValue(viewModel.frequencyMHz)
            val parsed = assertNotNull(parseLosNumber(reopened.text))
            assertEquals(value, parsed)
            assertEquals(value, reopened.typed(reopened.text) { it > 0 }.committed)
        }
    }

    @Test
    fun aPartlyTypedDecimalSurvivesAndKeepsTheLastCommit() {
        var draft = LosNumericDraft.forValue(7.0)
        for (text in listOf("1", "12", "12.")) draft = draft.keystroke(text)
        assertEquals("12.", draft.text)
        assertEquals(12.0, draft.committed)
        draft = draft.keystroke("12.5")
        assertEquals(12.5, draft.committed)
    }

    @Test
    fun anExternalChangeStillReseedsTheField() {
        val draft = LosNumericDraft.forValue(7.0).keystroke("25")
        assertEquals(25.0, draft.committed)
        // Relocating the point resets its height; that did not come from the field, so it re-seeds.
        val reseeded = draft.synced(7.0)
        assertEquals("7.0", reseeded.text)
        assertEquals(7.0, reseeded.committed)
    }

    @Test
    fun commaDecimalsParseAndNonFiniteInputIsRejected() {
        assertEquals(1.33, parseLosNumber("1,33"))
        assertEquals(915.0, parseLosNumber("915"))
        assertNull(parseLosNumber("Infinity"))
        assertNull(parseLosNumber("NaN"))
        assertNull(parseLosNumber(""))
    }

    @Test
    fun anUnacceptableValueIsNotCommitted() {
        val draft = LosNumericDraft.forValue(868.0).keystroke("0") { it > 0 }
        assertEquals("0", draft.text)
        assertEquals(868.0, draft.committed)
    }
}
