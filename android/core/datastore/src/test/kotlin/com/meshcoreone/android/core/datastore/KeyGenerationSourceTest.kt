// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/KeyGenerationServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.datastore

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class KeyGenerationSourceTest {
    private val generator = KeyGenerationService()

    @OriginalCase("KeyGenerationServiceTests::Generated expanded key is 64 bytes and public key is 32 bytes()")
    @Test
    fun keyWidths() = runBlocking {
        val result = generator.generateIdentity(null)
        assertEquals(64, result.expandedPrivateKey.size)
        assertEquals(32, result.publicKey.size)
    }

    @OriginalCase("KeyGenerationServiceTests::Public key never starts with 0x00 or 0xFF()")
    @Test
    fun reservedFirstBytesNeverGenerated() = runBlocking {
        repeat(20) {
            val first = generator.generateIdentity(null).publicKey[0]
            assertNotEquals(0.toUByte(), first)
            assertNotEquals(255.toUByte(), first)
        }
    }

    @OriginalCase("KeyGenerationServiceTests::2-char vanity prefix is respected(prefix : String)", 3)
    @Test
    fun twoCharacterVanityFamily() = runBlocking {
        for (prefix in listOf("AA", "42", "7F")) assertVanity(prefix)
    }

    @OriginalCase("KeyGenerationServiceTests::1-char vanity prefix is respected(prefix : String)", 3)
    @Test
    fun oneCharacterVanityFamily() = runBlocking {
        for (prefix in listOf("A", "7", "3")) assertVanity(prefix)
    }

    @OriginalCase("KeyGenerationServiceTests::3-char vanity prefix is respected()")
    @Test
    fun threeCharacterVanity() = runBlocking { assertVanity("A7B") }

    @OriginalCase("KeyGenerationServiceTests::4-char vanity prefix is respected()")
    @Test
    fun fourCharacterVanity() = runBlocking { assertVanity("A7B2") }

    @OriginalCase("KeyGenerationServiceTests::Nil prefix generates any valid key()")
    @Test
    fun nilAcceptsAnyValidKey() = runBlocking {
        assertEquals(32, generator.generateIdentity(null).publicKey.size)
    }

    @OriginalCase("KeyGenerationServiceTests::Reserved prefix '00' throws reservedPrefix error()")
    @Test
    fun zeroReserved() = runBlocking<Unit> { assertFailsWith<KeyGenerationFailure.ReservedPrefix> { generator.generateIdentity("00") } }

    @OriginalCase("KeyGenerationServiceTests::Reserved prefix 'FF' throws reservedPrefix error()")
    @Test
    fun ffReserved() = runBlocking<Unit> { assertFailsWith<KeyGenerationFailure.ReservedPrefix> { generator.generateIdentity("FF") } }

    @OriginalCase("KeyGenerationServiceTests::Reserved multi-char prefix '00A1' throws reservedPrefix error()")
    @Test
    fun longerZeroReserved() = runBlocking<Unit> { assertFailsWith<KeyGenerationFailure.ReservedPrefix> { generator.generateIdentity("00A1") } }

    @OriginalCase("KeyGenerationServiceTests::Reserved multi-char prefix 'FFB2' throws reservedPrefix error()")
    @Test
    fun longerFFReserved() = runBlocking<Unit> { assertFailsWith<KeyGenerationFailure.ReservedPrefix> { generator.generateIdentity("FFB2") } }

    @OriginalCase("KeyGenerationServiceTests::Single-char '0' is not reserved and succeeds()")
    @Test
    fun singleZeroAllowed() = runBlocking { assertVanity("0") }

    @OriginalCase("KeyGenerationServiceTests::Single-char 'F' is not reserved and succeeds()")
    @Test
    fun singleFAllowed() = runBlocking { assertVanity("F") }

    @Test
    fun cancellationBeforeStartNeverReachesEntropy() = runBlocking {
        var calls = 0
        val cancellable = KeyGenerationService(SeedSource { calls++; rfc8032Seed })
        val task = launch(start = CoroutineStart.LAZY) { cancellable.generateIdentity("0101") }
        task.cancel()
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(0, calls)
    }

    @OriginalCase("KeyGenerationServiceTests::Cancellation is respected()")
    @Test
    fun cancellationStopsAnExecutingVanitySearch() = runTest(timeout = 10.seconds) {
        var calls = 0
        var completed = false
        var cleanedUp = false
        lateinit var task: Deferred<GeneratedIdentity>
        val cancellation = CancellationException("Test-only active search cancellation")
        val cancellable = KeyGenerationService(
            SeedSource {
                calls++
                assertTrue(calls <= 2, "Cancelled search must not request another seed")
                // Reaching the second seed proves the first nonmatching attempt completed.
                if (calls == 2) task.cancel(cancellation)
                rfc8032Seed
            },
            StandardTestDispatcher(testScheduler),
        )
        assertFalse(rfc8032PublicKey.hexUppercase().startsWith("AA"))
        task = async {
            try {
                cancellable.generateIdentity("AA").also { completed = true }
            } finally {
                cleanedUp = true
            }
        }

        val failure = assertFailsWith<CancellationException> { task.await() }
        assertEquals(cancellation.message, failure.message)
        assertTrue(task.isCancelled)
        assertTrue(task.isCompleted)
        assertTrue(cleanedUp)
        assertFalse(completed)
        assertEquals(2, calls)
    }

    @OriginalCase("KeyGenerationServiceTests::Expanded key has correct SHA-512 clamping()")
    @Test
    fun scalarClamping() = runBlocking {
        val key = generator.generateIdentity(null).expandedPrivateKey
        assertEquals(0, key[0].toInt() and 7)
        assertEquals(0, key[31].toInt() and 128)
        assertEquals(64, key[31].toInt() and 64)
    }

    @OriginalCase("KeyGenerationServiceTests::Expansion matches manual SHA-512 + clamp()")
    @Test
    fun independentRfcSeedAndSingleSha512Expansion() = runBlocking {
        val identity = KeyGenerationService(SeedSource { rfc8032Seed }).generateIdentity(null)
        val expected = MessageDigest.getInstance("SHA-512").digest(rfc8032Seed.toByteArray())
        expected[0] = (expected[0].toInt() and 248).toByte()
        expected[31] = ((expected[31].toInt() and 127) or 64).toByte()
        assertEquals(Bytes(expected), identity.expandedPrivateKey)
        assertEquals(rfc8032PublicKey, identity.publicKey)
        assertNotEquals(identity.expandedPrivateKey.prefix(32), identity.publicKey)
    }

    @OriginalCase("KeyGenerationServiceTests::randomGenerationFailed error has a description()")
    @Test
    fun randomErrorDescription() {
        assertTrue(checkNotNull(KeyGenerationFailure.RandomGenerationFailed().message).isNotEmpty())
    }

    @OriginalCase("KeyGenerationServiceTests::Multiple generations produce different keys()")
    @Test
    fun generationsDiffer() = runBlocking {
        val a = generator.generateIdentity(null)
        val b = generator.generateIdentity(null)
        assertNotEquals(a.publicKey, b.publicKey)
        assertNotEquals(a.expandedPrivateKey, b.expandedPrivateKey)
    }

    @OriginalCase("KeyGenerationServiceTests::Valid expanded key passes validation()")
    @Test
    fun validExpandedKey() = runBlocking {
        KeyGenerationService.validateExpandedKey(generator.generateIdentity(null).expandedPrivateKey)
    }

    @OriginalCase("KeyGenerationServiceTests::Wrong length throws invalidKey(length : Int)", 4)
    @Test
    fun wrongLengthFamily() {
        for (length in listOf(32, 63, 65, 0)) {
            assertFailsWith<KeyGenerationFailure.InvalidKey> {
                KeyGenerationService.validateExpandedKey(Bytes(ByteArray(length) { 64 }))
            }
        }
    }

    @OriginalCase("KeyGenerationServiceTests::Bad clamping byte 0 (lowest 3 bits set) throws invalidKey()")
    @Test
    fun scalarFirstByteInvalid() = runBlocking<Unit> {
        val key = generator.generateIdentity(null).expandedPrivateKey.toByteArray()
        key[0] = (key[0].toInt() or 7).toByte()
        assertFailsWith<KeyGenerationFailure.InvalidKey> { KeyGenerationService.validateExpandedKey(Bytes(key)) }
    }

    @OriginalCase("KeyGenerationServiceTests::Bad clamping byte 31 (highest bit set) throws invalidKey()")
    @Test
    fun scalarHighBitInvalid() = runBlocking<Unit> {
        val key = generator.generateIdentity(null).expandedPrivateKey.toByteArray()
        key[31] = (key[31].toInt() or 128).toByte()
        assertFailsWith<KeyGenerationFailure.InvalidKey> { KeyGenerationService.validateExpandedKey(Bytes(key)) }
    }

    @OriginalCase("KeyGenerationServiceTests::Bad clamping byte 31 (second-highest bit clear) throws invalidKey()")
    @Test
    fun scalarRequiredBitInvalid() = runBlocking<Unit> {
        val key = generator.generateIdentity(null).expandedPrivateKey.toByteArray()
        key[31] = (key[31].toInt() and 64.inv()).toByte()
        assertFailsWith<KeyGenerationFailure.InvalidKey> { KeyGenerationService.validateExpandedKey(Bytes(key)) }
    }

    @OriginalCase("KeyGenerationServiceTests::invalidKey error has a description()")
    @Test
    fun invalidKeyDescription() {
        assertTrue(checkNotNull(KeyGenerationFailure.InvalidKey().message).isNotEmpty())
    }

    @OriginalCase("KeyGenerationServiceTests::Validation works on a non-zero-based Data slice()")
    @Test
    fun nonzeroOffsetSlice() = runBlocking {
        val key = generator.generateIdentity(null).expandedPrivateKey
        val padded = ByteArray(4) { -1 } + key.toByteArray()
        val slice = Bytes(padded.copyOfRange(4, padded.size))
        assertEquals(key, slice)
        KeyGenerationService.validateExpandedKey(slice)
    }

    private suspend fun assertVanity(prefix: String) {
        val result = generator.generateIdentity(prefix)
        assertTrue(result.publicKey.hexUppercase().startsWith(prefix), "Required source prefix $prefix")
        KeyGenerationService.validateExpandedKey(result.expandedPrivateKey)
        assertEquals(result.publicKey, KeyGenerationService.importExpandedKey(result.expandedPrivateKey).publicKey)
    }
}
