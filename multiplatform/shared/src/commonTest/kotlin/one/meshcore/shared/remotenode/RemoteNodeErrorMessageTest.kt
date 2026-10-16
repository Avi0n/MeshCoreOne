package one.meshcore.shared.remotenode

import one.meshcore.shared.remotenode.ui.messageResource
import one.meshcore.shared.remotenode.ui.nodeAuthSurfaceText
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_error_device_storage_full
import one.meshcore.shared.resources.localizable_error_mesh_core_timeout
import one.meshcore.shared.room.RoomServerError
import one.meshcore.shared.room.ui.messageResource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Both error families carry developer English in their constructors, so an unmapped case reaches the
 * screen untranslated. The mappers are exhaustive `when`s, which makes a new case a compile error rather
 * than a silent fallback; these tests cover what the compiler cannot — that no two cases share a string,
 * and that a wrapped lower-layer failure resolves through its cause.
 */
class RemoteNodeErrorMessageTest {
    @Test
    fun everyRemoteNodeCaseResolvesToItsOwnString() {
        val cases = listOf(
            RemoteNodeError.NotConnected,
            RemoteNodeError.LoginFailed("x"),
            RemoteNodeError.SendFailed("x"),
            RemoteNodeError.InvalidResponse,
            RemoteNodeError.PermissionDenied,
            RemoteNodeError.Timeout,
            RemoteNodeError.SessionNotFound,
            RemoteNodeError.PasswordNotFound,
            RemoteNodeError.FloodRouted,
            RemoteNodeError.PathDiscoveryFailed,
            RemoteNodeError.ContactNotFound,
            RemoteNodeError.RadioContactsFull,
            RemoteNodeError.Cancelled,
        )

        assertEquals(
            cases.size,
            cases.map { it.messageResource() }.toSet().size,
            "two cases share a string, so one of them shows the wrong message",
        )
    }

    @Test
    fun everyRoomServerCaseResolvesToItsOwnString() {
        val cases = listOf(
            RoomServerError.NotConnected,
            RoomServerError.SessionNotFound,
            RoomServerError.SendFailed("x"),
            RoomServerError.PermissionDenied,
            RoomServerError.InvalidResponse,
        )

        assertEquals(
            cases.size,
            cases.map { it.messageResource() }.toSet().size,
            "two cases share a string, so one of them shows the wrong message",
        )
    }

    @Test
    fun aWrappedTimeoutResolvesThroughItsCauseInBothFamilies() {
        assertEquals(
            Res.string.localizable_error_mesh_core_timeout,
            RemoteNodeError.SessionError(MeshError.Timeout).messageResource(),
        )
        assertEquals(
            Res.string.localizable_error_mesh_core_timeout,
            RoomServerError.SessionError(MeshError.Timeout).messageResource(),
        )
    }

    @Test
    fun aFirmwareSubCodeSharesTheDeviceErrorTable() {
        assertEquals(
            Res.string.localizable_error_device_storage_full,
            RemoteNodeError.SessionError(MeshError.DeviceError(DEVICE_ERROR_TABLE_FULL)).messageResource(),
        )
    }

    @Test
    fun nodeAuthPrefersRemoteThenBridgeThenErrorOccurred() {
        val surface = nodeAuthSurfaceText(
            remote = { if (it is RemoteNodeError.Timeout) "TIMEOUT" else null },
            bridge = { if (it.message == "bridge") "BRIDGE" else null },
            unmapped = "UNMAPPED",
        )

        assertEquals("TIMEOUT", surface(RemoteNodeError.Timeout))
        assertEquals("BRIDGE", surface(IllegalStateException("bridge")))
        assertEquals("UNMAPPED", surface(IllegalStateException("login")))
    }

    private companion object {
        const val DEVICE_ERROR_TABLE_FULL = 3
    }
}
