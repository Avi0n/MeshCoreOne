package one.meshcore.shared.channel.ui

import one.meshcore.shared.channel.ChannelService
import one.meshcore.shared.contact.ui.QrEncoder
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/** A camera scanner slot for channel QR codes: feeds decoded strings to [onDecoded], reports a denial. */
typealias ChannelQrScanner = @androidx.compose.runtime.Composable (
    onDecoded: (String) -> Unit,
    onPermissionDenied: () -> Unit,
) -> Unit

/**
 * The live per-connection providers and platform slots every channel create/join flow needs, grouped so the
 * hub and its sub-screens read one surface (the Kotlin analogue of the iOS sheets all reaching `appState`).
 * A `null` [channelService] / [radioId] models the disconnected state the controllers guard on; [encode]
 * and [scanner] default to placeholder / unavailable so desktop and tests compose without a QR library.
 * Android injects the encoder and CameraX scanner. Copy / share / open-settings are platform closures.
 */
data class ChannelFlowProviders(
    val channelService: () -> ChannelService?,
    val radioId: () -> Uuid?,
    val maxChannels: () -> Int?,
    val encode: QrEncoder = { _, _ -> null },
    val scanner: ChannelQrScanner = { _, _ -> },
    val onCopy: (String) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onScanHaptic: () -> Unit = {},
    val floodPersistLogger: PersistentLogger? = null,
)
