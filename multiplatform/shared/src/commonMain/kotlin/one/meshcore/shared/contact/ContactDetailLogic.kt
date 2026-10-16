package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.map.formatRounded5
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.runExceptionFallback
import kotlin.math.abs

/**
 * Pure helpers for contact-detail location and role-gated actions. Mirrors the iOS contact-detail
 * action matrix (chat vs repeater vs room) without Compose dependencies so unit tests can freeze it.
 */
object ContactDetailLogic {
    /** iOS mini-map span (~2 km at mid latitudes). */
    const val MINI_MAP_SPAN_DEG = 0.02

    private const val ZERO_EPSILON = 1e-9
    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0

    /** Role-gated action rows on contact detail (iOS ContactActionsSection / NodeActionRows). */
    data class ActionFlags(
        val joinRoom: Boolean,
        val sendMessage: Boolean,
        val management: Boolean,
        val block: Boolean,
        val clearMessages: Boolean,
    )

    /**
     * In-flight flags that disable individual detail actions on top of the radio gate.
     */
    data class ActionBusyState(
        val isPinging: Boolean = false,
        val pathBusy: Boolean = false,
        val isDiscoveringPath: Boolean = false,
        val isFloodRouted: Boolean = false,
        val shareAdvertBusy: Boolean = false,
        val shareAdvertSuccess: Boolean = false,
    )

    /**
     * Whether each radio-backed detail action is tappable. The local-only rows (saved history, QR share,
     * clear messages, copy route, open in maps) are absent because they stay tappable offline.
     */
    data class ActionEnablement(
        val sendMessage: Boolean,
        val joinRoom: Boolean,
        val telemetry: Boolean,
        val management: Boolean,
        val ping: Boolean,
        val shareViaAdvert: Boolean,
        val discoverPath: Boolean,
        val editPath: Boolean,
        val resetPath: Boolean,
        val favorite: Boolean,
        val block: Boolean,
        val delete: Boolean,
    )

    /**
     * Every action that reaches the radio is disabled while the connection is not ready, so a mid-screen
     * disconnect cannot leave a command tappable. Mirrors the iOS `.radioDisabled(for:)` set on contact
     * detail, including each row's own in-flight condition.
     */
    fun actionEnablement(isRadioReady: Boolean, busy: ActionBusyState): ActionEnablement = ActionEnablement(
        sendMessage = isRadioReady,
        joinRoom = isRadioReady,
        telemetry = isRadioReady,
        management = isRadioReady,
        ping = isRadioReady && !busy.isPinging,
        shareViaAdvert = isRadioReady && !busy.shareAdvertBusy && !busy.shareAdvertSuccess,
        discoverPath = isRadioReady && !busy.pathBusy,
        editPath = isRadioReady,
        // Already flood-routed: a reset is a no-op round-trip (iOS `isFloodRouted`).
        resetPath = isRadioReady && !busy.pathBusy && !busy.isDiscoveringPath && !busy.isFloodRouted,
        favorite = isRadioReady,
        block = isRadioReady,
        delete = isRadioReady,
    )

    /** The outcome of a contact-detail action that reaches the radio. */
    sealed interface ActionOutcome {
        data object Succeeded : ActionOutcome

        /** The service went away before the action ran; iOS raises `servicesUnavailable`. */
        data object Unavailable : ActionOutcome

        data class Failed(val error: Throwable) : ActionOutcome
    }

    /**
     * Reloads the open contact row. A persist fault keeps [current] so `listInvalidations` can
     * keep collecting. iOS `ContactDetailView` uses `try? getContact` for the same reason.
     */
    data class ContactReload(val contact: ContactDto?, val loadFailed: Boolean)

    suspend fun reloadContact(current: ContactDto?, fetch: suspend () -> ContactDto?): ContactReload =
        runExceptionFallback({ ContactReload(contact = current, loadFailed = current == null) }) {
            val fetched = fetch()
            ContactReload(contact = fetched, loadFailed = fetched == null)
        }

    /**
     * The service that may run a write. Offline [ContactService] still exists for reloads; iOS
     * `appState.services?.contactService` is nil while disconnected, so a disconnected stand-in
     * must not be treated as live.
     */
    fun writeService(service: ContactService?, isRadioConnected: Boolean): ContactService? =
        service.takeIf { isRadioConnected }

    /**
     * Broadcasts [publicKey] via advert (`CMD_SHARE_CONTACT`). Offline [ContactService] still exists
     * for reloads; iOS `appState.services?.contactService.shareContact` is a no-op while
     * disconnected, so a disconnected stand-in must not be treated as live (and must not flash success).
     */
    suspend fun shareViaAdvert(
        service: ContactService?,
        isRadioConnected: Boolean,
        publicKey: ByteArray,
    ): ActionOutcome = runContactAction(writeService(service, isRadioConnected)) { live ->
        live.shareContact(publicKey)
    }

    /**
     * Runs a detail action against the live [service]. A `null` service is [ActionOutcome.Unavailable],
     * never a success: reporting success would pop the screen as though the radio had carried out a
     * delete or a block that never left the phone.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun runContactAction(service: ContactService?, action: suspend (ContactService) -> Unit): ActionOutcome {
        if (service == null) return ActionOutcome.Unavailable
        return try {
            action(service)
            ActionOutcome.Succeeded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ActionOutcome.Failed(error)
        }
    }

    fun pinStyleFor(type: ContactType): PinStyle = when (type) {
        ContactType.REPEATER -> PinStyle.CONTACT_REPEATER
        ContactType.ROOM -> PinStyle.CONTACT_ROOM
        ContactType.CHAT -> PinStyle.CONTACT_CHAT
    }

    /**
     * The rows a contact of [type] shows. A blocked chat contact loses Send Message entirely, matching the
     * iOS gate: inviting a user to message someone they blocked is an affordance that contradicts the state
     * the screen is showing them. Block/Unblock stays so the block can be lifted. [showFromDirectChat]
     * also hides Send (iOS `ContactDetailView(showFromDirectChat:)`), because the user is already in that DM.
     */
    fun actionFlags(type: ContactType, isBlocked: Boolean, showFromDirectChat: Boolean = false): ActionFlags =
        when (type) {
            ContactType.CHAT -> ActionFlags(
                joinRoom = false,
                sendMessage = !isBlocked && !showFromDirectChat,
                management = false,
                block = true,
                clearMessages = true,
            )
            ContactType.REPEATER -> ActionFlags(
                joinRoom = false,
                sendMessage = false,
                management = true,
                block = false,
                clearMessages = false,
            )
            ContactType.ROOM -> ActionFlags(
                joinRoom = true,
                sendMessage = false,
                management = true,
                block = false,
                clearMessages = false,
            )
        }

    /**
     * Saved History from a chat contact omits the Neighbors SNR section (iOS
     * `ContactRoute.TelemetryHistory(showNeighbors: false)`). Repeaters and rooms keep it.
     */
    fun showTelemetryHistoryNeighbors(type: ContactType): Boolean = type != ContactType.CHAT

    fun miniMapCamera(latitude: Double, longitude: Double): MapCameraRegion = MapCameraRegion(
        center = LatLng(latitude, longitude),
        spanLatDeg = MINI_MAP_SPAN_DEG,
        spanLonDeg = MINI_MAP_SPAN_DEG,
    )

    fun miniMapPoint(contact: ContactDto): MapPoint? {
        val latitude = contact.latitude
        val longitude = contact.longitude
        val valid = latitude in MIN_LATITUDE..MAX_LATITUDE &&
            longitude in MIN_LONGITUDE..MAX_LONGITUDE &&
            !(abs(latitude) < ZERO_EPSILON && abs(longitude) < ZERO_EPSILON)
        if (!valid) return null
        return MapPoint(
            id = contact.id.value,
            coordinate = LatLng(latitude, longitude),
            pinStyle = pinStyleFor(contact.contactType),
            label = contact.displayName,
            isClusterable = false,
        )
    }
}

/**
 * Android `geo:` URI for the system maps chooser. Label is optional; when present it is appended
 * as the query display name (sanitized enough for common nicknames without full URL encoding).
 */
internal fun mapsGeoUri(latitude: Double, longitude: Double, label: String?): String {
    val lat = formatRounded5(latitude)
    val lon = formatRounded5(longitude)
    val q = if (label.isNullOrBlank()) {
        "$lat,$lon"
    } else {
        "$lat,$lon(${sanitizeMapLabel(label)})"
    }
    return "geo:$lat,$lon?q=$q"
}

private fun sanitizeMapLabel(label: String): String = label.replace('(', ' ').replace(')', ' ').replace(',', ' ').trim()
