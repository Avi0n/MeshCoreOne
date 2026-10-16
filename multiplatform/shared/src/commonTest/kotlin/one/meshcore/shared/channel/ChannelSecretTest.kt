package one.meshcore.shared.channel

import one.meshcore.shared.model.decodeHex
import one.meshcore.shared.model.toUppercaseHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mirrors the secret/config cases of the iOS `ChannelServiceTests` (`hashSecret` size/determinism/
 * empty/unicode, `validateSecret`, `isChannelConfigured`) plus the public-channel constant and the
 * hashtag derivation rule. Independently-computed golden hex (Python `hashlib`, recorded in the slice
 * report) pins the actual bytes, not just structural properties.
 */
class ChannelSecretTest {
    private companion object {
        const val ZERO_SECRET_HEX = "00000000000000000000000000000000"
        const val PUBLIC_SECRET_HEX = "8B3387E9C5CDEA6AC9E5EDBAA115CD72"

        // SHA-256(utf8)[0..16], computed independently with hashlib.
        const val TEST_PASSPHRASE_HEX = "DD07B663EC5851EBE92C71FB0AD9BEF0"
        const val UNICODE_PASSPHRASE = "🔐 secure 密码"
        const val UNICODE_SECRET_HEX = "BA44E9D48F603D5B93F213B650F341F3"
        const val HASHTAG_AVION_HEX = "B612EF23CF94915B815BCEE46DABE902"

        fun bytes(hex: String): ByteArray = assertNotNull(decodeHex(hex))
    }

    @Test
    fun hashSecretProduces16ByteOutput() {
        assertEquals(ChannelSecret.SIZE_BYTES, ChannelSecret.hashSecret("test passphrase").size)
    }

    @Test
    fun hashSecretIsDeterministic() {
        assertContentEquals(ChannelSecret.hashSecret("same passphrase"), ChannelSecret.hashSecret("same passphrase"))
    }

    @Test
    fun hashSecretDiffersForDifferentInputs() {
        val one = ChannelSecret.hashSecret("passphrase one")
        val two = ChannelSecret.hashSecret("passphrase two")
        assertFalse(one.contentEquals(two))
    }

    @Test
    fun hashSecretHandlesEmptyStringWith16Zeros() {
        val secret = ChannelSecret.hashSecret("")
        assertEquals(ChannelSecret.SIZE_BYTES, secret.size)
        assertContentEquals(bytes(ZERO_SECRET_HEX), secret)
    }

    @Test
    fun hashSecretHandlesUnicode() {
        val secret = ChannelSecret.hashSecret(UNICODE_PASSPHRASE)
        assertEquals(ChannelSecret.SIZE_BYTES, secret.size)
        assertEquals(UNICODE_SECRET_HEX, secret.toUppercaseHex())
    }

    @Test
    fun hashSecretMatchesFrozenVector() {
        assertEquals(TEST_PASSPHRASE_HEX, ChannelSecret.hashSecret("test passphrase").toUppercaseHex())
    }

    @Test
    fun validateSecretAccepts16ByteSecrets() {
        assertTrue(ChannelSecret.validateSecret(ByteArray(ChannelSecret.SIZE_BYTES)))
    }

    @Test
    fun validateSecretRejectsWrongSizedSecrets() {
        assertFalse(ChannelSecret.validateSecret(ByteArray(ChannelSecret.SIZE_BYTES - 1)))
        assertFalse(ChannelSecret.validateSecret(ByteArray(ChannelSecret.SIZE_BYTES + 1)))
    }

    @Test
    fun isChannelConfiguredTrueForEmptyNameWithNonZeroSecret() {
        assertTrue(ChannelSecret.isChannelConfigured(name = "", secret = ByteArray(ChannelSecret.SIZE_BYTES) { 0x42 }))
    }

    @Test
    fun isChannelConfiguredFalseForEmptyNameWithZeroSecret() {
        assertFalse(ChannelSecret.isChannelConfigured(name = "", secret = ByteArray(ChannelSecret.SIZE_BYTES)))
    }

    @Test
    fun isChannelConfiguredTrueForNamedZeroSecretChannel() {
        assertTrue(ChannelSecret.isChannelConfigured(name = "Public", secret = ByteArray(ChannelSecret.SIZE_BYTES)))
    }

    @Test
    fun publicChannelSecretMatchesFrozenConstant() {
        assertEquals(PUBLIC_SECRET_HEX, ChannelSecret.publicChannelSecret().toUppercaseHex())
        assertEquals(ChannelSecret.SIZE_BYTES, ChannelSecret.publicChannelSecret().size)
        assertTrue(ChannelSecret.validateSecret(ChannelSecret.publicChannelSecret()))
    }

    @Test
    fun publicChannelSecretReturnsADefensiveCopy() {
        val first = ChannelSecret.publicChannelSecret()
        first[0] = 0
        assertEquals(PUBLIC_SECRET_HEX, ChannelSecret.publicChannelSecret().toUppercaseHex())
    }

    @Test
    fun hashtagChannelStringPrependsHash() {
        assertEquals("#avion-testing", ChannelSecret.hashtagChannelString("avion-testing"))
    }

    @Test
    fun hashtagSecretHashesTheHashPrefixedString() {
        // The '#' is part of the hashed input, so #name derives the same key everywhere.
        assertContentEquals(
            ChannelSecret.hashSecret("#avion-testing"),
            ChannelSecret.hashtagChannelSecret("avion-testing"),
        )
        assertEquals(HASHTAG_AVION_HEX, ChannelSecret.hashtagChannelSecret("avion-testing").toUppercaseHex())
    }

    @Test
    fun hasStableSecretOnlyForNonEmptyNonZero() {
        // Anchors the (radioID, secret) import key: only empty/all-zero (unconfigured) secrets are
        // unstable; the public channel's well-known constant is stable and reconciles by secret.
        assertTrue(ChannelSecret.hasStableSecret(ByteArray(ChannelSecret.SIZE_BYTES) { 0xCD.toByte() }))
        assertFalse(ChannelSecret.hasStableSecret(ByteArray(ChannelSecret.SIZE_BYTES)))
        assertFalse(ChannelSecret.hasStableSecret(ByteArray(0)))
        assertTrue(ChannelSecret.hasStableSecret(ChannelSecret.publicChannelSecret()))
    }
}
