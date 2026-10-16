package one.meshcore.shared.contact

import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Runs [reload] for each list invalidation and each device-table contact event.
 * A null [service] still collects [invalidations].
 */
internal suspend fun collectContactUiReloads(
    service: ContactService?,
    invalidations: Flow<Unit>? = null,
    reload: suspend () -> Unit,
) {
    coroutineScope {
        if (invalidations != null) {
            launch { invalidations.collect { reload() } }
        }
        launch {
            val events = service?.events() ?: return@launch
            events.consumeEach { event ->
                if (event.reloadsContactUi) reload()
            }
        }
    }
}
