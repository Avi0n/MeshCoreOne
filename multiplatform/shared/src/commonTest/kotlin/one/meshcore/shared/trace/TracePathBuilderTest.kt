package one.meshcore.shared.trace

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.pathedit.DiscoveredNode
import one.meshcore.shared.pathedit.PathEditFixtures
import one.meshcore.shared.pathedit.PickerNode
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trace path builder: hop width follows the radio's `pathHashMode` until the user overrides it, and
 * the hop list supports the reorder/remove editing the iOS `TracePathListView` offers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TracePathBuilderTest {
    private val radio = PathEditFixtures.uuid(100)
    private val repeaterA = PathEditFixtures.contact(
        name = "Alpha",
        publicKey = PathEditFixtures.key(0x11, 0x22, 0x33, 0x44, 0x55),
        radioID = radio,
        id = PathEditFixtures.uuid(201),
    )
    private val repeaterB = PathEditFixtures.contact(
        name = "Bravo",
        publicKey = PathEditFixtures.key(0x66, 0x77, 0x88, 0x99, 0xAA),
        radioID = radio,
        id = PathEditFixtures.uuid(202),
    )

    private suspend fun service(): ContactService {
        val store = InMemoryContactStore()
        store.saveContact(repeaterA)
        store.saveContact(repeaterB)
        return testContactService(store = store)
    }

    private data class BuilderOptions(
        val supportsOverride: Boolean = true,
        val discovered: List<DiscoveredNode> = emptyList(),
        val recents: RecentHopsStore? = null,
        val userLocation: UserLocation? = null,
    )

    private fun TestScope.builder(
        service: ContactService?,
        devicePathHashMode: Int? = null,
        options: BuilderOptions = BuilderOptions(),
    ) = TracePathBuilder(
        scope = backgroundScope,
        dependencies = TracePathBuilder.Dependencies(
            contactService = { service },
            radioId = { radio },
            devicePathHashMode = { devicePathHashMode },
            supportsHashSizeOverride = { options.supportsOverride },
            discoveredNodes = { options.discovered },
            userLocation = { options.userLocation },
        ),
        recents = options.recents,
    )

    /** A trace builder with no radio, so an edit can prove it did not read contacts, mode, or location. */
    private fun TestScope.quietTrace(supportsOverride: Boolean = false): QuietTrace {
        val reads = QuietTraceReads()
        val builder = TracePathBuilder(
            scope = backgroundScope,
            dependencies = TracePathBuilder.Dependencies(
                contactService = {
                    reads.serviceReads += 1
                    null
                },
                radioId = {
                    reads.radioReads += 1
                    null
                },
                devicePathHashMode = {
                    reads.modeReads += 1
                    null
                },
                supportsHashSizeOverride = {
                    reads.overrideReads += 1
                    supportsOverride
                },
                discoveredNodes = {
                    reads.discoveredReads += 1
                    emptyList()
                },
                userLocation = {
                    reads.locationReads += 1
                    null
                },
            ),
        )
        return QuietTrace(builder, reads)
    }

    @Test
    fun keepingTheTraceHashWidthDoesNotRadio() = runTest {
        val quiet = quietTrace()
        val builder = quiet.builder
        builder.setTraceHashMode(0)
        assertEquals(0, builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.modeReads)
        assertEquals(0, quiet.reads.overrideReads)
        assertEquals(0, quiet.reads.discoveredReads)
        assertEquals(0, quiet.reads.locationReads)

        builder.setTraceHashMode(0)

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.modeReads)
        assertEquals(0, quiet.reads.overrideReads)
        assertEquals(0, quiet.reads.discoveredReads)
        assertEquals(0, quiet.reads.locationReads)
        assertEquals(0, builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())
    }

    @Test
    fun choosingATraceHashWidthOnAnEmptyPathDoesNotRadio() = runTest {
        val quiet = quietTrace()
        val builder = quiet.builder
        assertNull(builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())

        builder.setTraceHashMode(0)

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.modeReads)
        assertEquals(0, quiet.reads.overrideReads)
        assertEquals(0, quiet.reads.discoveredReads)
        assertEquals(0, quiet.reads.locationReads)
        assertEquals(0, builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())
    }

    @Test
    fun adoptingATraceWidthTheRadioCannotHonorDoesNotRadio() = runTest {
        val quiet = quietTrace()
        val builder = quiet.builder
        assertNull(builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.modeReads)
        assertEquals(0, quiet.reads.overrideReads)
        assertEquals(0, quiet.reads.discoveredReads)
        assertEquals(0, quiet.reads.locationReads)

        builder.adoptHashSize("1122,6677")
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.modeReads)
        assertEquals(1, quiet.reads.overrideReads)
        assertEquals(0, quiet.reads.discoveredReads)
        assertEquals(0, quiet.reads.locationReads)
        assertNull(builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())
    }

    @Test
    fun adoptingAnEmptyTraceHashWidthWhileOverrideIsSupportedDoesNotRadio() = runTest {
        val quiet = quietTrace(supportsOverride = true)
        val builder = quiet.builder
        assertNull(builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())
        assertEquals(0, quiet.reads.overrideReads)

        builder.adoptHashSize("")
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.modeReads)
        assertEquals(1, quiet.reads.overrideReads)
        assertEquals(0, quiet.reads.discoveredReads)
        assertEquals(0, quiet.reads.locationReads)
        assertNull(builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())
    }

    @Test
    fun clearingATraceHashOverrideDoesNotRadio() = runTest {
        val quiet = quietTrace()
        val builder = quiet.builder
        builder.setTraceHashMode(0)
        assertEquals(0, builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())

        builder.clearPath()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.modeReads)
        assertEquals(0, quiet.reads.overrideReads)
        assertEquals(0, quiet.reads.discoveredReads)
        assertEquals(0, quiet.reads.locationReads)
        assertNull(builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())
    }

    @Test
    fun clearingAnEmptyTracePathDoesNotRadio() = runTest {
        val quiet = quietTrace()
        val builder = quiet.builder
        assertNull(builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())

        builder.clearPath()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.modeReads)
        assertEquals(0, quiet.reads.overrideReads)
        assertEquals(0, quiet.reads.discoveredReads)
        assertEquals(0, quiet.reads.locationReads)
        assertNull(builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())
    }

    @Test
    fun removingAHopFromAnEmptyTracePathDoesNotRadio() = runTest {
        val quiet = quietTrace()
        val builder = quiet.builder
        assertNull(builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())
        assertNull(builder.state.value.insertionIntent)
        assertNull(builder.state.value.errorMessage)
        assertEquals(false, builder.state.value.isSettingPath)
        assertEquals(listOf(0, 0, 0, 0, 0, 0), quiet.reads.snapshot())
        builder.removeHop(0)

        assertEquals(listOf(0, 0, 0, 0, 0, 0), quiet.reads.snapshot())
        assertNull(builder.traceHashMode.value)
        assertTrue(builder.outboundPath.isEmpty())
        assertNull(builder.state.value.insertionIntent)
        assertNull(builder.state.value.errorMessage)
        assertEquals(false, builder.state.value.isSettingPath)
    }

    @Test
    fun hopWidthFollowsTheRadioPathHashMode() = runTest {
        val builder = builder(service(), devicePathHashMode = 1)
        builder.load()
        builder.appendHop(PickerNode.Contact(repeaterA))

        assertEquals(2, builder.hashSize, "a mode-1 radio traces with 2-byte hop hashes")
        assertEquals(1, builder.effectiveTraceMode)
        assertContentEquals(byteArrayOf(0x11, 0x22), builder.outboundPath.single().hashBytes)
    }

    @Test
    fun fourByteRadioBuildsFourByteHops() = runTest {
        val builder = builder(service(), devicePathHashMode = 2)
        builder.load()
        builder.appendHop(PickerNode.Contact(repeaterA))

        assertEquals(4, builder.hashSize, "trace widths are power-of-2: mode 2 is 4 bytes, not 3")
        assertContentEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), builder.outboundPath.single().hashBytes)
        assertContentEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), builder.fullPathBytes(autoReturnPath = false))
    }

    @Test
    fun unknownDeviceModeFallsBackToOneByte() = runTest {
        val builder = builder(service(), devicePathHashMode = null)
        builder.load()
        builder.appendHop(PickerNode.Contact(repeaterA))

        assertEquals(1, builder.hashSize)
        assertNull(builder.traceHashMode.value)
    }

    @Test
    fun overrideWinsOverTheDeviceModeAndRebuildsExistingHops() = runTest {
        val builder = builder(service(), devicePathHashMode = 2)
        builder.load()
        builder.appendHop(PickerNode.Contact(repeaterA))
        builder.setTraceHashMode(0)

        assertEquals(1, builder.hashSize)
        assertContentEquals(byteArrayOf(0x11), builder.outboundPath.single().hashBytes)
    }

    @Test
    fun autoReturnMirrorsThePathWithoutRepeatingTheTurnaround() = runTest {
        val builder = builder(service(), devicePathHashMode = 0)
        builder.load()
        builder.appendHop(PickerNode.Contact(repeaterA))
        builder.appendHop(PickerNode.Contact(repeaterB))

        assertContentEquals(byteArrayOf(0x11, 0x66, 0x11), builder.fullPathBytes(autoReturnPath = true))
        assertEquals("11,66", builder.fullPathString(autoReturnPath = false))
        assertEquals("11,66,11", builder.fullPathString(autoReturnPath = true))
    }

    @Test
    fun hopsReorderAndRemove() = runTest {
        val builder = builder(service(), devicePathHashMode = 0)
        builder.load()
        builder.appendHop(PickerNode.Contact(repeaterA))
        builder.appendHop(PickerNode.Contact(repeaterB))

        // SwiftUI onMove offsets: moving index 0 down passes index + 2.
        builder.moveHop(0, 2)
        assertEquals(listOf("Bravo", "Alpha"), builder.outboundPath.map { it.resolvedName })

        builder.removeHop(0)
        assertEquals(listOf("Alpha"), builder.outboundPath.map { it.resolvedName })

        builder.removeHop(5)
        assertEquals(1, builder.outboundPath.size, "an out-of-range remove is a no-op")
    }

    @Test
    fun clearPathEmptiesHopsAndDropsTheHashOverride() = runTest {
        val builder = builder(service(), devicePathHashMode = 2)
        builder.load()
        builder.appendHop(PickerNode.Contact(repeaterA))
        builder.setTraceHashMode(0)
        assertEquals(1, builder.hashSize)
        assertEquals(0, builder.traceHashMode.value)

        builder.clearPath()

        assertTrue(builder.outboundPath.isEmpty())
        assertNull(builder.traceHashMode.value)
        assertEquals(2, builder.effectiveTraceMode, "clearing the override restores the radio width")
    }

    @Test
    fun bulkCodesResolveNamesAndAdoptAPastedWidth() = runTest {
        val builder = builder(service(), devicePathHashMode = 0)
        builder.load()
        builder.adoptHashSize("1122,6677")
        val result = builder.addCodes("1122,6677")

        assertEquals(2, builder.hashSize, "a uniform 2-byte paste adopts the wider trace width")
        assertEquals(listOf("1122", "6677"), result.added)
        assertEquals(listOf("Alpha", "Bravo"), builder.outboundPath.map { it.resolvedName })
    }

    @Test
    fun pastedWidthIsIgnoredWhenTheFirmwareCannotHonorIt() = runTest {
        val builder = builder(service(), devicePathHashMode = 0, options = BuilderOptions(supportsOverride = false))
        builder.load()
        builder.adoptHashSize("1122,6677")

        assertEquals(1, builder.hashSize)
    }

    @Test
    fun addedHopsAreRecordedAsRecent() = runTest {
        val recents = RecentHopsStore(InMemoryPreferencesStore())
        val builder = builder(service(), devicePathHashMode = 0, options = BuilderOptions(recents = recents))
        builder.load()
        builder.appendHop(PickerNode.Contact(repeaterA))
        runCurrent()

        assertTrue(
            builder.state.value.recentPublicKeys.any { it.contentEquals(repeaterA.publicKey) },
            "the picker's Recent section is fed by every appended hop",
        )
    }

    @Test
    fun loadExposesRepeatersRoomsAndDiscoveredToThePicker() = runTest {
        val discovered = PathEditFixtures.discovered()
        val builder =
            builder(service(), devicePathHashMode = 0, options = BuilderOptions(discovered = listOf(discovered)))
        builder.load()

        assertEquals(2, builder.state.value.availableRepeaters.size)
        assertEquals(listOf(discovered), builder.state.value.discoveredNodes)
        assertNull(builder.hopLimit, "trace paths are uncapped")
    }

    @Test
    fun loadingASavedPathAdoptsItsStoredWidth() = runTest {
        val builder = builder(service(), devicePathHashMode = 0)
        builder.load()
        // Saved full path A,B,A at 2 bytes per hop; only the outbound half is rebuilt.
        builder.loadSavedPath(byteArrayOf(0x11, 0x22, 0x66, 0x77, 0x11, 0x22), savedHashSize = 2)

        assertEquals(2, builder.hashSize)
        assertEquals(listOf("Alpha", "Bravo"), builder.outboundPath.map { it.resolvedName })
    }

    @Test
    fun loadingASavedPathKeepsTheRadioWidthWhenTheOverrideIsNotHonored() = runTest {
        val builder = builder(service(), devicePathHashMode = 0, options = BuilderOptions(supportsOverride = false))
        builder.load()
        builder.loadSavedPath(byteArrayOf(0x11, 0x22, 0x66, 0x77, 0x11, 0x22), savedHashSize = 2)

        assertNull(
            builder.traceHashMode.value,
            "a radio that ignores the per-trace override decodes at its configured pathHashMode",
        )
        assertEquals(1, builder.hashSize, "the trace declares the width the radio actually reads")
    }

    /**
     * A 1-byte hop hash is often shared by two saved repeaters. iOS `RepeaterResolver.bestMatch` picks
     * the nearest to the user; recency is only the fallback when there is no fix.
     */
    @Test
    fun aCollidingSavedHopPicksTheNearestNode() = runTest {
        val far = PathEditFixtures.contact(
            name = "Far",
            publicKey = PathEditFixtures.key(0x11, 0xAA),
            radioID = radio,
            id = PathEditFixtures.uuid(301),
        ).copy(latitude = 40.0, longitude = -122.0, lastAdvertTimestamp = 900)
        val near = PathEditFixtures.contact(
            name = "Near",
            publicKey = PathEditFixtures.key(0x11, 0xBB),
            radioID = radio,
            id = PathEditFixtures.uuid(302),
        ).copy(latitude = 37.1, longitude = -122.0, lastAdvertTimestamp = 10)
        val store = InMemoryContactStore()
        store.saveContact(far)
        store.saveContact(near)
        val builder = builder(
            testContactService(store = store),
            devicePathHashMode = 0,
            options = BuilderOptions(userLocation = UserLocation(latitude = 37.0, longitude = -122.0)),
        )
        builder.load()
        builder.loadSavedPath(byteArrayOf(0x11), savedHashSize = 1)

        assertEquals(listOf("Near"), builder.outboundPath.map { it.resolvedName })
    }

    @Test
    fun aCollidingPastedHopPicksTheNearestNode() = runTest {
        val far = PathEditFixtures.contact(
            name = "Far",
            publicKey = PathEditFixtures.key(0x11, 0xAA),
            radioID = radio,
            id = PathEditFixtures.uuid(301),
        ).copy(latitude = 40.0, longitude = -122.0, lastAdvertTimestamp = 900)
        val near = PathEditFixtures.contact(
            name = "Near",
            publicKey = PathEditFixtures.key(0x11, 0xBB),
            radioID = radio,
            id = PathEditFixtures.uuid(302),
        ).copy(latitude = 37.1, longitude = -122.0, lastAdvertTimestamp = 10)
        val store = InMemoryContactStore()
        store.saveContact(far)
        store.saveContact(near)
        val builder = builder(
            testContactService(store = store),
            devicePathHashMode = 0,
            options = BuilderOptions(userLocation = UserLocation(latitude = 37.0, longitude = -122.0)),
        )
        builder.load()
        builder.addCodes("11")

        assertEquals(listOf("Near"), builder.outboundPath.map { it.resolvedName })
    }
}

private class QuietTrace(val builder: TracePathBuilder, val reads: QuietTraceReads)

private class QuietTraceReads {
    var serviceReads = 0
    var radioReads = 0
    var modeReads = 0
    var overrideReads = 0
    var discoveredReads = 0
    var locationReads = 0

    fun snapshot(): List<Int> = listOf(
        serviceReads,
        radioReads,
        modeReads,
        overrideReads,
        discoveredReads,
        locationReads,
    )
}
