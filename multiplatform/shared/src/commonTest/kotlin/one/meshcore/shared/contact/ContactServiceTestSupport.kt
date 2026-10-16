package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.meshcore.ContactFetchResult
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.sequentialTestUuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger

/** Fixed epoch-second clock the contact-service tests read through the injected `now`. */
internal const val CONTACT_TEST_NOW_SECONDS: Long = 1_700_000_000L

/** A 32-byte public key filled with [byte], for deterministic identity in the tests. */
internal fun publicKey(byte: Int): ByteArray = ByteArray(32) { byte.toByte() }

/**
 * A controllable [ContactSession] for the contact-service tests: it answers `getContacts` from a settable
 * list, records every command, and can fail a chosen command with a [ContactDeviceException]. The Kotlin
 * stand-in for the Swift `MockMeshCoreSession`.
 */
internal class FakeContactSession : ContactSession {
    var stubbedContacts: List<MeshContact> = emptyList()

    /** When set, [getContact] returns this contact for a matching public key; otherwise scans [stubbedContacts]. */
    var singleContactLookup: MeshContact? = null

    val addedContacts = mutableListOf<MeshContact>()
    val removedPublicKeys = mutableListOf<ByteArray>()
    val resetPathPublicKeys = mutableListOf<ByteArray>()
    val sharedPublicKeys = mutableListOf<ByteArray>()
    val changeFlagsCalls = mutableListOf<Pair<MeshContact, Int>>()
    val pathDiscoveryDestinations = mutableListOf<ByteArray>()
    val getContactKeys = mutableListOf<ByteArray>()

    /** When set, the named command throws this device error. */
    var addContactError: Int? = null
    var removeContactError: Int? = null
    var resetPathError: Int? = null

    /** When set, [resetPath] throws this instead of a device error. */
    var resetPathThrowable: Throwable? = null
    var shareContactError: Int? = null
    var pathDiscoveryError: Int? = null

    /** Optional hook at the start of [sendPathDiscovery], before the device-error check. */
    var onSendPathDiscovery: (suspend () -> Unit)? = null

    var pathDiscoveryResult = MessageSentInfo(route = 0, expectedAck = ByteArray(0), suggestedTimeoutMs = 5000)

    /** Optional hook invoked at the start of [getContacts] (e.g. mid-sync 0x80 injection). */
    var onGetContacts: (suspend () -> Unit)? = null

    /** Optional hook invoked at the start of [getContact] (e.g. 0x8F mid-fetch). */
    var onGetContact: (suspend () -> Unit)? = null

    override suspend fun getContacts(since: Long?): List<MeshContact> {
        onGetContacts?.invoke()
        return stubbedContacts
    }

    /** When true, [getContactsReportingTotal] returns a nil total (no `contactsStart` header). */
    var omitReportedTotal: Boolean = false

    /**
     * Explicit device-reported total. Null means treat the stubbed list as complete
     * (`stubbedContacts.size`) unless [omitReportedTotal] is set.
     */
    var stubbedReportedTotal: Int? = null

    override suspend fun getContactsReportingTotal(since: Long?): ContactFetchResult {
        val contacts = getContacts(since)
        val total = when {
            omitReportedTotal -> null
            stubbedReportedTotal != null -> stubbedReportedTotal
            else -> contacts.size
        }
        return ContactFetchResult(contacts = contacts, reportedTotal = total)
    }

    override suspend fun getContact(publicKey: ByteArray): MeshContact? {
        getContactKeys += publicKey
        onGetContact?.invoke()
        singleContactLookup?.let { if (it.publicKey.contentEquals(publicKey)) return it }
        return stubbedContacts.firstOrNull { it.publicKey.contentEquals(publicKey) }
    }

    /**
     * When set, [addContact] throws this instead of a device error. Stands in for a transport or bridge
     * failure, which `ContactService` does not wrap into a `ContactServiceError`.
     */
    var addContactThrowable: Throwable? = null

    override suspend fun addContact(contact: MeshContact) {
        addContactError?.let { throw ContactDeviceException(it) }
        addContactThrowable?.let { throw it }
        addedContacts += contact
    }

    /** Optional hook at the start of [removeContact], before the device-error check. */
    var onRemoveContact: (suspend () -> Unit)? = null

    /** When set, [removeContact] throws this instead of recording the command. */
    var removeContactThrowable: Throwable? = null

    override suspend fun removeContact(publicKey: ByteArray) {
        onRemoveContact?.invoke()
        removeContactError?.let { throw ContactDeviceException(it) }
        removeContactThrowable?.let { throw it }
        removedPublicKeys += publicKey
    }

    override suspend fun resetPath(publicKey: ByteArray) {
        resetPathThrowable?.let { throw it }
        resetPathError?.let { throw ContactDeviceException(it) }
        resetPathPublicKeys += publicKey
    }

    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo {
        onSendPathDiscovery?.invoke()
        pathDiscoveryError?.let { throw ContactDeviceException(it) }
        pathDiscoveryDestinations += destination
        return pathDiscoveryResult
    }

    override suspend fun shareContact(publicKey: ByteArray) {
        shareContactError?.let { throw ContactDeviceException(it) }
        sharedPublicKeys += publicKey
    }

    /** When set, [changeContactFlags] throws this instead of recording the call. */
    var changeFlagsThrowable: Throwable? = null

    override suspend fun changeContactFlags(contact: MeshContact, flags: Int) {
        changeFlagsThrowable?.let { throw it }
        changeFlagsCalls += contact to flags
    }
}

/** A [ContactCleanupHandling] that records each invocation, mirroring the Swift `RecordingCleanupCoordinator`. */
internal class RecordingCleanupHandler : ContactCleanupHandling {
    data class Invocation(val contactID: Uuid, val reason: ContactCleanupReason, val publicKey: ByteArray)

    val invocations = mutableListOf<Invocation>()

    override suspend fun handleCleanup(contactID: Uuid, reason: ContactCleanupReason, publicKey: ByteArray) {
        invocations += Invocation(contactID, reason, publicKey)
    }
}

/** A [ContactDiscoveryNotifier] that records each new-contact-discovered call. */
internal class RecordingContactDiscoveryNotifier : ContactDiscoveryNotifier {
    data class Notified(val contactID: Uuid, val contactName: String?, val contactType: ContactType)

    val notified = mutableListOf<Notified>()

    override suspend fun notifyNewContact(contactID: Uuid, contactName: String?, contactType: ContactType) {
        notified += Notified(contactID, contactName, contactType)
    }
}

/** Builds a [MeshContact] with the tests' default zeros; only the varying fields are named. */
@Suppress("LongParameterList")
internal fun meshContact(
    keyByte: Int,
    name: String,
    type: ContactType = ContactType.CHAT,
    typeRawValue: Int = type.raw,
    flags: Int = 0,
    outPathLength: Int = 0,
    outPath: ByteArray = ByteArray(0),
    latitude: Double = 0.0,
    longitude: Double = 0.0,
    lastAdvertisement: Long = 0,
    lastModified: Long = 0,
): MeshContact {
    val key = publicKey(keyByte)
    return MeshContact(
        id = key.toUppercaseHex(),
        publicKey = key,
        typeRawValue = typeRawValue,
        flags = flags,
        outPathLength = outPathLength,
        outPath = outPath,
        advertisedName = name,
        lastAdvertisement = lastAdvertisement,
        latitude = latitude,
        longitude = longitude,
        lastModified = lastModified,
    )
}

/** Builds a [ContactFrame] with the tests' default zeros; only the varying fields are named. */
internal fun contactFrame(keyByte: Int, name: String, type: ContactType = ContactType.CHAT): ContactFrame =
    ContactFrame(
        publicKey = publicKey(keyByte),
        type = type,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        name = name,
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
    )

/** Builds a persisted [ContactDto] with the tests' defaults; only the varying fields are named. */
@Suppress("LongParameterList")
internal fun contactDto(
    id: Uuid,
    radioID: Uuid,
    keyByte: Int = 0x01,
    name: String = "TestContact",
    typeRawValue: Int = ContactType.CHAT.raw,
    flags: Int = 0,
    outPathLength: Int = 0,
    outPath: ByteArray = ByteArray(0),
    nickname: String? = null,
    isBlocked: Boolean = false,
    isMuted: Boolean = false,
    isFavorite: Boolean = false,
    lastMessageDate: Double? = null,
    unreadCount: Int = 0,
    unreadMentionCount: Int = 0,
    ocvPreset: String? = null,
    customOCVArrayString: String? = null,
): ContactDto = ContactDto(
    id = id,
    radioID = radioID,
    publicKey = publicKey(keyByte),
    name = name,
    typeRawValue = typeRawValue,
    flags = flags,
    outPathLength = outPathLength,
    outPath = outPath,
    lastAdvertTimestamp = 0,
    latitude = 0.0,
    longitude = 0.0,
    lastModified = 0,
    isBlocked = isBlocked,
    isMuted = isMuted,
    isFavorite = isFavorite,
    unreadCount = unreadCount,
    unreadMentionCount = unreadMentionCount,
    nickname = nickname,
    lastMessageDate = lastMessageDate,
    ocvPreset = ocvPreset,
    customOCVArrayString = customOCVArrayString,
)

/** A distinct, deterministic [Uuid] per call within a test run. */
internal object ContactTestIds {
    private var counter = 0

    fun fresh(): Uuid {
        counter += 1
        return sequentialTestUuid(counter)
    }

    val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
}

/** Builds a [ContactService] wired to [session]/[store] with a fixed clock. */
@Suppress("LongParameterList")
internal fun testContactService(
    session: FakeContactSession = FakeContactSession(),
    store: ContactStore = InMemoryContactStore(),
    cleanup: ContactCleanupHandling = NoOpContactCleanupHandling,
    now: () -> Long = { CONTACT_TEST_NOW_SECONDS },
    discoveryNotifier: ContactDiscoveryNotifier = ContactDiscoveryNotifier.NoOp,
    selfPublicKey: suspend (Uuid) -> ByteArray? = { ByteArray(32) { 0x11 } },
    logger: one.meshcore.shared.settings.PersistentLogger? = null,
    discoverTrace: one.meshcore.shared.settings.PersistentLogger? = null,
    orphanDmAdopter: OrphanDmAdopter = OrphanDmAdopter.NoOp,
    advertSyncClaim: AdvertContactSyncClaim = AdvertContactSyncClaim(),
    syncCoordinator: ContactSyncCoordinator = NoOpContactSyncCoordinator,
    discoveredNodes: DiscoveredNodeStore? = null,
    isInForeground: suspend () -> Boolean = { true },
): ContactService = ContactService(
    session = session,
    store = store,
    now = now,
    collaborators = ContactServiceCollaborators(
        syncCoordinator = syncCoordinator,
        cleanupCoordinator = cleanup,
        discoveryNotifier = discoveryNotifier,
        orphanDmAdopter = orphanDmAdopter,
        discoveredNodes = discoveredNodes,
    ),
    advert = ContactServiceAdvert(
        advertSyncClaim = advertSyncClaim,
        isInForeground = isInForeground,
    ),
    edges = ContactServiceEdges(
        selfPublicKey = selfPublicKey,
        logger = logger,
        discoverTrace = discoverTrace,
    ),
)

/** A contact service that logs and resolves one pending contact, so a fault test can vary only the store. */
internal fun TestScope.loggedPendingContact(
    store: ContactStore,
    logs: MutableList<DebugLogEntryDto>,
    keyByte: Int,
    name: String,
    onContactsChanged: suspend () -> Unit,
): ContactService = testContactService(
    session = FakeContactSession().apply {
        singleContactLookup = meshContact(keyByte = keyByte, name = name)
    },
    store = store,
    syncCoordinator = CallbackContactSyncCoordinator(onContactsChanged = onContactsChanged),
    logger = PersistentLogger(
        scope = this,
        category = "ContactService",
        record = { logs += it },
    ),
)

/** A contact service that logs under ContactService, so a fault test can vary only the store. */
internal fun TestScope.loggedContactService(store: ContactStore, logs: MutableList<DebugLogEntryDto>): ContactService =
    testContactService(
        store = store,
        logger = PersistentLogger(
            scope = this,
            category = "ContactService",
            record = { logs += it },
        ),
    )

/** A contact service that logs under AdvertisementService, so a fault test can vary only the store. */
internal fun TestScope.loggedAdvertService(store: ContactStore, logs: MutableList<DebugLogEntryDto>): ContactService =
    testContactService(
        store = store,
        logger = PersistentLogger(
            scope = this,
            category = "AdvertisementService",
            record = { logs += it },
        ),
    )

/**
 * [ContactStore] that throws on the first [throwOnFirstTouches] lastHeard stamps, then
 * delegates. Used to prove iOS lastHeard inner-catches.
 */
internal class FlakyTouchContactStore(
    private val inner: InMemoryContactStore,
    private val throwOnFirstTouches: Int = 0,
    private val cancelOnFirstTouch: Boolean = false,
    private val throwOnFirstFetches: Int = 0,
    private val throwOnFetchKey: ByteArray? = null,
) : ContactStore by inner {
    var touchCalls = 0
    var fetchCalls = 0

    override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? {
        val matches = throwOnFetchKey == null || publicKey.contentEquals(throwOnFetchKey)
        if (matches) {
            fetchCalls += 1
            if (fetchCalls <= throwOnFirstFetches) {
                error("storeUnavailable")
            }
        }
        return inner.fetchContact(radioID, publicKey)
    }

    override suspend fun touchContactHeard(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean {
        touchCalls += 1
        if (cancelOnFirstTouch && touchCalls == 1) {
            throw CancellationException("cancelled")
        }
        if (touchCalls <= throwOnFirstTouches) {
            error("storeUnavailable")
        }
        return inner.touchContactHeard(radioID, publicKey, atEpochSeconds)
    }
}
