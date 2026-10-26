// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/AppBackupEnvelope+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/ImportResult+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
// Real Room, independent destination lifetimes and process DataStore; no persistence surrogate.
package com.meshcoreone.android.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
internal annotation class OriginalCase(val value: String)

internal val RADIO = RadioId(UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
internal val OTHER_RADIO = RadioId(UUID.fromString("FEDCBA98-7654-3210-FEDC-BA9876543210"))
internal val AT: Instant = Instant.ofEpochSecond(1_700_000_000)
internal fun id(value: Long): UUID = UUID(0L, value)
internal fun key(value: Int = 0xAB): Bytes = Bytes(ByteArray(32) { value.toByte() })
internal fun device(radio: RadioId = RADIO, publicKey: Bytes = key(), id: UUID = id(1)): DeviceDTO =
    DeviceDTO(id, radio, publicKey, "TestDevice", lastConnected = AT)
internal fun contact(radio: RadioId = RADIO, publicKey: Bytes = key(0xAA), id: UUID = id(2)): ContactDTO =
    ContactDTO(id, radio, publicKey, "Alice", typeRawValue = 1u, lastHeardTimestamp = 0u)
internal fun channel(radio: RadioId = RADIO, index: UByte = 0u, secret: Bytes = Bytes(ByteArray(16)), id: UUID = id(3)): ChannelDTO =
    ChannelDTO(id, radio, index, "General", secret)
internal fun message(
    radio: RadioId = RADIO, id: UUID = id(4), direction: MessageDirection = MessageDirection.OUTGOING,
    contactID: UUID? = id(2), index: UByte? = null, text: String = "Hello", date: Instant = AT,
): MessageDTO = MessageDTO(id, radio, contactID, index, text, 1_700_000_000u, date,
    direction = direction, status = MessageStatus.SENT)
internal fun repeat(messageID: UUID = id(4), id: UUID = id(5), path: Bytes = Bytes.of(0x31)): MessageRepeatDTO =
    MessageRepeatDTO(id, messageID, AT, path, 1u)
internal fun reaction(radio: RadioId = RADIO, messageID: UUID = id(4), id: UUID = id(6)): ReactionDTO =
    ReactionDTO(id, messageID, "\uD83D\uDC4D", "Eve", "hash", "raw", AT, radioId = radio)
internal fun session(radio: RadioId = RADIO, publicKey: Bytes = key(0xCD), id: UUID = id(8)): RemoteNodeSessionDTO =
    RemoteNodeSessionDTO(id, radio, publicKey, "Room", RemoteNodeRole.ROOM_SERVER)
internal fun roomMessage(sessionID: UUID = id(8), id: UUID = id(7)): RoomMessageDTO =
    RoomMessageDTO(id, sessionID, Bytes.of(1, 2, 3, 4), text = "Room text", timestamp = 42u, createdAt = AT)
internal fun run(id: UUID = id(10), date: Instant = AT): TracePathRunDTO =
    TracePathRunDTO(id, date, true, 95, SnapshotList.of(1.5, -2.25))
internal fun path(radio: RadioId = RADIO, pathBytes: Bytes = Bytes.of(0x12, 0x34), id: UUID = id(9)): SavedTracePathDTO =
    SavedTracePathDTO(id, radio, "Route", pathBytes, 2, AT, SnapshotList.of(run()))
internal fun snapshot(id: UUID = id(12), date: Instant = AT): NodeStatusSnapshotDTO =
    NodeStatusSnapshotDTO(id, date, key(0xF5), batteryMillivolts = 3800u)
internal fun discovered(value: Int = 0, radio: RadioId = RADIO): DiscoveredNodeDTO {
    val bytes = ByteArray(32)
    bytes[0] = value.toByte()
    bytes[1] = (value ushr 8).toByte()
    return DiscoveredNodeDTO(id(1000L + value), radio, Bytes(bytes), "N$value", 1u, AT.plusSeconds(value.toLong()),
        value.toUInt(), 0.0, 0.0, 255u, Bytes.EMPTY, null, null)
}
internal fun envelope(
    devices: List<DeviceDTO> = emptyList(), contacts: List<ContactDTO> = emptyList(), channels: List<ChannelDTO> = emptyList(),
    messages: List<MessageDTO> = emptyList(), repeats: List<MessageRepeatDTO> = emptyList(), reactions: List<ReactionDTO> = emptyList(),
    sessions: List<RemoteNodeSessionDTO> = emptyList(), roomMessages: List<RoomMessageDTO> = emptyList(),
    paths: List<SavedTracePathDTO> = emptyList(), blocked: List<BlockedChannelSenderDTO> = emptyList(),
    snapshots: List<NodeStatusSnapshotDTO> = emptyList(), discovered: List<DiscoveredNodeDTO> = emptyList(),
    preferences: BackupUserDefaults? = null, exportDate: Instant = AT,
): AppBackupEnvelope = AppBackupEnvelope(
    exportDate = exportDate, appVersion = "1.0.0", appBuild = "42", devices = devices.snapshot(), contacts = contacts.snapshot(),
    channels = channels.snapshot(), messages = messages.snapshot(), messageRepeats = repeats.snapshot(), reactions = reactions.snapshot(),
    remoteNodeSessions = sessions.snapshot(), roomMessages = roomMessages.snapshot(), savedTracePaths = paths.snapshot(),
    blockedChannelSenders = blocked.snapshot(), nodeStatusSnapshots = snapshots.snapshot(), discoveredNodes = discovered.snapshot(),
    userDefaults = preferences,
).withActualManifest()

internal fun fullEnvelope(): AppBackupEnvelope = envelope(
    devices = listOf(device()), contacts = listOf(contact()), channels = listOf(channel()), messages = listOf(message()),
    repeats = listOf(repeat()), reactions = listOf(reaction()), sessions = listOf(session()), roomMessages = listOf(roomMessage()),
    paths = listOf(path()), blocked = listOf(BlockedChannelSenderDTO(id(11), "Blocked", RADIO, AT)),
    snapshots = listOf(snapshot()), discovered = listOf(discovered()),
    preferences = BackupUserDefaults(hasCompletedOnboarding = true),
)

internal fun AppBackupEnvelope.jsonObject(): JsonObject {
    val output = java.io.ByteArrayOutputStream()
    AppBackupCodec().encodeJson(this, output)
    return backupJson.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject
}
internal fun JsonObject.compressed(): Bytes = Bytes.utf8(toString()).zlibCompressed()
internal fun JsonObject.replacing(key: String, value: JsonElement?): JsonObject =
    JsonObject(toMutableMap().apply { if (value == null) remove(key) else put(key, value) })
internal fun JsonObject.modifyingRow(key: String, index: Int = 0, transform: (JsonObject) -> JsonObject): JsonObject =
    replacing(key, JsonArray(getValue(key).jsonArray.mapIndexed { offset, row -> if (offset == index) transform(row.jsonObject) else row }))
internal fun fraction(value: String): Instant = instantFromUnixSeconds(BigDecimal(value))
internal fun ImportResult.count(kind: BackupModelKind): PerTypeCounts = counts.getValue(kind)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
abstract class BackupRoomTest {
    protected lateinit var db: MeshCoreDatabase
    protected lateinit var store: RoomPersistenceStore
    protected lateinit var storage: MeshCoreStorage
    protected lateinit var service: AppBackupService
    protected val clock: Clock = Clock.fixed(AT.plusSeconds(20_000), ZoneOffset.UTC)
    protected val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(TestCoroutineScheduler()))
    protected val context: Context get() = ApplicationProvider.getApplicationContext()
    protected val immediate = Executor { it.run() }

    @Before fun openStores() {
        db = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        store = RoomPersistenceStore(db, owner, clock)
        storage = MeshCoreStorage.get(context)
        runBlocking { storage.preferences.resetAppPreferences() }
        service = newService()
    }

    internal fun newService(hooks: BackupImportHooks = BackupImportHooks()): AppBackupService =
        AppBackupService(AppBackupCodec(), storage.backupPreferences, "1.0.0", "42", clock, hooks)

    protected suspend fun seed(value: AppBackupEnvelope) {
        for (dto in value.devices) db.devices().insert(dto.toEntity())
        for (dto in value.contacts) db.contacts().insert(dto.toEntity())
        for (dto in value.channels) db.channels().insert(dto.toEntity())
        for (dto in value.messages) db.messages().insert(dto.toEntity())
        for (dto in value.remoteNodeSessions) db.sessions().insert(dto.toEntity())
    }

    protected fun fileDatabase(file: File): MeshCoreDatabase =
        Room.databaseBuilder(context, MeshCoreDatabase::class.java, file.absolutePath)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()

    protected suspend fun withIndependentStore(block: suspend (MeshCoreDatabase, RoomPersistenceStore) -> Unit) {
        val destination = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        try {
            val destinationStore = RoomPersistenceStore(destination, owner, clock)
            try {
                block(destination, destinationStore)
            } finally {
                destinationStore.close()
            }
        } finally {
            destination.close()
        }
    }

    @After fun closeStores() {
        try {
            runBlocking { store.close(); storage.close() }
        } finally {
            owner.cancel()
            db.close()
        }
    }
}
