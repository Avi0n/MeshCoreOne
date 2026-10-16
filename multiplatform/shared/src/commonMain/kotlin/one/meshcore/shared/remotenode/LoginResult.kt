package one.meshcore.shared.remotenode

import one.meshcore.shared.model.RoomPermissionLevel

/**
 * The decoded outcome of a remote-node login, mirroring the Swift `LoginResult`. Built from the
 * `loginSuccess`/`loginFailed` push events. [publicKeyPrefix] is the 6-byte prefix the firmware echoes.
 *
 * Admin is driven by the firmware's [isAdmin] boolean, never re-derived from [aclPermissions]: the wire
 * ACL byte for admin is 3 (`PERM_ACL_ADMIN`), but the app's [RoomPermissionLevel.ADMIN] is raw 2, so
 * mapping the byte numerically would misclassify admin.
 */
data class LoginResult(
    val success: Boolean,
    val isAdmin: Boolean,
    val aclPermissions: Int?,
    val publicKeyPrefix: ByteArray,
    /** The node's RTC (epoch seconds) carried in the login response, when present; used for clock-drift. */
    val serverTime: Long? = null,
) {
    val permissionLevel: RoomPermissionLevel
        get() = if (isAdmin) {
            RoomPermissionLevel.ADMIN
        } else {
            RoomPermissionLevel.fromRaw(aclPermissions ?: 0) ?: RoomPermissionLevel.GUEST
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoginResult) return false
        return success == other.success &&
            isAdmin == other.isAdmin &&
            aclPermissions == other.aclPermissions &&
            publicKeyPrefix.contentEquals(other.publicKeyPrefix)
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + isAdmin.hashCode()
        result = 31 * result + (aclPermissions ?: 0)
        result = 31 * result + publicKeyPrefix.contentHashCode()
        return result
    }
}
