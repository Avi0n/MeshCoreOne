package one.meshcore.shared.contact

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ContactServiceEventBindingTest {
    @Test
    fun registrationIsEagerAndRebindingSameServiceKeepsOriginalCollector() = runTest {
        val service = testContactService()
        val binding = ContactServiceEventBinding(this)
        val heard = mutableListOf<ContactServiceEvent>()
        var replacementCalls = 0
        binding.bind(service) { heard += it }
        service.eventBroadcaster.yield(ContactServiceEvent.NodeDeleted)
        runCurrent()
        assertEquals(listOf<ContactServiceEvent>(ContactServiceEvent.NodeDeleted), heard)

        binding.bind(service) { replacementCalls++ }
        service.eventBroadcaster.yield(ContactServiceEvent.NodeDeleted)
        runCurrent()
        assertEquals(
            listOf<ContactServiceEvent>(ContactServiceEvent.NodeDeleted, ContactServiceEvent.NodeDeleted),
            heard,
        )
        assertEquals(0, replacementCalls)
        binding.bind(null) {}
        runCurrent()
    }

    @Test
    fun newServiceReplacesOldCollectorAndNullStopsCallbacks() = runTest {
        val first = testContactService()
        val second = testContactService()
        val binding = ContactServiceEventBinding(this)
        val heard = mutableListOf<String>()
        binding.bind(first) { heard += "first" }
        first.eventBroadcaster.yield(ContactServiceEvent.NodeDeleted)
        runCurrent()

        binding.bind(second) { heard += "second" }
        first.eventBroadcaster.yield(ContactServiceEvent.NodeDeleted)
        second.eventBroadcaster.yield(ContactServiceEvent.NodeDeleted)
        runCurrent()
        assertEquals(listOf("first", "second"), heard)

        binding.bind(null) {}
        first.eventBroadcaster.yield(ContactServiceEvent.NodeDeleted)
        second.eventBroadcaster.yield(ContactServiceEvent.NodeDeleted)
        runCurrent()
        assertEquals(listOf("first", "second"), heard)
    }

    @Test
    fun closeIsTerminal() = runTest {
        val service = testContactService()
        val binding = ContactServiceEventBinding(this)
        var calls = 0

        binding.close()
        binding.bind(service) { calls += 1 }
        service.eventBroadcaster.yield(ContactServiceEvent.NodeDeleted)
        runCurrent()

        assertEquals(0, calls)
    }
}
