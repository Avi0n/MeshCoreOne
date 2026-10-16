package one.meshcore.android

import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import one.meshcore.shared.connection.CompanionAssociateResult
import one.meshcore.shared.connection.companionDeviceManagerCanRemoveBond
import one.meshcore.shared.connection.companionHandleMatches

/** One in-flight Companion Device Manager chooser, from request through forget. */
@MainThread
internal class CompanionAssociationSession(
    private val state: CompanionRegistryState,
    private val catalog: CompanionAssociationCatalog,
    private val presence: CompanionPresenceObserver,
    registerChooser: Boolean = true,
) {
    private val chooserLauncher: ActivityResultLauncher<IntentSenderRequest>? =
        if (registerChooser) {
            checkNotNull(state.activity).registerForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { result ->
                val attempt = state.chooserAttempt ?: return@registerForActivityResult
                state.chooserAttempt = null
                if (result.resultCode != Activity.RESULT_OK) {
                    complete(attempt, CompanionAssociateResult.Cancelled)
                    return@registerForActivityResult
                }
                val chosen = catalog.associationFromChooser(result.data) ?: CompanionAssociateResult.Cancelled
                complete(attempt, chosen)
            }
        } else {
            null
        }

    @Suppress("TooGenericExceptionCaught")
    suspend fun associate(): CompanionAssociateResult {
        if (!state.isAvailable) return CompanionAssociateResult.Unavailable
        if (state.pending?.isActive == true || state.chooserAttempt != null) {
            return CompanionAssociateResult.Failed("already in progress")
        }
        val deferred = CompletableDeferred<CompanionAssociateResult>()
        val attempt = state.attemptGate.begin()
        state.pending = deferred
        try {
            return try {
                startAssociate(attempt)
                val chosen = deferred.await()
                if (chosen is CompanionAssociateResult.Associated) {
                    state.previousAssociations = catalog.associations()
                }
                chosen
            } catch (cancelled: CancellationException) {
                // Dismiss the system chooser before the coroutine ends.
                cancelAssociate()
                throw cancelled
            } catch (error: Throwable) {
                val failed = CompanionAssociateResult.Failed(error.message)
                complete(attempt, failed)
                failed
            }
        } finally {
            if (state.pending === deferred) state.pending = null
        }
    }

    fun cancelAssociate() {
        if (state.attemptGate.cancel() == null) return
        state.pending?.takeIf { it.isActive }?.complete(CompanionAssociateResult.Cancelled)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val intentSender = runCatching { state.manager?.buildAssociationCancellationIntent() }.getOrNull() ?: return
        val activity = state.activity
        if (activity != null) {
            @Suppress("DEPRECATION")
            runCatching {
                activity.startIntentSenderForResult(
                    intentSender,
                    ASSOCIATION_CANCELLATION_REQUEST_CODE,
                    null,
                    0,
                    0,
                    0,
                )
            }
        }
    }

    fun disassociate(handle: String) {
        val cdm = state.manager ?: return
        val key = handle.trim().takeIf { it.isNotEmpty() } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.myAssociations.forEach { info ->
                val associated = catalog.associationHandle(info) ?: return@forEach
                if (companionHandleMatches(key, associated)) {
                    presence.stop(associated)
                    dropBondThenDisassociate(cdm, info.id)
                }
            }
            return
        }
        @Suppress("DEPRECATION")
        cdm.associations.filter { companionHandleMatches(key, it) }.forEach { mac ->
            presence.stop(mac)
            cdm.disassociate(mac)
        }
    }

    private fun startAssociate(attempt: Long) {
        val cdm = state.manager ?: run {
            complete(attempt, CompanionAssociateResult.Unavailable)
            return
        }
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid.fromString(NORDIC_UART_SERVICE_UUID))
                    .build(),
            )
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.associate(
                request,
                state.executor,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) {
                        launchChooser(attempt, intentSender)
                    }

                    override fun onAssociationCreated(associationInfo: AssociationInfo) {
                        complete(attempt, catalog.fromInfo(associationInfo))
                    }

                    override fun onFailure(error: CharSequence?) {
                        complete(attempt, CompanionAssociateResult.Failed(error?.toString()))
                    }
                },
            )
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(
                request,
                @Suppress("DEPRECATION")
                object : CompanionDeviceManager.Callback() {
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onDeviceFound(intentSender: IntentSender) {
                        launchChooser(attempt, intentSender)
                    }

                    override fun onFailure(error: CharSequence?) {
                        complete(attempt, CompanionAssociateResult.Failed(error?.toString()))
                    }
                },
                state.mainHandler,
            )
        }
    }

    private fun launchChooser(attempt: Long, intentSender: IntentSender) {
        if (!state.attemptGate.isActive(attempt)) return
        state.chooserAttempt = attempt
        checkNotNull(chooserLauncher).launch(IntentSenderRequest.Builder(intentSender).build())
    }

    private fun complete(attempt: Long, result: CompanionAssociateResult) {
        when (state.attemptGate.complete(attempt)) {
            CompanionAssociationAttemptGate.Completion.ACCEPTED -> {
                state.chooserAttempt = null
                state.pending?.takeIf { it.isActive }?.complete(result)
            }
            CompanionAssociationAttemptGate.Completion.CANCELLED -> {
                if (result is CompanionAssociateResult.Associated) disassociate(result.association.handle)
            }
            CompanionAssociationAttemptGate.Completion.INACTIVE -> Unit
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Suppress("MissingPermission")
    private fun dropBondThenDisassociate(cdm: CompanionDeviceManager, associationId: Int) {
        if (canRemoveCompanionBond()) {
            // Association must still exist; the system looks up the MAC then starts unbond.
            runCatching { cdm.removeBond(associationId) }
        }
        cdm.disassociate(associationId)
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.BAKLAVA)
    private fun canRemoveCompanionBond(): Boolean = companionDeviceManagerCanRemoveBond(Build.VERSION.SDK_INT)

    private companion object {
        const val ASSOCIATION_CANCELLATION_REQUEST_CODE = 0x4D43
        const val NORDIC_UART_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    }
}
