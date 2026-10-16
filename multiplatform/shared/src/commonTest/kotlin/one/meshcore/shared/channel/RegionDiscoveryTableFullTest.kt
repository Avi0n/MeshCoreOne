package one.meshcore.shared.channel

import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Region discovery stops probing when the radio's contact table is full, so mistaking another code for
 * `TABLE_FULL` ends the sweep early and mistaking `TABLE_FULL` for something else keeps probing a radio
 * that cannot accept another entry.
 */
class RegionDiscoveryTableFullTest {
    @Test
    fun aFullTableIsRecognised() {
        assertTrue(isTableFull("device error code=3 (tableFull)"))
    }

    /**
     * A substring test for "code=3" also matches "code=30". Any firmware code sharing that prefix would
     * have read as a full table and stopped the sweep.
     */
    @Test
    fun aCodeSharingTheDigitPrefixIsNotAFullTable() {
        assertFalse(isTableFull("device error code=30"))
        assertFalse(isTableFull("device error code=31"))
    }

    @Test
    fun anotherContactCodeIsNotAFullTable() {
        assertFalse(isTableFull("device error code=2 (notFound)"))
    }

    /** A failure the bridge described in words carries no code, and probing has to continue. */
    @Test
    fun aFailureWithNoCodeIsNotAFullTable() {
        assertFalse(isTableFull("not connected"))
    }

    private fun isTableFull(detail: String): Boolean =
        RegionDiscoveryService.isTableFullDeviceError(MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, detail))
}
