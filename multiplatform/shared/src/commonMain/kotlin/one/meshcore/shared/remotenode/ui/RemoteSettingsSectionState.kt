package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.remotenode.NodeSettingsValidation

/** Fields the shared identity section reads from either screen's state. */
interface RemoteIdentitySectionState {
    val identityExpanded: Boolean
    val isLoadingIdentity: Boolean
    val identityLoaded: Boolean
    val identitySectionError: Boolean
    val name: String
    val latitudeText: String
    val longitudeText: String
    val identityModified: Boolean
    val identityValidation: NodeSettingsValidation.IdentityValidation?
    val isApplyingIdentity: Boolean
    val identityApplySuccess: Boolean
}

interface RemoteRadioSectionState {
    val radioExpanded: Boolean
    val isLoadingRadio: Boolean
    val radioLoaded: Boolean
    val radioSectionError: Boolean
    val radioModified: Boolean
    val frequencyText: String
    val bandwidthText: String
    val spreadingFactorText: String
    val codingRateText: String
    val isApplyingRadio: Boolean
}

interface RemoteContactInfoSectionState {
    val contactInfoExpanded: Boolean
    val isLoadingContactInfo: Boolean
    val contactInfoLoaded: Boolean
    val contactInfoSectionError: Boolean
    val ownerInfo: String
    val ownerInfoTooLong: Boolean
    val applyBanner: ApplyBanner?
    val contactInfoModified: Boolean
    val isApplyingContactInfo: Boolean
    val contactInfoApplySuccess: Boolean
}

interface RemoteBehaviorSectionState {
    val behaviorExpanded: Boolean
    val isLoadingBehavior: Boolean
    val behaviorLoaded: Boolean
    val behaviorSectionError: Boolean
    val advertIntervalText: String
    val floodAdvertIntervalText: String
    val floodMaxHopsText: String
    val behaviorValidation: NodeSettingsValidation.BehaviorValidation?
    val behaviorModified: Boolean
    val isApplyingBehavior: Boolean
    val behaviorApplySuccess: Boolean
}

interface RemoteSecuritySectionState {
    val securityExpanded: Boolean
    val newPassword: String
    val confirmPassword: String
    val isChangingPassword: Boolean
    val changePasswordSuccess: Boolean
}

interface RemoteDeviceInfoSectionState {
    val deviceInfoExpanded: Boolean
    val isLoadingDeviceInfo: Boolean
    val deviceInfoLoaded: Boolean
    val deviceInfoSectionError: Boolean
    val firmwareVersion: String?
    val deviceTimeEpochSeconds: Long?
    val deviceTimeUtc: String?
    val clockDriftSeconds: Double?
}

interface RemoteDeviceActionsSectionState {
    val deviceActionsExpanded: Boolean
    val isSendingAdvert: Boolean
    val isSyncingTime: Boolean
    val isRebooting: Boolean
}
