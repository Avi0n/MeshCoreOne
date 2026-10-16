package one.meshcore.android

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.MainThread
import one.meshcore.shared.connection.companionDeviceManagerCanObservePresence
import one.meshcore.shared.connection.companionHandleMatches

/** Starts and stops CDM presence observation so it matches the radios the app still cares about. */
@MainThread
internal class CompanionPresenceObserver(
    private val state: CompanionRegistryState,
    private val catalog: CompanionAssociationCatalog,
) {
    fun sync(handles: Collection<String>) {
        val wanted = handles.map(String::trim).filter(String::isNotEmpty)
        catalog.associations().forEach { association ->
            if (wanted.any { companionHandleMatches(it, association.handle) }) {
                start(association.handle)
            } else {
                stop(association.handle)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun stop(handle: String) {
        val cdm = state.manager ?: return
        val address = handle.trim().takeIf { it.isNotEmpty() } ?: return
        if (!canObservePresence()) return
        runCatching { cdm.stopObservingDevicePresence(address) }
    }

    @Suppress("DEPRECATION")
    private fun start(handle: String) {
        val cdm = state.manager ?: return
        val address = handle.trim().takeIf { it.isNotEmpty() } ?: return
        if (!canObservePresence()) return
        runCatching { cdm.startObservingDevicePresence(address) }
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    private fun canObservePresence(): Boolean = companionDeviceManagerCanObservePresence(Build.VERSION.SDK_INT)
}
