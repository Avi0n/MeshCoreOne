// PortedFrom: MC1Services/Tests/MC1ServicesTests/ChatTimelineWriterTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: WP-501 Supplementary live-positive witnesses for otherwise inert stale-writer inputs.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotMap
import com.meshcoreone.android.core.services.rendering.BaseColorSlot
import com.meshcoreone.android.core.services.rendering.ChatCoordinator
import com.meshcoreone.android.core.services.rendering.ChatCoordinatorRegistry
import com.meshcoreone.android.core.services.rendering.ChatRenderState
import com.meshcoreone.android.core.services.rendering.ChatTimelineWriter
import com.meshcoreone.android.core.services.rendering.ChatWriterRole
import com.meshcoreone.android.core.services.rendering.EnvInputs
import com.meshcoreone.android.core.services.rendering.MessageBuildInputs
import com.meshcoreone.android.core.services.rendering.MessageFragmentBuilder
import com.meshcoreone.android.core.services.rendering.NodeNameMatchKind
import com.meshcoreone.android.core.services.rendering.NodeNameResolution
import com.meshcoreone.android.core.services.rendering.PreviewLoadState
import java.lang.ref.Reference
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTimelineWriterObservablePredicateTest : RoomProcessTest() {
    @Test fun staleLoadingMutationsCannotHideBehindAnAlreadyLoadedPhase() = runTest {
        withWriters { f ->
            val initial = f.coordinator.renderState
            val initialID = f.coordinator.renderStateID
            assertEquals(ChatRenderState.LoadPhase.UNINITIALIZED, initial.phase)
            f.stale.beginLoading()
            assertEquals(initial, f.coordinator.renderState)
            assertEquals(initialID, f.coordinator.renderStateID)

            f.live.beginLoading()
            val loading = initial.copy(phase = ChatRenderState.LoadPhase.LOADING)
            assertEquals(loading, f.coordinator.renderState)
            assertEquals(initialID + 1UL, f.coordinator.renderStateID)
            f.stale.markLoaded()
            assertEquals(loading, f.coordinator.renderState)
            assertEquals(initialID + 1UL, f.coordinator.renderStateID)

            f.live.markLoaded()
            assertEquals(initial.copy(phase = ChatRenderState.LoadPhase.LOADED), f.coordinator.renderState)
            assertEquals(initialID + 2UL, f.coordinator.renderStateID)
            assertTrue(f.coordinator.messages.isEmpty())
            assertTrue(f.coordinator.messagesByID.isEmpty())
        }
    }

    @Test fun staleStatusAndRenderMutationsCannotHideBehindEmptyOrPolicyRejectedInputs() = runTest {
        withWriters { f ->
            val message = message()
            store.saveMessage(message)
            assertEquals(message, store.fetchMessage(EntityKey(RADIO, message.id)))
            f.live.replaceAll(listOf(message))
            val item = MessageFragmentBuilder.makeItem(message, inputs(message.id), EnvInputs.DEFAULT)
            f.live.appendRenderItem(item)
            val seeded = f.coordinator.renderState
            val seededID = f.coordinator.renderStateID
            assertEquals(listOf(item), seeded.items.toList())
            assertEquals(0L, seeded.itemIndexByID[message.id])
            assertFalse(item.grouping.showTimestamp)

            f.stale.updateRenderItem(message.id) { it.copy(grouping = it.grouping.copy(showTimestamp = true)) }
            assertEquals(seeded, f.coordinator.renderState)
            assertEquals(seededID, f.coordinator.renderStateID)
            f.live.updateRenderItem(message.id) { it.copy(grouping = it.grouping.copy(showTimestamp = true)) }
            val editedItem = item.copy(grouping = item.grouping.copy(showTimestamp = true))
            val edited = seeded.copy(items = listOf(editedItem).snapshot())
            assertEquals(edited, f.coordinator.renderState)
            assertEquals(seededID + 1UL, f.coordinator.renderStateID)

            // Keep the original DELIVERED input; only this supplemental control enables a legitimate downgrade.
            f.stale.applyStatusUpdate(message.id, MessageStatus.FAILED, userInitiated = true)
            assertEquals(listOf(message), f.coordinator.messages.toList())
            assertEquals(mapOf(message.id to message), f.coordinator.messagesByID)
            assertEquals(edited, f.coordinator.renderState)
            assertEquals(seededID + 1UL, f.coordinator.renderStateID)
            f.live.applyStatusUpdate(message.id, MessageStatus.FAILED, userInitiated = true)
            val failedMessage = message.copy(status = MessageStatus.FAILED)
            val failedItem = editedItem.copy(
                envelope = editedItem.envelope.copy(status = MessageStatus.FAILED, hasFailed = true),
                footer = editedItem.footer.copy(status = MessageStatus.FAILED),
            )
            val failed = edited.copy(items = listOf(failedItem).snapshot())
            assertEquals(listOf(failedMessage), f.coordinator.messages.toList())
            assertEquals(mapOf(message.id to failedMessage), f.coordinator.messagesByID)
            assertEquals(failed, f.coordinator.renderState)
            assertEquals(seededID + 3UL, f.coordinator.renderStateID)

            f.stale.removeRenderItem(message.id)
            assertEquals(failed, f.coordinator.renderState)
            assertEquals(seededID + 3UL, f.coordinator.renderStateID)
            f.live.removeRenderItem(message.id)
            assertEquals(
                failed.copy(items = SnapshotList.empty(), itemIndexByID = emptyMap<UUID, Long>().snapshotMap()),
                f.coordinator.renderState,
            )
            assertEquals(seededID + 4UL, f.coordinator.renderStateID)
            assertEquals(listOf(failedMessage), f.coordinator.messages.toList())
            assertEquals(mapOf(message.id to failedMessage), f.coordinator.messagesByID)
            assertEquals(message, store.fetchMessage(EntityKey(RADIO, message.id)), "Timeline writes do not persist status")
        }
    }

    @Test fun staleReloadCannotScheduleEitherOverloadWhileTheCurrentWriterReadsTheRealRoomCommit() = runTest {
        withWriters { f ->
            val message = message()
            store.saveMessage(message)
            f.live.replaceAll(listOf(message))
            val changed = message.copy(text = "committed reload")
            store.saveMessage(changed)
            assertEquals(changed, store.fetchMessage(EntityKey(RADIO, message.id)))
            val seededState = f.coordinator.renderState
            val seededID = f.coordinator.renderStateID

            f.stale.enqueueReload(message.id)
            assertNull(f.coordinator.coalescedReloadTask)
            f.stale.enqueueReload(setOf(message.id))
            assertNull(f.coordinator.coalescedReloadTask)
            runCurrent()
            assertEquals(listOf(message), f.coordinator.messages.toList())
            assertEquals(mapOf(message.id to message), f.coordinator.messagesByID)
            assertEquals(seededState, f.coordinator.renderState)
            assertEquals(seededID, f.coordinator.renderStateID)
            assertTrue(f.rebuilt.isEmpty())
            assertNull(f.coordinator.hardResetTask)
            assertNull(f.coordinator.buildItemsTask)

            f.live.enqueueReload(message.id)
            val firstReload = assertNotNull(f.coordinator.coalescedReloadTask)
            firstReload.join()
            assertFalse(firstReload.isCancelled)
            assertEquals(listOf(changed), f.coordinator.messages.toList())
            assertEquals(mapOf(message.id to changed), f.coordinator.messagesByID)
            assertEquals(listOf(message.id), f.rebuilt)
            assertEquals(seededID + 1UL, f.coordinator.renderStateID)
            assertEquals(seededState, f.coordinator.renderState)

            val changedAgain = changed.copy(text = "second committed reload")
            store.saveMessage(changedAgain)
            f.live.enqueueReload(setOf(message.id))
            val secondReload = assertNotNull(f.coordinator.coalescedReloadTask)
            assertTrue(secondReload !== firstReload)
            secondReload.join()
            assertFalse(secondReload.isCancelled)
            assertEquals(listOf(changedAgain), f.coordinator.messages.toList())
            assertEquals(mapOf(message.id to changedAgain), f.coordinator.messagesByID)
            assertEquals(listOf(message.id, message.id), f.rebuilt)
            assertEquals(seededID + 2UL, f.coordinator.renderStateID)
            assertEquals(seededState, f.coordinator.renderState)
            assertEquals(changedAgain, store.fetchMessage(EntityKey(RADIO, message.id)))
            assertNull(f.coordinator.hardResetTask)
            assertNull(f.coordinator.buildItemsTask)
        }
    }

    private suspend fun TestScope.withWriters(block: suspend (Writers) -> Unit) {
        val registry = ChatCoordinatorRegistry(store, backgroundScope, buildDispatcher = StandardTestDispatcher(testScheduler))
        val coordinator = registry.coordinator(ChatConversationID.dm(RADIO, CONTACT))
        val ownerA = Any()
        val ownerB = Any()
        val rebuilt = mutableListOf<UUID>()
        try {
            val stale = assertNotNull(coordinator.bindWriter(ownerA, ChatWriterRole.INTERACTIVE))
            assertTrue(stale.isCurrent)
            val live = assertNotNull(coordinator.bindWriter(ownerB, ChatWriterRole.INTERACTIVE, renderItemRebuilder = { rebuilt += it }))
            assertFalse(stale.isCurrent)
            assertTrue(live.isCurrent)
            block(Writers(coordinator, stale, live, rebuilt))
        } finally {
            withContext(NonCancellable) {
                val jobs = listOfNotNull(coordinator.buildItemsTask, coordinator.coalescedReloadTask, coordinator.hardResetTask)
                registry.clear()
                jobs.forEach { it.cancelAndJoin() }
                Reference.reachabilityFence(ownerA)
                Reference.reachabilityFence(ownerB)
            }
        }
    }

    private data class Writers(
        val coordinator: ChatCoordinator,
        val stale: ChatTimelineWriter,
        val live: ChatTimelineWriter,
        val rebuilt: List<UUID>,
    )

    private fun message() = MessageDTO(
        id = UUID.fromString("30000000-0000-0000-0000-000000000003"),
        radioId = RADIO,
        contactID = CONTACT,
        text = "hello",
        timestamp = 1u,
        createdAt = Instant.ofEpochSecond(1_704_067_200),
        direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED,
    )

    private fun inputs(id: UUID) = MessageBuildInputs(
        messageID = id, previewState = PreviewLoadState.IDLE, loadedPreview = null, cachedURL = null,
        isInlineImageURL = false, hasInlineImageRef = false, hasPreviewImageRef = false, hasPreviewIconRef = false,
        imageIsGIF = false, formattedText = null, baseColor = BaseColorSlot.INCOMING, formattedPath = null,
        senderResolution = NodeNameResolution("Sender", NodeNameMatchKind.EXACT),
        showTimestamp = false, showDirectionGap = false, showSenderName = false, showNewMessagesDivider = false,
    )

    private companion object {
        val RADIO = RadioId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
        val CONTACT: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
    }
}
