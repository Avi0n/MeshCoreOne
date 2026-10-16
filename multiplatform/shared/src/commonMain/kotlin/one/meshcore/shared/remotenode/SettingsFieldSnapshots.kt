package one.meshcore.shared.remotenode

import one.meshcore.shared.settings.RadioOptions

/** Identity fields both settings screens show after a fetch or a late CLI reply. */
internal data class IdentitySettingsFields(
    val loaded: Boolean,
    val sectionError: Boolean,
    val name: String,
    val latitudeText: String,
    val longitudeText: String,
    val modified: Boolean,
)

/** Radio fields both settings screens show after a fetch or a late CLI reply. */
internal data class RadioSettingsFields(
    val loaded: Boolean,
    val sectionError: Boolean,
    val frequencyText: String,
    val bandwidthText: String,
    val spreadingFactorText: String,
    val codingRateText: String,
)

/** Contact-info fields both settings screens show after a fetch or a late CLI reply. */
internal data class ContactInfoSettingsFields(
    val loaded: Boolean,
    val sectionError: Boolean,
    val ownerInfo: String,
    val ownerInfoTooLong: Boolean,
    val modified: Boolean,
)

internal fun NodeSettingsController.identitySettingsFields(): IdentitySettingsFields = IdentitySettingsFields(
    loaded = identityLoaded || name != null,
    sectionError = identityError,
    name = name.orEmpty(),
    latitudeText = latitude?.toString().orEmpty(),
    longitudeText = longitude?.toString().orEmpty(),
    modified = identitySettingsModified,
)

internal fun NodeSettingsController.radioSettingsFields(): RadioSettingsFields = RadioSettingsFields(
    loaded = radioLoaded,
    sectionError = radioError,
    frequencyText = frequency?.toString().orEmpty(),
    bandwidthText = bandwidth?.let { bw -> RadioOptions.formatBandwidthKHz(bw) }.orEmpty(),
    spreadingFactorText = spreadingFactor?.toString().orEmpty(),
    codingRateText = codingRate?.toString().orEmpty(),
)

internal fun NodeSettingsController.contactInfoSettingsFields(): ContactInfoSettingsFields = ContactInfoSettingsFields(
    loaded = contactInfoLoaded,
    sectionError = contactInfoError,
    ownerInfo = ownerInfo.orEmpty(),
    ownerInfoTooLong = isOwnerInfoTooLong,
    modified = contactInfoSettingsModified,
)

/** Advert and flood fields both settings screens show after a fetch or a late CLI reply. */
internal data class BehaviorIntervalFields(
    val loaded: Boolean,
    val sectionError: Boolean,
    val advertIntervalText: String,
    val floodAdvertIntervalText: String,
    val floodMaxHopsText: String,
    val modified: Boolean,
)

internal fun RepeaterBehavior.behaviorIntervalFields(): BehaviorIntervalFields = BehaviorIntervalFields(
    loaded = behaviorLoaded,
    sectionError = behaviorError,
    advertIntervalText = advertIntervalMinutes?.toString().orEmpty(),
    floodAdvertIntervalText = floodAdvertIntervalHours?.toString().orEmpty(),
    floodMaxHopsText = floodMaxHops?.toString().orEmpty(),
    modified = behaviorSettingsModified,
)
