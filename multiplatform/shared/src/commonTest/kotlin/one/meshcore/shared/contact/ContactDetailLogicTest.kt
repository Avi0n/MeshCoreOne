package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactDetailLogicTest {
    @Test
    fun everyRadioActionIsDisabledWhileTheRadioIsNotReady() {
        val enablement = ContactDetailLogic.actionEnablement(
            isRadioReady = false,
            busy = ContactDetailLogic.ActionBusyState(),
        )
        assertFalse(enablement.sendMessage)
        assertFalse(enablement.joinRoom)
        assertFalse(enablement.telemetry)
        assertFalse(enablement.management)
        assertFalse(enablement.ping)
        assertFalse(enablement.shareViaAdvert)
        assertFalse(enablement.discoverPath)
        assertFalse(enablement.editPath)
        assertFalse(enablement.resetPath)
        assertFalse(enablement.favorite)
        assertFalse(enablement.block)
        assertFalse(enablement.delete)
    }

    @Test
    fun aReadyRadioEnablesEveryActionThatIsNotBusy() {
        val enablement = ContactDetailLogic.actionEnablement(
            isRadioReady = true,
            busy = ContactDetailLogic.ActionBusyState(),
        )
        assertTrue(enablement.sendMessage)
        assertTrue(enablement.joinRoom)
        assertTrue(enablement.telemetry)
        assertTrue(enablement.management)
        assertTrue(enablement.ping)
        assertTrue(enablement.shareViaAdvert)
        assertTrue(enablement.discoverPath)
        assertTrue(enablement.editPath)
        assertTrue(enablement.resetPath)
        assertTrue(enablement.favorite)
        assertTrue(enablement.block)
        assertTrue(enablement.delete)
    }

    @Test
    fun inFlightWorkStillDisablesItsOwnActionOnAReadyRadio() {
        val busy = ContactDetailLogic.actionEnablement(
            isRadioReady = true,
            busy = ContactDetailLogic.ActionBusyState(
                isPinging = true,
                pathBusy = true,
                isDiscoveringPath = true,
                isFloodRouted = true,
                shareAdvertBusy = true,
            ),
        )
        assertFalse(busy.ping)
        assertFalse(busy.discoverPath)
        assertFalse(busy.resetPath)
        assertFalse(busy.shareViaAdvert)
        assertTrue(busy.delete)
        // A flood-routed contact has nothing to reset, but the path is still editable.
        assertTrue(busy.editPath)
    }

    @Test
    fun aPersistFaultOnReloadKeepsTheOpenContact() = runTest {
        val open = fixture(lat = 45.0, lon = -93.0)
        val next = ContactDetailLogic.reloadContact(open) { error("disk full") }
        assertEquals(open, next.contact)
        assertFalse(next.loadFailed)
    }

    @Test
    fun aPersistFaultOnTheFirstReloadMarksLoadFailed() = runTest {
        val next = ContactDetailLogic.reloadContact(current = null) { error("disk full") }
        assertNull(next.contact)
        assertTrue(next.loadFailed)
    }

    @Test
    fun aCancelledReloadPropagatesWithoutKeepingTheOpenContact() = runTest {
        val open = fixture(lat = 45.0, lon = -93.0)
        assertFailsWith<CancellationException> {
            ContactDetailLogic.reloadContact(open) { throw CancellationException("superseded") }
        }
    }

    @Test
    fun aReloadErrorPropagatesWithoutKeepingTheOpenContact() = runTest {
        val open = fixture(lat = 45.0, lon = -93.0)
        assertFailsWith<ContactDetailProbeError> {
            ContactDetailLogic.reloadContact(open) { throw ContactDetailProbeError() }
        }
    }

    @Test
    fun aSuccessfulReloadAdoptsTheFetchedRow() = runTest {
        val open = fixture(lat = 45.0, lon = -93.0)
        val fetched = fixture(lat = 10.0, lon = 20.0)
        val next = ContactDetailLogic.reloadContact(open) { fetched }
        assertEquals(fetched, next.contact)
        assertFalse(next.loadFailed)
    }

    @Test
    fun aSuccessfulReloadMissMarksLoadFailed() = runTest {
        val open = fixture(lat = 45.0, lon = -93.0)
        val next = ContactDetailLogic.reloadContact(open) { null }
        assertNull(next.contact)
        assertTrue(next.loadFailed)
    }

    @Test
    fun shareViaAdvertWhileDisconnectedDoesNotReachTheRadio() = runTest {
        val session = FakeContactSession()
        val outcome = ContactDetailLogic.shareViaAdvert(
            service = testContactService(session = session),
            isRadioConnected = false,
            publicKey = publicKey(0x11),
        )
        assertEquals(ContactDetailLogic.ActionOutcome.Unavailable, outcome)
        assertTrue(session.sharedPublicKeys.isEmpty())
    }

    @Test
    fun shareViaAdvertOnALiveSessionReachesTheRadio() = runTest {
        val session = FakeContactSession()
        val key = publicKey(0x11)
        val outcome = ContactDetailLogic.shareViaAdvert(
            service = testContactService(session = session),
            isRadioConnected = true,
            publicKey = key,
        )
        assertEquals(ContactDetailLogic.ActionOutcome.Succeeded, outcome)
        assertEquals(1, session.sharedPublicKeys.size)
        assertTrue(session.sharedPublicKeys.single().contentEquals(key))
    }

    @Test
    fun anOfflineServiceIsNotAWriteService() = runTest {
        val service = testContactService()
        assertNull(ContactDetailLogic.writeService(service, isRadioConnected = false))
        assertEquals(service, ContactDetailLogic.writeService(service, isRadioConnected = true))
        var ran = false
        val outcome = ContactDetailLogic.runContactAction(
            ContactDetailLogic.writeService(service, isRadioConnected = false),
        ) { ran = true }
        assertEquals(ContactDetailLogic.ActionOutcome.Unavailable, outcome)
        assertFalse(ran)
    }

    @Test
    fun aLostServiceIsUnavailableNeverSuccess() = runTest {
        // The screen pops on success. Reporting success for a disconnected radio would tell the user a
        // contact was deleted (or blocked) while it still exists on the device.
        var ran = false
        val outcome = ContactDetailLogic.runContactAction(service = null) { ran = true }
        assertEquals(ContactDetailLogic.ActionOutcome.Unavailable, outcome)
        assertFalse(ran)
    }

    @Test
    fun aLiveServiceRunsTheActionAndReportsFailures() = runTest {
        val service = testContactService()
        var ran = false
        assertEquals(
            ContactDetailLogic.ActionOutcome.Succeeded,
            ContactDetailLogic.runContactAction(service) { ran = true },
        )
        assertTrue(ran)

        val failed = ContactDetailLogic.runContactAction(service) { throw ContactServiceError.NotConnected() }
        val error = (failed as ContactDetailLogic.ActionOutcome.Failed).error
        assertTrue(error is ContactServiceError.NotConnected)
    }

    @Test
    fun aCancelledContactActionPropagates() = runTest {
        val service = testContactService()
        assertFailsWith<CancellationException> {
            ContactDetailLogic.runContactAction(service) { throw CancellationException("superseded") }
        }
    }

    @Test
    fun aContactActionErrorPropagates() = runTest {
        val service = testContactService()
        assertFailsWith<ContactDetailProbeError> {
            ContactDetailLogic.runContactAction(service) { throw ContactDetailProbeError() }
        }
    }

    @Test
    fun pinStyleMapsContactTypes() {
        assertEquals(PinStyle.CONTACT_CHAT, ContactDetailLogic.pinStyleFor(ContactType.CHAT))
        assertEquals(PinStyle.CONTACT_REPEATER, ContactDetailLogic.pinStyleFor(ContactType.REPEATER))
        assertEquals(PinStyle.CONTACT_ROOM, ContactDetailLogic.pinStyleFor(ContactType.ROOM))
    }

    @Test
    fun miniMapCameraUsesIosSpan() {
        val camera = ContactDetailLogic.miniMapCamera(37.77, -122.42)
        assertEquals(37.77, camera.center.latitude)
        assertEquals(-122.42, camera.center.longitude)
        assertEquals(ContactDetailLogic.MINI_MAP_SPAN_DEG, camera.spanLatDeg)
        assertEquals(ContactDetailLogic.MINI_MAP_SPAN_DEG, camera.spanLonDeg)
    }

    @Test
    fun mapsGeoUriIncludesCoordinatesAndSanitizedLabel() {
        val uri = mapsGeoUri(37.7749, -122.4194, "Hill(top), HQ")
        assertEquals(
            "geo:37.7749,-122.4194?q=37.7749,-122.4194(Hill top   HQ)",
            uri,
        )
    }

    @Test
    fun mapsGeoUriWithoutLabelUsesBareCoordinates() {
        assertEquals(
            "geo:1.0,2.0?q=1.0,2.0",
            mapsGeoUri(1.0, 2.0, null),
        )
    }

    @Test
    fun miniMapPointRejectsZeroAndInvalidCoordinates() {
        assertNull(ContactDetailLogic.miniMapPoint(fixture(lat = 0.0, lon = 0.0)))
        assertNull(ContactDetailLogic.miniMapPoint(fixture(lat = 100.0, lon = 0.0)))
        val point = ContactDetailLogic.miniMapPoint(fixture(lat = 45.0, lon = -93.0, type = ContactType.REPEATER))
        assertNotNull(point)
        assertEquals(PinStyle.CONTACT_REPEATER, point.pinStyle)
        assertFalse(point.isClusterable)
    }

    @Test
    fun roleGatedActionsMatchIosMatrix() {
        val chat = ContactDetailLogic.actionFlags(ContactType.CHAT, isBlocked = false)
        assertFalse(chat.joinRoom)
        assertTrue(chat.sendMessage)
        assertFalse(chat.management)
        assertTrue(chat.block)
        assertTrue(chat.clearMessages)

        val repeater = ContactDetailLogic.actionFlags(ContactType.REPEATER, isBlocked = false)
        assertFalse(repeater.joinRoom)
        assertFalse(repeater.sendMessage)
        assertTrue(repeater.management)
        assertFalse(repeater.block)
        assertFalse(repeater.clearMessages)

        val room = ContactDetailLogic.actionFlags(ContactType.ROOM, isBlocked = false)
        assertTrue(room.joinRoom)
        assertFalse(room.sendMessage)
        assertTrue(room.management)
        assertFalse(room.block)
        assertFalse(room.clearMessages)
    }

    @Test
    fun blockedChatContactLosesSendMessageAndKeepsUnblock() {
        val blocked = ContactDetailLogic.actionFlags(ContactType.CHAT, isBlocked = true)

        assertFalse(blocked.sendMessage, "iOS hides Send Message for a blocked contact")
        assertTrue(blocked.block, "the row stays so the contact can be unblocked")
        assertTrue(blocked.clearMessages)
    }

    @Test
    fun fromDirectChatHidesSendMessageOnUnblockedChatContact() {
        val flags = ContactDetailLogic.actionFlags(
            ContactType.CHAT,
            isBlocked = false,
            showFromDirectChat = true,
        )

        assertFalse(flags.sendMessage, "iOS ContactDetailView(showFromDirectChat:) hides Send")
        assertTrue(flags.block)
        assertTrue(flags.clearMessages)
    }

    @Test
    fun chatSavedHistoryOmitsNeighborsWhileRepeatersAndRoomsKeepThem() {
        assertFalse(ContactDetailLogic.showTelemetryHistoryNeighbors(ContactType.CHAT))
        assertTrue(ContactDetailLogic.showTelemetryHistoryNeighbors(ContactType.REPEATER))
        assertTrue(ContactDetailLogic.showTelemetryHistoryNeighbors(ContactType.ROOM))
    }

    private fun fixture(lat: Double, lon: Double, type: ContactType = ContactType.CHAT): ContactDto = ContactDto(
        id = Uuid.normalized("11111111-1111-1111-1111-111111111111"),
        radioID = Uuid.normalized("22222222-2222-2222-2222-222222222222"),
        publicKey = ByteArray(32) { it.toByte() },
        name = "Test",
        typeRawValue = type.raw,
        flags = 0,
        outPathLength = 255,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = lat,
        longitude = lon,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )
}

private class ContactDetailProbeError : Error("probe")
