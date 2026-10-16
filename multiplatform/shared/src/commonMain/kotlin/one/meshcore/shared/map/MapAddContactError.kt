package one.meshcore.shared.map

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.contact.AddContactError
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactServiceError

/**
 * The service that may add a discovered node. Offline [ContactService] still exists for map
 * reloads; iOS `MapView.addDiscoveredNode` requires `appState.services?.contactService`.
 */
fun mapAddDiscoveredWriteService(service: ContactService?, isRadioConnected: Boolean): ContactService? =
    service.takeIf { isRadioConnected }

/**
 * Classifies a failure from adding a discovered node into the shared [AddContactError], so the map flow
 * renders the same localized copy as the Add-contact form. The table-full case carries the device cap,
 * routing through the count-bearing "node list full (max N)" helper instead of the raw English
 * exception text. Mirrors the `catch` ladder in iOS `MapView.addDiscoveredNode`.
 */
fun mapAddDiscoveredNodeError(error: Throwable, maxContacts: Int?, errorText: (Throwable) -> String): AddContactError =
    when (error) {
        is ContactServiceError.ContactTableFull -> AddContactError.NodeListFull(maxContacts)
        is ContactServiceError.NotConnected -> AddContactError.NotConnected
        else -> AddContactError.ServiceMessage(errorText(error))
    }

/**
 * Adds a discovered node. Cancellation is rethrown. Any other exception becomes [AddContactError]
 * through [mapAddDiscoveredNodeError]. A thrown error still propagates.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runDiscoveredNodeAdd(
    maxContacts: () -> Int?,
    errorText: (Throwable) -> String,
    onError: (AddContactError) -> Unit,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onError(mapAddDiscoveredNodeError(error, maxContacts(), errorText))
    }
}
