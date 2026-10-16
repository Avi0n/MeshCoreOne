package one.meshcore.shared.map.ui

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactServiceEvent
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.VContactIdentity
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.map.MapStyleDisabledReason
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.MapFocusRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapScreenDecisionsTest {
    @Test
    fun focusedPinUsesTheStableDroppedId() {
        assertNull(focusedMapPin(null))
        val pin = focusedMapPin(MapFocusRequest(latitude = 3.0, longitude = 4.0))
        assertEquals(FOCUSED_PIN_ID, pin?.id)
        assertEquals(LatLng(3.0, 4.0), pin?.coordinate)
        assertEquals(PinStyle.DROPPED_PIN, pin?.pinStyle)
        assertEquals(false, pin?.isClusterable)
        assertNull(pin?.label)
    }

    @Test
    fun focusFramingUsesTheSameSpanOnBothAxes() {
        val region = focusCameraRegion(LatLng(1.5, -2.5))
        assertEquals(LatLng(1.5, -2.5), region.center)
        assertEquals(FOCUS_SPAN_DEG, region.spanLatDeg)
        assertEquals(region.spanLatDeg, region.spanLonDeg)
    }

    @Test
    fun discoveredRowsKeepLocatedNodesThatAreNotAlreadyContacts() {
        val ada = contact(name = "Ada", keyByte = 1)
        val shown = discoveredRowsForRadio(
            snapshot = listOf(
                discovered(name = "Ada", keyByte = 1, radio = RADIO, latitude = 1.0, longitude = 2.0),
                discovered(name = "Ridge", keyByte = 2, radio = RADIO, latitude = 3.0, longitude = 4.0),
                discovered(name = "Other", keyByte = 3, radio = OTHER_RADIO, latitude = 5.0, longitude = 6.0),
                discovered(name = "Unlocated", keyByte = 4, radio = RADIO, latitude = 0.0, longitude = 0.0),
            ),
            activeRadio = RADIO,
            contacts = listOf(ada),
            mapFilter = MapFilterState(showDiscovered = true),
            now = 1_000L,
        )
        assertEquals(listOf("Ridge"), shown.map { it.name })
    }

    @Test
    fun discoveredRowsStayEmptyWithoutARadioOrTheToggle() {
        val ridge = discovered(name = "Ridge", keyByte = 2, radio = RADIO, latitude = 3.0, longitude = 4.0)
        val filter = MapFilterState(showDiscovered = true)
        assertEquals(
            emptyList(),
            discoveredRowsForRadio(
                listOf(ridge),
                RADIO,
                emptyList(),
                filter.copy(showDiscovered = false),
                now = 1_000L,
            ),
        )
        assertEquals(
            emptyList(),
            discoveredRowsForRadio(listOf(ridge), null, emptyList(), filter, now = 1_000L),
        )
    }

    @Test
    fun offlineStylesExplainNetworkAndCoverageWhenNoPackIsLoaded() {
        val online = mapStyleDisabledReasons(isNetworkAvailable = true, registry = null, viewportBounds = null)
        assertTrue(online.values.all { it == null })
        val offline = mapStyleDisabledReasons(isNetworkAvailable = false, registry = null, viewportBounds = null)
        assertEquals(MapStyleDisabledReason.NO_OFFLINE_COVERAGE, offline[MapStyleSelection.STANDARD])
        assertEquals(MapStyleDisabledReason.REQUIRES_NETWORK, offline[MapStyleSelection.SATELLITE])
        assertEquals(MapStyleDisabledReason.NO_OFFLINE_COVERAGE, offline[MapStyleSelection.TOPO])
    }

    @Test
    fun disabledHintMatchesTheReason() {
        assertEquals("network", mapStyleDisabledHint(MapStyleDisabledReason.REQUIRES_NETWORK, "network", "coverage"))
        assertEquals(
            "coverage",
            mapStyleDisabledHint(MapStyleDisabledReason.NO_OFFLINE_COVERAGE, "network", "coverage"),
        )
        assertNull(mapStyleDisabledHint(null, "network", "coverage"))
    }

    @Test
    fun contactEventsReloadOnlyForThisRadioOrADeletion() {
        val advert = ContactServiceEvent.ContactAdvertisementApplied(RADIO, ByteArray(1))
        assertTrue(contactEventReloadsMap(advert, RADIO))
        assertFalse(contactEventReloadsMap(advert, OTHER_RADIO))
        assertTrue(contactEventReloadsMap(ContactServiceEvent.NodeDeleted, RADIO))
        assertFalse(contactEventReloadsMap(ContactServiceEvent.SyncProgress(received = 1, total = 2), RADIO))
    }

    @Test
    fun vContactCheckNeedsTheRadiosOwnKey() {
        val selfKey = ByteArray(32) { 0x11 }
        val derived = VContactIdentity.publicKey(selfKey)
        val contact = contact(name = "Ada", keyByte = 1).copy(publicKey = derived ?: ByteArray(0))
        assertFalse(mapContactIsVContact(contact, null))
        assertTrue(derived != null && mapContactIsVContact(contact, selfKey))
        assertFalse(mapContactIsVContact(contact, ByteArray(32) { 0x22 }))
    }

    @Test
    fun pointClicksChooseThePinThatWasHit() {
        val ada = contact(name = "Ada", keyByte = 1)
        val ridge = discovered(name = "Ridge", keyByte = 2, radio = RADIO, latitude = 3.0, longitude = 4.0)
        val state = MapScreenState()
        state.contactsById = mapOf(ada.id.value to ada)
        state.onMapPointClick(FOCUSED_PIN_ID, state.contactsById, mapOf(ridge.id.value to ridge))
        assertTrue(state.showDroppedPinCallout)
        assertNull(state.selectedContact)
        state.onMapPointClick(ada.id.value, state.contactsById, mapOf(ridge.id.value to ridge))
        assertEquals(ada, state.selectedContact)
        assertFalse(state.showDroppedPinCallout)
        assertNull(state.selectedDiscovered)
        state.onMapPointClick(ridge.id.value, state.contactsById, mapOf(ridge.id.value to ridge))
        assertEquals(ridge, state.selectedDiscovered)
        assertNull(state.selectedContact)
        state.onMapPointClick("missing", state.contactsById, mapOf(ridge.id.value to ridge))
        assertEquals(ridge, state.selectedDiscovered)
    }

    @Test
    fun delayedMarkerTapCannotOpenAFilteredContact() {
        val ada = contact(name = "Ada", keyByte = 1)
        val state = MapScreenState()
        state.contactsById = mapOf(ada.id.value to ada)
        state.onMapPointClick(ada.id.value, emptyMap(), emptyMap())
        assertNull(state.selectedContact)
    }

    @Test
    fun inboundHopsIncludeUnlocatedDuplicateContactsButStayRadioScoped() {
        val sameRadio = discovered(name = "Ada", keyByte = 1, radio = RADIO, latitude = 0.0, longitude = 0.0)
            .copy(inboundHopCount = 2)
        val otherRadio = sameRadio.copy(radioID = OTHER_RADIO, inboundHopCount = 5)
        assertEquals(listOf(2), mapInboundHops(listOf(sameRadio, otherRadio), RADIO).values.toList())
        assertEquals(emptyMap(), mapInboundHops(listOf(sameRadio, otherRadio), null))
    }
}

private val RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
private val OTHER_RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000002")

private fun contact(name: String, keyByte: Int): ContactDto = ContactDto(
    id = Uuid.normalized("C0000000-0000-0000-0000-00000000000$keyByte"),
    radioID = RADIO,
    publicKey = ByteArray(32) { keyByte.toByte() },
    name = name,
    typeRawValue = ContactType.CHAT.raw,
    flags = 0,
    outPathLength = 0,
    outPath = ByteArray(0),
    lastAdvertTimestamp = 0,
    latitude = 1.0,
    longitude = 2.0,
    lastModified = 0,
    isBlocked = false,
    isMuted = false,
    isFavorite = false,
    unreadCount = 0,
)

private fun discovered(
    name: String,
    keyByte: Int,
    radio: Uuid,
    latitude: Double,
    longitude: Double,
): DiscoveredNodeRecord = DiscoveredNodeRecord(
    id = Uuid.normalized("D0000000-0000-0000-0000-00000000000$keyByte"),
    radioID = radio,
    publicKey = ByteArray(32) { keyByte.toByte() },
    name = name,
    typeRawValue = ContactType.CHAT.raw,
    lastHeardEpochSeconds = 1,
    latitude = latitude,
    longitude = longitude,
)
