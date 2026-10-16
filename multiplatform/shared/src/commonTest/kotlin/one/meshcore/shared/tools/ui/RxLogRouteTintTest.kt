package one.meshcore.shared.tools.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RxLogRouteTintTest {
    @Test
    fun floodAndDirectUseThemeRoles() {
        assertEquals(RxLogRouteRole.TERTIARY, rxLogRouteRole(isFlood = true))
        assertEquals(RxLogRouteRole.PRIMARY, rxLogRouteRole(isFlood = false))
    }
}
