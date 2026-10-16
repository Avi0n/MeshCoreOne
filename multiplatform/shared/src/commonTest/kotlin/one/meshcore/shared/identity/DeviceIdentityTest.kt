package one.meshcore.shared.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.meshcore.shared.fixtures.loadRepoFixture
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Byte-locked against `fixtures/identity/derive-uuid.json`. Kotlin is a second implementation of
 * `DeviceIdentity.deriveUUID`, not of packet framing. Loading the file (not copying hex) is what
 * keeps the two sides from drifting.
 */
class DeviceIdentityTest {
    @Test
    fun matchesFrozenSha256UuidVectors() {
        val vectors = identityVectors()
        assertTrue(vectors.size >= MIN_VECTORS)
        for (vector in vectors) {
            assertEquals(Uuid.normalized(vector.uuid), DeviceIdentity.deriveUuid(vector.publicKey))
        }
    }

    @Test
    fun isDeterministicAndDistinctPerKey() {
        val vectors = identityVectors()
        val first = DeviceIdentity.deriveUuid(vectors[0].publicKey)
        val same = DeviceIdentity.deriveUuid(vectors[0].publicKey)
        assertEquals(first, same)
        if (vectors.size > 1) {
            assertNotEquals(DeviceIdentity.deriveUuid(vectors[1].publicKey), first)
        }
    }

    private data class Vector(val publicKey: ByteArray, val uuid: String)

    private fun identityVectors(): List<Vector> {
        val root = Json.parseToJsonElement(loadRepoFixture("identity/derive-uuid.json")).jsonObject
        return root.getValue("vectors").jsonArray.map { row ->
            val obj = row.jsonObject
            Vector(
                publicKey = hex(obj.getValue("publicKeyHex").jsonPrimitive.content),
                uuid = obj.getValue("uuid").jsonPrimitive.content,
            )
        }
    }

    private companion object {
        const val MIN_VECTORS = 2

        fun hex(value: String): ByteArray = requireNotNull(decodeHex(value))
    }
}
