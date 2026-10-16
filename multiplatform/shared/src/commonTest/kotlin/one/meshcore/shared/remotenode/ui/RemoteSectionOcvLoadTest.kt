package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.testContact
import one.meshcore.shared.remotenode.testSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSectionOcvLoadTest {
    @Test
    fun expandedConsumersShareOneSavedCurveRead() = runTest {
        val release = CompletableDeferred<Unit>()
        val contact = testContact().copy(ocvPreset = OCVPreset.LI_FE_PO4.rawValue)
        var reads = 0
        val backing = InMemoryContactStore()
        backing.saveContact(contact)
        val contacts = ContactService(
            NoOpContactSession,
            object : ContactStore by backing {
                override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? {
                    reads++
                    release.await()
                    return backing.fetchContact(radioID, publicKey)
                }
            },
            now = { 0L },
        )
        val vm = NodeStatusViewModel(
            backgroundScope,
            testSession(),
            NodeStatusViewModel.Dependencies(
                repeaterAdminService = { null },
                contactService = { contacts },
                radioId = { contact.radioID },
            ),
        )
        runCurrent()
        assertEquals(0, reads)
        vm.setStatusExpanded(true)
        runCurrent()
        vm.setTelemetryExpanded(true)
        vm.setBatteryCurveExpanded(true)
        runCurrent()
        assertEquals(1, reads)
        assertFalse(vm.state.value.ocvLoaded)
        release.complete(Unit)
        runCurrent()
        assertEquals(OCVPreset.LI_FE_PO4.ocvArray, vm.state.value.ocvValues)
        vm.close()
    }
}
