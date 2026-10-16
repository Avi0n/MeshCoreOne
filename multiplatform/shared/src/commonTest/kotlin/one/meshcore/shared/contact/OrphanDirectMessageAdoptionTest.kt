package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrphanDirectMessageAdoptionTest {
    private val contactId = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
    private val otherId = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")
    private val messageId = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDDDD")
    private val key = ByteArray(32) { 0x41 }

    @Test
    fun uniquePrefixMatchAdoptsAndBumpsUnread() {
        val plan = planOrphanDirectMessageAdoption(
            orphans = listOf(orphan(prefix = key.copyOf(6))),
            contacts = listOf(OrphanDmContact(contactId, key, isBlocked = false)),
        )
        assertEquals(setOf(contactId), plan.adoptedContactIDs)
        assertEquals(1, plan.unreadByContact[contactId])
        assertEquals(messageId, plan.matches.single().messageId)
        assertTrue(plan.matches.single().deduplicationKey.startsWith("dm-${contactId.value}-"))
    }

    @Test
    fun channelAndOutgoingAreNotInTheOrphanListSoPlannerNeverSeesThem() {
        val plan = planOrphanDirectMessageAdoption(orphans = emptyList(), contacts = listOf(contact()))
        assertTrue(plan.matches.isEmpty())
    }

    @Test
    fun reactionWireTextIsSkipped() {
        val plan = planOrphanDirectMessageAdoption(
            orphans = listOf(orphan(text = "👍\n7f3a9c12")),
            contacts = listOf(contact()),
        )
        assertTrue(plan.matches.isEmpty())
    }

    @Test
    fun ambiguousPrefixIsSkipped() {
        val shared = ByteArray(6) { 0x42 }
        val a = OrphanDmContact(contactId, shared + ByteArray(26) { 0x01 }, isBlocked = false)
        val b = OrphanDmContact(otherId, shared + ByteArray(26) { 0x02 }, isBlocked = false)
        val plan = planOrphanDirectMessageAdoption(
            orphans = listOf(orphan(prefix = shared)),
            contacts = listOf(a, b),
        )
        assertTrue(plan.matches.isEmpty())
    }

    @Test
    fun blockedContactAdoptsWithoutUnreadBump() {
        val plan = planOrphanDirectMessageAdoption(
            orphans = listOf(orphan(prefix = key.copyOf(6), containsSelfMention = true)),
            contacts = listOf(OrphanDmContact(contactId, key, isBlocked = true)),
        )
        assertEquals(setOf(contactId), plan.adoptedContactIDs)
        assertTrue(plan.unreadByContact.isEmpty())
        assertTrue(plan.mentionByContact.isEmpty())
    }

    @Test
    fun mentionBumpRequiresUnseenSelfMention() {
        val plan = planOrphanDirectMessageAdoption(
            orphans = listOf(orphan(prefix = key.copyOf(6), containsSelfMention = true, mentionSeen = false)),
            contacts = listOf(contact()),
        )
        assertEquals(1, plan.mentionByContact[contactId])
    }

    private fun contact() = OrphanDmContact(contactId, key, isBlocked = false)

    private fun orphan(
        prefix: ByteArray? = key.copyOf(6),
        text: String = "hello",
        containsSelfMention: Boolean = false,
        mentionSeen: Boolean = false,
    ) = OrphanDmMessage(
        id = messageId,
        senderKeyPrefix = prefix,
        text = text,
        timestamp = 1_700_000_000L,
        senderNodeName = null,
        sortDate = 1_700_000_000.0,
        isRead = false,
        containsSelfMention = containsSelfMention,
        mentionSeen = mentionSeen,
    )
}
