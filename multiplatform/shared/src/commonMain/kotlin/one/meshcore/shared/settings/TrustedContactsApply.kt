package one.meshcore.shared.settings

import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.setTelemetryPermissions
import one.meshcore.shared.model.Uuid

/**
 * Apply for the Trusted Contacts picker (telemetry bits 1–3). Offline [ContactService] still exists
 * for the list load; iOS `applyChanges` gates on `appState.services?.contactService`.
 */
object TrustedContactsApply {
    fun writeService(service: ContactService?, isRadioConnected: Boolean): ContactService? =
        service.takeIf { isRadioConnected }

    /**
     * Writes telemetry grants and revokes. Returns false when there is no live radio session so the
     * caller does not treat a no-op as success.
     */
    suspend fun apply(
        service: ContactService?,
        isRadioConnected: Boolean,
        toGrant: Collection<Uuid>,
        toRevoke: Collection<Uuid>,
    ): Boolean {
        val live = writeService(service, isRadioConnected) ?: return false
        for (id in toGrant) {
            live.setTelemetryPermissions(id, granted = true)
        }
        for (id in toRevoke) {
            live.setTelemetryPermissions(id, granted = false)
        }
        return true
    }
}
