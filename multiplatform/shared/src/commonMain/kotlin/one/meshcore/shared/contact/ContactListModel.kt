package one.meshcore.shared.contact

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** List state and bind jobs shared by the contact list's load and edit paths. */
internal class ContactListModel(val scope: CoroutineScope, val dependencies: ContactListViewModel.Dependencies) {
    val serviceEvents = ContactServiceEventBinding(scope)
    var sortRestoreJob: Job? = null
    var invalidationsJob: Job? = null
    var invalidationsFlow: Flow<Unit>? = null
    val state = MutableStateFlow(ContactListState())
    var isClosed = false
        private set

    fun close() {
        if (isClosed) return
        isClosed = true
        serviceEvents.close()
        sortRestoreJob?.cancel()
        sortRestoreJob = null
        invalidationsJob?.cancel()
        invalidationsJob = null
        invalidationsFlow = null
    }

    /** The localized lower-layer message to surface; never interpolates the exception itself. */
    fun serviceMessage(error: Throwable): String = dependencies.errorText(error)
}
