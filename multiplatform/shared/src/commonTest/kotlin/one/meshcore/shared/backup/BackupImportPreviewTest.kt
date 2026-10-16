package one.meshcore.shared.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupImportPreviewTest {
    @Test
    fun fullEnvelopePreviewCountsMatchArrays() {
        val preview = parseBackupImportPreview(loadBackupFixture("full-envelope.json"))
        assertEquals(AppBackupEnvelope.CURRENT_VERSION, preview.version)
        assertTrue(preview.manifestValid)
        assertEquals(1, preview.deviceCount)
        assertEquals(1, preview.contactCount)
        assertEquals(1, preview.channelCount)
        assertEquals(2, preview.messageCount)
        assertEquals(1, preview.reactionCount)
        assertEquals(1, preview.roomMessageCount)
    }

    @Test
    fun malformedJsonFails() {
        assertFails { parseBackupImportPreview("{not-json") }
    }

    /**
     * Swift funnels every decode failure into `AppBackupError.invalidFile` so the user reads the
     * "invalid file" copy. A raw serializer message would reach the screen as untranslated English.
     */
    @Test
    fun malformedJsonSurfacesAsAnInvalidFile() {
        assertFailsWith<AppBackupError.InvalidFile> { parseBackupImportPreview("{not-json") }
        assertFailsWith<AppBackupError.InvalidFile> { decodeBackupEnvelope("""{"version":1}""") }
    }

    /**
     * The commit-point connection re-check. A foreground auto-reconnect can bring the radio up while
     * the preview is on screen, and the merge must not run against a live-syncing store.
     */
    @Test
    fun aValidPreviewCannotCommitWhileTheRadioIsConnected() {
        assertTrue(canCommitBackupImport(manifestValid = true, isRadioConnected = false))
        assertFalse(canCommitBackupImport(manifestValid = true, isRadioConnected = true))
        assertFalse(canCommitBackupImport(manifestValid = false, isRadioConnected = false))
    }

    /**
     * File pick validates before preview (Swift `parseBackup`). A newer format must fail here so
     * the screen never offers a confirm that would merge an unsupported envelope.
     */
    @Test
    fun previewPickedBackupJsonRejectsAnUnsupportedVersion() {
        val future = decodeBackupEnvelope(loadBackupFixture("full-envelope.json"))
            .copy(version = AppBackupEnvelope.CURRENT_VERSION + 1)
        assertFailsWith<AppBackupError.UnsupportedVersion> {
            previewPickedBackupJson(encodeBackupEnvelope(future))
        }
    }

    @Test
    fun previewPickedBackupJsonAcceptsAValidEnvelope() {
        val preview = previewPickedBackupJson(loadBackupFixture("full-envelope.json"))
        assertTrue(preview.manifestValid)
        assertEquals(1, preview.deviceCount)
    }
}
