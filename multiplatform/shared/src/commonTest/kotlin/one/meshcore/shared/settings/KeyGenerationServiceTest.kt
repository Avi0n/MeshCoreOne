package one.meshcore.shared.settings

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyGenerationServiceTest {
    @Test
    fun `maxAttempts scales with prefix length`() {
        assertEquals(10_000, KeyGenerationService.maxAttempts(0))
        assertEquals(10_000, KeyGenerationService.maxAttempts(1)) // 16*20 = 320 → floor 10000
        assertEquals(10_000, KeyGenerationService.maxAttempts(2)) // 256*20 = 5120 → floor 10000
        assertEquals(81_920, KeyGenerationService.maxAttempts(3)) // 4096*20
        assertEquals(1_310_720, KeyGenerationService.maxAttempts(4)) // 65536*20
    }

    @Test
    fun `reserved prefix throws`() = runTest {
        assertFailsWith<KeyGenerationService.Error.ReservedPrefix> {
            KeyGenerationService.generateIdentity(
                hexPrefix = "00",
                keypairFromSeed = fakeKeypair { seed -> fixedPair(seed, publicFirst = 0x11) },
            )
        }
        assertFailsWith<KeyGenerationService.Error.ReservedPrefix> {
            KeyGenerationService.generateIdentity(
                hexPrefix = "00A1",
                keypairFromSeed = fakeKeypair { seed -> fixedPair(seed, publicFirst = 0x11) },
            )
        }
    }

    @Test
    fun `nil prefix rejects reserved first public bytes and returns valid key`() = runTest {
        var n = 0
        val pair = KeyGenerationService.generateIdentity(
            hexPrefix = null,
            randomSeed = {
                n += 1
                ByteArray(32) { (n + it).toByte() }
            },
            keypairFromSeed = fakeKeypair { seed ->
                // First two seeds produce reserved public keys; third is fine.
                val first = when (seed[0].toInt() and 0xFF) {
                    1 -> 0x00
                    2 -> 0xFF
                    else -> 0x2B
                }
                fixedPair(seed, publicFirst = first)
            },
        )
        assertEquals(0x2B, pair.publicKey[0].toInt() and 0xFF)
        assertEquals(64, pair.expandedPrivateKey.size)
        assertEquals(32, pair.publicKey.size)
    }

    @Test
    fun `vanity prefix is respected`() = runTest {
        val pair = KeyGenerationService.generateIdentity(
            hexPrefix = "AB",
            randomSeed = { ByteArray(32) { 7 } },
            keypairFromSeed = fakeKeypair { seed ->
                // Always return public key starting with AB
                val public = ByteArray(32) { 0 }
                public[0] = 0xAB.toByte()
                public[1] = 0x00
                KeyGenerationService.GeneratedIdentity(
                    expandedPrivateKey = ByteArray(64) { seed.getOrElse(it) { 0 } },
                    publicKey = public,
                )
            },
        )
        assertEquals(0xAB, pair.publicKey[0].toInt() and 0xFF)
    }

    @Test
    fun `exhausted attempts throw MaxAttemptsExceeded`() = runTest {
        assertFailsWith<KeyGenerationService.Error.MaxAttemptsExceeded> {
            KeyGenerationService.generateIdentity(
                hexPrefix = "AA",
                randomSeed = { ByteArray(32) { 1 } },
                keypairFromSeed = fakeKeypair { seed ->
                    fixedPair(seed, publicFirst = 0x11) // never matches AA
                },
            )
        }
    }

    @Test
    fun `validateExpandedKey accepts a clamped 64-byte key and rejects everything else`() {
        val clamped = ByteArray(64) { 0x22 }
        clamped[0] = 0xF8.toByte() // low 3 bits clear
        clamped[31] = 0x40 // high bit clear, second-highest set
        assertTrue(KeyGenerationService.validateExpandedKey(clamped))

        assertFalse(KeyGenerationService.validateExpandedKey(ByteArray(32) { 0x22 })) // wrong size
        val unclampedLow = clamped.copyOf().also { it[0] = 0x07 }
        assertFalse(KeyGenerationService.validateExpandedKey(unclampedLow))
        val unclampedHighBitSet = clamped.copyOf().also { it[31] = 0xC0.toByte() }
        assertFalse(KeyGenerationService.validateExpandedKey(unclampedHighBitSet))
        val unclampedSecondBitClear = clamped.copyOf().also { it[31] = 0x00 }
        assertFalse(KeyGenerationService.validateExpandedKey(unclampedSecondBitClear))
    }

    private fun fakeKeypair(
        block: (ByteArray) -> KeyGenerationService.GeneratedIdentity,
    ): KeyGenerationService.KeypairFromSeed = KeyGenerationService.KeypairFromSeed { seed -> block(seed) }

    private fun fixedPair(seed: ByteArray, publicFirst: Int): KeyGenerationService.GeneratedIdentity {
        val public = ByteArray(32) { 0x22 }
        public[0] = publicFirst.toByte()
        val expanded = ByteArray(64)
        seed.copyInto(expanded, 0, 0, seed.size.coerceAtMost(32))
        // Satisfy size only — clamping not required for fake mining tests.
        expanded[0] = (expanded[0].toInt() and 0xF8).toByte()
        expanded[31] = ((expanded[31].toInt() and 0x7F) or 0x40).toByte()
        return KeyGenerationService.GeneratedIdentity(expandedPrivateKey = expanded, publicKey = public)
    }
}
