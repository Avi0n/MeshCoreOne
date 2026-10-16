package one.meshcore.shared.contact

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The radio-drop dump must carry Discover B4 reload lines. iOS writes these through
 * `PersistentLogger(category: "discover-trace")`; these tests pin the same strings on Kotlin.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModelDiscoverTraceTest {
    private val radio = ContactTestIds.RADIO

    @Test
    fun `a successful load records B4 view reload loaded`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryDiscoveredNodeStore()
        store.upsert(
            DiscoveredNodeRecord(
                id = newDiscoveredNodeId(),
                radioID = radio,
                publicKey = publicKey(0x21),
                name = "Nova",
                typeRawValue = ContactType.CHAT.raw,
                lastHeardEpochSeconds = 1,
            ),
        )
        val vm = DiscoveryViewModel(
            scope = this,
            dependencies = DiscoveryViewModel.Dependencies(
                store = { store },
                contactService = { null },
                radioId = { radio },
                discoverTrace = PersistentLogger(this, category = "discover-trace", record = { recorded += it }),
                loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
                errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
            ),
        )

        vm.load()

        val line = recorded.single { it.message.contains("B4 view reload loaded=") }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertEquals("discover-trace", line.category)
        assertTrue(line.message.contains("loaded=1"))
        assertTrue(line.message.contains("addedKeys=0"))
        assertTrue(line.message.contains("radio=$radio"))
    }

    @Test
    fun `a persist fault records B4 view reload FAILED and marks loaded`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun list(radioID: Uuid) = error("db locked")
        }
        val vm = DiscoveryViewModel(
            scope = this,
            dependencies = DiscoveryViewModel.Dependencies(
                store = { store },
                contactService = { null },
                radioId = { radio },
                discoverTrace = PersistentLogger(this, category = "discover-trace", record = { recorded += it }),
                loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
                errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
            ),
        )

        vm.load()

        val line = recorded.single { it.message.contains("B4 view reload FAILED") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("discover-trace", line.category)
        assertTrue(line.message.contains("radio=$radio"))
        assertTrue(line.message.contains("db locked"))
        assertTrue(vm.state.value.hasLoadedOnce)
        assertEquals(DISCOVERY_LOAD_FAILED_MESSAGE, vm.state.value.errorMessage)
    }
}
