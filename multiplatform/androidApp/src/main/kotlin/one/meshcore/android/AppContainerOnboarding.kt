package one.meshcore.android

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.prefs.PersistenceKeys
import org.jetbrains.compose.resources.getString

/** The onboarding starting path for the current permission/region state, for the resume pre-seed. */
suspend fun AppContainer.suggestedOnboardingPath(
    locationDetermined: Boolean,
    notificationDetermined: Boolean,
    regionAlreadySet: Boolean,
) = appState.suggestedOnboardingPath(
    pairedAccessoriesCount = companionDevices.associations().size,
    locationPermissionDetermined = locationDetermined,
    notificationPermissionDetermined = notificationDetermined,
    regionAlreadySet = regionAlreadySet,
)

/** Whether a region is already persisted, for the onboarding resume decision. */
suspend fun AppContainer.regionAlreadySet(): Boolean =
    prefs.getString(one.meshcore.shared.prefs.PersistenceKeys.REGION_SELECTION) != null

/** Whether onboarding has already completed, read once at launch to route the root. */
suspend fun AppContainer.hasCompletedOnboarding(): Boolean =
    prefs.getBoolean(one.meshcore.shared.prefs.PersistenceKeys.HAS_COMPLETED_ONBOARDING) ?: false

/**
 * Reconciles local entitlements against Play's authoritative owned set: grants + acknowledges
 * or consumes purchased items and revokes any refunded non-consumable. Runs on startup and again
 * when the app returns to the foreground. Billing faults are logged and remain best-effort.
 */
@Suppress("TooGenericExceptionCaught")
fun AppContainer.reconcileEntitlements() {
    entitlementReconciliation.launch {
        try {
            entitlementReconciler.reconcile { billingClient.queryOwnedPurchases() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            settingsLogger.warning("Entitlement reconciliation failed: ${error::class.simpleName}")
        }
    }
}
