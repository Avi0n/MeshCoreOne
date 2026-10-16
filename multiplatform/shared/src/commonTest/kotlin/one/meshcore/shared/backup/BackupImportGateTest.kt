package one.meshcore.shared.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The gates Swift `parseBackup` applies before a byte of a backup is merged: compressed-size cap,
 * format-version ceiling, and manifest integrity.
 */
class BackupImportGateTest {
    @Test
    fun validateForImportAcceptsAWellFormedEnvelope() {
        envelope().validateForImport()
    }

    @Test
    fun validateForImportRejectsANewerFormatVersion() {
        val future = envelope().copy(version = AppBackupEnvelope.CURRENT_VERSION + 1)
        val error = assertFailsWith<AppBackupError.UnsupportedVersion> { future.validateForImport() }
        assertEquals(AppBackupEnvelope.CURRENT_VERSION + 1, error.found)
        assertEquals(AppBackupEnvelope.CURRENT_VERSION, error.maxSupported)
    }

    @Test
    fun validateForImportRejectsAManifestThatDisagreesWithTheArrays() {
        // A truncated file: the manifest still declares the contacts the arrays no longer carry.
        val truncated = envelope().copy(contacts = emptyList())
        assertFailsWith<AppBackupError.CorruptedManifest> { truncated.validateForImport() }
    }

    @Test
    fun fileBytesOverTheCompressedCapAreRejectedBeforeDecompression() {
        val oversized = ByteArray(CAP_BYTES + 1)
        val error =
            assertFailsWith<AppBackupError.FileTooLarge> {
                backupFileBytesToJsonText(oversized, maxCompressedBytes = CAP_BYTES)
            }
        assertEquals(CAP_BYTES + 1, error.actualBytes)
        assertEquals(CAP_BYTES, error.maxBytes)
    }

    /**
     * A file whose array key is gone must fail to decode. If the array defaulted to empty and its
     * manifest count defaulted to zero, the integrity gate would compare two defaults, agree, and
     * report a successful restore of a family that is simply missing.
     */
    @Test
    fun anEnvelopeMissingARequiredFamilyKeyFailsToDecode() {
        val complete = encodeBackupEnvelope(envelope())
        for (key in REQUIRED_FAMILY_KEYS) {
            val truncated = jsonWithoutTopLevelKey(complete, key)
            assertFailsWith<AppBackupError.InvalidFile>("removing \"$key\" must fail decode") {
                decodeBackupEnvelope(truncated)
            }
        }
    }

    /**
     * The manifest counts are the other half of the same gate: dropping only the count key must fail
     * too, otherwise a hand-edited manifest silently shrinks to whatever the arrays happen to hold.
     */
    @Test
    fun aManifestMissingARequiredCountKeyFailsToDecode() {
        val complete = encodeBackupEnvelope(envelope())
        for (key in REQUIRED_MANIFEST_KEYS) {
            val truncated = jsonWithoutManifestKey(complete, key)
            assertFailsWith<AppBackupError.InvalidFile>("removing manifest \"$key\" must fail decode") {
                decodeBackupEnvelope(truncated)
            }
        }
    }

    /** The two keys added after the format shipped stay optional, so older envelopes still decode. */
    @Test
    fun theLateAddedDiscoveredNodeKeysRemainOptional() {
        val complete = encodeBackupEnvelope(envelope())
        val withoutArray = jsonWithoutTopLevelKey(complete, "discoveredNodes")
        val withoutBoth = jsonWithoutManifestKey(withoutArray, "discoveredNodeCount")

        val decoded = decodeBackupEnvelope(withoutBoth)
        assertEquals(emptyList(), decoded.discoveredNodes)
        assertEquals(0, decoded.manifest.discoveredNodeCount)
        decoded.validateForImport()
    }

    /** Every frozen cross-platform vector must still import under the tightened decode. */
    @Test
    fun theFrozenFixturesStillDecodeAndClearTheGate() {
        for (name in FROZEN_FIXTURES) {
            val envelope = decodeBackupEnvelope(loadBackupFixture(name))
            envelope.validateForImport()
        }
        // legacy-minimal.json is the one that physically lacks the late-added keys.
        val legacy = decodeBackupEnvelope(loadBackupFixture("legacy-minimal.json"))
        assertEquals(emptyList(), legacy.discoveredNodes)
        assertEquals(0, legacy.manifest.discoveredNodeCount)
        assertEquals(1, legacy.messages.size)
    }

    @Test
    fun parseBackupFileValidatesTheDecodedEnvelope() {
        val truncated = envelope().copy(contacts = emptyList())
        assertFailsWith<AppBackupError.CorruptedManifest> {
            parseBackupFile(encodeBackupFileBytes(truncated))
        }
        // The same bytes still decode when nothing gates them, which is what the product path did.
        assertEquals(0, decodeBackupFileBytes(encodeBackupFileBytes(truncated)).contacts.size)
    }

    private fun envelope(): AppBackupEnvelope = BackupEnvelopeBuilder.build(
        BackupExportInput(
            appVersion = "1.0.0",
            appBuild = "1",
            exportDate = 1.0,
            contacts = listOf(
                ContactDto(
                    id = Uuid.normalized("C0000000-0000-0000-0000-000000000001"),
                    radioID = Uuid.normalized("A0000000-0000-0000-0000-000000000001"),
                    publicKey = ByteArray(32) { 0x11 },
                    name = "Alice",
                    typeRawValue = 1,
                    flags = 0,
                    outPathLength = 255,
                    outPath = ByteArray(0),
                    lastAdvertTimestamp = 0,
                    latitude = 0.0,
                    longitude = 0.0,
                    lastModified = 0,
                    isBlocked = false,
                    isMuted = false,
                    isFavorite = false,
                    unreadCount = 0,
                ),
            ),
        ),
    )

    /** Re-emits [jsonText] with one top-level key deleted, the shape of a truncated or edited file. */
    private fun jsonWithoutTopLevelKey(jsonText: String, key: String): String {
        val root = backupJson.parseToJsonElement(jsonText).jsonObject
        assertTrue(key in root, "fixture must carry \"$key\" for its removal to mean anything")
        return JsonObject(root - key).toString()
    }

    /** Re-emits [jsonText] with one key deleted from the nested manifest object. */
    private fun jsonWithoutManifestKey(jsonText: String, key: String): String {
        val root = backupJson.parseToJsonElement(jsonText).jsonObject
        val manifest = root.getValue("manifest").jsonObject
        assertTrue(key in manifest, "manifest must carry \"$key\" for its removal to mean anything")
        return JsonObject(root + ("manifest" to JsonObject(manifest - key))).toString()
    }

    private companion object {
        /** Small stand-in for the production compressed cap, so the test allocates kilobytes. */
        const val CAP_BYTES = 1_024

        /** The eleven families that shipped with the format; Swift hard-decodes each one. */
        val REQUIRED_FAMILY_KEYS = listOf(
            "devices",
            "contacts",
            "channels",
            "messages",
            "messageRepeats",
            "reactions",
            "roomMessages",
            "remoteNodeSessions",
            "savedTracePaths",
            "blockedChannelSenders",
            "nodeStatusSnapshots",
        )

        val REQUIRED_MANIFEST_KEYS = listOf(
            "deviceCount",
            "contactCount",
            "channelCount",
            "messageCount",
            "messageRepeatCount",
            "reactionCount",
            "roomMessageCount",
            "remoteNodeSessionCount",
            "savedTracePathCount",
            "blockedChannelSenderCount",
            "nodeStatusSnapshotCount",
        )

        val FROZEN_FIXTURES = listOf(
            "full-envelope.json",
            "legacy-minimal.json",
            "unicode-edge.json",
            "kotlin-reexport-full.json",
        )
    }
}
