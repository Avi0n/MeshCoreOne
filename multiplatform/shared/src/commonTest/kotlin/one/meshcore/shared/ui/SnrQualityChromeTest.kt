package one.meshcore.shared.ui

import one.meshcore.shared.map.SnrQuality
import kotlin.test.Test
import kotlin.test.assertEquals

class SnrQualityChromeTest {
    @Test
    fun barTintsUseThemeRoles() {
        assertEquals(SignalChromeRole.PRIMARY, SnrQuality.EXCELLENT.signalChromeRole())
        assertEquals(SignalChromeRole.PRIMARY, SnrQuality.GOOD.signalChromeRole())
        assertEquals(SignalChromeRole.TERTIARY, SnrQuality.FAIR.signalChromeRole())
        assertEquals(SignalChromeRole.ERROR, SnrQuality.POOR.signalChromeRole())
        assertEquals(SignalChromeRole.MUTED, SnrQuality.UNKNOWN.signalChromeRole())
    }

    @Test
    fun favoriteStarUsesTertiary() {
        assertEquals(SignalChromeRole.TERTIARY, FAVORITE_STAR_ROLE)
    }
}
