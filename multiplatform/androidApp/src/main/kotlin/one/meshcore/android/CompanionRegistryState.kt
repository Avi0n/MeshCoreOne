package one.meshcore.android

import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import one.meshcore.shared.connection.CompanionAssociateResult
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.companionDeviceManagerCanRemoveBond
import java.util.concurrent.Executor

/** CDM handle, in-flight chooser, and the association snapshot shared by the registry pieces. */
@MainThread
internal class CompanionRegistryState private constructor(
    val activity: ComponentActivity?,
    private val resolveManager: () -> CompanionDeviceManager?,
    private val forceAvailable: Boolean,
    executor: Executor?,
    mainHandler: Handler?,
) {
    constructor(activity: ComponentActivity) : this(
        activity = activity,
        resolveManager = {
            activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
        },
        forceAvailable = false,
        executor = ContextCompat.getMainExecutor(activity),
        mainHandler = Handler(Looper.getMainLooper()),
    )

    internal constructor(resolveManager: () -> CompanionDeviceManager?) : this(
        activity = null,
        resolveManager = resolveManager,
        forceAvailable = true,
        executor = null,
        mainHandler = null,
    )

    // Activity.getSystemService throws before onCreate. Resolve CDM on first use so this
    // type can be constructed as an Activity field (registerForActivityResult).
    val manager: CompanionDeviceManager? by lazy { resolveManager() }
    val executor: Executor = executor ?: Executor(Runnable::run)
    private val mainHandlerOrNull = mainHandler
    val mainHandler: Handler
        get() = checkNotNull(mainHandlerOrNull)
    var pending: CompletableDeferred<CompanionAssociateResult>? = null
    val attemptGate = CompanionAssociationAttemptGate()
    var chooserAttempt: Long? = null
    var previousAssociations: List<CompanionAssociation> = emptyList()
    val removals = MutableSharedFlow<List<CompanionAssociation>>(extraBufferCapacity = 8)

    val isAvailable: Boolean
        get() = forceAvailable || (manager != null && hasCompanionDeviceFeature())

    private fun hasCompanionDeviceFeature(): Boolean =
        activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP) == true

    val canDropOsBond: Boolean
        get() = isAvailable && companionDeviceManagerCanRemoveBond(Build.VERSION.SDK_INT)
}
