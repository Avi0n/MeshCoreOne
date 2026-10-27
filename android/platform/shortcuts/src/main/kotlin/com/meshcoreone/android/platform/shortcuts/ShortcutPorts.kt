// AndroidOnly: WP-404 Platform-owned ports replacing the Swift IntentBridge/AppState reach so the module never depends on core:services.
package com.meshcoreone.android.platform.shortcuts

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId

/** Cached, zero-await view of the radio used for the read-only status shortcut. */
data class RadioStatusSnapshot(
    val connectionState: DeviceConnectionState,
    val connectedNodeName: String?,
    val lastConnectedName: String?,
    /** Null when no battery is present or no reading exists; never reported as zero percent. */
    val batteryPercent: Int?,
)

/** App-provided access to the live radio. Implemented by the app over its services. */
interface ShortcutRadioPort {
    val connectionState: DeviceConnectionState
    val currentRadioId: RadioId?
    val hasRestorableRadio: Boolean
    val connectedNodeNameByteCount: Int
    fun status(): RadioStatusSnapshot
    suspend fun contacts(radioId: RadioId): List<ContactDTO>
    suspend fun channels(radioId: RadioId): List<ChannelDTO>

    /** Durable enqueue, never delivery; absent services require foreground, other failures use [ShortcutException]. */
    suspend fun queueMessage(recipient: ShortcutRecipient, text: String)

    /** Must throw [ShortcutException] for failures; no location permission prompt may be raised. */
    suspend fun sendAdvert(flood: Boolean)
}

/** The selected radio remains operational, but its connection-owned services disappeared before enqueue. */
class ShortcutServicesUnavailableException : Exception("Shortcut services require foreground recovery")

sealed interface ConfirmationRequest {
    val prompt: String
    data class SendMessage(val recipientName: String, val message: String, override val prompt: String) : ConfirmationRequest
    data class SendAdvert(val reach: AdvertReach, override val prompt: String) : ConfirmationRequest
}

/** App-provided explicit user confirmation UI. A false/cancelled result always aborts. */
interface ShortcutConfirmationPort {
    suspend fun confirm(request: ConfirmationRequest): Boolean
}

/** Localized text seam; the Android implementation resolves core:l10n resources. */
interface ShortcutText {
    fun sendConfirm(recipientName: String): String
    fun advertConfirm(reach: AdvertReach): String
    fun advertSent(reach: AdvertReach): String
    /** SessionError delegates to its underlying MeshCore user-facing copy, never generic send copy. */
    fun error(error: ShortcutError): String
    val sendForeground: String
    val statusNotReady: String
    val statusRadioFallbackName: String
    val statusDisconnectedUnknown: String
    fun statusConnectedNoBattery(name: String): String
    fun statusConnectedWithBattery(name: String, percent: Int): String
    fun statusConnecting(name: String): String
    fun statusDisconnectedNamed(name: String): String
    val channelSubtitle: String
    val sendShortTitle: String
    val statusShortTitle: String
    val advertShortTitle: String
    fun advertReachLabel(reach: AdvertReach): String
}
