package one.meshcore.shared.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.meshcore.shared.fixtures.loadRepoFixture
import one.meshcore.shared.model.decodeHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Byte-locked against `fixtures/crypto/ed25519-x25519.json`. Kotlin is a second implementation of
 * the birational map, not of packet framing. Loading the file (not copying hex) is what keeps the
 * two sides from drifting.
 */
class Ed25519ToX25519FixtureTest {
    @Test
    fun matchesFrozenBirationalMapVectors() {
        val vectors = cryptoVectors()
        assertTrue(vectors.size >= MIN_VECTORS)
        for (vector in vectors) {
            assertContentEquals(vector.x25519, ed25519PublicToX25519(vector.ed25519))
        }
    }

    @Test
    fun wrongLengthIsRejected() {
        assertNull(ed25519PublicToX25519(ByteArray(0)))
        assertNull(ed25519PublicToX25519(ByteArray(SHORT_KEY_SIZE)))
    }

    private data class Vector(val ed25519: ByteArray, val x25519: ByteArray)

    private fun cryptoVectors(): List<Vector> {
        val root = Json.parseToJsonElement(loadRepoFixture("crypto/ed25519-x25519.json")).jsonObject
        return root.getValue("vectors").jsonArray.map { row ->
            val obj = row.jsonObject
            Vector(
                ed25519 = hex(obj.getValue("ed25519PublicHex").jsonPrimitive.content),
                x25519 = hex(obj.getValue("x25519PublicHex").jsonPrimitive.content),
            )
        }
    }

    private companion object {
        const val MIN_VECTORS = 2
        const val SHORT_KEY_SIZE = 16

        fun hex(value: String): ByteArray = requireNotNull(decodeHex(value))
    }
}
