package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Mirrors iOS `IncomingAvatarIdentityTests`. */
class IncomingAvatarIdentityTest {
    @Test
    fun revisionOfNilOrEmptyIsNull() {
        assertNull(IncomingAvatarIdentity.revision(null))
        assertNull(IncomingAvatarIdentity.revision(ByteArray(0)))
    }

    @Test
    fun resolveTrimsSenderNodeNameBeforeTableLookup() {
        val photo = IncomingAvatarIdentity(
            name = "Alice",
            matchedContactID = Uuid.normalized("A0000000-0000-0000-0000-000000000001"),
            imageRevision = 1,
        )
        val resolved = IncomingAvatarIdentity.resolve(
            senderNodeName = " Alice ",
            displayName = " Alice ",
            table = mapOf("alice" to photo),
        )
        assertEquals(photo, resolved)
    }

    @Test
    fun resolveDoesNotUseDisplayNameAsATableKey() {
        val photo = IncomingAvatarIdentity(
            name = "Alice",
            matchedContactID = Uuid.normalized("A0000000-0000-0000-0000-000000000001"),
            imageRevision = 1,
        )
        val resolved = IncomingAvatarIdentity.resolve(
            senderNodeName = null,
            displayName = "Alice",
            table = mapOf("alice" to photo),
        )
        assertEquals(IncomingAvatarIdentity.initials(name = "Alice"), resolved)
    }

    @Test
    fun identitiesFromOmitsCollidingNames() {
        val alice = ChatFixtures.contact(name = "Alice")
        val alice2 = ChatFixtures.contact(name = "Alice")
        val bob = ChatFixtures.contact(name = "Bob")
        val table = IncomingAvatarIdentity.identitiesFrom(listOf(alice, alice2, bob))
        assertNull(table["alice"])
        assertEquals(bob.id, table["bob"]?.matchedContactID)
    }
}
