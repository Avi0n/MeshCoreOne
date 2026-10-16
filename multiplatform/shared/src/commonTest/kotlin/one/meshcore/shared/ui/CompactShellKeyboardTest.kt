package one.meshcore.shared.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompactShellKeyboardTest {
    @Test
    fun keyboardHidesTheBottomTabBarSoItsHeightIsNotReservedAboveTheIme() {
        assertTrue(shouldShowCompactTabBar(tabBarVisible = true, imeVisible = false))
        assertFalse(shouldShowCompactTabBar(tabBarVisible = true, imeVisible = true))
        assertFalse(shouldShowCompactTabBar(tabBarVisible = false, imeVisible = false))
    }
}
