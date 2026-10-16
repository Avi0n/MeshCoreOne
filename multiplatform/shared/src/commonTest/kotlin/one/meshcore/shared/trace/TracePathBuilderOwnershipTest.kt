package one.meshcore.shared.trace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.pathedit.DiscoveredNode
import one.meshcore.shared.pathedit.PathEditFixtures
import one.meshcore.shared.pathedit.PickerNode
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TracePathBuilderOwnershipTest {
    @Test
    fun resetWhileRecentsAreLoadingCannotRestoreRetiredContactsOrKeys() = runTest {
        val release = CompletableDeferred<Unit>()
        val preferences = object : PreferencesStore by InMemoryPreferencesStore() {
            override suspend fun getString(key: String): String {
                withContext(NonCancellable) { release.await() }
                return KEY_HEX
            }
        }
        val store = InMemoryContactStore()
        store.saveContact(
            PathEditFixtures.contact(name = "Old", publicKey = KEY, radioID = RADIO, id = PathEditFixtures.uuid(2)),
        )
        val service = testContactService(store = store)
        val builder = TracePathBuilder(
            backgroundScope,
            TracePathBuilder.Dependencies(contactService = { service }, radioId = { RADIO }),
            RecentHopsStore(preferences),
        )
        val load = backgroundScope.launch { builder.load() }
        runCurrent()
        builder.reset()
        release.complete(Unit)
        load.join()
        assertTrue(builder.state.value.recentPublicKeys.isEmpty())
        assertTrue(builder.state.value.allContacts.isEmpty())
    }

    @Test
    fun oldRecentsRecordCannotPopulateTheReplacementRadioPicker() = runTest {
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val preferences = object : PreferencesStore by InMemoryPreferencesStore() {
            override suspend fun putString(key: String, value: String) {
                writes++
                withContext(NonCancellable) { release.await() }
            }
        }
        var radio = RADIO
        val builder = TracePathBuilder(
            backgroundScope,
            TracePathBuilder.Dependencies(contactService = { null }, radioId = { radio }),
            RecentHopsStore(preferences),
        )
        builder.appendHop(PickerNode.Discovered(DiscoveredNode(PathEditFixtures.uuid(3), KEY, "Old")))
        runCurrent()
        assertEquals(1, writes)
        builder.reset()
        radio = PathEditFixtures.uuid(4)
        release.complete(Unit)
        runCurrent()
        assertTrue(builder.state.value.recentPublicKeys.isEmpty())
        assertTrue(builder.outboundPath.isEmpty())
    }

    private companion object {
        val RADIO = PathEditFixtures.uuid(1)
        const val KEY_HEX = "11"
        val KEY = byteArrayOf(0x11)
    }
}
