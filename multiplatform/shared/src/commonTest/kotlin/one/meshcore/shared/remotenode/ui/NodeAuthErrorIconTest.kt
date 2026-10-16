package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeAuthErrorIconTest {
    @Test
    fun usesFilledErrorCircle() {
        assertEquals(AppIcons.Error, NODE_AUTH_ERROR_ICON)
    }
}
