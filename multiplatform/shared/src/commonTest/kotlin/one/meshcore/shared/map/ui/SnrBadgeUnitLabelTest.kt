package one.meshcore.shared.map.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_snr_badge_unit
import kotlin.test.Test
import kotlin.test.assertEquals

class SnrBadgeUnitLabelTest {
    @Test
    fun badgeUsesRemoteNodesSnrUnit() {
        assertEquals(Res.string.remotenodes_remote_nodes_status_snr_badge_unit, snrBadgeUnitResource())
    }
}
