package one.meshcore.shared.transport

import com.juul.kable.AndroidPeripheral
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KableBleWriteBusyTest {
    @Test
    fun writeRequestBusyIsBusy() {
        assertTrue(isWriteBusyResult(AndroidPeripheral.WriteResult.WriteRequestBusy))
    }

    @Test
    fun unknownIsBusyOnPreApi33() {
        assertTrue(isWriteBusyResult(AndroidPeripheral.WriteResult.Unknown))
    }

    @Test
    fun writeNotAllowedIsNotBusy() {
        assertFalse(isWriteBusyResult(AndroidPeripheral.WriteResult.WriteNotAllowed))
    }
}
