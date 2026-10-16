package one.meshcore.shared.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.billing.BillingClient
import one.meshcore.shared.billing.EntitlementReconciler
import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.billing.NoOpBillingClient
import one.meshcore.shared.map.CoordinateBounds
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.map.OfflineMapPack
import one.meshcore.shared.map.OfflineMapPackRegistry
import one.meshcore.shared.notification.StoredNotificationPreferences
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.settings.ChatDisplayPreferencesStore
import one.meshcore.shared.settings.DemoModeStore
import one.meshcore.shared.settings.TranslationTargetPreferencesStore
import one.meshcore.shared.theme.AppearancePreferencesStore
import one.meshcore.shared.whatsnew.WhatsNewStore

interface ShellPreferenceHost {
    /** Whether this build offers the simulator/demo connect affordances (Android debug + Play reviewers). */
    val supportsSimulatorConnect: Boolean

    /** Key-value store backing the path editor's recent-hops LRU (per-radio); the default does not persist. */
    val pathEditPreferences: PreferencesStore

    /**
     * Live chat display toggles (incoming path/hop/region/send-time, link previews). Optional so a
     * desktop/test shell without prefs still compiles; Settings → Chats and thread envInputs bind here.
     */
    val chatDisplayPreferences: ChatDisplayPreferencesStore?

    /** In-bubble Translate target and default-app toggle; Settings → Language and open threads. */
    val translationTargetPreferences: TranslationTargetPreferencesStore?

    /** Notification message toggles; Settings → Notifications binds here when non-null. */
    val notificationPreferences: StoredNotificationPreferences?

    /** Appearance (color scheme + selected theme id); Settings → Appearance and AppRoot MaterialTheme. */
    val appearancePreferences: AppearancePreferencesStore?

    /** Map display toggles (discovered pins + basemap appearance); Settings → Maps and the Map tab. */
    val mapDisplayPreferences: MapDisplayPreferencesStore?

    /** App-wide location permission + one-shot fix; the Map tab's locate control and user dot. Null hides them. */
    val locationService: LocationService?

    /** What's New sheet baseline; null skips the post-onboarding sheet. */
    val whatsNewStore: WhatsNewStore?

    /** One-time coach marks (device menu, live status). */
    val coachMarkStore: CoachMarkStore?

    /** Demo mode unlock/enable for Support settings and fixtures. */
    val demoModeStore: DemoModeStore?

    /** Offline maps pack registry for Settings → Offline Maps. */
    val offlineMapPackRegistry: OfflineMapPackRegistry?

    /** Persist offline pack metadata after list mutations. */
    val onOfflineMapPacksChanged: () -> Unit

    /** Start MapLibre tile download for a pack with explicit region bounds from the picker. */
    val startOfflineMapDownload: (OfflineMapPack, CoordinateBounds) -> Unit

    /** The last offline-pack download failure, or null. A silent failure freezes the row at "Downloading". */
    val offlineMapError: StateFlow<String?>

    /** Clears the surfaced offline-pack failure once shown. */
    val clearOfflineMapError: () -> Unit

    /** Last Map-tab camera for the offline region picker seed (null → continental default). */
    val loadMapCameraRegion: suspend () -> MapCameraRegion?

    /** Delete native OfflineManager region for [packId]. */
    val deleteOfflineMapPack: (String) -> Unit

    /** Pause an in-flight offline pack download. */
    val pauseOfflineMapPack: (String) -> Unit

    /** Resume a paused offline pack download. */
    val resumeOfflineMapPack: (String) -> Unit

    /** Free bytes on the app data volume for the offline free-space gate; null skips the gate. */
    val offlineMapAvailableBytes: () -> Long?

    /** MapLibre offline db file length for Settings → Offline Maps Storage Used. */
    val offlineMapDatabaseSizeBytes: () -> Long

    /** Theme-bundle and tip entitlements reconciled from Play Billing. */
    val entitlementStore: EntitlementStore?

    /** Play Billing / no-op client for Support purchases and restore. */
    val billingClient: BillingClient

    /** Single purchase-lifecycle reconciler; drives Support restore (grant/acknowledge/consume/revoke). */
    val entitlementReconciler: EntitlementReconciler?

    /** Map offline badge: true when the device has no usable network route. */
    val isNetworkOffline: () -> Boolean

    /**
     * Live network-offline flag. When set, the map collects it so a drop or restore updates the
     * badge and style gate without a tab switch. iOS `OfflineMapService.isNetworkAvailable`.
     */
    val networkOffline: kotlinx.coroutines.flow.StateFlow<Boolean>?
}

/** App preferences, offline maps, billing, and the network-offline flag. */
data class ShellPreferenceInputs(
    override val supportsSimulatorConnect: Boolean = false,
    override val pathEditPreferences: PreferencesStore = InMemoryPreferencesStore(),
    override val chatDisplayPreferences: ChatDisplayPreferencesStore? = null,
    override val translationTargetPreferences: TranslationTargetPreferencesStore? = null,
    override val notificationPreferences: StoredNotificationPreferences? = null,
    override val appearancePreferences: AppearancePreferencesStore? = null,
    override val mapDisplayPreferences: MapDisplayPreferencesStore? = null,
    override val locationService: LocationService? = null,
    override val whatsNewStore: WhatsNewStore? = null,
    override val coachMarkStore: CoachMarkStore? = null,
    override val demoModeStore: DemoModeStore? = null,
    override val offlineMapPackRegistry: OfflineMapPackRegistry? = null,
    override val onOfflineMapPacksChanged: () -> Unit = {},
    override val startOfflineMapDownload: (OfflineMapPack, CoordinateBounds) -> Unit = { _, _ -> },
    override val offlineMapError: StateFlow<String?> = MutableStateFlow(null),
    override val clearOfflineMapError: () -> Unit = {},
    override val loadMapCameraRegion: suspend () -> MapCameraRegion? = { null },
    override val deleteOfflineMapPack: (String) -> Unit = {},
    override val pauseOfflineMapPack: (String) -> Unit = {},
    override val resumeOfflineMapPack: (String) -> Unit = {},
    override val offlineMapAvailableBytes: () -> Long? = { null },
    override val offlineMapDatabaseSizeBytes: () -> Long = { 0L },
    override val entitlementStore: EntitlementStore? = null,
    override val billingClient: BillingClient = NoOpBillingClient(),
    override val entitlementReconciler: EntitlementReconciler? = null,
    override val isNetworkOffline: () -> Boolean = { false },
    override val networkOffline: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
) : ShellPreferenceHost

/**
 * The current offline snapshot. Does not subscribe; a composable that must recompose on change uses
 * [shellNetworkOffline]. A null flow falls back to [ShellPreferenceHost.isNetworkOffline].
 */
internal fun ShellPreferenceHost.networkOfflineNow(): Boolean = networkOffline?.value ?: isNetworkOffline()
