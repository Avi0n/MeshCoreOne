package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContactFieldUpdateContractTest {
    @Test fun roomReturnsPriorRowAndPreservesIdentityAndPin() = prior(room = true)

    @Test fun memoryReturnsPriorRowAndPreservesIdentityAndPin() = prior(room = false)

    @Test fun roomMissingTargetDoesNotRunTransformOrInsert() = missing(room = true)

    @Test fun memoryMissingTargetDoesNotRunTransformOrInsert() = missing(room = false)

    private fun prior(room: Boolean) = exercise(room) { store, previous ->
        var invocations = 0
        val result = store.updateContactFields(previous.id) { current ->
            invocations++
            assertEquals(encoded(previous), encoded(current))
            current.copy(
                id = Uuid.random(),
                radioID = Uuid.random(),
                publicKey = byteArrayOf(31),
                isPinned = !current.isPinned,
                nickname = EDITED_NICKNAME,
            )
        }
        assertEquals(1, invocations)
        assertEquals(encoded(previous), encoded(result))
        assertEquals(encoded(previous.copy(nickname = EDITED_NICKNAME)), encoded(store.fetchContact(previous.id)))
    }

    private fun missing(room: Boolean) = exercise(room) { store, previous ->
        val missing = Uuid.random()
        val before = store.fetchContacts(previous.radioID).map(::encoded)
        var invocations = 0
        assertNull(
            store.updateContactFields(missing) { current ->
                invocations++
                current
            },
        )
        assertEquals(0, invocations)
        assertNull(store.fetchContact(missing))
        assertEquals(before, store.fetchContacts(previous.radioID).map(::encoded))
    }

    private fun exercise(room: Boolean, block: suspend (ContactStore, ContactDto) -> Unit) {
        val rows = RoomContactDeleteAtomicityFixture()
        try {
            runBlocking {
                rows.seed()
                val previous = requireNotNull(rows.store.fetchContact(rows.firstContact)).copy(isPinned = true)
                val store = if (room) rows.store else InMemoryContactStore()
                store.saveContact(previous)
                val seeded = requireNotNull(store.fetchContact(previous.id))
                block(store, seeded)
            }
        } finally {
            rows.close()
        }
    }

    private fun encoded(dto: ContactDto?) = JSON.encodeToJsonElement(dto)

    companion object {
        private const val EDITED_NICKNAME = "Field edit"
        private val JSON = Json { encodeDefaults = true }
    }
}
