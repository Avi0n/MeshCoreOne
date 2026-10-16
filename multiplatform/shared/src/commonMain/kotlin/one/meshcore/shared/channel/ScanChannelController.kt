package one.meshcore.shared.channel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.MeshCoreDeepLink
import one.meshcore.shared.settings.PersistentLogger

/** An error surfaced by the channel-QR scanner, resolved to a localized message in the UI layer. */
sealed interface ScanChannelError {
    /** The decoded string was not a valid `meshcore://channel/add` URI; the scanner keeps scanning. */
    data object InvalidQrFormat : ScanChannelError

    /** The join write failed after the scan was confirmed. */
    data class Join(val error: ChannelActionError) : ScanChannelError
}

/**
 * The channel-QR scanner state. Before a scan, [scanned] is null and the camera runs; a decoded valid URI
 * stops the camera and shows the confirmation carrying [scanned]. [permissionDenied] swaps the camera for the
 * settings prompt; [successTick] increments once per accepted code so the UI can fire the scan haptic
 * (mirrors the iOS `AudioServicesPlaySystemSound` on capture).
 */
data class ScanChannelState(
    val scanned: MeshCoreDeepLink.Channel? = null,
    val isJoining: Boolean = false,
    val permissionDenied: Boolean = false,
    val error: ScanChannelError? = null,
    val successTick: Int = 0,
)

/**
 * Drives the channel-QR scan → parse → confirm → join flow (the Kotlin analogue of the iOS
 * `ScanChannelQRView` state machine), independent of any camera so it is unit-testable: a decoded string is
 * fed to [onDecoded] exactly as a real CameraX + barcode callback would feed it. A valid
 * `meshcore://channel/add` URI is parsed via the shared [ChannelUri] parser; the user then confirms the
 * name/secret and [join] writes the channel on the first free slot.
 */
class ScanChannelController(private val dependencies: Dependencies) {
    /** Live, per-connection inputs read at join time; a null provider result means disconnected. */
    class Dependencies(
        val channelService: () -> ChannelService?,
        val radioId: () -> Uuid?,
        val availableSlots: () -> List<Int>,
        val floodPersistLogger: PersistentLogger? = null,
    )

    private val _state = MutableStateFlow(ScanChannelState())
    val state: StateFlow<ScanChannelState> = _state.asStateFlow()

    /** Reports a camera-permission denial; the UI shows the settings prompt. */
    fun onPermissionDenied() = _state.update { it.copy(permissionDenied = true) }

    /**
     * Handles a decoded QR string. A code arriving after one is already confirmed is ignored. An unparseable
     * code raises [ScanChannelError.InvalidQrFormat] and keeps scanning; a valid one bumps [successTick] and
     * shows the confirmation.
     */
    fun onDecoded(decoded: String) {
        if (_state.value.scanned != null) return
        val channel = ChannelUri.parse(decoded)
        if (channel == null) {
            _state.update { it.copy(error = ScanChannelError.InvalidQrFormat) }
            return
        }
        _state.update { it.copy(scanned = channel, error = null, successTick = it.successTick + 1) }
    }

    /** Returns to the camera to scan another code, clearing the prior confirmation and error. */
    fun scanAgain() = _state.update { it.copy(scanned = null, error = null) }

    /** Joins the scanned channel on the first free slot, invoking [onJoined] with the resulting row. */
    suspend fun join(onJoined: (ChannelDto?) -> Unit) {
        val current = _state.value
        val channel = current.scanned
        if (channel == null || current.isJoining) return
        val service = dependencies.channelService()
        val radioId = dependencies.radioId()
        if (service == null || radioId == null) {
            _state.update { it.copy(error = ScanChannelError.Join(channelUnavailableError(radioId))) }
            return
        }

        val slot = dependencies.availableSlots().firstOrNull() ?: ChannelSlots.USER_CHANNEL_MIN_SLOT
        _state.update { it.copy(isJoining = true, error = null) }
        val outcome = service.runChannelWrite(radioId, slot) {
            setChannelWithSecret(radioId, slot, channel.name, channel.secret)
        }
        when (outcome) {
            is ChannelWriteOutcome.Written -> {
                // Apply the scanned code's `region_scope` preference after the write, mirroring the
                // from-message link flow; a null read-back or missing region leaves the channel unchanged.
                val joined = outcome.channel?.let {
                    ChannelJoinFloodScopeApplier.applyIfNeeded(
                        it,
                        channel.regionScope,
                        dependencies.floodPersistLogger,
                    ) { id, scope ->
                        service.setChannelFloodScope(id, scope)
                    }
                }
                _state.update { it.copy(isJoining = false) }
                onJoined(joined)
            }
            is ChannelWriteOutcome.Failed ->
                _state.update { it.copy(isJoining = false, error = ScanChannelError.Join(outcome.error)) }
        }
    }
}
