package one.meshcore.android

import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import one.meshcore.shared.connection.CompanionAssociateResult
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.CompanionDeviceRegistry

/**
 * Companion Device Manager pairing registry. Discovers MeshCore radios by Nordic UART service
 * UUID (iOS ASK `bluetoothServiceUUID`), lists leftover associations with no Device row, and
 * disassociates on forget. The chooser is the system sheet; GATT connect still goes through Kable.
 */
@MainThread
class AndroidCompanionDeviceRegistry(activity: ComponentActivity) : CompanionDeviceRegistry {
    private val state = CompanionRegistryState(activity)
    private val catalog = CompanionAssociationCatalog(state)
    private val presence = CompanionPresenceObserver(state, catalog)
    private val session = CompanionAssociationSession(state, catalog, presence)

    override val isAvailable: Boolean
        get() = state.isAvailable

    override val canDropOsBond: Boolean
        get() = state.canDropOsBond

    override val associationRemovals: Flow<List<CompanionAssociation>> = state.removals.asSharedFlow()

    override fun associations(): List<CompanionAssociation> = catalog.associations()

    override suspend fun associate(): CompanionAssociateResult = session.associate()

    override fun cancelAssociate() = session.cancelAssociate()

    override fun disassociate(handle: String) = session.disassociate(handle)

    override fun refreshAssociationSnapshot(): List<CompanionAssociation> = catalog.refresh()

    override fun syncObservedPresence(handles: Collection<String>) = presence.sync(handles)
}
