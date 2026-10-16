package one.meshcore.shared.chat.ui

import one.meshcore.shared.channel.ChannelInfoController
import one.meshcore.shared.contact.ui.QrEncoder
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapDisplayPreferencesStore

interface ChatThreadShareActions {
    /**
     * Live inputs for the channel-info sheet. Null when the thread is not a channel or the graph is
     * disconnected; the toolbar info affordance is then hidden.
     */
    val channelInfoDependencies: () -> ChannelInfoController.Dependencies?

    /** QR encoder for private-channel share; no-op when unavailable. */
    val qrEncode: QrEncoder

    /** Clipboard copy for channel secret / share actions. */
    val copyToClipboard: (String) -> Unit

    /** Connected radio public key for "Share My Info" tokens; null when disconnected. */
    val selfPublicKey: () -> ByteArray?

    /**
     * Bytes per hop hash from the connected radio. Share-picker public-key prefixes use this,
     * matching iOS `connectedDevice?.hashSize`. Null or non-positive falls back to 1.
     */
    val deviceHashSize: () -> Int?

    /**
     * Last-known node coordinates for "Share Location" when phone GPS is unavailable.
     * Null when the Device row has no valid fix.
     */
    val nodeCoordinate: () -> LatLng?

    /**
     * Phone location seam for a fresh fix on "Share Location". Null until the platform
     * [LocationService] is wired (node coordinates still work alone).
     */
    val locationService: () -> LocationService?

    /** Shared map-options (style / labels / north lock) for the message path map. */
    val mapDisplayPreferences: MapDisplayPreferencesStore?
}

/** Channel info, share actions, and the location the thread offers to send. */
data class ChatThreadShareInputs(
    override val channelInfoDependencies: () -> ChannelInfoController.Dependencies? = { null },
    override val qrEncode: QrEncoder = { _, _ -> null },
    override val copyToClipboard: (String) -> Unit = {},
    override val selfPublicKey: () -> ByteArray? = { null },
    override val deviceHashSize: () -> Int? = { null },
    override val nodeCoordinate: () -> LatLng? = { null },
    override val locationService: () -> LocationService? = { null },
    override val mapDisplayPreferences: MapDisplayPreferencesStore? = null,
) : ChatThreadShareActions
