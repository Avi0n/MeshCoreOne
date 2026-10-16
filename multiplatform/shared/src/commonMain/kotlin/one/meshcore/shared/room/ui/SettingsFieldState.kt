package one.meshcore.shared.room.ui

import one.meshcore.shared.remotenode.BehaviorIntervalFields
import one.meshcore.shared.remotenode.ContactInfoSettingsFields
import one.meshcore.shared.remotenode.IdentitySettingsFields
import one.meshcore.shared.remotenode.RadioSettingsFields

internal fun RoomSettingsUiState.withIdentitySettings(
    fields: IdentitySettingsFields,
    loading: Boolean = isLoadingIdentity,
): RoomSettingsUiState = copy(
    isLoadingIdentity = loading,
    identityLoaded = fields.loaded,
    identitySectionError = fields.sectionError,
    name = fields.name,
    latitudeText = fields.latitudeText,
    longitudeText = fields.longitudeText,
    identityModified = fields.modified,
)

internal fun RoomSettingsUiState.withRadioSettings(
    fields: RadioSettingsFields,
    loading: Boolean = isLoadingRadio,
    modified: Boolean = radioModified,
): RoomSettingsUiState = copy(
    isLoadingRadio = loading,
    radioLoaded = fields.loaded,
    radioSectionError = fields.sectionError,
    frequencyText = fields.frequencyText,
    bandwidthText = fields.bandwidthText,
    spreadingFactorText = fields.spreadingFactorText,
    codingRateText = fields.codingRateText,
    radioModified = modified,
)

internal fun RoomSettingsUiState.withBehaviorSettings(
    fields: BehaviorIntervalFields,
    loading: Boolean = isLoadingBehavior,
): RoomSettingsUiState = copy(
    isLoadingBehavior = loading,
    behaviorLoaded = fields.loaded,
    behaviorSectionError = fields.sectionError,
    advertIntervalText = fields.advertIntervalText,
    floodAdvertIntervalText = fields.floodAdvertIntervalText,
    floodMaxHopsText = fields.floodMaxHopsText,
    behaviorModified = fields.modified,
)

internal fun RoomSettingsUiState.withContactInfoSettings(
    fields: ContactInfoSettingsFields,
    loading: Boolean = isLoadingContactInfo,
): RoomSettingsUiState = copy(
    isLoadingContactInfo = loading,
    contactInfoLoaded = fields.loaded,
    contactInfoSectionError = fields.sectionError,
    ownerInfo = fields.ownerInfo,
    ownerInfoTooLong = fields.ownerInfoTooLong,
    contactInfoModified = fields.modified,
)
