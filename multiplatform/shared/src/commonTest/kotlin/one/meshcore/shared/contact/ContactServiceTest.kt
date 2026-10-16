package one.meshcore.shared.contact

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toUppercaseHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `ContactServiceTests`: sync-result values, error distinctness, MeshContact <-> frame
 * conversions and round-trips, the remove/clear/preferences/reset-path behaviors, the favorite-bit
 * preservation on frame update, the device-error mapping, and the three-axis identity invariant
 * (radioID always the caller's, never minted in a contact path).
 */
@Suppress("LargeClass")
class ContactServiceTest {
    private companion object {
        val TEST_KEY = ByteArray(32) { (it + 1).toByte() }
        const val FAVORITE_FLAG = 0x01
        const val TELEMETRY_BASE_FLAG = 0x02
        const val UNMODELED_CONTACT_TYPE = 0x7F
        const val RESERVED_PATH_LENGTH = 0xC0
    }

    // MARK: ContactSyncResult

    @Test
    fun contactSyncResultCarriesItsValues() {
        val result = ContactSyncResult(contactsReceived = 5, lastSyncTimestamp = 1_234_567_890L, isIncremental = true)
        assertEquals(5, result.contactsReceived)
        assertEquals(1_234_567_890L, result.lastSyncTimestamp)
        assertTrue(result.isIncremental)
    }

    @Test
    fun contactSyncResultHandlesZeroAndMax() {
        assertEquals(0, ContactSyncResult(0, 0L, false).contactsReceived)
        val max = ContactSyncResult(Int.MAX_VALUE, UInt.MAX_VALUE.toLong(), true)
        assertEquals(Int.MAX_VALUE, max.contactsReceived)
        assertEquals(UInt.MAX_VALUE.toLong(), max.lastSyncTimestamp)
    }

    // MARK: Errors

    @Test
    fun contactServiceErrorCasesAreDistinct() {
        val errors = listOf(
            ContactServiceError.NotConnected(),
            ContactServiceError.SendFailed(),
            ContactServiceError.InvalidResponse(),
            ContactServiceError.SyncInterrupted(),
            ContactServiceError.ContactNotFound(),
            ContactServiceError.ContactTableFull(),
        )
        assertEquals(errors.size, errors.map { it.message }.toSet().size)
    }

    // MARK: MeshContact -> ContactFrame

    @Test
    fun meshContactConvertsToContactFrame() {
        val contact = meshContact(
            keyByte = 0x11,
            name = "TestNode",
            flags = FAVORITE_FLAG,
            outPathLength = 2,
            outPath = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
            latitude = 37.7749,
            longitude = -122.4194,
            lastAdvertisement = 1_700_000_000,
            lastModified = 1_700_000_100,
        )
        val frame = contact.toContactFrame()
        assertTrue(frame.publicKey.contentEquals(publicKey(0x11)))
        assertEquals(ContactType.CHAT, frame.type)
        assertEquals(FAVORITE_FLAG, frame.flags)
        assertEquals(2, frame.outPathLength)
        assertEquals("TestNode", frame.name)
        assertEquals(1_700_000_000L, frame.lastAdvertTimestamp)
        assertEquals(1_700_000_100L, frame.lastModified)
        assertEquals(37.7749, frame.latitude)
    }

    @Test
    fun meshContactHandlesAllContactTypeConversions() {
        assertEquals(ContactType.CHAT, meshContact(0, "c", type = ContactType.CHAT).toContactFrame().type)
        assertEquals(ContactType.REPEATER, meshContact(0, "r", type = ContactType.REPEATER).toContactFrame().type)
        assertEquals(ContactType.ROOM, meshContact(0, "m", type = ContactType.ROOM).toContactFrame().type)
    }

    @Test
    fun invalidContactTypeByteDefaultsToChatButRoundTripsVerbatim() {
        val unmodeled = 0xFF
        val frame = meshContact(0, "x", typeRawValue = unmodeled).toContactFrame()
        assertEquals(ContactType.CHAT, frame.type)
        // The raw byte survives so it round-trips to the device instead of being coerced.
        assertEquals(unmodeled, frame.typeRawValue)
    }

    @Test
    fun meshContactHandlesFloodRoutingPath() {
        val frame = meshContact(0, "flood", outPathLength = CONTACT_FLOOD_PATH_SENTINEL).toContactFrame()
        assertEquals(CONTACT_FLOOD_PATH_SENTINEL, frame.outPathLength)
        assertTrue(frame.outPath.isEmpty())
    }

    // MARK: ContactFrame -> MeshContact

    @Test
    fun contactFrameConvertsToMeshContactWithHexId() {
        val frame = contactFrame(0x11, name = "TestNode").copy(flags = FAVORITE_FLAG, outPathLength = 2)
        val contact = frame.toMeshContact()
        assertEquals(publicKey(0x11).toUppercaseHex(), contact.id)
        assertTrue(contact.publicKey.contentEquals(publicKey(0x11)))
        assertEquals(FAVORITE_FLAG, contact.flags)
        assertEquals(2, contact.outPathLength)
        assertEquals("TestNode", contact.advertisedName)
    }

    @Test
    fun contactFrameHandlesAllContactTypeConversions() {
        assertEquals(ContactType.CHAT.raw, contactFrame(0, "c", ContactType.CHAT).toMeshContact().typeRawValue)
        assertEquals(ContactType.REPEATER.raw, contactFrame(0, "r", ContactType.REPEATER).toMeshContact().typeRawValue)
        assertEquals(ContactType.ROOM.raw, contactFrame(0, "m", ContactType.ROOM).toMeshContact().typeRawValue)
    }

    @Test
    fun roundTripMeshContactToFrameToMeshContact() {
        val original = meshContact(
            keyByte = 0x11,
            name = "OriginalNode",
            type = ContactType.REPEATER,
            flags = 0x05,
            outPathLength = 3,
            outPath = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()),
            latitude = 40.7128,
            longitude = -74.0060,
            lastAdvertisement = 1_700_000_000,
            lastModified = 1_700_000_200,
        )
        val roundTripped = original.toContactFrame().toMeshContact()
        assertEquals(original.id, roundTripped.id)
        assertTrue(original.publicKey.contentEquals(roundTripped.publicKey))
        assertEquals(original.typeRawValue, roundTripped.typeRawValue)
        assertEquals(original.flags, roundTripped.flags)
        assertEquals(original.outPathLength, roundTripped.outPathLength)
        assertTrue(original.outPath.contentEquals(roundTripped.outPath))
        assertEquals(original.advertisedName, roundTripped.advertisedName)
        assertEquals(original.lastAdvertisement, roundTripped.lastAdvertisement)
        assertEquals(original.latitude, roundTripped.latitude)
        assertEquals(original.lastModified, roundTripped.lastModified)
    }

    @Test
    fun roundTripFrameToMeshContactToFrame() {
        val original = ContactFrame(
            publicKey = TEST_KEY,
            type = ContactType.ROOM,
            flags = 0x03,
            outPathLength = 1,
            outPath = byteArrayOf(0xFF.toByte()),
            name = "OriginalRoom",
            lastAdvertTimestamp = 1_700_000_000,
            latitude = 51.5074,
            longitude = -0.1278,
            lastModified = 1_700_000_300,
        )
        assertEquals(original, original.toMeshContact().toContactFrame())
    }

    // MARK: Remove / clear

    @Test
    fun removeContactDeletesMessagesAndTriggersDeletedCleanup() = runTest {
        val store = InMemoryContactStore()
        val cleanup = RecordingCleanupHandler()
        val radioID = ContactTestIds.RADIO
        val contactID = ContactTestIds.fresh()
        store.saveContact(contactDto(id = contactID, radioID = radioID, keyByte = 0x01, unreadCount = 3))
        store.seedMessages(contactID, 3)

        val service = testContactService(store = store, cleanup = cleanup)
        service.removeContact(radioID, publicKey(0x01))

        assertEquals(listOf(contactID), store.deletedMessagesForContactIDs())
        assertNull(store.fetchContact(contactID))
        assertEquals(1, cleanup.invocations.size)
        assertEquals(contactID, cleanup.invocations[0].contactID)
        assertEquals(ContactCleanupReason.DELETED, cleanup.invocations[0].reason)
    }

    @Test
    fun clearContactMessagesZeroesBothCountersPreservingLastMessageDate() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val contactID = ContactTestIds.fresh()
        val lastMessageDate = 1_700_000_000.0
        store.saveContact(
            contactDto(
                id = contactID,
                radioID = radioID,
                unreadCount = 4,
                unreadMentionCount = 2,
                lastMessageDate = lastMessageDate,
            ),
        )
        store.seedMessages(contactID, 3)

        val service = testContactService(store = store)
        val events = service.events()
        service.clearContactMessages(contactID)

        assertEquals(ContactServiceEvent.MessagesCleared(contactID), events.tryReceive().getOrNull())
        assertEquals(listOf(contactID), store.deletedMessagesForContactIDs())
        assertEquals(0, store.remainingMessageCount(contactID))
        val updated = store.fetchContact(contactID)
        // The conversation stays listed: lastMessageDate preserved, both unread counters zeroed.
        assertEquals(lastMessageDate, updated?.lastMessageDate)
        assertEquals(0, updated?.unreadCount)
        assertEquals(0, updated?.unreadMentionCount)
    }

    @Test
    fun clearContactMessagesRunsClearedCleanup() = runTest {
        val store = InMemoryContactStore()
        val cleanup = RecordingCleanupHandler()
        val contactID = ContactTestIds.fresh()
        store.saveContact(contactDto(id = contactID, radioID = ContactTestIds.RADIO))

        val service = testContactService(store = store, cleanup = cleanup)
        service.clearContactMessages(contactID)

        assertEquals(1, cleanup.invocations.size)
        assertEquals(contactID, cleanup.invocations[0].contactID)
        assertEquals(ContactCleanupReason.CLEARED, cleanup.invocations[0].reason)
    }

    // MARK: Preferences

    @Test
    fun updateContactPreferencesClearsUnreadAndRunsBlockedCleanupWhenBlocking() = runTest {
        val store = InMemoryContactStore()
        val cleanup = RecordingCleanupHandler()
        val contactID = ContactTestIds.fresh()
        store.saveContact(contactDto(id = contactID, radioID = ContactTestIds.RADIO, unreadCount = 5))

        val service = testContactService(store = store, cleanup = cleanup)
        service.updateContactPreferences(contactID, isBlocked = true)

        val updated = store.fetchContact(contactID)
        assertEquals(0, updated?.unreadCount)
        assertTrue(updated?.isBlocked == true)
        assertEquals(1, cleanup.invocations.size)
        assertEquals(ContactCleanupReason.BLOCKED, cleanup.invocations[0].reason)
    }

    @Test
    fun updateContactPreferencesDoesNotRunCleanupWhenNotBlocking() = runTest {
        val store = InMemoryContactStore()
        val cleanup = RecordingCleanupHandler()
        val contactID = ContactTestIds.fresh()
        store.saveContact(contactDto(id = contactID, radioID = ContactTestIds.RADIO, unreadCount = 5))

        val service = testContactService(store = store, cleanup = cleanup)
        service.updateContactPreferences(contactID, nickname = "NewNickname")

        val updated = store.fetchContact(contactID)
        assertEquals(5, updated?.unreadCount)
        assertEquals("NewNickname", updated?.nickname)
        assertTrue(cleanup.invocations.isEmpty())
    }

    @Test
    fun updateContactPreferencesPreservesFieldsWhenBlocking() = runTest {
        val store = InMemoryContactStore()
        val contactID = ContactTestIds.fresh()
        store.saveContact(
            contactDto(
                id = contactID,
                radioID = ContactTestIds.RADIO,
                nickname = "MyNickname",
                isFavorite = true,
                lastMessageDate = 1.0,
                unreadCount = 5,
                ocvPreset = "medium",
                customOCVArrayString = "custom",
            ),
        )

        val service = testContactService(store = store)
        service.updateContactPreferences(contactID, isBlocked = true)

        val updated = store.fetchContact(contactID)
        assertEquals("MyNickname", updated?.nickname)
        assertTrue(updated?.isFavorite == true)
        assertEquals("medium", updated?.ocvPreset)
        assertEquals("custom", updated?.customOCVArrayString)
        assertEquals(0, updated?.unreadCount)
        assertTrue(updated?.isBlocked == true)
    }

    @Test
    fun unblockingRunsUnblockedCleanup() = runTest {
        val store = InMemoryContactStore()
        val cleanup = RecordingCleanupHandler()
        val contactID = ContactTestIds.fresh()
        store.saveContact(contactDto(id = contactID, radioID = ContactTestIds.RADIO, isBlocked = true))

        val service = testContactService(store = store, cleanup = cleanup)
        service.updateContactPreferences(contactID, isBlocked = false)

        assertFalse(store.fetchContact(contactID)?.isBlocked == true)
        assertEquals(1, cleanup.invocations.size)
        assertEquals(ContactCleanupReason.UNBLOCKED, cleanup.invocations[0].reason)
    }

    @Test
    fun updateContactPreferencesClearsNicknameOnEmptyOrWhitespaceAndTrimsOtherwise() = runTest {
        val store = InMemoryContactStore()
        val service = testContactService(store = store)

        val emptyId = ContactTestIds.fresh()
        store.saveContact(contactDto(id = emptyId, radioID = ContactTestIds.RADIO, nickname = "Old"))
        service.updateContactPreferences(emptyId, nickname = "")
        assertNull(store.fetchContact(emptyId)?.nickname)

        val wsId = ContactTestIds.fresh()
        store.saveContact(contactDto(id = wsId, radioID = ContactTestIds.RADIO, nickname = "Old"))
        service.updateContactPreferences(wsId, nickname = "   ")
        assertNull(store.fetchContact(wsId)?.nickname)

        val trimId = ContactTestIds.fresh()
        store.saveContact(contactDto(id = trimId, radioID = ContactTestIds.RADIO))
        service.updateContactPreferences(trimId, nickname = "  Rico  ")
        assertEquals("Rico", store.fetchContact(trimId)?.nickname)
    }

    @Test
    fun updateContactPreferencesKeepsNicknameAndUnrelatedFieldsWhenNicknameArgIsNull() = runTest {
        val store = InMemoryContactStore()
        val contactID = ContactTestIds.fresh()
        store.saveContact(
            contactDto(
                id = contactID,
                radioID = ContactTestIds.RADIO,
                nickname = "KeepMe",
                isMuted = true,
                unreadMentionCount = 5,
            ),
        )

        val service = testContactService(store = store)
        service.updateContactPreferences(contactID, isFavorite = true)

        val updated = store.fetchContact(contactID)
        assertEquals("KeepMe", updated?.nickname)
        assertTrue(updated?.isFavorite == true)
        // A preferences edit must not silently reset unrelated persisted fields.
        assertTrue(updated?.isMuted == true)
        assertEquals(5, updated?.unreadMentionCount)
    }

    @Test
    fun updateContactPreferencesNotifiesContactsChanged() = runTest {
        val store = InMemoryContactStore()
        val sync = RecordingContactSyncCoordinator()
        val contactID = ContactTestIds.fresh()
        store.saveContact(contactDto(id = contactID, radioID = ContactTestIds.RADIO, nickname = "Old"))
        val service = testContactService(store = store, syncCoordinator = sync)

        service.updateContactPreferences(contactID, nickname = "New")

        assertEquals(1, sync.contactsChanged)
    }

    @Test
    fun updateContactAvatarNotifiesContactsChanged() = runTest {
        val store = InMemoryContactStore()
        val sync = RecordingContactSyncCoordinator()
        val contactID = ContactTestIds.fresh()
        store.saveContact(contactDto(id = contactID, radioID = ContactTestIds.RADIO))
        val service = testContactService(store = store, syncCoordinator = sync)

        service.updateContactAvatar(contactID, imageData = byteArrayOf(0x01, 0x02))

        assertEquals(1, sync.contactsChanged)
        assertContentEquals(byteArrayOf(0x01, 0x02), store.fetchContact(contactID)?.avatarImageData)
    }

    // MARK: Reset path

    @Test
    fun resetPathFloodRoutesWhilePreservingUnmodeledTypeByte() = runTest {
        val store = InMemoryContactStore()
        val session = FakeContactSession()
        val radioID = ContactTestIds.RADIO
        val unmodeledType = 0x7F
        store.saveContact(
            contactDto(
                id = ContactTestIds.fresh(),
                radioID = radioID,
                keyByte = 0x01,
                typeRawValue = unmodeledType,
                outPathLength = 2,
                outPath = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
            ),
        )

        val service = testContactService(session = session, store = store)
        service.resetPath(radioID, publicKey(0x01))

        assertEquals(1, session.resetPathPublicKeys.size)
        assertTrue(session.resetPathPublicKeys[0].contentEquals(publicKey(0x01)))
        val reset = store.fetchContact(radioID, publicKey(0x01))
        assertEquals(CONTACT_FLOOD_PATH_SENTINEL, reset?.outPathLength)
        assertEquals(unmodeledType, reset?.typeRawValue)
    }

    // MARK: Add / share / device-error mapping

    @Test
    fun addOrUpdateContactRoundTripsThroughSessionAndStore() = runTest {
        val store = InMemoryContactStore()
        val session = FakeContactSession()
        val radioID = ContactTestIds.RADIO
        val service = testContactService(session = session, store = store)

        service.addOrUpdateContact(radioID, contactFrame(0x01, name = "Added"))

        assertEquals(1, session.addedContacts.size)
        val stored = store.fetchContact(radioID, publicKey(0x01))
        assertEquals("Added", stored?.name)
    }

    @Test
    fun addContactTableFullMapsToContactTableFull() = runTest {
        val session = FakeContactSession().apply { addContactError = ContactProtocolErrorCode.TABLE_FULL }
        val service = testContactService(session = session)
        assertFailsWith<ContactServiceError.ContactTableFull> {
            service.addOrUpdateContact(ContactTestIds.RADIO, contactFrame(0x01, name = "x"))
        }
    }

    @Test
    fun removeContactNotFoundMapsToContactNotFound() = runTest {
        val session = FakeContactSession().apply { removeContactError = ContactProtocolErrorCode.NOT_FOUND }
        val service = testContactService(session = session)
        assertFailsWith<ContactServiceError.ContactNotFound> {
            service.removeContact(ContactTestIds.RADIO, publicKey(0x01))
        }
    }

    @Test
    fun shareContactTableFullMapsToShareUnavailable() = runTest {
        val session = FakeContactSession().apply { shareContactError = ContactProtocolErrorCode.TABLE_FULL }
        val service = testContactService(session = session)
        assertFailsWith<ContactServiceError.ShareContactUnavailable> {
            service.shareContact(publicKey(0x01))
        }
    }

    @Test
    fun markHeardDualWritesDiscoverWhenTheContactRowExists() = runTest {
        val contacts = InMemoryContactStore()
        val discovered = InMemoryDiscoveredNodeStore()
        val key = publicKey(0x42)
        contacts.saveContact(
            contactDto(
                id = ContactTestIds.fresh(),
                radioID = ContactTestIds.RADIO,
                keyByte = 0x42,
                name = "Hilltop",
            ),
        )
        val service = testContactService(store = contacts, discoveredNodes = discovered)
        service.markHeard(ContactTestIds.RADIO, key)
        val row = discovered.list(ContactTestIds.RADIO).single()
        assertEquals("Hilltop", row.name)
        assertEquals(CONTACT_TEST_NOW_SECONDS, row.lastHeardEpochSeconds)
        assertTrue(row.publicKey.contentEquals(key))
    }

    @Test
    fun sendPathDiscoveryReturnsFirmwareTimeoutAndNotFoundMaps() = runTest {
        val session = FakeContactSession()
        val service = testContactService(session = session)
        assertEquals(5000, service.sendPathDiscovery(publicKey(0x01)).suggestedTimeoutMs)

        session.pathDiscoveryError = ContactProtocolErrorCode.NOT_FOUND
        assertFailsWith<ContactServiceError.ContactNotFound> {
            service.sendPathDiscovery(publicKey(0x01))
        }
    }

    @Test
    fun refreshPathFromDevicePersistsDeviceOutPath() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x22)
        store.saveContact(
            radioID,
            contactFrame(0x22, name = "Routed").copy(
                outPathLength = 255,
                outPath = ByteArray(0),
            ),
        )
        val devicePath = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(
                keyByte = 0x22,
                name = "Routed",
                outPathLength = 2,
                outPath = devicePath,
            )
        }
        val service = testContactService(session = session, store = store)

        assertTrue(service.refreshPathFromDevice(radioID, key))
        val updated = store.fetchContact(radioID, key)!!
        assertEquals(2, updated.outPathLength)
        assertTrue(updated.outPath.contentEquals(devicePath))
        assertEquals(1, session.getContactKeys.size)
    }

    @Test
    fun applyPathDiscoveryResponseMatchesPrefixAndWritesOutPath() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x33)
        store.saveContact(
            radioID,
            contactFrame(0x33, name = "Far").copy(
                lastModified = 1_234L,
                outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
                outPath = byteArrayOf(0xAB.toByte()),
                typeRawValue = UNMODELED_CONTACT_TYPE,
            ),
        )
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)
        val events = service.events()
        val outPath = byteArrayOf(0x10, 0x20, 0x30)
        val path = one.meshcore.shared.meshcore.PathInfo(
            publicKeyPrefix = key.copyOfRange(0, 6),
            outPathLength = 3,
            outPath = outPath,
            inPathLength = 0,
            inPath = ByteArray(0),
        )

        assertTrue(service.applyPathDiscoveryResponse(radioID, path))
        val updated = store.fetchContact(radioID, key)!!
        assertEquals(3, updated.outPathLength)
        assertTrue(updated.outPath.contentEquals(outPath))
        assertEquals(CONTACT_TEST_NOW_SECONDS, updated.lastModified)
        assertEquals(CONTACT_TEST_NOW_SECONDS, updated.lastHeardTimestamp)
        assertEquals(UNMODELED_CONTACT_TYPE, updated.typeRawValue)
        val written = session.addedContacts.single()
        assertEquals(3, written.outPathLength)
        assertTrue(written.outPath.contentEquals(outPath))
        assertEquals(UNMODELED_CONTACT_TYPE, written.typeRawValue)
        assertTrue(events.tryReceive().getOrNull() is ContactServiceEvent.PathDiscoveryAccepted)
    }

    @Test
    fun applyPathDiscoveryResponseKeepsThePathWhenLastHeardStampFails() = runTest {
        val inner = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x33)
        inner.saveContact(
            radioID,
            contactFrame(0x33, name = "Far").copy(lastModified = 1_234L),
        )
        inner.saveContact(inner.fetchContact(radioID, key)!!.copy(lastHeardTimestamp = 1L))
        val store = FlakyTouchContactStore(inner, throwOnFirstTouches = 1)
        val service = testContactService(store = store)
        val outPath = byteArrayOf(0x10, 0x20, 0x30)
        val path = one.meshcore.shared.meshcore.PathInfo(
            publicKeyPrefix = key.copyOfRange(0, 6),
            outPathLength = 3,
            outPath = outPath,
            inPathLength = 0,
            inPath = ByteArray(0),
        )

        assertTrue(service.applyPathDiscoveryResponse(radioID, path))
        val updated = inner.fetchContact(radioID, key)!!
        assertEquals(3, updated.outPathLength)
        assertTrue(updated.outPath.contentEquals(outPath))
        assertEquals(1L, updated.lastHeardTimestamp)
        assertEquals(1, store.touchCalls)
    }

    @Test
    fun applyPathDiscoveryResponseLeavesTheStoredPathWhenTheRadioRejects() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x33)
        val storedPath = byteArrayOf(0xAB.toByte())
        store.saveContact(
            radioID,
            contactFrame(0x33, name = "Far").copy(
                outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
                outPath = storedPath,
            ),
        )
        val session = FakeContactSession().apply { addContactError = ContactProtocolErrorCode.TABLE_FULL }
        val service = testContactService(session = session, store = store)
        val events = service.events()
        val path = one.meshcore.shared.meshcore.PathInfo(
            publicKeyPrefix = key.copyOfRange(0, 6),
            outPathLength = 3,
            outPath = byteArrayOf(0x10, 0x20, 0x30),
            inPathLength = 0,
            inPath = ByteArray(0),
        )

        assertFalse(service.applyPathDiscoveryResponse(radioID, path))
        val updated = store.fetchContact(radioID, key)!!
        assertEquals(CONTACT_FLOOD_PATH_SENTINEL, updated.outPathLength)
        assertTrue(updated.outPath.contentEquals(storedPath))
        assertTrue(session.addedContacts.isEmpty())
        assertTrue(events.tryReceive().getOrNull() is ContactServiceEvent.PathDiscoveryWriteFailed)
    }

    @Test
    fun applyPathDiscoveryResponseWritesAZeroHopPathAsDirect() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x34)
        store.saveContact(
            radioID,
            contactFrame(0x34, name = "Near").copy(
                outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
                outPath = byteArrayOf(0xAB.toByte()),
            ),
        )
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)
        val path = one.meshcore.shared.meshcore.PathInfo(
            publicKeyPrefix = key.copyOfRange(0, 6),
            outPathLength = 0,
            outPath = ByteArray(0),
            inPathLength = 0,
            inPath = ByteArray(0),
        )

        assertTrue(service.applyPathDiscoveryResponse(radioID, path))
        val updated = store.fetchContact(radioID, key)!!
        assertEquals(0, updated.outPathLength)
        assertTrue(updated.outPath.isEmpty())
        assertEquals(0, session.addedContacts.single().outPathLength)
    }

    @Test
    fun applyPathDiscoveryResponseDoesNotWriteAReservedLengthByte() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x35)
        val storedPath = byteArrayOf(0xAB.toByte())
        store.saveContact(
            radioID,
            contactFrame(0x35, name = "Reserved").copy(
                outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
                outPath = storedPath,
            ),
        )
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)
        val path = one.meshcore.shared.meshcore.PathInfo(
            publicKeyPrefix = key.copyOfRange(0, 6),
            outPathLength = RESERVED_PATH_LENGTH,
            outPath = ByteArray(0),
            inPathLength = 0,
            inPath = ByteArray(0),
        )

        assertTrue(service.applyPathDiscoveryResponse(radioID, path))
        val updated = store.fetchContact(radioID, key)!!
        assertEquals(CONTACT_FLOOD_PATH_SENTINEL, updated.outPathLength)
        assertTrue(updated.outPath.contentEquals(storedPath))
        assertTrue(session.addedContacts.isEmpty())
    }

    @Test
    fun markHeardStampsPhoneClockWithoutRewritingRadioTimestamps() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x34)
        store.saveContact(
            radioID,
            contactFrame(0x34, name = "Pinged").copy(lastAdvertTimestamp = 50, lastModified = 50),
        )
        val service = testContactService(store = store)
        service.markHeard(radioID, key)
        val updated = store.fetchContact(radioID, key)!!
        assertEquals(CONTACT_TEST_NOW_SECONDS, updated.lastHeardTimestamp)
        assertEquals(50, updated.lastModified)
        assertEquals(50, updated.lastAdvertTimestamp)
    }

    @Test
    fun markHeardDoesNotThrowWhenLastHeardStampFails() = runTest {
        val inner = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x34)
        inner.saveContact(
            radioID,
            contactFrame(0x34, name = "Pinged").copy(lastAdvertTimestamp = 50, lastModified = 50),
        )
        inner.saveContact(inner.fetchContact(radioID, key)!!.copy(lastHeardTimestamp = 1L))
        val store = FlakyTouchContactStore(inner, throwOnFirstTouches = 1)
        val service = testContactService(store = store)
        service.markHeard(radioID, key)
        assertEquals(1, store.touchCalls)
        assertEquals(1L, inner.fetchContact(radioID, key)?.lastHeardTimestamp)
    }

    @Test
    fun refreshPathFromDeviceReturnsFalseWhenDeviceHasNoContact() = runTest {
        val service = testContactService()
        assertTrue(!service.refreshPathFromDevice(ContactTestIds.RADIO, publicKey(0x99)))
    }

    // MARK: Device 0x80 advertisement (auto-add; known hear does not rewrite radio timestamps)

    @Test
    fun handleAdvertisementLeavesRadioTimestampsOnKnownContactWithoutDeviceFetch() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x28)
        store.saveContact(
            radioID,
            contactFrame(0x28, name = "Known").copy(lastAdvertTimestamp = 1_000L, lastModified = 1_000L),
        )
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)

        assertTrue(service.handleAdvertisement(radioID, key))
        val updated = store.fetchContact(radioID, key)!!
        assertEquals(1_000L, updated.lastAdvertTimestamp)
        assertEquals(1_000L, updated.lastModified)
        assertEquals(CONTACT_TEST_NOW_SECONDS, updated.lastHeardTimestamp)
        assertEquals("Known", updated.name)
        assertTrue(session.getContactKeys.isEmpty())
    }

    @Test
    fun handleAdvertisementAutoAddsUnknownContactFromDevice() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x29)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(
                keyByte = 0x29,
                name = "AutoAdded",
                type = ContactType.REPEATER,
                lastAdvertisement = 1_700_000_050L,
            )
        }
        val service = testContactService(session = session, store = store)

        assertTrue(service.handleAdvertisement(radioID, key))
        val saved = store.fetchContact(radioID, key)!!
        assertEquals("AutoAdded", saved.name)
        assertEquals(ContactType.REPEATER.raw, saved.typeRawValue)
        assertEquals(CONTACT_TEST_NOW_SECONDS, saved.lastHeardTimestamp)
        assertEquals(1, session.getContactKeys.size)
        assertTrue(session.getContactKeys.single().contentEquals(key))
    }

    @Test
    fun handleAdvertisementAutoAddKeepsTheContactWhenLastHeardStampFails() = runTest {
        val inner = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x2B)
        val store = FlakyTouchContactStore(inner, throwOnFirstTouches = 1)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x2B, name = "AutoAdded")
        }
        val service = testContactService(session = session, store = store)

        assertTrue(service.handleAdvertisement(radioID, key))
        val saved = inner.fetchContact(radioID, key)
        assertEquals("AutoAdded", saved?.name)
        assertNull(saved?.lastHeardTimestamp)
        assertEquals(1, store.touchCalls)
    }

    @Test
    fun handleAdvertisementNotifiesDiscoveryOnlyForAnUnknownContact() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val discoveryNotifier = RecordingContactDiscoveryNotifier()

        // Known contact: stamp-only path never notifies.
        val knownKey = publicKey(0x28)
        store.saveContact(radioID, contactFrame(0x28, name = "Known"))
        val known = testContactService(store = store, discoveryNotifier = discoveryNotifier)
        assertTrue(known.handleAdvertisement(radioID, knownKey))
        assertTrue(discoveryNotifier.notified.isEmpty())

        // Unknown contact: auto-add path notifies once, with the fresh contact's id/name/type.
        val newKey = publicKey(0x29)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x29, name = "AutoAdded", type = ContactType.REPEATER)
        }
        val service = testContactService(session = session, store = store, discoveryNotifier = discoveryNotifier)
        assertTrue(service.handleAdvertisement(radioID, newKey))

        val notified = discoveryNotifier.notified.single()
        assertEquals("AutoAdded", notified.contactName)
        assertEquals(ContactType.REPEATER, notified.contactType)
        assertEquals(store.fetchContact(radioID, newKey)!!.id, notified.contactID)
    }

    /**
     * The deferred drain re-fetches a key that was unknown when the advert arrived. If the bulk sync
     * added that contact meanwhile, the drain is a refresh, not a discovery, and must stay quiet.
     */
    @Test
    fun theDeferredDrainDoesNotNotifyForAContactTheSyncAlreadyAdded() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x2F)
        val discoveryNotifier = RecordingContactDiscoveryNotifier()
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x2F, name = "Drained")
        }
        val service = testContactService(session = session, store = store, discoveryNotifier = discoveryNotifier)

        service.setSyncingContacts(radioID, isSyncing = true)
        assertFalse(service.handleAdvertisement(radioID, key))
        // The bulk sync lands the same contact while the advert is parked.
        store.saveContact(radioID, contactFrame(0x2F, name = "FromSync"))
        service.setSyncingContacts(radioID, isSyncing = false)

        assertEquals("Drained", store.fetchContact(radioID, key)!!.name)
        assertTrue(discoveryNotifier.notified.isEmpty())
    }

    @Test
    fun handleAdvertisementReturnsFalseWhenUnknownAndDeviceHasNoContact() = runTest {
        val store = InMemoryContactStore()
        val service = testContactService(store = store)
        assertFalse(service.handleAdvertisement(ContactTestIds.RADIO, publicKey(0x2A)))
        assertTrue(store.fetchContacts(ContactTestIds.RADIO).isEmpty())
    }

    @Test
    fun handleAdvertisementDefersUnknownFetchWhileSyncingContacts() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x2B)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x2B, name = "Deferred")
        }
        val service = testContactService(session = session, store = store)

        service.setSyncingContacts(radioID, isSyncing = true)
        assertFalse(service.handleAdvertisement(radioID, key))
        assertTrue(session.getContactKeys.isEmpty())
        assertNull(store.fetchContact(radioID, key))

        service.setSyncingContacts(radioID, isSyncing = false)
        assertEquals(1, session.getContactKeys.size)
        assertTrue(session.getContactKeys.single().contentEquals(key))
        assertEquals("Deferred", store.fetchContact(radioID, key)!!.name)
    }

    @Test
    fun handleAdvertisementLeavesRadioTimestampsOnKnownContactWhileSyncing() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x2C)
        store.saveContact(
            radioID,
            contactFrame(0x2C, name = "KnownSync").copy(lastAdvertTimestamp = 1_000L, lastModified = 1_000L),
        )
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)

        service.setSyncingContacts(radioID, isSyncing = true)
        assertTrue(service.handleAdvertisement(radioID, key))
        assertTrue(session.getContactKeys.isEmpty())
        assertEquals(1_000L, store.fetchContact(radioID, key)!!.lastAdvertTimestamp)
        assertEquals(1_000L, store.fetchContact(radioID, key)!!.lastModified)
        assertEquals(CONTACT_TEST_NOW_SECONDS, store.fetchContact(radioID, key)!!.lastHeardTimestamp)
        service.setSyncingContacts(radioID, isSyncing = false)
        assertTrue(session.getContactKeys.isEmpty())
    }

    @Test
    fun syncContactsDefersMidSyncAdvertAndDrainsAfterBulkPull() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val bulkKey = publicKey(0x2D)
        val advertKey = publicKey(0x2E)
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)
        session.stubbedContacts = listOf(meshContact(keyByte = 0x2D, name = "Bulk"))
        session.singleContactLookup = meshContact(keyByte = 0x2E, name = "FromAdvert")
        session.onGetContacts = {
            // Collector fires 0x80 while getContacts is in flight — must not call getContact yet.
            assertFalse(service.handleAdvertisement(radioID, advertKey))
            assertTrue(session.getContactKeys.isEmpty())
            assertNull(store.fetchContact(radioID, advertKey))
        }

        service.syncContacts(radioID, since = null)

        assertEquals("Bulk", store.fetchContact(radioID, bulkKey)!!.name)
        assertEquals("FromAdvert", store.fetchContact(radioID, advertKey)!!.name)
        assertEquals(1, session.getContactKeys.size)
        assertTrue(session.getContactKeys.single().contentEquals(advertKey))
    }

    @Test
    fun nestedSetSyncingContactsDoesNotDrainUntilTheOuterHoldReleases() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x31)
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(meshContact(keyByte = 0x30, name = "Bulk"))
            singleContactLookup = meshContact(keyByte = 0x31, name = "FromAdvert")
        }
        val service = testContactService(session = session, store = store)
        session.onGetContacts = {
            assertFalse(service.handleAdvertisement(radioID, key))
            assertTrue(session.getContactKeys.isEmpty())
        }

        service.setSyncingContacts(radioID, isSyncing = true)
        service.syncContacts(radioID, since = null)
        assertTrue(
            session.getContactKeys.isEmpty(),
            "inner GET_CONTACTS finally must not release a connect-window hold",
        )
        assertNull(store.fetchContact(radioID, key))
        assertTrue(service.isSyncingContacts)

        service.setSyncingContacts(radioID, isSyncing = false)
        assertEquals(1, session.getContactKeys.size)
        assertTrue(session.getContactKeys.single().contentEquals(key))
        assertEquals("FromAdvert", store.fetchContact(radioID, key)!!.name)
        assertFalse(service.isSyncingContacts)
    }

    // MARK: Device overwrite-oldest / contacts-full (0x8F / 0x90)

    @Test
    fun handleDeviceContactDeletedRemovesLocalRowAndMessages() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x44)
        val contactID = ContactTestIds.fresh()
        store.saveContact(contactDto(id = contactID, radioID = radioID, keyByte = 0x44, name = "Victim"))
        val cleanup = RecordingCleanupHandler()
        val service = testContactService(store = store, cleanup = cleanup)

        assertTrue(service.handleDeviceContactDeleted(radioID, key))
        assertNull(store.fetchContact(radioID, key))
        assertEquals(listOf(contactID), store.deletedMessagesForContactIDs())
        assertEquals(1, cleanup.invocations.size)
        assertEquals(contactID, cleanup.invocations[0].contactID)
        assertEquals(ContactCleanupReason.DELETED, cleanup.invocations[0].reason)
    }

    @Test
    fun handleDeviceContactDeletedReturnsFalseWhenNoLocalRow() = runTest {
        val service = testContactService()
        assertFalse(service.handleDeviceContactDeleted(ContactTestIds.RADIO, publicKey(0x55)))
    }

    @Test
    fun refreshPathFromDeviceDefersGetContactWhileSyncing() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x81)
        store.saveContact(radioID, contactFrame(0x81, name = "Routed"))
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x81, name = "Routed")
        }
        val service = testContactService(session = session, store = store)

        service.setSyncingContacts(radioID, isSyncing = true)
        assertFalse(service.refreshPathFromDevice(radioID, key))
        assertTrue(session.getContactKeys.isEmpty())

        service.setSyncingContacts(radioID, isSyncing = false)
        assertEquals(1, session.getContactKeys.size)
        assertTrue(session.getContactKeys.single().contentEquals(key))
    }

    @Test
    fun handleDeviceContactDeletedDropsAQueuedUnknownFetch() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x8F)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x8F, name = "Resurrected")
        }
        val service = testContactService(session = session, store = store)

        service.setSyncingContacts(radioID, isSyncing = true)
        assertFalse(service.handleAdvertisement(radioID, key))
        assertFalse(service.handleDeviceContactDeleted(radioID, key))
        service.setSyncingContacts(radioID, isSyncing = false)

        assertTrue(session.getContactKeys.isEmpty())
        assertNull(store.fetchContact(radioID, key))
    }

    @Test
    fun handleDeviceContactDeletedSuppressesAnInFlightUnknownFetch() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x90)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x90, name = "Late")
            onGetContact = { gate.await() }
        }
        val service = testContactService(session = session, store = store)

        val fetch = async { service.handleAdvertisement(radioID, key) }
        while (session.getContactKeys.isEmpty()) {
            yield()
        }
        assertFalse(service.handleDeviceContactDeleted(radioID, key))
        gate.complete(Unit)
        assertFalse(fetch.await())
        assertNull(store.fetchContact(radioID, key))
    }

    @Test
    fun notifyNodeStorageFullYieldsNodeStorageFullChanged() = runTest {
        val service = testContactService()
        val events = service.events()
        service.notifyNodeStorageFull()
        val event = events.receive()
        assertTrue(event is ContactServiceEvent.NodeStorageFullChanged)
        assertTrue((event as ContactServiceEvent.NodeStorageFullChanged).isFull)
    }

    @Test
    fun aLateStorageFullReadSeesTheCurrentFlag() = runTest {
        val service = testContactService()
        service.notifyNodeStorageFull()
        assertTrue(service.isNodeStorageFull.value)
    }

    @Test
    fun clearNodeStorageFullDropsTheFlag() = runTest {
        val service = testContactService()
        service.notifyNodeStorageFull()
        service.clearNodeStorageFull()
        assertFalse(service.isNodeStorageFull.value)
    }

    @Test
    fun aLocalRemoveClearsTheStorageFullFlag() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x11)
        store.saveContact(radioID, contactFrame(0x11, name = "Full"))
        val service = testContactService(store = store)
        service.notifyNodeStorageFull()
        val contact = store.fetchContact(radioID, key) ?: error("seeded contact missing")
        service.removeLocalContact(contact.id, key)
        assertFalse(service.isNodeStorageFull.value)
    }

    // MARK: Favorite bit / device flags

    @Test
    fun favoriteBitIsPreservedWhenTheRadioReReportsTheContact() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        // Seed a favorite contact (flags bit 0 set).
        store.saveContact(radioID, contactFrame(0x01, name = "Fav").copy(flags = FAVORITE_FLAG))

        // The radio re-reports it without the favorite bit but with a telemetry bit set.
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(meshContact(0x01, name = "Fav", flags = TELEMETRY_BASE_FLAG))
        }
        val service = testContactService(session = session, store = store)
        service.syncContacts(radioID, since = null)

        val updated = store.fetchContact(radioID, publicKey(0x01))
        // Bit 0 kept from local, bits 1-7 taken from the frame.
        assertEquals(FAVORITE_FLAG or TELEMETRY_BASE_FLAG, updated?.flags)
        assertTrue(updated?.isFavorite == true)
    }

    @Test
    fun setContactFavoriteWritesDeviceFlagsAndPersists() = runTest {
        val store = InMemoryContactStore()
        val session = FakeContactSession()
        val contactID = ContactTestIds.fresh()
        store.saveContact(contactDto(id = contactID, radioID = ContactTestIds.RADIO, flags = 0, isFavorite = false))

        val service = testContactService(session = session, store = store)
        service.setContactFavorite(contactID, isFavorite = true)

        assertEquals(1, session.changeFlagsCalls.size)
        assertEquals(FAVORITE_FLAG, session.changeFlagsCalls[0].second)
        val updated = store.fetchContact(contactID)
        assertEquals(FAVORITE_FLAG, updated?.flags)
        assertTrue(updated?.isFavorite == true)
    }

    @Test
    fun setTelemetryPermissionsPreservesFavoriteBit() = runTest {
        val store = InMemoryContactStore()
        val session = FakeContactSession()
        val contactID = ContactTestIds.fresh()
        store.saveContact(contactDto(id = contactID, radioID = ContactTestIds.RADIO, flags = FAVORITE_FLAG))

        val service = testContactService(session = session, store = store)
        service.setTelemetryPermissions(contactID, granted = true)

        // Bits 1-3 set, favorite bit 0 preserved.
        assertEquals(FAVORITE_FLAG or 0x0E, store.fetchContact(contactID)?.flags)
    }

    // MARK: Identity invariant

    @Test
    fun syncUpsertsByPublicKeySoResyncUpdatesTheSameRow() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)

        session.stubbedContacts = listOf(meshContact(0xAA, name = "Alice"))
        service.syncContacts(radioID, since = null)
        session.stubbedContacts = listOf(meshContact(0xAA, name = "Alice2"))
        service.syncContacts(radioID, since = null)

        val stored = store.fetchContacts(radioID)
        assertEquals(1, stored.size)
        assertEquals("Alice2", stored[0].name)
    }

    @Test
    fun addContactPersistsUnderInjectedRadioIdNeverMintingOne() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val otherRadio = ContactTestIds.fresh()
        val service = testContactService(store = store)

        service.addOrUpdateContact(radioID, contactFrame(0x01, name = "Added"))

        // The row lives under exactly the radioID the caller passed, not a freshly minted one.
        assertEquals(radioID, store.fetchContact(radioID, publicKey(0x01))?.radioID)
        assertTrue(store.fetchContacts(otherRadio).isEmpty())
    }

    // MARK: Static URI

    @Test
    fun exportContactURIDelegatesToTheWireProducer() {
        val uri = ContactService.exportContactURI("Node", TEST_KEY, ContactType.CHAT)
        assertEquals(ContactCardUri.export("Node", TEST_KEY, ContactType.CHAT), uri)
        assertTrue(uri.startsWith("meshcore://contact/add?name=Node"))
    }
}
