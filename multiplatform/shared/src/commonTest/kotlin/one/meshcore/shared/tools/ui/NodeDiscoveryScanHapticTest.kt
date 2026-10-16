package one.meshcore.shared.tools.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NodeDiscoveryScanHapticTest {
    @Test
    fun scanStartTapsAndAFinishedScanFollowsTheHits() {
        assertEquals(
            NodeDiscoveryScanHaptic.TAP,
            nodeDiscoveryScanHaptic(wasScanning = false, isScanning = true, hasResults = false),
        )
        assertEquals(
            NodeDiscoveryScanHaptic.SUCCESS,
            nodeDiscoveryScanHaptic(wasScanning = true, isScanning = false, hasResults = true),
        )
        assertEquals(
            NodeDiscoveryScanHaptic.WARNING,
            nodeDiscoveryScanHaptic(wasScanning = true, isScanning = false, hasResults = false),
        )
        assertNull(nodeDiscoveryScanHaptic(wasScanning = false, isScanning = false, hasResults = false))
        assertNull(nodeDiscoveryScanHaptic(wasScanning = true, isScanning = true, hasResults = true))
    }
}
