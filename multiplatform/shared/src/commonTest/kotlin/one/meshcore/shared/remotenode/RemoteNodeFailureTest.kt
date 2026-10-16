package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RemoteNodeFailureTest {
    @Test
    fun timeoutStaysRetryable() {
        val thrown = assertFailsWith<RemoteNodeError.Timeout> {
            remoteNodeFailure(MeshError.Timeout)
        }

        assertSame(RemoteNodeError.Timeout, thrown)
    }

    @Test
    fun otherMeshErrorStaysASessionError() {
        val error = MeshError.DeviceError(code = 7)

        val thrown = assertFailsWith<RemoteNodeError.SessionError> {
            remoteNodeFailure(error)
        }

        assertSame(error, thrown.error)
    }
}
