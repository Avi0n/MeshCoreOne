package one.meshcore.shared.channel.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class JoinPublicGlobeTest {
    @Test
    fun globeSizeMatchesMaterialEmptyState() {
        assertEquals(48, JOIN_PUBLIC_GLOBE_DP)
    }
}
