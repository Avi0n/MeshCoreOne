package one.meshcore.shared.channel

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import one.meshcore.shared.backup.loadBackupFixture
import one.meshcore.shared.model.decodeHex
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.room.RoomMessageDedup
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Kotlin side of the channel/room wire-format freeze. Decodes the checked-in vectors under
 * `fixtures/channel/` and asserts the derivations, the URI encoder, and the dedup-key generator all
 * reproduce the frozen bytes/strings. A drift here means a channel QR, a passphrase-derived key, or a
 * room dedup key generated on Android would no longer match iOS.
 *
 * Fixtures resolve as a sibling of the backup fixtures dir (`mc1.fixturesDir` points at
 * `fixtures/backup`), reusing the existing loader so this slice adds no per-platform test plumbing.
 */
class ChannelInteropGoldenTest {
    @Serializable
    private data class SecretVector(val label: String, val passphrase: String, val secretHex: String)

    @Serializable
    private data class HashtagVector(
        val label: String,
        val cleanName: String,
        val hashedString: String,
        val secretHex: String,
    )

    @Serializable
    private data class UriVector(
        val label: String,
        val name: String,
        val secretHex: String,
        val uri: String,
        val iosProducerParity: Boolean,
    )

    @Serializable
    private data class DedupVector(
        val label: String,
        val timestamp: Long,
        val authorHex: String,
        val text: String,
        val key: String,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun secretVectors(): List<SecretVector> =
            json.decodeFromString(loadBackupFixture("../channel/secret-vectors.json"))

        fun hashtagVectors(): List<HashtagVector> =
            json.decodeFromString(loadBackupFixture("../channel/hashtag-vectors.json"))

        fun uriVectors(): List<UriVector> = json.decodeFromString(loadBackupFixture("../channel/uri-vectors.json"))

        fun dedupVectors(): List<DedupVector> =
            json.decodeFromString(loadBackupFixture("../channel/dedup-vectors.json"))
    }

    @Test
    fun everySecretVectorDerivesTheFrozenSecret() {
        for (vector in secretVectors()) {
            val derived = ChannelSecret.hashSecret(vector.passphrase)
            assertEquals(vector.secretHex, derived.toUppercaseHex(), "secret drift for '${vector.label}'")
            assertEquals(ChannelSecret.SIZE_BYTES, derived.size, vector.label)
        }
    }

    @Test
    fun everyHashtagVectorDerivesTheFrozenSecret() {
        for (vector in hashtagVectors()) {
            assertEquals(vector.hashedString, ChannelSecret.hashtagChannelString(vector.cleanName), vector.label)
            val derived = ChannelSecret.hashtagChannelSecret(vector.cleanName)
            assertEquals(vector.secretHex, derived.toUppercaseHex(), "hashtag secret drift for '${vector.label}'")
        }
    }

    @Test
    fun everyUriVectorEmitsAndParsesByteIdentically() {
        for (vector in uriVectors()) {
            val secret = assertNotNull(decodeHex(vector.secretHex), vector.label)

            val emitted = ChannelUri.export(vector.name, secret)
            assertEquals(vector.uri, emitted, "emit drift for '${vector.label}'")

            val parsed = assertNotNull(ChannelUri.parse(vector.uri), "decode failed for '${vector.label}'")
            assertEquals(vector.name, parsed.name, vector.label)
            assertEquals(vector.secretHex, parsed.id, vector.label)
            assertContentEquals(secret, parsed.secret, vector.label)
        }
    }

    @Test
    fun everyDedupVectorReproducesTheFrozenKey() {
        for (vector in dedupVectors()) {
            val author = assertNotNull(decodeHex(vector.authorHex), vector.label)
            val key = RoomMessageDedup.generateDeduplicationKey(vector.timestamp.toUInt(), author, vector.text)
            assertEquals(vector.key, key, "dedup key drift for '${vector.label}'")
        }
    }
}
