package one.meshcore.shared.contact

import one.meshcore.shared.contact.ui.messageResource
import one.meshcore.shared.contact.ui.userFacingResource
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_error_occurred
import one.meshcore.shared.resources.localizable_error_device_not_found
import one.meshcore.shared.resources.localizable_error_mesh_core_timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Every contact-service failure the user can meet must resolve to a shipped string. The exception's own
 * text is hardcoded English, so an unmapped case reaches the screen untranslated whatever the device
 * locale is.
 */
class ContactServiceErrorMessageTest {
    @Test
    fun everyContactErrorCaseResolvesToAShippedString() {
        val cases = listOf(
            ContactServiceError.NotConnected(),
            ContactServiceError.SendFailed(),
            ContactServiceError.InvalidResponse(),
            ContactServiceError.SyncInterrupted(),
            ContactServiceError.ContactNotFound(),
            ContactServiceError.ContactTableFull(),
            ContactServiceError.ShareContactUnavailable(),
        )

        cases.forEach { error ->
            assertNotNull(error.messageResource(), "${error::class.simpleName} has no shipped string")
        }
    }

    @Test
    fun eachCaseResolvesToItsOwnString() {
        assertEquals(
            cases().size,
            cases().mapNotNull { it.messageResource() }.toSet().size,
            "two cases share a string, so one of them shows the wrong message",
        )
    }

    @Test
    fun aWrappedSessionFailureResolvesThroughItsCause() {
        val wrapped = ContactServiceError.SessionError(MeshBridgeException(BridgeErrorCode.TIMEOUT, ""))

        assertEquals(Res.string.localizable_error_mesh_core_timeout, wrapped.messageResource())
    }

    @Test
    fun aFirmwareDeviceCauseResolvesToItsDeviceString() {
        val wrapped = ContactServiceError.SessionError(ContactDeviceException(DEVICE_CODE_NOT_FOUND))
        assertEquals(Res.string.localizable_error_device_not_found, wrapped.messageResource())
    }

    @Test
    fun aSessionFailureWithNothingShippedUsesErrorOccurredOnTheSurface() {
        val wrapped = ContactServiceError.SessionError(IllegalStateException("raw"))

        assertNull(wrapped.messageResource(), "a null answer is what lets rememberContactServiceErrorText use unmapped")
        assertEquals(Res.string.contacts_common_error_occurred, wrapped.userFacingResource())
    }

    private fun cases() = listOf(
        ContactServiceError.NotConnected(),
        ContactServiceError.SendFailed(),
        ContactServiceError.InvalidResponse(),
        ContactServiceError.SyncInterrupted(),
        ContactServiceError.ContactNotFound(),
        ContactServiceError.ContactTableFull(),
        ContactServiceError.ShareContactUnavailable(),
    )
}

private const val DEVICE_CODE_NOT_FOUND = 2
