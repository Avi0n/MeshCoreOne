package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.flow.update
import one.meshcore.shared.remotenode.behaviorIntervalFields
import one.meshcore.shared.remotenode.contactInfoSettingsFields
import one.meshcore.shared.remotenode.identitySettingsFields
import one.meshcore.shared.remotenode.radioSettingsFields

internal fun NodeSettingsModel.publishRecoveredFields() {
    stateUpdates.update {
        it.withIdentitySettings(helper.identitySettingsFields())
            .withRadioSettings(helper.radioSettingsFields())
            .withContactInfoSettings(helper.contactInfoSettingsFields())
            .copy(
                deviceInfoLoaded = helper.deviceInfoLoaded || helper.firmwareVersion != null,
                deviceInfoSectionError = helper.deviceInfoError,
                firmwareVersion = helper.firmwareVersion,
                supportsRegionDefaultScope = controller.supportsRegionDefaultScope,
                regionsLoaded = controller.regionsLoaded,
                regionsSectionError = controller.regionsError,
                regions = controller.regions,
                defaultScopeName = controller.defaultScopeName,
                defaultScopeLoaded = controller.defaultScopeLoaded,
                isLoadingDefaultScope = controller.isLoadingDefaultScope,
                hasUnsavedRegionChanges = controller.hasUnsavedRegionChanges,
                deviceTimeUtc = helper.deviceTimeUtcString,
                deviceTimeEpochSeconds = helper.deviceTimeEpochSeconds,
                clockDriftSeconds = helper.clockDriftSeconds,
            ).withBehaviorSettings(
                controller.behaviorIntervalFields(),
                repeaterEnabled = controller.repeaterEnabled == true,
            )
    }
}
