package one.meshcore.shared.chat

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exclusive timeline writes: interactive always wins; prime is denied while an interactive owner
 * is alive. Mirrors iOS `ChatTimelineWriterTests`.
 */
class ChatTimelineWriterTest {

    @Test
    fun interactiveBindAlwaysSucceedsAndRevokesThePriorWriter() = runTest {
        val coordinator = coordinator()
        val ownerA = WriterOwner()
        val ownerB = WriterOwner()

        val writerA = assertNotNull(coordinator.bindWriter(ownerA, ChatWriterRole.INTERACTIVE))
        assertTrue(writerA.isCurrent)

        val writerB = assertNotNull(coordinator.bindWriter(ownerB, ChatWriterRole.INTERACTIVE))
        assertTrue(writerB.isCurrent)
        assertFalse(writerA.isCurrent)
    }

    @Test
    fun releaseWriterVacatesTheSlotForALaterPrimeBind() = runTest {
        val coordinator = coordinator()
        val owner = WriterOwner()
        val primeOwner = WriterOwner()

        val writer = assertNotNull(coordinator.bindWriter(owner, ChatWriterRole.INTERACTIVE))
        assertNull(coordinator.bindWriter(primeOwner, ChatWriterRole.PRIME))

        coordinator.releaseWriter(owner)

        val primeWriter = assertNotNull(coordinator.bindWriter(primeOwner, ChatWriterRole.PRIME))
        assertTrue(primeWriter.isCurrent)
        assertFalse(writer.isCurrent)
    }

    @Test
    fun releaseWriterFromASupersededOwnerDoesNotEvictTheSuccessor() = runTest {
        val coordinator = coordinator()
        val ownerA = WriterOwner()
        val ownerB = WriterOwner()
        val primeOwner = WriterOwner()

        coordinator.bindWriter(ownerA, ChatWriterRole.INTERACTIVE)
        val writerB = assertNotNull(coordinator.bindWriter(ownerB, ChatWriterRole.INTERACTIVE))

        coordinator.releaseWriter(ownerA)

        assertNull(coordinator.bindWriter(primeOwner, ChatWriterRole.PRIME))
        assertTrue(writerB.isCurrent)
    }

    @Test
    fun staleWriterReplaceAllNoOps() = runTest {
        val coordinator = coordinator()
        val ownerA = WriterOwner()
        val ownerB = WriterOwner()
        val seeded = ChatFixtures.dmMessage(timestamp = 1_000)

        val writerA = assertNotNull(coordinator.bindWriter(ownerA, ChatWriterRole.INTERACTIVE))
        writerA.replaceAll(listOf(seeded))
        assertEquals(1, coordinator.messages.size)

        coordinator.bindWriter(ownerB, ChatWriterRole.INTERACTIVE)
        val renderStateIDBefore = coordinator.renderStateID
        writerA.replaceAll(emptyList())
        assertEquals(renderStateIDBefore, coordinator.renderStateID)
        assertEquals(seeded.id, coordinator.messages.single().id)
    }

    @Test
    fun primeBindIsDeniedWhileAnInteractiveOwnerIsAlive() = runTest {
        val coordinator = coordinator()
        val interactive = assertNotNull(
            coordinator.bindWriter(WriterOwner(), ChatWriterRole.INTERACTIVE),
        )
        assertNull(coordinator.bindWriter(WriterOwner(), ChatWriterRole.PRIME))
        assertTrue(interactive.isCurrent)
    }

    @Test
    fun primeBindSucceedsOverAVacantSlotAndAPriorPrime() = runTest {
        val coordinator = coordinator()
        val primeA = assertNotNull(coordinator.bindWriter(WriterOwner(), ChatWriterRole.PRIME))
        val primeB = assertNotNull(coordinator.bindWriter(WriterOwner(), ChatWriterRole.PRIME))
        assertFalse(primeA.isCurrent)
        assertTrue(primeB.isCurrent)
    }

    @Test
    fun primingIsDeniedWhenTheOpenThreadHoldsTheWriter() = runTest {
        val store = FakeCoordinatorStore()
        store.directPage = listOf(ChatFixtures.dmMessage(timestamp = 2_000))
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val id = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        val coordinator = registry.coordinator(id)
        coordinator.bindWriter(WriterOwner(), ChatWriterRole.INTERACTIVE)
        val conversation = Conversation.Direct(ChatFixtures.contact(name = "Pat", id = ChatFixtures.CONTACT))

        ChatTimelinePrimer(
            ChatTimelinePrimer.Hooks(
                registry = { registry },
                store = { store },
                isConversationActive = { false },
                unknownLabel = { "Unknown" },
            ),
        ).prime(conversation)

        assertTrue(coordinator.messages.isEmpty())
    }

    @Test
    fun currentWriterUpdateRenderStateApplies() = runTest {
        val coordinator = coordinator()
        val writer = assertNotNull(coordinator.bindWriter(WriterOwner(), ChatWriterRole.INTERACTIVE))

        writer.updateRenderState { it.copy(isLoadingOlder = true, totalFetchedCount = 7) }

        assertTrue(coordinator.renderState.isLoadingOlder)
        assertEquals(7, coordinator.renderState.totalFetchedCount)
    }

    @Test
    fun staleWriterUpdateRenderStateNoOps() = runTest {
        val coordinator = coordinator()
        val writerA = assertNotNull(coordinator.bindWriter(WriterOwner(), ChatWriterRole.INTERACTIVE))
        coordinator.bindWriter(WriterOwner(), ChatWriterRole.INTERACTIVE)
        val before = coordinator.renderState

        writerA.updateRenderState { it.copy(isLoadingOlder = true) }

        assertEquals(before, coordinator.renderState)
    }

    @Test
    fun windowOperationsDoNotInterleave() = runTest {
        val coordinator = coordinator()
        val order = mutableListOf<Int>()
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstMayFinish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val first = launch {
            coordinator.performWindowOperation {
                firstStarted.complete(Unit)
                firstMayFinish.await()
                order.add(1)
            }
        }
        firstStarted.await()
        val second = launch {
            coordinator.performWindowOperation { order.add(2) }
        }
        firstMayFinish.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf(1, 2), order)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(): ChatCoordinator =
        ChatCoordinatorRegistry(FakeCoordinatorStore(), backgroundScope)
            .coordinator(ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT))

    private class WriterOwner
}
