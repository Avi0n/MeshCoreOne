package one.meshcore.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.ui.OnboardingPermission
import one.meshcore.shared.ui.PermissionController
import one.meshcore.shared.ui.PermissionUiState

/**
 * The Android [PermissionController]: requests the runtime permissions the onboarding cards manage via
 * the Activity Result API and re-reads their grant state on demand (the Activity calls [refresh] on
 * resume, mirroring the iOS `PermissionsCoordinator` re-check on `scenePhase == .active`).
 *
 * Notifications map to `POST_NOTIFICATIONS` (API 33+), Location to `ACCESS_FINE_LOCATION` +
 * `ACCESS_COARSE_LOCATION`, and the Android-new Bluetooth card to `BLUETOOTH_SCAN` +
 * `BLUETOOTH_CONNECT` (API 31+). Below API 31 BLE scan still needs Location, so the Bluetooth card
 * asks for fine + coarse location there — iOS never needs Location for scan, but Android 9–11 does.
 *
 * Every published state comes from [PermissionStateReader.read], so a grant or a revoke made in system
 * Settings is picked up whole on the next [refresh] rather than merged into a remembered set.
 */
class AndroidPermissionController(private val activity: ComponentActivity) : PermissionController {
    private val reader = PermissionStateReader(
        store = SharedPreferencesAskedPermissions(activity),
        isGranted = { isGranted(it) },
        needsRationale = { needsRationale(it) },
    )
    private val _state = MutableStateFlow(reader.read())
    override val state: StateFlow<PermissionUiState> = _state

    // Registered during Activity onCreate as the contract requires. The asked flag is durable before
    // launch, so the result only needs to refresh the live grant state.
    private val launcher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
        }

    override fun request(permission: OnboardingPermission) {
        val manifest = manifestPermissions(permission)
        if (manifest.isEmpty()) {
            // Nothing to request on this API level; it is granted at install time.
            reader.markAsked(permission)
            refresh()
            return
        }
        // Stamp asked before the system dialog. Activity recreation delivers the result to a new
        // controller; without this stamp a refusal is lost and onboarding walks the user through
        // the same card again.
        reader.markAsked(permission)
        launcher.launch(manifest.toTypedArray())
    }

    override fun refresh() {
        _state.value = reader.read()
    }

    private fun isGranted(permission: OnboardingPermission): Boolean {
        val manifest = manifestPermissions(permission)
        if (manifest.isEmpty()) return true
        return isSatisfied(
            permission,
            manifest.map { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED },
        )
    }

    // The system's own "the user said no once" signal, true only between a first refusal and the refusal
    // that turns the prompt off for good.
    private fun needsRationale(permission: OnboardingPermission): Boolean =
        manifestPermissions(permission).any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

    private fun manifestPermissions(permission: OnboardingPermission): List<String> = when (permission) {
        OnboardingPermission.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyList()
            }
        // API 31+ ignores a fine-only request; both must be asked for together or the system never
        // shows the precise/approximate choice.
        OnboardingPermission.LOCATION -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        OnboardingPermission.CAMERA -> listOf(Manifest.permission.CAMERA)
        OnboardingPermission.BLUETOOTH -> bluetoothManifestPermissions(Build.VERSION.SDK_INT)
    }
}

/**
 * Manifest names the Bluetooth onboarding card requests. API 31+ uses the dedicated scan/connect
 * pair. Below that, BLE scan still needs Location; an empty list would treat the card as granted
 * at install time and start an empty first-pair scan.
 */
@SuppressLint("InlinedApi")
internal fun bluetoothManifestPermissions(sdkInt: Int): List<String> = if (sdkInt >= Build.VERSION_CODES.S) {
    listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
}

/**
 * The record of every onboarding permission this install has already prompted for, persisted because it
 * has to survive the process. It is the durable half of the refused-versus-never-asked distinction:
 * `shouldShowRequestPermissionRationale` reports a first refusal but goes quiet again once the user
 * refuses for good, so on its own it cannot tell a permanently refused permission from an unasked one.
 * [AndroidLocationService] reads the same record so an onboarding refusal is denied rather than
 * undetermined.
 */
internal const val ASKED_PERMISSIONS_PREFS_FILE = "onboarding-permissions-asked"

internal fun locationPermissionAsked(context: Context): Boolean =
    context.getSharedPreferences(ASKED_PERMISSIONS_PREFS_FILE, Context.MODE_PRIVATE)
        .getBoolean(OnboardingPermission.LOCATION.name, false)

internal interface AskedPermissionsStore {
    fun asked(): Set<OnboardingPermission>

    fun markAsked(permission: OnboardingPermission)
}

/**
 * Computes the onboarding permission state from live platform reads.
 *
 * `determined` is the tri-state iOS gets free from `CLAuthorizationStatus` / `UNAuthorizationStatus` and
 * Android has no single API for: a permission counts as answered when it is granted, when this install
 * has already prompted for it, or when the system asks for a rationale. Onboarding resume reads this
 * field, so a refusal that failed to register would walk a returning user back through steps they had
 * already finished.
 *
 * Both fields are recomputed from scratch on every [read]; nothing is carried over from the previous
 * state, so a permission granted or revoked in system Settings lands on the next read.
 */
internal class PermissionStateReader(
    private val store: AskedPermissionsStore,
    private val isGranted: (OnboardingPermission) -> Boolean,
    private val needsRationale: (OnboardingPermission) -> Boolean,
) {
    fun read(): PermissionUiState {
        val asked = store.asked()
        val granted = OnboardingPermission.entries.filter { isGranted(it) }.toSet()
        val answered = OnboardingPermission.entries.filter { it in asked || needsRationale(it) }.toSet()
        return PermissionUiState(granted = granted, determined = granted + answered)
    }

    fun markAsked(permission: OnboardingPermission) {
        store.markAsked(permission)
    }
}

/**
 * [AskedPermissionsStore] over a private preferences file, one flag per permission. The flag names are
 * the enum case names, so renaming a case orphans its record and re-offers a prompt the user already
 * answered.
 */
private class SharedPreferencesAskedPermissions(context: Context) : AskedPermissionsStore {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun asked(): Set<OnboardingPermission> =
        OnboardingPermission.entries.filter { prefs.getBoolean(it.name, false) }.toSet()

    override fun markAsked(permission: OnboardingPermission) {
        prefs.edit { putBoolean(permission.name, true) }
    }

    private companion object {
        const val FILE_NAME = ASKED_PERMISSIONS_PREFS_FILE
    }
}

/**
 * Whether [grants] — the grant state of each permission behind an onboarding card, in the order the
 * card requests them — satisfies that card. Location is satisfied by either precision, so an
 * approximate-only grant reads as granted rather than as a permanent denial; every other card needs
 * all of its permissions.
 */
internal fun isSatisfied(permission: OnboardingPermission, grants: List<Boolean>): Boolean =
    if (permission == OnboardingPermission.LOCATION) {
        grants.any { it }
    } else {
        grants.all { it }
    }
