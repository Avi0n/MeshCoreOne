package one.meshcore.shared.remotenode

import kotlinx.serialization.json.Json
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Domain value-type behaviour: enums, LoginResult, TelemetryModes, session extensions, DTO round-trips. */
class RemoteNodeDomainTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun permissionLevelComparisonAndHelpers() {
        assertTrue(RoomPermissionLevel.ADMIN > RoomPermissionLevel.READ_WRITE)
        assertTrue(RoomPermissionLevel.READ_WRITE > RoomPermissionLevel.GUEST)
        assertFalse(RoomPermissionLevel.GUEST.canPost)
        assertTrue(RoomPermissionLevel.READ_WRITE.canPost)
        assertTrue(RoomPermissionLevel.ADMIN.canPost)
        assertFalse(RoomPermissionLevel.READ_WRITE.isAdmin)
        assertTrue(RoomPermissionLevel.ADMIN.isAdmin)
    }

    @Test
    fun roleFromContactType() {
        assertEquals(RemoteNodeRole.REPEATER, RemoteNodeRole.fromContactType(ContactType.REPEATER))
        assertEquals(RemoteNodeRole.ROOM_SERVER, RemoteNodeRole.fromContactType(ContactType.ROOM))
        assertNull(RemoteNodeRole.fromContactType(ContactType.CHAT))
    }

    @Test
    fun loginResultAdminIsDrivenByBooleanNotAclByte() {
        // Admin flag wins regardless of the acl byte (the wire ADMIN byte is 3, not 2).
        val admin = LoginResult(success = true, isAdmin = true, aclPermissions = 3, publicKeyPrefix = testPublicKey())
        assertEquals(RoomPermissionLevel.ADMIN, admin.permissionLevel)

        // Without the admin flag, the acl byte maps by raw value.
        val member = LoginResult(success = true, isAdmin = false, aclPermissions = 1, publicKeyPrefix = testPublicKey())
        assertEquals(RoomPermissionLevel.READ_WRITE, member.permissionLevel)

        // An unmapped acl byte (3) with no admin flag falls back to guest, never admin.
        val unmapped =
            LoginResult(success = true, isAdmin = false, aclPermissions = 3, publicKeyPrefix = testPublicKey())
        assertEquals(RoomPermissionLevel.GUEST, unmapped.permissionLevel)

        val nullAcl =
            LoginResult(success = false, isAdmin = false, aclPermissions = null, publicKeyPrefix = testPublicKey())
        assertEquals(RoomPermissionLevel.GUEST, nullAcl.permissionLevel)
    }

    @Test
    fun telemetryModesPackRoundTrip() {
        val modes = TelemetryModes(base = 1, location = 2, environment = 3)
        assertEquals((3 shl 4) or (2 shl 2) or 1, modes.packed)
        assertEquals(modes, TelemetryModes.fromPacked(modes.packed))
        // masked() drops overflow bits.
        assertEquals(
            TelemetryModes(base = 1, location = 0, environment = 1),
            TelemetryModes.masked(0b101, 0b100, 0b101),
        )
    }

    @Test
    fun sessionPrefixAndRoleHelpers() {
        val repeater = testSession(role = RemoteNodeRole.REPEATER, permissionLevel = RoomPermissionLevel.ADMIN)
        assertContentEquals(repeater.publicKey.copyOf(6), repeater.publicKeyPrefix)
        assertTrue(repeater.isRepeater)
        assertFalse(repeater.isRoom)
        assertTrue(repeater.isAdmin)
        // canPost is room-only even for an admin repeater.
        assertFalse(repeater.canPost)

        val room = testSession(role = RemoteNodeRole.ROOM_SERVER, permissionLevel = RoomPermissionLevel.READ_WRITE)
        assertTrue(room.isRoom)
        assertTrue(room.canPost)
    }

    @Test
    fun sessionLocationSentinel() {
        val noGps = testSession().copy(latitude = 0.0, longitude = 0.0)
        assertFalse(noGps.hasLocation)
        assertNull(noGps.coordinate)

        val located = testSession().copy(latitude = 37.33, longitude = -122.03)
        assertTrue(located.hasLocation)
        assertEquals(NodeCoordinate(37.33, -122.03), located.coordinate)

        val invalid = testSession().copy(latitude = 200.0, longitude = 10.0)
        assertFalse(invalid.hasLocation)
    }

    @Test
    fun sessionCopyPreservesOriginal() {
        val base = testSession(permissionLevel = RoomPermissionLevel.ADMIN)
        assertEquals(NotificationLevel.MUTED, base.copy(notificationLevel = NotificationLevel.MUTED).notificationLevel)
        assertTrue(base.copy(isPinned = true).isPinned)
        // Original is untouched.
        assertEquals(NotificationLevel.ALL, base.notificationLevel)
        assertFalse(base.isPinned)
    }

    @Test
    fun remoteNodeSessionDtoBackupRoundTrip() {
        val dto = testSession(permissionLevel = RoomPermissionLevel.ADMIN, lastSyncTimestamp = 42)
            .copy(lastMessageDate = 1_700_000_000.0, notificationLevel = NotificationLevel.MENTIONS_ONLY)
        val encoded = json.encodeToString(RemoteNodeSessionDto.serializer(), dto)
        val decoded = json.decodeFromString(RemoteNodeSessionDto.serializer(), encoded)
        // Re-encode to compare stably (ByteArray fields defeat data-class equals).
        assertEquals(encoded, json.encodeToString(RemoteNodeSessionDto.serializer(), decoded))
        assertContentEquals(dto.publicKey, decoded.publicKey)
        assertEquals(dto.permissionLevel, decoded.permissionLevel)
        assertEquals(dto.notificationLevel, decoded.notificationLevel)
        assertEquals(dto.lastMessageDate, decoded.lastMessageDate)
    }

    @Test
    fun nodeStatusSnapshotDtoBackupRoundTrip() {
        val dto = NodeStatusSnapshotDto(
            id = testUuid(7),
            timestamp = 1_700_000_000.0,
            nodePublicKey = testPublicKey(),
            batteryMillivolts = 3850,
            uptimeSeconds = 3600,
            directDuplicates = 11,
            floodDuplicates = 22,
        )
        val encoded = json.encodeToString(NodeStatusSnapshotDto.serializer(), dto)
        val decoded = json.decodeFromString(NodeStatusSnapshotDto.serializer(), encoded)
        assertEquals(encoded, json.encodeToString(NodeStatusSnapshotDto.serializer(), decoded))
        assertEquals(dto.batteryMillivolts, decoded.batteryMillivolts)
        assertEquals(dto.uptimeSeconds, decoded.uptimeSeconds)
    }
}
