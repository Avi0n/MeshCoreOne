package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MapSendErrorTest {
    @Test
    fun remoteNodeErrorPassesThrough() {
        val error = RemoteNodeError.NotConnected

        assertSame(error, mapSendError(error))
    }

    @Test
    fun meshErrorBecomesASessionError() {
        val error = MeshError.Timeout

        val mapped = mapSendError(error) as RemoteNodeError.SessionError

        assertSame(error, mapped.error)
    }

    @Test
    fun otherThrowableBecomesALostLink() {
        val mapped = mapSendError(IllegalStateException("dropped")) as RemoteNodeError.SessionError
        val lost = mapped.error as MeshError.ConnectionLost

        assertEquals("dropped", lost.detail)
    }
}
