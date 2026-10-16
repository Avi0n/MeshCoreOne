package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.remotenode.BehaviorIntervalFields
import one.meshcore.shared.remotenode.ContactInfoSettingsFields
import one.meshcore.shared.remotenode.IdentitySettingsFields
import one.meshcore.shared.remotenode.RadioSettingsFields

internal fun NodeSettingsState.withIdentitySettings(
    fields: IdentitySettingsFields,
    loading: Boolean = isLoadingIdentity,
): NodeSettingsState = copy(
    isLoadingIdentity = loading,
    identityLoaded = fields.loaded,
    identitySectionError = fields.sectionError,
    name = fields.name,
    latitudeText = fields.latitudeText,
    longitudeText = fields.longitudeText,
    identityModified = fields.modified,
)

internal fun NodeSettingsState.withRadioSettings(
    fields: RadioSettingsFields,
    loading: Boolean = isLoadingRadio,
    modified: Boolean = radioModified,
): NodeSettingsState = copy(
    isLoadingRadio = loading,
    radioLoaded = fields.loaded,
    radioSectionError = fields.sectionError,
    frequencyText = fields.frequencyText,
    bandwidthText = fields.bandwidthText,
    spreadingFactorText = fields.spreadingFactorText,
    codingRateText = fields.codingRateText,
    radioModified = modified,
)

internal fun NodeSettingsState.withBehaviorSettings(
    fields: BehaviorIntervalFields,
    loading: Boolean = isLoadingBehavior,
    repeaterEnabled: Boolean = this.repeaterEnabled,
): NodeSettingsState = copy(
    isLoadingBehavior = loading,
    behaviorLoaded = fields.loaded,
    behaviorSectionError = fields.sectionError,
    repeaterEnabled = repeaterEnabled,
    advertIntervalText = fields.advertIntervalText,
    floodAdvertIntervalText = fields.floodAdvertIntervalText,
    floodMaxHopsText = fields.floodMaxHopsText,
    behaviorModified = fields.modified,
)

internal fun NodeSettingsState.withContactInfoSettings(
    fields: ContactInfoSettingsFields,
    loading: Boolean = isLoadingContactInfo,
): NodeSettingsState = copy(
    isLoadingContactInfo = loading,
    contactInfoLoaded = fields.loaded,
    contactInfoSectionError = fields.sectionError,
    ownerInfo = fields.ownerInfo,
    ownerInfoTooLong = fields.ownerInfoTooLong,
    contactInfoModified = fields.modified,
)
