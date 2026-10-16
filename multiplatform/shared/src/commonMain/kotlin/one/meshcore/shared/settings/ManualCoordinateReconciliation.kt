package one.meshcore.shared.settings

/**
 * What a verified manual-coordinate write leaves to reconcile. The write switches the radio's on-board
 * GPS off, so a preference or advert policy that depends on a live fix no longer describes the node.
 */
data class ManualCoordinateReconciliation(val disableAutoUpdate: Boolean, val demoteAdvertPolicy: Boolean)

/**
 * The reconciliations the Swift `LocationPickerView.forLocalDevice` performs after a verified manual
 * write: an auto-update fed by the node's own GPS is turned off, and an advert policy of
 * [AdvertLocationPolicy.SHARE] is demoted to [AdvertLocationPolicy.PREFS] so the node advertises the
 * coordinate just written rather than a live fix from a GPS that is now off.
 */
fun manualCoordinateReconciliation(
    autoUpdateLocation: Boolean,
    gpsSource: GpsSource,
    advertLocationPolicyRaw: Int,
): ManualCoordinateReconciliation = ManualCoordinateReconciliation(
    disableAutoUpdate = autoUpdateLocation && gpsSource == GpsSource.DEVICE,
    demoteAdvertPolicy = advertLocationPolicyRaw == AdvertLocationPolicy.SHARE.rawValue,
)
