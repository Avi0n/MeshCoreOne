package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_error_prefix
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeAuthErrorSpokenLabelTest {
    @Test
    fun errorSpeaksPrefixedFailure() {
        assertEquals(Res.string.remotenodes_remote_nodes_auth_error_prefix, authErrorSpokenResource())
        assertEquals(
            "Error: Authentication failed",
            authErrorSpokenLabel("Authentication failed", "Error: %s"),
        )
    }
}
