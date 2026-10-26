// Resolves ShortcutText from the shared core:l10n resources without a reverse UI/service dependency.
// PortedFrom: MC1/Intents/IntentError.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Extensions/Errors/MeshCoreError+UserFacingMessage.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Extensions/Errors/ProtocolError+UserFacingMessage.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.platform.shortcuts

import android.content.Context
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.ErrorCode

class AndroidShortcutText(private val context: Context) : ShortcutText {
    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

    override fun sendConfirm(recipientName: String) = s(R.string.l10n_app_tools_intent_send_confirm, recipientName)
    override fun advertConfirm(reach: AdvertReach) = s(R.string.l10n_app_tools_intent_advert_title) + " (" + advertReachLabel(reach) + ")?"
    override fun advertSent(reach: AdvertReach) = when (reach) {
        AdvertReach.ZERO_HOP -> s(R.string.l10n_app_tools_intent_advert_dialog_sentzerohop)
        AdvertReach.FLOOD -> s(R.string.l10n_app_tools_intent_advert_dialog_sentflood)
    }
    override fun error(error: ShortcutError) = when (error) {
        ShortcutError.NOT_CONNECTED -> s(R.string.l10n_app_localizable_error_intent_notconnected)
        ShortcutError.INVALID_RECIPIENT -> s(R.string.l10n_app_localizable_error_intent_invalidrecipient)
        ShortcutError.MESSAGE_TOO_LONG -> s(R.string.l10n_app_localizable_error_intent_messagetoolong)
        ShortcutError.ADVERT_FAILED -> s(L.errorAdvertisementSendFailed)
        ShortcutError.SEND_FAILED, ShortcutError.DENIED ->
            s(R.string.l10n_app_localizable_error_intent_sendfailed)
        is ShortcutError.SessionError -> sessionError(error.underlying)
    }

    private fun sessionError(error: MeshCoreException): String = when (error) {
        is MeshCoreException.Timeout -> s(L.errorMeshCoreTimeout)
        is MeshCoreException.DeviceError -> when (ErrorCode.fromRawValue(error.code)) {
            ErrorCode.UNSUPPORTED_COMMAND -> s(L.errorDeviceUnsupportedCommand)
            ErrorCode.NOT_FOUND -> s(L.errorDeviceNotFound)
            ErrorCode.TABLE_FULL -> s(L.errorDeviceStorageFull)
            ErrorCode.BAD_STATE -> s(L.errorDeviceInvalidState)
            ErrorCode.FILE_IO_ERROR -> s(L.errorDeviceFileSystem)
            ErrorCode.ILLEGAL_ARGUMENT -> s(L.errorDeviceInvalidParameter)
            null -> L.errorDeviceUnknown(context.resources, error.code.toLong())
        }
        is MeshCoreException.ParseError -> L.errorMeshCoreParseError(context.resources, error.reason)
        is MeshCoreException.NotConnected -> s(L.errorMeshCoreNotConnected)
        is MeshCoreException.CommandFailed -> L.errorMeshCoreCommandFailed(context.resources, error.reason)
        is MeshCoreException.InvalidResponse -> L.errorMeshCoreInvalidResponse(context.resources, error.expected, error.got)
        is MeshCoreException.ContactNotFound -> s(L.errorMeshCoreContactNotFound)
        is MeshCoreException.DataTooLarge -> L.errorMeshCoreDataTooLarge(context.resources, error.actualSize, error.maxSize)
        is MeshCoreException.SigningFailed -> L.errorMeshCoreSigningFailed(context.resources, error.reason)
        is MeshCoreException.InvalidInput -> L.errorMeshCoreInvalidInput(context.resources, error.reason)
        is MeshCoreException.Unknown -> L.errorMeshCoreUnknown(context.resources, error.reason)
        is MeshCoreException.BluetoothUnavailable -> s(L.errorMeshCoreBluetoothUnavailable)
        is MeshCoreException.BluetoothUnauthorized -> s(L.errorMeshCoreBluetoothUnauthorized)
        is MeshCoreException.BluetoothPoweredOff -> s(L.errorMeshCoreBluetoothPoweredOff)
        is MeshCoreException.ConnectionLost -> error.cause?.let { underlying ->
            val detail = if (underlying is MeshCoreException) sessionError(underlying)
                else underlying.localizedMessage ?: underlying.toString()
            L.errorMeshCoreConnectionLost(context.resources, detail)
        } ?: s(L.errorMeshCoreConnectionLostNoDetail)
        is MeshCoreException.SessionNotStarted -> s(L.errorMeshCoreSessionNotStarted)
        is MeshCoreException.FeatureDisabled -> s(L.errorMeshCoreFeatureDisabled)
    }
    override val sendForeground get() = s(R.string.l10n_app_tools_intent_send_foreground)
    override val statusNotReady get() = s(R.string.l10n_app_tools_intent_status_dialog_notready)
    override val statusRadioFallbackName get() = s(R.string.l10n_app_tools_intent_status_radiofallbackname)
    override val statusDisconnectedUnknown get() = s(R.string.l10n_app_tools_intent_status_dialog_disconnectedunknown)
    override fun statusConnectedNoBattery(name: String) = s(R.string.l10n_app_tools_intent_status_dialog_connectednobattery, name)
    override fun statusConnectedWithBattery(name: String, percent: Int) =
        s(R.string.l10n_app_tools_intent_status_dialog_connectedwithbattery, name, percent)
    override fun statusConnecting(name: String) = s(R.string.l10n_app_tools_intent_status_dialog_connecting, name)
    override fun statusDisconnectedNamed(name: String) = s(R.string.l10n_app_tools_intent_status_dialog_disconnectednamed, name)
    override val channelSubtitle get() = s(R.string.l10n_app_tools_intent_entity_channel)
    override val sendShortTitle get() = s(R.string.l10n_app_tools_intent_send_shorttitle)
    override val statusShortTitle get() = s(R.string.l10n_app_tools_intent_status_shorttitle)
    override val advertShortTitle get() = s(R.string.l10n_app_tools_intent_advert_shorttitle)
    override fun advertReachLabel(reach: AdvertReach) = when (reach) {
        AdvertReach.ZERO_HOP -> s(R.string.l10n_app_tools_intent_advert_reach_zerohop)
        AdvertReach.FLOOD -> s(R.string.l10n_app_tools_intent_advert_reach_flood)
    }
}
