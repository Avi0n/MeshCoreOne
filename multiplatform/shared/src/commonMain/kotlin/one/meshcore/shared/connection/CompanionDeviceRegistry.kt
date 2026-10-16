package one.meshcore.shared.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Android Companion Device Manager — the ASK analog: system chooser keyed on Nordic UART,
 * leftover associations with no Device row, and disassociate on forget.
 *
 * Desktop and hosts without `FEATURE_COMPANION_DEVICE_SETUP` use [NoOpCompanionDeviceRegistry].
 */
interface CompanionDeviceRegistry {
    val isAvailable: Boolean

    fun associations(): List<CompanionAssociation>

    suspend fun associate(): CompanionAssociateResult

    /** Cancels an in-flight system association chooser. No-op when none is active. */
    fun cancelAssociate() = Unit

    fun disassociate(handle: String)

    /**
     * Whether forget / Remove-and-Try-Again also drops the OS Bluetooth bond. iOS AccessorySetupKit
     * can; Companion Device Manager `removeBond` is API 36+. When false, pairing-failure copy tells
     * the user to forget the radio in Bluetooth settings first — matching iOS Mac
     * `authenticationFailedMac` / `pinRejectedMac`.
     */
    val canDropOsBond: Boolean
        get() = false

    /**
     * Handles that left the OS association list (Settings unpair or in-app forget).
     * Empty on hosts without a live Companion Device Manager.
     */
    val associationRemovals: Flow<List<CompanionAssociation>>
        get() = emptyFlow()

    /** Re-diff associations after Settings or before reconnect. API 32 has no public listener. */
    fun refreshAssociationSnapshot(): List<CompanionAssociation> = emptyList()

    /**
     * Reconciles OS presence observation to [handles], stopping every other association.
     * No-op below API 31 and on hosts without CDM.
     */
    fun syncObservedPresence(handles: Collection<String>) = Unit
}
