package one.meshcore.shared.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore

/**
 * Drives the onboarding flow: the step stack the shell renders, forward/back navigation with the
 * region-skip branch, the committed region selection, and the completion write. The iOS
 * `OnboardingState` is a `NavigationStack(path:)` rooted at Welcome; this models the same stack as a
 * `StateFlow<List<OnboardingStep>>` where an empty list is the Welcome root and each entry is a pushed
 * step, so a resume path can be pre-seeded exactly as iOS pushes `[permissions, pair, preset]` above the
 * root.
 *
 * Pure over an injected [PreferencesStore]: connection actions (pair, WiFi, simulator) are performed by
 * the composables against the app graph and, on success, drive [advance]; this holder stays free of
 * platform and connection types so it is unit-testable on the desktop JVM.
 */
class OnboardingViewModel(private val prefs: PreferencesStore, private val json: Json = Json) {
    private val _path = MutableStateFlow<List<OnboardingStep>>(emptyList())

    /** The pushed steps above the Welcome root; empty means Welcome is showing. */
    val path: StateFlow<List<OnboardingStep>> = _path.asStateFlow()

    private val _completed = MutableStateFlow(false)

    /** Whether onboarding has completed this launch; the root routing switches to the shell on `true`. */
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    /**
     * Whether the region step is skipped because a region is already set. Seeded from the resume path
     * (a `[permissions, pair, preset]` resume has no region) and honored by [advance] after Pair.
     */
    var regionSkipped: Boolean = false
        private set

    /** The step currently rendered, or `null` for the Welcome root. */
    val currentStep: OnboardingStep?
        get() = _path.value.lastOrNull()

    /**
     * Pre-seeds the stack from [suggestedStartingPath]. An empty path leaves Welcome showing (a brand-new
     * install or a fully-onboarded one the root routing never renders). A path without [OnboardingStep.REGION]
     * but with [OnboardingStep.PRESET] means the region was already set and the region step is skipped.
     */
    fun seedResume(steps: List<OnboardingStep>) {
        _path.value = steps
        regionSkipped = OnboardingStep.PRESET in steps && OnboardingStep.REGION !in steps
    }

    /** Advances from the Welcome root to the first step. */
    fun start() {
        if (_path.value.isEmpty()) _path.value = listOf(OnboardingStep.PERMISSIONS)
    }

    /**
     * Advances from the current step to the next, honoring the region-skip branch. Advancing off the
     * final step ([OnboardingStep.PRESET]) completes onboarding.
     */
    suspend fun advance() {
        val next = nextStep(currentStep) ?: run {
            complete()
            return
        }
        _path.value = _path.value + next
    }

    /** Pops the top step; a no-op at the Welcome root. Returns whether anything was popped. */
    fun back(): Boolean {
        if (_path.value.isEmpty()) return false
        _path.value = _path.value.dropLast(1)
        return true
    }

    private val _regionLabel = MutableStateFlow<String?>(null)

    /**
     * Display label for the committed region (country code, optional administrative area), used by the
     * preset step subtitle. Null until [commitRegion] or a resume path that already has a region.
     */
    val regionLabel: StateFlow<String?> = _regionLabel.asStateFlow()

    /**
     * Commits [selection] as the user's region (persisted as the same JSON blob the iOS app writes under
     * `userPrefs.region`) and advances to the preset step.
     */
    suspend fun commitRegion(selection: RegionSelection) {
        try {
            prefs.putString(
                PersistenceKeys.REGION_SELECTION,
                json.encodeToString(RegionSelection.serializer(), selection),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // iOS UserDefaults.set does not throw. A DataStore fault must not hold the user on Region.
        }
        _regionLabel.value = formatRegionLabel(selection)
        advance()
    }

    /** Seeds the preset subtitle when resuming with a region already on disk. */
    suspend fun loadRegionLabelFromPrefs() {
        val raw = prefs.getString(PersistenceKeys.REGION_SELECTION) ?: return
        runCatching {
            val selection = json.decodeFromString(RegionSelection.serializer(), raw)
            _regionLabel.value = formatRegionLabel(selection)
        }
    }

    private fun formatRegionLabel(selection: RegionSelection): String {
        val area = selection.administrativeAreaCode?.takeIf { it.isNotBlank() }
        return if (area != null) "${selection.countryCode}, $area" else selection.countryCode
    }

    /** Writes the onboarding-complete flag and flips [completed], switching the root to the shell. */
    suspend fun complete() {
        try {
            prefs.putBoolean(PersistenceKeys.HAS_COMPLETED_ONBOARDING, true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // iOS UserDefaults.set does not throw. A DataStore fault must not leave the user on Preset.
        }
        _completed.value = true
    }

    private fun nextStep(from: OnboardingStep?): OnboardingStep? = when (from) {
        null -> OnboardingStep.PERMISSIONS
        OnboardingStep.WELCOME -> OnboardingStep.PERMISSIONS
        OnboardingStep.PERMISSIONS -> OnboardingStep.PAIR
        OnboardingStep.PAIR -> if (regionSkipped) OnboardingStep.PRESET else OnboardingStep.REGION
        OnboardingStep.REGION -> OnboardingStep.PRESET
        OnboardingStep.PRESET -> null
    }
}
