package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mirrors the Swift `ChatCoordinatorTests`: the mutation/renderStateID/phase family, the stale-build guard,
 * the coalesced reload, and the sibling-sharing phase observation. Adds the S6 coalescing (N triggers -> 1
 * drain) and hard-reset coverage the spec §8.4 calls out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatCoordinatorTest {
    // region Mutations + renderStateID

    @Test
    fun `append adds a new message and bumps renderStateID`() = runTest {
        val coordinator = makeCoordinator()
        val message = ChatFixtures.message()
        val before = coordinator.renderStateID

        val inserted = coordinator.append(message)

        assertTrue(inserted)
        assertEquals(1, coordinator.messages.size)
        assertEquals(message, coordinator.messagesByID[message.id])
        assertEquals(before + 1uL, coordinator.renderStateID)
    }

    @Test
    fun `append is idempotent on duplicate id`() = runTest {
        val coordinator = makeCoordinator()
        val message = ChatFixtures.message()
        coordinator.append(message)
        val countBefore = coordinator.messages.size
        val idBefore = coordinator.renderStateID

        val inserted = coordinator.append(message)

        assertFalse(inserted)
        assertEquals(countBefore, coordinator.messages.size)
        assertEquals(idBefore, coordinator.renderStateID)
    }

    @Test
    fun `update no-ops on missing id`() = runTest {
        val coordinator = makeCoordinator()
        val idBefore = coordinator.renderStateID

        coordinator.update(ChatFixtures.freshUuid()) { it.copy(text = "x") }

        assertTrue(coordinator.messages.isEmpty())
        assertEquals(idBefore, coordinator.renderStateID)
    }

    @Test
    fun `update mutates an existing message in place and bumps renderStateID`() = runTest {
        val coordinator = makeCoordinator()
        val message = ChatFixtures.message(text = "before")
        coordinator.append(message)
        val idBefore = coordinator.renderStateID

        coordinator.update(message.id) { it.copy(text = "after") }

        assertEquals("after", coordinator.messages.first().text)
        assertEquals("after", coordinator.messagesByID[message.id]?.text)
        assertEquals(idBefore + 1uL, coordinator.renderStateID)
    }

    @Test
    fun `renderStateID increments on every mutation`() = runTest {
        val coordinator = makeCoordinator()
        val initial = coordinator.renderStateID

        coordinator.replaceAll(listOf(ChatFixtures.message()))
        coordinator.append(ChatFixtures.message())
        coordinator.update(coordinator.messages[0].id) { it }
        coordinator.remove(coordinator.messages[0].id)

        assertEquals(initial + 4uL, coordinator.renderStateID)
    }

    // endregion

    // region Stale-build guard

    @Test
    fun `rebuildItems fires renderStateInvalidated when setRenderState rejects stale build`() = runTest {
        val coordinator = makeCoordinator()
        val message = ChatFixtures.message()
        coordinator.append(message)

        var invalidated = 0
        coordinator.renderStateInvalidated = { invalidated += 1 }

        coordinator.rebuildItems(listOf(message to buildInputs(message.id)), EnvInputs.DEFAULT)
        // Advance renderStateID before the off-thread build lands, so the apply is rejected.
        coordinator.append(ChatFixtures.message())
        testScheduler.runCurrent()

        assertTrue(coordinator.renderState.items.isEmpty())
        assertEquals(1, invalidated)
    }

    @Test
    fun `rebuildItems applies when renderStateID is unchanged`() = runTest {
        val coordinator = makeCoordinator()
        val message = ChatFixtures.message()
        coordinator.append(message)

        coordinator.rebuildItems(listOf(message to buildInputs(message.id)), EnvInputs.DEFAULT)
        testScheduler.runCurrent()

        assertEquals(1, coordinator.renderState.items.size)
        assertEquals(message.id, coordinator.renderState.items.first().id)
    }

    @Test
    fun `setRenderState returns false on stale capturedID`() = runTest {
        val coordinator = makeCoordinator()
        val staleID = coordinator.renderStateID
        coordinator.append(ChatFixtures.message())

        val applied = coordinator.setRenderState(
            ChatRenderState.EMPTY.copy(hasMoreMessages = false),
            capturedID = staleID,
        )

        assertFalse(applied)
        assertTrue(coordinator.renderState.hasMoreMessages)
    }

    @Test
    fun `setRenderState applies when capturedID matches`() = runTest {
        val coordinator = makeCoordinator()
        val currentID = coordinator.renderStateID

        val applied = coordinator.setRenderState(
            ChatRenderState.EMPTY.copy(hasMoreMessages = false),
            capturedID = currentID,
        )

        assertTrue(applied)
        assertFalse(coordinator.renderState.hasMoreMessages)
    }

    // endregion

    // region Coalesced reload

    @Test
    fun `enqueueReload unions IDs into pendingReloadIDs`() = runTest {
        val coordinator = makeCoordinator()
        val id1 = ChatFixtures.freshUuid()
        val id2 = ChatFixtures.freshUuid()

        coordinator.enqueueReload(setOf(id1))
        coordinator.enqueueReload(setOf(id2))

        // The drain is scheduled but has not run yet; both unioned ids are still buffered.
        assertTrue(coordinator.pendingReloadIDs.contains(id1))
        assertTrue(coordinator.pendingReloadIDs.contains(id2))
        assertTrue(coordinator.reloadInFlight)
    }

    @Test
    fun `applyReloadedIDs invokes renderItemRebuilder after refreshing a DTO`() = runTest {
        val store = FakeCoordinatorStore()
        val coordinator = makeCoordinator(store)
        val initial = ChatFixtures.message(status = MessageStatus.SENDING)
        store.put(initial)
        coordinator.append(initial)

        val rebuilt = mutableListOf<Any>()
        coordinator.renderItemRebuilder = { rebuilt.add(it) }
        // An ack lands in the store after the coordinator loaded the row.
        store.put(initial.copy(status = MessageStatus.SENT))

        coordinator.enqueueReload(initial.id)
        testScheduler.runCurrent()

        assertFalse(coordinator.reloadInFlight)
        assertEquals(listOf<Any>(initial.id), rebuilt)
        assertEquals(MessageStatus.SENT, coordinator.messagesByID[initial.id]?.status)
    }

    @Test
    fun `applyReloadedIDs skips renderItemRebuilder for unknown IDs`() = runTest {
        val store = FakeCoordinatorStore()
        val coordinator = makeCoordinator(store)
        val pagedOut = ChatFixtures.message(status = MessageStatus.SENT)
        store.put(pagedOut) // present in the store but never appended to the coordinator

        val rebuilt = mutableListOf<Any>()
        coordinator.renderItemRebuilder = { rebuilt.add(it) }

        coordinator.enqueueReload(pagedOut.id)
        testScheduler.runCurrent()

        assertTrue(rebuilt.isEmpty())
    }

    @Test
    fun `a burst of reload triggers collapses into a single drain`() = runTest {
        val store = FakeCoordinatorStore()
        val coordinator = makeCoordinator(store)
        val messages = List(3) { ChatFixtures.message(status = MessageStatus.SENDING) }
        messages.forEach {
            store.put(it)
            coordinator.append(it)
            store.put(it.copy(status = MessageStatus.SENT))
        }

        // Three triggers before the scheduler runs: only the first launches a drain.
        messages.forEach { coordinator.enqueueReload(it.id) }
        assertEquals(1, coordinator.coalescedReloadLaunches)

        testScheduler.runCurrent()

        assertEquals(1, coordinator.coalescedReloadLaunches)
        assertFalse(coordinator.reloadInFlight)
        messages.forEach { assertEquals(MessageStatus.SENT, coordinator.messagesByID[it.id]?.status) }
    }

    @Test
    fun `an id enqueued mid-drain reconciles in the same drain`() = runTest {
        val store = FakeCoordinatorStore()
        val coordinator = makeCoordinator(store)
        val first = ChatFixtures.message(status = MessageStatus.SENDING)
        val second = ChatFixtures.message(status = MessageStatus.SENDING)
        listOf(first, second).forEach {
            store.put(it)
            coordinator.append(it)
        }
        store.put(first.copy(status = MessageStatus.SENT))
        store.put(second.copy(status = MessageStatus.DELIVERED))

        val gate = CompletableDeferred<Unit>()
        store.gateFirstFetch = gate

        coordinator.enqueueReload(first.id) // launches the drain; it parks on the gated first fetch
        testScheduler.runCurrent()
        assertEquals(1, coordinator.coalescedReloadLaunches)

        // Landing while the drain is parked must not start a second drain.
        coordinator.enqueueReload(second.id)
        assertEquals(1, coordinator.coalescedReloadLaunches)

        gate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(1, coordinator.coalescedReloadLaunches)
        assertFalse(coordinator.reloadInFlight)
        assertEquals(MessageStatus.SENT, coordinator.messagesByID[first.id]?.status)
        assertEquals(MessageStatus.DELIVERED, coordinator.messagesByID[second.id]?.status)
    }

    // endregion

    // region Hard reset

    @Test
    fun `a null fetch for a held id triggers a hard reset that refetches and invalidates`() = runTest {
        val store = FakeCoordinatorStore()
        val coordinator = makeCoordinator(store)
        val held = ChatFixtures.message(status = MessageStatus.SENDING)
        coordinator.append(held) // held in memory but absent from the store -> inconsistency

        val fresh = listOf(
            ChatFixtures.message(text = "fresh-1"),
            ChatFixtures.message(text = "fresh-2"),
        )
        store.directPage = fresh
        var invalidated = 0
        coordinator.renderStateInvalidated = { invalidated += 1 }

        coordinator.enqueueReload(held.id)
        testScheduler.runCurrent()

        assertEquals(fresh, coordinator.messages)
        assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
        assertEquals(fresh.size, coordinator.renderState.totalFetchedCount)
        assertFalse(coordinator.renderState.hasMoreMessages)
        assertFalse(coordinator.hardResetInFlight)
        assertEquals(1, invalidated)
    }

    @Test
    fun `hardReset refetches the loaded window and restamps paging flags`() = runTest {
        val rows = List(60) { index ->
            ChatFixtures.dmMessage(timestamp = (index + 1) * 1_000L)
        }
        val store = FakeChatThreadStore(rows = rows)
        val coordinator = makeCoordinator(store)
        coordinator.replaceAll(rows)
        coordinator.updateRenderState {
            it.copy(hasMoreMessages = false, totalFetchedCount = rows.size)
        }

        val ghost = ChatFixtures.dmMessage(timestamp = 99_000L)
        coordinator.append(ghost)

        var invalidated = 0
        coordinator.renderStateInvalidated = { invalidated += 1 }

        coordinator.enqueueReload(ghost.id)
        testScheduler.runCurrent()

        assertEquals(rows.map { it.id }, coordinator.messages.map { it.id })
        assertEquals(rows.size, coordinator.renderState.totalFetchedCount)
        assertFalse(coordinator.renderState.hasMoreMessages)
        assertEquals(1, invalidated)
    }

    @Test
    fun `updateRenderState applies the transform and bumps renderStateID`() = runTest {
        val coordinator = makeCoordinator()
        val before = coordinator.renderStateID

        coordinator.updateRenderState { it.copy(hasMoreMessages = false, totalFetchedCount = 12) }

        assertFalse(coordinator.renderState.hasMoreMessages)
        assertEquals(12, coordinator.renderState.totalFetchedCount)
        assertEquals(before + 1uL, coordinator.renderStateID)
    }

    // endregion

    // region applyStatusUpdate

    @Test
    fun `applyStatusUpdate blocks failed to pending unless userInitiated`() = runTest {
        val coordinator = makeCoordinator()
        val message = ChatFixtures.message(status = MessageStatus.PENDING)
        coordinator.append(message)

        coordinator.applyStatusUpdate(message.id, MessageStatus.FAILED)
        assertEquals(MessageStatus.FAILED, coordinator.messagesByID[message.id]?.status)

        coordinator.applyStatusUpdate(message.id, MessageStatus.PENDING)
        assertEquals(MessageStatus.FAILED, coordinator.messagesByID[message.id]?.status)

        coordinator.applyStatusUpdate(message.id, MessageStatus.PENDING, userInitiated = true)
        assertEquals(MessageStatus.PENDING, coordinator.messagesByID[message.id]?.status)
    }

    @Test
    fun `applyStatusUpdate preserves roundTripTime when null`() = runTest {
        val coordinator = makeCoordinator()
        val message = ChatFixtures.message(status = MessageStatus.SENT).copy(roundTripTime = 1234L)
        coordinator.append(message)

        coordinator.applyStatusUpdate(message.id, MessageStatus.DELIVERED)

        assertEquals(1234L, coordinator.messagesByID[message.id]?.roundTripTime)
        assertEquals(MessageStatus.DELIVERED, coordinator.messagesByID[message.id]?.status)
    }

    @Test
    fun `applyStatusUpdate rebuilds the rendered item envelope and footer status`() = runTest {
        val coordinator = makeCoordinator()
        val message = ChatFixtures.message(status = MessageStatus.SENDING)
        coordinator.append(message)
        coordinator.rebuildItems(listOf(message to buildInputs(message.id)), EnvInputs.DEFAULT)
        testScheduler.runCurrent()

        coordinator.applyStatusUpdate(message.id, MessageStatus.SENT)

        val item = coordinator.renderState.items.single()
        assertEquals(MessageStatus.SENT, item.envelope.status)
        assertEquals(MessageStatus.SENT, item.footer.status)
    }

    // endregion

    // region Load phase

    @Test
    fun `fresh coordinator starts uninitialized`() = runTest {
        val coordinator = makeCoordinator()
        assertEquals(ChatRenderState.LoadPhase.UNINITIALIZED, coordinator.renderState.phase)
    }

    @Test
    fun `beginLoading transitions uninitialized to loading and is a no-op afterward`() = runTest {
        val coordinator = makeCoordinator()
        val idBefore = coordinator.renderStateID

        coordinator.beginLoading()
        assertEquals(ChatRenderState.LoadPhase.LOADING, coordinator.renderState.phase)
        assertEquals(idBefore + 1uL, coordinator.renderStateID)

        val idAfter = coordinator.renderStateID
        coordinator.beginLoading()
        assertEquals(ChatRenderState.LoadPhase.LOADING, coordinator.renderState.phase)
        assertEquals(idAfter, coordinator.renderStateID)
    }

    @Test
    fun `markLoaded reaches loaded and is idempotent`() = runTest {
        val coordinator = makeCoordinator()
        coordinator.markLoaded()
        assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)

        val idBefore = coordinator.renderStateID
        coordinator.markLoaded()
        assertEquals(idBefore, coordinator.renderStateID)
    }

    @Test
    fun `replaceAll reaches loaded even when empty`() = runTest {
        val coordinator = makeCoordinator()
        coordinator.beginLoading()

        coordinator.replaceAll(emptyList())

        assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
        assertTrue(coordinator.messages.isEmpty())
    }

    @Test
    fun `phase is observed identically by siblings sharing one coordinator`() = runTest {
        val registry = ChatCoordinatorRegistry(
            store = FakeCoordinatorStore(),
            scope = backgroundScope,
            computeDispatcher = StandardTestDispatcher(testScheduler),
        )
        val conversationID = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        val a = registry.coordinator(conversationID)
        val b = registry.coordinator(conversationID)
        testScheduler.runCurrent()

        assertTrue(a === b)
        assertEquals(ChatRenderState.LoadPhase.UNINITIALIZED, a.renderState.phase)

        a.replaceAll(emptyList())

        assertEquals(ChatRenderState.LoadPhase.LOADED, a.renderState.phase)
        assertEquals(ChatRenderState.LoadPhase.LOADED, b.renderState.phase)
    }

    // endregion
}
