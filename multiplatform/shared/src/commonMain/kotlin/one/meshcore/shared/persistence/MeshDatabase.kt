package one.meshcore.shared.persistence

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters

/** First production schema. Versions below this are unsupported pre-launch databases. */
const val MESH_SCHEMA_VERSION: Int = 11

/**
 * Schema versions [productionSchemaPolicy] may drop. A file already at [MESH_SCHEMA_VERSION] is not in
 * this list, and neither is any future version: those require a real migration.
 */
internal val UNSUPPORTED_PRELAUNCH_SCHEMA_VERSIONS: IntArray =
    IntArray(MESH_SCHEMA_VERSION - 1) { index -> index + 1 }

/** Room takes the dropped versions as varargs, so this copies the ten-element list once per open. */
@Suppress("SpreadOperator")
fun <T : RoomDatabase> RoomDatabase.Builder<T>.productionSchemaPolicy(): RoomDatabase.Builder<T> =
    fallbackToDestructiveMigrationFrom(
        dropAllTables = true,
        *UNSUPPORTED_PRELAUNCH_SCHEMA_VERSIONS,
    )

/**
 * The mesh Room database. Entities mirror the SwiftData `@Model` set carried in a backup, plus
 * `TracePathRun` (SwiftData models it as a cascade relationship on `SavedTracePath`) and `PendingSend`
 * (the durable send-queue row, added in schema v2).
 *
 * [MESH_SCHEMA_VERSION] is the first production schema. Versions 1 through 10 are unsupported
 * pre-launch installs, and [productionSchemaPolicy] drops those tables. A later schema must ship a
 * `Migration` from this version. There is no blanket destructive fallback, so a missing migration
 * cannot wipe a file already at [MESH_SCHEMA_VERSION].
 *
 * The version still tracks the schema (`version = 2` added `PendingSend`, `version = 3` the regenerable
 * `LinkPreview` cache, `version = 4` `DebugLogEntry`, `version = 5` the `Contact.avatarImageData` column,
 * `version = 6` the `DiscoveredNode` table plus the `NodeStatusSnapshot` GPS columns, `version = 7` the
 * regenerable `RxLogEntry` table, `version = 8` dropped `RxLogEntry.decodedText` and added its
 * `transportCode` / `payloadTypeBits` columns, `version = 9` added `Contact.lastHeardTimestamp`,
 * `Message.failureSeen`, `Message.regionScopeMatches`, and `RoomMessage.failureSeen`, `version = 10`
 * added `Device.appliedRadioPresetID`, and `version = 11` split local chat pins from radio favorites)
 * because Room derives the mismatch from it.
 */
@Database(
    entities = [
        DeviceEntity::class,
        ContactEntity::class,
        ChannelEntity::class,
        MessageEntity::class,
        MessageRepeatEntity::class,
        ReactionEntity::class,
        RoomMessageEntity::class,
        RemoteNodeSessionEntity::class,
        SavedTracePathEntity::class,
        TracePathRunEntity::class,
        BlockedChannelSenderEntity::class,
        NodeStatusSnapshotEntity::class,
        PendingSendEntity::class,
        LinkPreviewEntity::class,
        DebugLogEntryEntity::class,
        DiscoveredNodeEntity::class,
        RxLogEntryEntity::class,
    ],
    version = MESH_SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class, SnapshotListConverters::class)
@ConstructedBy(MeshDatabaseConstructor::class)
abstract class MeshDatabase :
    RoomDatabase(),
    MeshMessagingDaos,
    MeshRadioDaos

/** Contact, channel, message, room, and send-queue accessors. */
interface MeshMessagingDaos {
    fun contactDao(): ContactDao

    fun channelDao(): ChannelDao

    fun messageDao(): MessageDao

    fun messageRepeatDao(): MessageRepeatDao

    fun reactionDao(): ReactionDao

    fun roomMessageDao(): RoomMessageDao

    fun remoteNodeSessionDao(): RemoteNodeSessionDao

    fun pendingSendDao(): PendingSendDao

    fun blockedChannelSenderDao(): BlockedChannelSenderDao
}

/** Device, trace, discovery, log, and preview accessors. */
interface MeshRadioDaos {
    fun deviceDao(): DeviceDao

    fun savedTracePathDao(): SavedTracePathDao

    fun tracePathRunDao(): TracePathRunDao

    fun nodeStatusSnapshotDao(): NodeStatusSnapshotDao

    fun linkPreviewDao(): LinkPreviewDao

    fun debugLogEntryDao(): DebugLogEntryDao

    fun discoveredNodeDao(): DiscoveredNodeDao

    fun rxLogEntryDao(): RxLogEntryDao
}

/**
 * KMP database constructor. Room's KSP processor generates the `actual` for each target, so there is
 * intentionally no hand-written actual — hence the suppression.
 */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpectDeclaration")
expect object MeshDatabaseConstructor : RoomDatabaseConstructor<MeshDatabase> {
    override fun initialize(): MeshDatabase
}
