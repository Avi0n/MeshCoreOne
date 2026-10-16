package one.meshcore.shared.contact

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import one.meshcore.shared.backup.loadBackupFixture
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Kotlin side of the contact wire-format freeze. Decodes the checked-in vectors under
 * `fixtures/contact/` and asserts that both encoders emit byte-identical strings and both decoders
 * recover the identity. A drift here means a contact QR or share token generated on Android would no
 * longer scan/parse on iOS (and vice-versa).
 *
 * Fixtures are resolved as a sibling of the backup fixtures dir (`mc1.fixturesDir` points at
 * `fixtures/backup`), reusing the existing loader so this slice adds no per-platform test plumbing.
 */
class ContactInteropGoldenTest {
    @Serializable
    private data class UriVector(
        val label: String,
        val name: String,
        val publicKeyHex: String,
        val type: Int,
        val uri: String,
    )

    @Serializable
    private data class ShareValidVector(
        val label: String,
        val name: String,
        val expectedName: String,
        val publicKeyHex: String,
        val type: Int,
        val token: String,
    )

    @Serializable
    private data class ShareInvalidVector(val label: String, val token: String, val reason: String)

    @Serializable
    private data class ShareVectors(val valid: List<ShareValidVector>, val invalid: List<ShareInvalidVector>)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun uriVectors(): List<UriVector> = json.decodeFromString(loadBackupFixture("../contact/uri-vectors.json"))

        fun shareVectors(): ShareVectors =
            json.decodeFromString(loadBackupFixture("../contact/share-token-vectors.json"))

        fun contactType(raw: Int): ContactType = assertNotNull(ContactType.fromRaw(raw))
    }

    @Test
    fun everyUriVectorEmitsAndParsesByteIdentically() {
        for (vector in uriVectors()) {
            val key = assertNotNull(decodeHex(vector.publicKeyHex), vector.label)

            val emitted = ContactCardUri.export(vector.name, key, contactType(vector.type))
            assertEquals(vector.uri, emitted, "emit drift for '${vector.label}'")

            val parsed = assertNotNull(ContactCardUri.parse(vector.uri), "decode failed for '${vector.label}'")
            assertEquals(vector.name, parsed.name, vector.label)
            assertEquals(vector.publicKeyHex, parsed.id, vector.label)
            assertEquals(contactType(vector.type), parsed.contactType, vector.label)
        }
    }

    @Test
    fun everyValidShareVectorEmitsAndParsesByteIdentically() {
        for (vector in shareVectors().valid) {
            val key = assertNotNull(decodeHex(vector.publicKeyHex), vector.label)

            val emitted = ContactShareToken.format(key, contactType(vector.type), vector.name)
            assertEquals(vector.token, emitted, "emit drift for '${vector.label}'")

            val parsed = assertNotNull(ContactShareToken.parse(vector.token), "decode failed for '${vector.label}'")
            assertEquals(vector.expectedName, parsed.name, vector.label)
            assertEquals(vector.publicKeyHex, parsed.id, vector.label)
            assertEquals(contactType(vector.type), parsed.contactType, vector.label)
        }
    }

    @Test
    fun everyInvalidShareVectorIsRejected() {
        for (vector in shareVectors().invalid) {
            assertNull(ContactShareToken.parse(vector.token), "must reject '${vector.label}': ${vector.reason}")
        }
    }
}
