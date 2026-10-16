package one.meshcore.shared.room.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomInfoHostStateTest {
    @Test
    fun openingTelemetryLeavesInfoUp() {
        val next = RoomInfoHostState().openInfo().openStatus()
        assertTrue(next.showInfo)
        assertTrue(next.showStatus)
        assertFalse(next.showSettings)
    }

    @Test
    fun dismissingTelemetryReturnsToInfo() {
        val next = RoomInfoHostState().openInfo().openStatus().dismissStatus()
        assertTrue(next.showInfo)
        assertFalse(next.showStatus)
    }

    @Test
    fun openingSettingsLeavesInfoUp() {
        val next = RoomInfoHostState().openInfo().openSettings()
        assertTrue(next.showInfo)
        assertTrue(next.showSettings)
        assertFalse(next.showStatus)
    }

    @Test
    fun dismissingSettingsReturnsToInfo() {
        val next = RoomInfoHostState().openInfo().openSettings().dismissSettings()
        assertTrue(next.showInfo)
        assertFalse(next.showSettings)
    }

    @Test
    fun dismissingInfoClosesStackedChildren() {
        val next = RoomInfoHostState().openInfo().openStatus().openSettings().dismissInfo()
        assertFalse(next.showInfo)
        assertFalse(next.showStatus)
        assertFalse(next.showSettings)
    }
}
