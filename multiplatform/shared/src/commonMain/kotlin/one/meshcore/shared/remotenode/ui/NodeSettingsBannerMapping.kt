package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.remotenode.ActionResult
import one.meshcore.shared.remotenode.PasswordApplyResult
import one.meshcore.shared.remotenode.RegionOpResult
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.SettingsApplyResult
import one.meshcore.shared.remotenode.SyncTimeResult

/**
 * The banner for a `clock sync` outcome. Shared by the repeater and room screens so one node reply cannot
 * be reported two ways; a failure the firmware described in its own words keeps those words.
 */
internal fun SyncTimeResult.toBanner(): ApplyBanner = when (this) {
    is SyncTimeResult.Synced -> ApplyBanner.TimeSynced
    is SyncTimeResult.ClockAhead -> ApplyBanner.ClockAhead
    is SyncTimeResult.Failed ->
        if (message.isBlank()) ApplyBanner.SyncTimeFailed else ApplyBanner.FirmwareError(message)
    is SyncTimeResult.Unexpected -> ApplyBanner.UnexpectedResponse(response)
    is SyncTimeResult.TransportError -> error.toTransportBanner()
}

/**
 * The banner for a settings apply. Shared by the repeater and room screens so one node reply cannot be
 * reported two ways. A local validation refusal (`InvalidFields`) has no banner: iOS identity apply
 * returns with the field errors only, and the radio off-grid gate is the same class of local refusal.
 */
internal fun SettingsApplyResult.toBanner(): ApplyBanner? = when (this) {
    SettingsApplyResult.Applied -> ApplyBanner.Success
    SettingsApplyResult.PartialFailure -> ApplyBanner.PartialFailure
    SettingsApplyResult.InvalidFields -> null
    SettingsApplyResult.NotLoaded -> ApplyBanner.NotLoaded
    is SettingsApplyResult.TransportError -> error.toTransportBanner()
}

/**
 * A section apply flashes success on its own button, so `Applied` does not also raise `ApplyBanner.Success`.
 * A local field refusal still has no banner.
 */
internal fun SettingsApplyResult.bannerUnlessApplied(): ApplyBanner? =
    if (this == SettingsApplyResult.Applied) null else toBanner()

/**
 * The banner for a password change. Applied has no banner: the screen flashes success instead.
 * Shared by the repeater and room screens so one refusal cannot be reported two ways.
 */
internal fun PasswordApplyResult.toBanner(): ApplyBanner? = when (this) {
    PasswordApplyResult.Applied -> null
    PasswordApplyResult.Empty -> ApplyBanner.PasswordEmpty
    PasswordApplyResult.Mismatch -> ApplyBanner.PasswordMismatch
    PasswordApplyResult.Failed -> ApplyBanner.PasswordFailed
    is PasswordApplyResult.TransportError -> error.toTransportBanner()
}

/** Advert and reboot each name their own success. A transport failure keeps the error text. */
internal fun ActionResult.toSentBanner(sent: ApplyBanner): ApplyBanner = when (this) {
    ActionResult.Sent -> sent
    is ActionResult.TransportError -> error.toTransportBanner()
}

/**
 * The banner for a radio-only apply. Success has to say the node must restart before the new parameters
 * take effect, and a refusal names the radio rather than reporting that some unnamed setting failed.
 */
internal fun SettingsApplyResult.toRadioBanner(): ApplyBanner? = when (this) {
    is SettingsApplyResult.Applied -> ApplyBanner.RadioApplied
    is SettingsApplyResult.PartialFailure -> ApplyBanner.RadioApplyFailed
    is SettingsApplyResult.InvalidFields -> null
    is SettingsApplyResult.NotLoaded -> ApplyBanner.NotLoaded
    is SettingsApplyResult.TransportError -> error.toTransportBanner()
}

/** iOS `RemoteNodeError.errorDescription` is shown verbatim rather than collapsed to "failed to send". */
internal fun RemoteNodeError.toTransportBanner(): ApplyBanner = ApplyBanner.TransportError(checkNotNull(message))

/** Live tree ops succeed without a saved-to-device claim; saving regions sets [ApplyBanner.RegionsSaved]. */
internal fun RegionOpResult.toBanner(): ApplyBanner? = when (this) {
    RegionOpResult.Applied -> ApplyBanner.Success
    RegionOpResult.NotLoaded -> ApplyBanner.NotLoaded
    RegionOpResult.SaveFailed -> ApplyBanner.RegionsSaveFailed
    RegionOpResult.UnknownRegion -> ApplyBanner.RegionUnknown
    RegionOpResult.AddFailed, RegionOpResult.EmptyName -> ApplyBanner.RegionAddFailed
    RegionOpResult.AddValidationFailed -> ApplyBanner.RegionAddValidationFailed
    RegionOpResult.RemoveFailed -> ApplyBanner.RegionRemoveFailed
    RegionOpResult.NotEmpty -> ApplyBanner.RegionNotEmpty
    is RegionOpResult.TransportError -> error.toTransportBanner()
}
