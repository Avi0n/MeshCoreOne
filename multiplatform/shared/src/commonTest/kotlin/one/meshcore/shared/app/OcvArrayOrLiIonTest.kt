package one.meshcore.shared.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.battery.OCVPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class OcvArrayOrLiIonTest {
    @Test
    fun aPersistFaultFallsBackToLiIon() = runTest {
        val array = ocvArrayOrLiIon { error("db locked") }
        assertEquals(OCVPreset.LI_ION.ocvArray, array)
    }

    @Test
    fun aMissingRowFallsBackToLiIon() = runTest {
        val array = ocvArrayOrLiIon { null }
        assertEquals(OCVPreset.LI_ION.ocvArray, array)
    }

    @Test
    fun aReadCurveIsReturned() = runTest {
        val expected = listOf(1, 2, 3)
        assertEquals(expected, ocvArrayOrLiIon { expected })
    }

    @Test
    fun cancellationIsRethrown() = runTest {
        val cancelled = CancellationException("teardown")
        val thrown = assertFailsWith<CancellationException> {
            ocvArrayOrLiIon { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun anErrorPropagates() = runTest {
        val fault = OcvReadProbeError()
        val thrown = assertFailsWith<OcvReadProbeError> {
            ocvArrayOrLiIon { throw fault }
        }
        assertSame(fault, thrown)
    }
}

private class OcvReadProbeError : Error("probe")
