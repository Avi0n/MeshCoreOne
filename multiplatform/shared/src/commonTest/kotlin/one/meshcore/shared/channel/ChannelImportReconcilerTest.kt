package one.meshcore.shared.channel

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.sequentialTestUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Placement/merge/drop reconcile for [ChannelImportReconciler], the `(radioID, secret)` import core. These
 * freeze the volatile-slot trap: a secret match reconciles regardless of slot, empty-secret channels key by
 * slot, a slot collision relocates within capacity (never onto reserved slot 0) or drops, and slot indices
 * only ever surface as placement — never as identity.
 */
class ChannelImportReconcilerTest {
    private val radio: Uuid = ChannelTestIds.RADIO

    /** Deterministic id minter so inserted-channel identity is assertable. */
    private fun sequentialIds(): () -> Uuid {
        var n = 1000
        return { sequentialTestUuid(n++) }
    }

    @Test
    fun secretMatchMergesRegardlessOfSlotAndRemapsMessages() {
        val local = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 3, secretByte = 0xAA))
        val backup = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 5, secretByte = 0xAA))

        val plan = ChannelImportReconciler.reconcile(local, backup)

        val decision = plan.decisions.single()
        assertTrue(decision is ChannelImportDecision.MergeBySecret)
        assertEquals(local[0].id, decision.localId)
        assertEquals(3, decision.localIndex)
        assertEquals(mapOf(radio to mapOf(5 to 3)), plan.channelIndexRemap)
        assertEquals(0, plan.insertedCount)
        assertEquals(1, plan.mergedCount)
    }

    @Test
    fun secretMatchAtSameSlotRecordsNoRemap() {
        val local = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 2, secretByte = 0xAA))
        val backup = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 2, secretByte = 0xAA))

        val plan = ChannelImportReconciler.reconcile(local, backup)

        assertTrue(plan.decisions.single() is ChannelImportDecision.MergeBySecret)
        assertTrue(plan.channelIndexRemap.isEmpty())
    }

    @Test
    fun emptySecretReconcilesBySlot() {
        // An all-zero secret (unconfigured slot) has no stable identity, so it reconciles by slot.
        val local = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 0, secretByte = 0x00))
        val backup = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 0, secretByte = 0x00))

        val plan = ChannelImportReconciler.reconcile(local, backup)

        val decision = plan.decisions.single()
        assertTrue(decision is ChannelImportDecision.MergeBySlot)
        assertEquals(local[0].id, decision.localId)
    }

    @Test
    fun distinctEmptySecretSlotsDoNotCollapse() {
        val local = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 0, secretByte = 0x00))
        val backup = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 1, secretByte = 0x00))

        val plan = ChannelImportReconciler.reconcile(
            local,
            backup,
            maxChannelsByRadioID = mapOf(radio to 8),
            newLocalId = sequentialIds(),
        )

        val decision = plan.decisions.single()
        assertTrue(decision is ChannelImportDecision.Insert)
        assertEquals(1, decision.placementIndex)
    }

    @Test
    fun insertsAtOwnFreeSlot() {
        val backup = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 4, secretByte = 0xBB))

        val plan = ChannelImportReconciler.reconcile(emptyList(), backup, mapOf(radio to 8), sequentialIds())

        val decision = plan.decisions.single()
        assertTrue(decision is ChannelImportDecision.Insert)
        assertEquals(4, decision.placementIndex)
        assertEquals(mapOf(radio to setOf(4)), plan.insertedLocalIndices)
        assertTrue(plan.channelIndexRemap.isEmpty())
    }

    @Test
    fun relocatesToLowestFreeSlotWhenOwnSlotOccupied() {
        val local = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 2, secretByte = 0xAA))
        val backup = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 2, secretByte = 0xBB))

        val plan = ChannelImportReconciler.reconcile(local, backup, mapOf(radio to 8), sequentialIds())

        val decision = plan.decisions.single()
        assertTrue(decision is ChannelImportDecision.Insert)
        assertEquals(1, decision.placementIndex)
        assertEquals(mapOf(radio to mapOf(2 to 1)), plan.channelIndexRemap)
    }

    @Test
    fun neverRelocatesOntoReservedSlotZero() {
        // Slot 0 is free but reserved; slot 1 is taken, so a colliding channel must land at slot 2, not 0.
        val local = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 1, secretByte = 0xAA))
        val backup = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 1, secretByte = 0xBB))

        val plan = ChannelImportReconciler.reconcile(local, backup, mapOf(radio to 4), sequentialIds())

        val decision = plan.decisions.single()
        assertTrue(decision is ChannelImportDecision.Insert)
        assertEquals(2, decision.placementIndex)
    }

    @Test
    fun dropsWhenNoFreeSlotWithinCapacity() {
        val local = listOf(
            channelDto(ChannelTestIds.fresh(), radio, index = 0, secretByte = 0x00),
            channelDto(ChannelTestIds.fresh(), radio, index = 1, secretByte = 0xAA),
        )
        val backup = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 1, secretByte = 0xBB))

        val plan = ChannelImportReconciler.reconcile(local, backup, mapOf(radio to 2), sequentialIds())

        assertTrue(plan.decisions.single() is ChannelImportDecision.Drop)
        assertEquals(mapOf(radio to setOf(1)), plan.droppedChannelIndices)
        assertEquals(1, plan.droppedCount)
    }

    @Test
    fun zeroMaxChannelsDropsACollidingChannelRatherThanInventingCapacity() {
        val local = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 1, secretByte = 0xAA))
        val backup = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 1, secretByte = 0xBB))

        val plan = ChannelImportReconciler.reconcile(local, backup, mapOf(radio to 0), sequentialIds())

        assertTrue(plan.decisions.single() is ChannelImportDecision.Drop)
        assertEquals(mapOf(radio to setOf(1)), plan.droppedChannelIndices)
    }

    @Test
    fun unboundedCapacityBoundsSearchByHighestOccupiedPlusOne() {
        val local = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 3, secretByte = 0xAA))
        val backup = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 3, secretByte = 0xBB))

        val plan = ChannelImportReconciler.reconcile(local, backup, newLocalId = sequentialIds())

        val decision = plan.decisions.single()
        assertTrue(decision is ChannelImportDecision.Insert)
        assertEquals(1, decision.placementIndex)
    }

    @Test
    fun placementIsDeterministicRegardlessOfInputOrder() {
        val local = listOf(
            channelDto(ChannelTestIds.fresh(), radio, index = 1, secretByte = 0xA1),
            channelDto(ChannelTestIds.fresh(), radio, index = 2, secretByte = 0xA2),
        )
        val dtoAtOne = channelDto(ChannelTestIds.fresh(), radio, index = 1, secretByte = 0xB1)
        val dtoAtTwo = channelDto(ChannelTestIds.fresh(), radio, index = 2, secretByte = 0xB2)
        val max = mapOf(radio to 5)

        val forward = ChannelImportReconciler.reconcile(local, listOf(dtoAtOne, dtoAtTwo), max, sequentialIds())
        val reversed = ChannelImportReconciler.reconcile(local, listOf(dtoAtTwo, dtoAtOne), max, sequentialIds())

        assertEquals(forward.channelIndexRemap, reversed.channelIndexRemap)
        assertEquals(mapOf(radio to mapOf(1 to 3, 2 to 4)), forward.channelIndexRemap)
    }

    @Test
    fun sameSecretTwiceInBackupInsertsOnceThenMerges() {
        val backup = listOf(
            channelDto(ChannelTestIds.fresh(), radio, index = 4, secretByte = 0xEE),
            channelDto(ChannelTestIds.fresh(), radio, index = 6, secretByte = 0xEE),
        )

        val plan = ChannelImportReconciler.reconcile(emptyList(), backup, mapOf(radio to 8), sequentialIds())

        assertEquals(1, plan.insertedCount)
        assertEquals(1, plan.mergedCount)
        val insert = plan.decisions.filterIsInstance<ChannelImportDecision.Insert>().first()
        val merge = plan.decisions.filterIsInstance<ChannelImportDecision.MergeBySecret>().first()
        assertEquals(insert.localId, merge.localId)
        assertEquals(mapOf(radio to mapOf(6 to 4)), plan.channelIndexRemap)
    }

    @Test
    fun reconcilesPerRadioIndependently() {
        val otherRadio = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")
        val local = listOf(channelDto(ChannelTestIds.fresh(), radio, index = 2, secretByte = 0xAA))
        // Same secret bytes but a different radio must NOT match the other radio's channel.
        val backup = listOf(channelDto(ChannelTestIds.fresh(), otherRadio, index = 2, secretByte = 0xAA))

        val plan = ChannelImportReconciler.reconcile(local, backup, mapOf(otherRadio to 8), sequentialIds())

        val decision = plan.decisions.single()
        assertTrue(decision is ChannelImportDecision.Insert)
        assertEquals(2, decision.placementIndex)
        assertNull(plan.channelIndexRemap[otherRadio])
    }
}
