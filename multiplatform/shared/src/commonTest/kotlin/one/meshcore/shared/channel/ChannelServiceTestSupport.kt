package one.meshcore.shared.channel

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.sequentialTestUuid

/**
 * A controllable [ChannelSession] for the channel-service tests: it answers `getChannel` from a settable
 * per-slot map, drives the pipelined `getChannels` from a settable batch, records every `setChannel`, and
 * can fail any of these with a chosen exception. The Kotlin stand-in for the Swift `MockMeshCoreSession`
 * plus the pipeline tests' `MockTransport` choreography, reduced to the seam the service actually sees.
 */
internal class FakeChannelSession : ChannelSession {
    /** Configured slots returned by [getChannel] and by pipelined reconcile reads. */
    val slots = mutableMapOf<Int, ChannelInfo>()

    /** Per-slot failure factories for [getChannel]; a fresh exception is thrown each call. */
    val slotErrors = mutableMapOf<Int, () -> Throwable>()

    /** Indices passed to [getChannel], in call order. */
    val getChannelIndices = mutableListOf<Int>()

    /** The pipelined batch result; when null, every requested index is reported missing. */
    var batchResult: ChannelBatchRead? = null

    /** When set, [getChannels] throws this (a hard send failure). */
    var batchError: (() -> Throwable)? = null

    /** The index lists passed to [getChannels], in call order. */
    val getChannelsCalls = mutableListOf<List<Int>>()

    data class SetChannelCall(val index: Int, val name: String, val secret: ByteArray)

    /** Every [setChannel] write, in call order. */
    val setChannelCalls = mutableListOf<SetChannelCall>()

    /** When set, [setChannel] throws this. */
    var setChannelError: (() -> Throwable)? = null

    /** Marks [index] as an unconfigured slot: a device NOT_FOUND, which the service reads as "empty slot". */
    fun markUnconfigured(index: Int) {
        slotErrors[index] = { ChannelDeviceException(ChannelProtocolErrorCode.NOT_FOUND) }
    }

    override suspend fun getChannel(index: Int): ChannelInfo {
        getChannelIndices += index
        slotErrors[index]?.let { throw it() }
        return slots[index] ?: throw ChannelDeviceException(ChannelProtocolErrorCode.NOT_FOUND)
    }

    override suspend fun getChannels(indices: List<Int>): ChannelBatchRead {
        getChannelsCalls += indices
        batchError?.let { throw it() }
        return batchResult ?: ChannelBatchRead(received = emptyList(), missing = indices)
    }

    override suspend fun setChannel(index: Int, name: String, secret: ByteArray) {
        setChannelError?.let { throw it() }
        setChannelCalls += SetChannelCall(index, name, secret.copyOf())
    }
}

/** A configured channel slot (non-empty name, non-zero secret) the sync should keep. */
internal fun channelInfo(index: Int, name: String = "ch$index", secretByte: Int = 0xAB): ChannelInfo =
    ChannelInfo(index = index, name = name, secret = ByteArray(ChannelSecret.SIZE_BYTES) { secretByte.toByte() })

/** An unconfigured slot as the device reports it: empty name, all-zero secret. */
internal fun emptySlot(index: Int): ChannelInfo =
    ChannelInfo(index = index, name = "", secret = ByteArray(ChannelSecret.SIZE_BYTES))

/** A 16-byte secret filled with [byte], for concise `setChannelWithSecret` calls. */
internal fun secret16(byte: Int = 0xAB): ByteArray = ByteArray(ChannelSecret.SIZE_BYTES) { byte.toByte() }

/** Builds a persisted [ChannelDto] with the tests' defaults; only the varying fields are named. */
@Suppress("LongParameterList")
internal fun channelDto(
    id: Uuid,
    radioID: Uuid,
    index: Int,
    name: String = "ch$index",
    secretByte: Int = 0xCD,
    unreadCount: Int = 0,
    unreadMentionCount: Int = 0,
    notificationLevel: NotificationLevel = NotificationLevel.ALL,
    isPinned: Boolean = false,
    floodScope: ChannelFloodScope = ChannelFloodScope.Inherit,
    lastMessageDate: Double? = null,
): ChannelDto {
    val (mode, region) = ChannelFloodScopeStorage.decompose(floodScope)
    return ChannelDto(
        id = id,
        radioID = radioID,
        index = index,
        name = name,
        secret = ByteArray(ChannelSecret.SIZE_BYTES) { secretByte.toByte() },
        isEnabled = true,
        unreadCount = unreadCount,
        unreadMentionCount = unreadMentionCount,
        notificationLevel = notificationLevel,
        isPinned = isPinned,
        floodScopeModeRawValue = mode,
        regionScope = region,
        lastMessageDate = lastMessageDate,
    )
}

/** A distinct, deterministic [Uuid] per call within a test run. */
internal object ChannelTestIds {
    private var counter = 0

    fun fresh(): Uuid {
        counter += 1
        return sequentialTestUuid(counter)
    }

    val RADIO: Uuid = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
}

/** A [ChannelListSink] that records every channel list it is fed. */
internal class RecordingChannelListSink : ChannelListSink {
    val updates = mutableListOf<List<ChannelDto>>()

    override suspend fun updateChannels(channels: List<ChannelDto>) {
        updates += channels
    }
}

/** A [ChannelSlotOccupantChanged] that records every vacated-or-rewritten-slot notification. */
internal class RecordingSlotOccupantChanged : ChannelSlotOccupantChanged {
    val received = mutableListOf<Pair<Uuid, Set<Int>>>()

    override suspend fun onOccupantChanged(radioID: Uuid, slots: Set<Int>) {
        received += radioID to slots
    }
}

/** Builds a [ChannelService] wired to [session]/[store] plus the recording side-channels. */
internal fun testChannelService(
    session: ChannelSession = FakeChannelSession(),
    store: ChannelStore = InMemoryChannelStore(),
    rxCache: ChannelListSink = NoOpChannelListSink,
    slotOccupantChanged: ChannelSlotOccupantChanged = NoOpChannelSlotOccupantChanged,
    logger: one.meshcore.shared.settings.PersistentLogger? = null,
): ChannelService = ChannelService(
    session = session,
    store = store,
    rxCache = rxCache,
    slotOccupantChanged = slotOccupantChanged,
    logger = logger,
)
