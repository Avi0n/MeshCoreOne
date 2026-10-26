// AndroidOnly: WP-303 Onboarding region catalog, region and preset ports over core:services device data.
// AndroidOnly: WP-211 Bind existing subdivision translations to current app-localized resources.
package com.meshcoreone.android.app.container.onboarding

import android.content.res.Resources
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.services.device.RadioPreset
import com.meshcoreone.android.core.services.device.RadioPresets
import com.meshcoreone.android.core.services.device.RegionalAreas
import com.meshcoreone.android.core.services.device.SubdivisionNameResolver
import com.meshcoreone.android.core.l10n.generated.RegionalSubdivisionNames
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.onboarding.AdministrativeAreaKind
import com.meshcoreone.android.feature.onboarding.OnboardingCountry
import com.meshcoreone.android.feature.onboarding.OnboardingPresetOption
import com.meshcoreone.android.feature.onboarding.OnboardingPresetOutcome
import com.meshcoreone.android.feature.onboarding.OnboardingPresetPort
import com.meshcoreone.android.feature.onboarding.OnboardingRegionCatalog
import com.meshcoreone.android.feature.onboarding.OnboardingRegionPort
import com.meshcoreone.android.feature.onboarding.OnboardingSubdivision
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** [OnboardingRegionCatalog] over `RegionalAreas` and `RadioPresets`; the picker never sees core:services types. */
class ServicesRegionCatalog(
    private val resources: (() -> Resources)? = null,
    private val locale: () -> Locale = { resources?.invoke()?.configuration?.locales?.get(0) ?: Locale.getDefault() },
) : OnboardingRegionCatalog {
    private val names = SubdivisionNameResolver { code, _ ->
        resources?.invoke()?.let { RegionalSubdivisionNames.displayName(it, code) }
    }

    override fun countries(): List<OnboardingCountry> =
        RegionalAreas.countriesSortedByLocalizedName.map { OnboardingCountry(it.id, it.name(locale())) }

    override fun showsSubdivisionPicker(countryCode: String?): Boolean = RegionalAreas.showsSubdivisionPicker(countryCode)

    override fun subdivisions(countryCode: String?): List<OnboardingSubdivision> =
        RegionalAreas.subdivisions(countryCode, locale(), names).map {
            OnboardingSubdivision(it.id, RegionalAreas.subdivisionDisplayName(it.id, locale(), names) ?: it.englishName)
        }

    override fun administrativeAreaKind(countryCode: String?): AdministrativeAreaKind =
        when (RegionalAreas.administrativeAreaKind(countryCode)) {
            RegionalAreas.AdministrativeAreaKind.PROVINCE -> AdministrativeAreaKind.PROVINCE
            RegionalAreas.AdministrativeAreaKind.STATE -> AdministrativeAreaKind.STATE
        }

    override fun displayName(region: RegionSelection): String = RegionalAreas.displayName(region, locale(), names)
    override fun countryDisplayName(countryCode: String): String = RegionalAreas.Country(countryCode, null).name(locale())
    override fun subdivisionDisplayName(code: String): String? = RegionalAreas.subdivisionDisplayName(code, locale(), names)

    override fun presets(region: RegionSelection?): List<OnboardingPresetOption> {
        val collator = Collator.getInstance(locale())
        return RadioPresets.visiblePresets(region, activeID = null, locale = locale())
            .map { OnboardingPresetOption(it.id, it.name, it.frequencyMHz) }
            .sortedWith { left, right -> collator.compare(left.name, right.name) }
    }
}

/** Selection state lives in app state (persisted by its region store); the resolver is location + geocoder. */
class AppOnboardingRegionPort(
    override val catalog: OnboardingRegionCatalog,
    override val selection: StateFlow<RegionSelection?>,
    private val persist: (RegionSelection) -> Unit,
    private val resolver: (suspend () -> RegionSelection?)?,
) : OnboardingRegionPort {
    override fun setSelection(selection: RegionSelection) = persist(selection)

    override suspend fun resolveRegion(): RegionSelection? {
        val resolve = resolver ?: return null
        return try {
            resolve()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        }
    }
}

/** The connected radio's settings seam: applies one preset and records it on the connected device. */
interface OnboardingRadioConfigurator {
    fun connectionStates(): Flow<DeviceConnectionState>
    val isDemoRadio: Boolean

    /** Throws [SettingsServiceException] (or a runtime failure) when the radio cannot be configured. */
    suspend fun applyPreset(preset: RadioPreset)
}

class AppOnboardingPresetPort(
    private val radio: OnboardingRadioConfigurator,
    scope: CoroutineScope,
    private val lookup: (String) -> RadioPreset? = { id -> RadioPresets.all.firstOrNull { it.id == id } },
) : OnboardingPresetPort {
    override val canApply: StateFlow<Boolean> =
        radio.connectionStates().map { it.isOperational }.stateIn(scope, SharingStarted.Eagerly, false)

    override suspend fun apply(presetId: String): OnboardingPresetOutcome {
        if (radio.isDemoRadio) return OnboardingPresetOutcome.NoRadioToConfigure
        val preset = lookup(presetId)
            ?: return OnboardingPresetOutcome.Failed(UiText.Verbatim("Unknown radio preset: $presetId"))
        return try {
            radio.applyPreset(preset)
            OnboardingPresetOutcome.Applied
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SettingsServiceException) {
            val message = UiText.Verbatim(failure.message ?: failure.javaClass.simpleName)
            when {
                failure.error == SettingsServiceError.NotConnected -> OnboardingPresetOutcome.NotConnected
                failure.isRetryable -> OnboardingPresetOutcome.Retryable(message)
                else -> OnboardingPresetOutcome.Failed(message)
            }
        } catch (failure: Exception) {
            OnboardingPresetOutcome.Failed(UiText.Verbatim(failure.message ?: failure.javaClass.simpleName))
        }
    }
}
