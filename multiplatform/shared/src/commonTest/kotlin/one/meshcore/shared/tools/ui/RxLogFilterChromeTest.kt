package one.meshcore.shared.tools.ui

import one.meshcore.shared.tools.RxLogDecryptFilter
import one.meshcore.shared.tools.RxLogRouteFilter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RxLogFilterChromeTest {
    @Test
    fun bothAllIsTheDefaultFilter() {
        assertTrue(rxLogFilterIsDefault(RxLogRouteFilter.ALL, RxLogDecryptFilter.ALL))
        assertFalse(rxLogFilterIsDefault(RxLogRouteFilter.FLOOD_ONLY, RxLogDecryptFilter.ALL))
        assertFalse(rxLogFilterIsDefault(RxLogRouteFilter.ALL, RxLogDecryptFilter.DECRYPTED))
    }
}
