package one.meshcore.shared.trace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.pathedit.DiscoveredNode
import one.meshcore.shared.pathedit.PathEditFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Contact loading for a trace path. A store fault clears the picker lists. Cancellation and an Error
 * leave a successful load in place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TracePathLoadTest {
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

    private suspend fun contacts() = testContactService(
        store = InMemoryContactStore().also {
            it.saveContact(repeaterA)
            it.saveContact(repeaterB)
        },
    )

    private fun TestScope.builder(
        service: ContactService,
        logWarning: (String) -> Unit,
        loadDiscovered: suspend () -> List<DiscoveredNode>,
    ) = TracePathBuilder(
        scope = backgroundScope,
        dependencies = TracePathBuilder.Dependencies(
            contactService = { service },
            radioId = { radio },
            devicePathHashMode = { 0 },
            discoveredNodes = { loadDiscovered() },
        ),
        logWarning = logWarning,
    )

    @Test
    fun aStoreFaultClearsLoadedContactsAndLogs() = runTest {
        val warnings = mutableListOf<String>()
        val discovered = PathEditFixtures.discovered()
        var fail = false
        val builder = builder(
            service = contacts(),
            logWarning = { warnings += it },
            loadDiscovered = { if (fail) error("store locked") else listOf(discovered) },
        )
        builder.load()
        assertEquals(2, builder.state.value.availableRepeaters.size)

        fail = true
        builder.load()

        assertTrue(builder.state.value.allContacts.isEmpty())
        assertTrue(builder.state.value.availableRepeaters.isEmpty())
        assertTrue(builder.state.value.availableRooms.isEmpty())
        assertTrue(builder.state.value.discoveredNodes.isEmpty())
        assertEquals(listOf("$TRACE_PATH_CONTACTS_FAILED: store locked"), warnings)
    }

    @Test
    fun aStoreErrorLeavesLoadedContactsAndPropagates() = runTest {
        val warnings = mutableListOf<String>()
        val discovered = PathEditFixtures.discovered()
        val fault = TracePathLoadProbeFault()
        var fail = false
        val builder = builder(
            service = contacts(),
            logWarning = { warnings += it },
            loadDiscovered = { if (fail) throw fault else listOf(discovered) },
        )
        builder.load()
        fail = true

        val thrown = assertFailsWith<TracePathLoadProbeFault> { builder.load() }

        assertSame(fault, thrown)
        assertEquals(2, builder.state.value.availableRepeaters.size)
        assertEquals(listOf(discovered), builder.state.value.discoveredNodes)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aCancelledLoadLeavesContactsAndRethrows() = runTest {
        val warnings = mutableListOf<String>()
        val discovered = PathEditFixtures.discovered()
        val cancelled = CancellationException("stopped")
        var fail = false
        val builder = builder(
            service = contacts(),
            logWarning = { warnings += it },
            loadDiscovered = { if (fail) throw cancelled else listOf(discovered) },
        )
        builder.load()
        fail = true

        val thrown = assertFailsWith<CancellationException> { builder.load() }

        assertSame(cancelled, thrown)
        assertEquals(2, builder.state.value.availableRepeaters.size)
        assertEquals(listOf(discovered), builder.state.value.discoveredNodes)
        assertTrue(warnings.isEmpty())
    }
}

private class TracePathLoadProbeFault : Error("probe")
