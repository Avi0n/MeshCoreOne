package one.meshcore.shared.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reverse golden: a checked-in envelope produced by [encodeBackupEnvelope] must stay stable and
 * round-trip through [decodeBackupEnvelope]. Swift's BackupFixtureTests also decode this file.
 */
class BackupKotlinExportFixtureTest {
    @Test
    fun kotlinReexportOfFullEnvelopeIsStableAndRoundTrips() {
        val source = decodeBackupEnvelope(loadBackupFixture("full-envelope.json"))
        val encoded = encodeBackupEnvelope(source)
        val checkedIn = loadBackupFixture("kotlin-reexport-full.json")
        assertEquals(
            checkedIn,
            encoded,
            "kotlin-reexport-full.json is stale; regenerate by writing encode(decode(full-envelope.json))",
        )
        val redecoded = decodeBackupEnvelope(encoded)
        assertEquals(source.version, redecoded.version)
        assertEquals(source.manifest, redecoded.manifest)
        assertEquals(source.devices.size, redecoded.devices.size)
        assertEquals(source.contacts.size, redecoded.contacts.size)
        assertTrue(redecoded.manifest.validate(redecoded))
    }
}
