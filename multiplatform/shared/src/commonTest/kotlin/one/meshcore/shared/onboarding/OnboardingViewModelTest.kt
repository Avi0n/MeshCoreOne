package one.meshcore.shared.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.backup.RegionSource
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the onboarding step machine: the Welcome→Preset linear flow, the region-skip branch a resume
 * seeds, back navigation, and the completion + region persistence writes. Pure over an in-memory
 * preferences store, so it runs on the desktop JVM.
 */
class OnboardingViewModelTest {
    private fun vm(prefs: InMemoryPreferencesStore = InMemoryPreferencesStore()) = OnboardingViewModel(prefs)

    @Test
    fun startsAtWelcomeRoot() {
        val model = vm()
        assertEquals(emptyList(), model.path.value)
        assertNull(model.currentStep)
    }

    @Test
    fun linearFlowVisitsEveryStepThenCompletes() = runTest {
        val model = vm()
        model.start()
        assertEquals(OnboardingStep.PERMISSIONS, model.currentStep)
        model.advance()
        assertEquals(OnboardingStep.PAIR, model.currentStep)
        model.advance()
        assertEquals(OnboardingStep.REGION, model.currentStep)
        model.advance()
        assertEquals(OnboardingStep.PRESET, model.currentStep)
        assertFalse(model.completed.value)
        model.advance() // off the final step completes
        assertTrue(model.completed.value)
    }

    @Test
    fun resumeThatSkipsRegionGoesPairToPreset() = runTest {
        val model = vm()
        model.seedResume(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.PRESET))
        assertTrue(model.regionSkipped)
        // Walking back to Pair and advancing must skip Region because a region is already set.
        model.back() // -> PAIR
        assertEquals(OnboardingStep.PAIR, model.currentStep)
        model.advance()
        assertEquals(OnboardingStep.PRESET, model.currentStep)
    }

    @Test
    fun resumeWithRegionStepDoesNotSkip() {
        val model = vm()
        model.seedResume(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.REGION))
        assertFalse(model.regionSkipped)
    }

    @Test
    fun backAtRootIsNoOp() {
        val model = vm()
        assertFalse(model.back())
    }

    @Test
    fun steppingBackAtTheWelcomeRootDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val model = vm(prefs)
        assertEquals(emptyList(), model.path.value)
        assertNull(model.currentStep)
        assertFalse(model.completed.value)
        assertNull(model.regionLabel.value)
        assertTrue(prefs.snapshot().isEmpty())

        assertFalse(model.back())

        assertEquals(emptyList(), model.path.value)
        assertNull(model.currentStep)
        assertFalse(model.completed.value)
        assertNull(model.regionLabel.value)
        assertTrue(prefs.snapshot().isEmpty())
    }

    @Test
    fun steppingBackInOnboardingDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val model = vm(prefs)
        model.seedResume(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR))
        assertEquals(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR), model.path.value)
        assertEquals(OnboardingStep.PAIR, model.currentStep)
        assertFalse(model.completed.value)
        assertTrue(prefs.snapshot().isEmpty())

        assertTrue(model.back())

        assertEquals(listOf(OnboardingStep.PERMISSIONS), model.path.value)
        assertEquals(OnboardingStep.PERMISSIONS, model.currentStep)
        assertFalse(model.completed.value)
        assertNull(model.regionLabel.value)
        assertTrue(prefs.snapshot().isEmpty())
    }

    @Test
    fun leavingTheOnboardingWelcomeStepDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val model = vm(prefs)
        assertEquals(emptyList(), model.path.value)
        assertNull(model.currentStep)
        assertFalse(model.completed.value)
        assertTrue(prefs.snapshot().isEmpty())

        model.start()

        assertEquals(listOf(OnboardingStep.PERMISSIONS), model.path.value)
        assertEquals(OnboardingStep.PERMISSIONS, model.currentStep)
        assertFalse(model.completed.value)
        assertNull(model.regionLabel.value)
        assertTrue(prefs.snapshot().isEmpty())
    }

    @Test
    fun seedingAnOnboardingResumePathDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val model = vm(prefs)
        assertEquals(emptyList(), model.path.value)
        assertNull(model.currentStep)
        assertFalse(model.completed.value)
        assertTrue(prefs.snapshot().isEmpty())

        model.seedResume(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.PRESET))

        assertEquals(
            listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.PRESET),
            model.path.value,
        )
        assertEquals(OnboardingStep.PRESET, model.currentStep)
        assertTrue(model.regionSkipped)
        assertFalse(model.completed.value)
        assertNull(model.regionLabel.value)
        assertTrue(prefs.snapshot().isEmpty())
    }

    @Test
    fun advancingFromOnboardingPermissionsDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val model = vm(prefs)
        model.seedResume(listOf(OnboardingStep.PERMISSIONS))
        assertEquals(listOf(OnboardingStep.PERMISSIONS), model.path.value)
        assertEquals(OnboardingStep.PERMISSIONS, model.currentStep)
        assertFalse(model.regionSkipped)
        assertFalse(model.completed.value)
        assertNull(model.regionLabel.value)
        assertTrue(prefs.snapshot().isEmpty())

        model.advance()

        assertEquals(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR), model.path.value)
        assertEquals(OnboardingStep.PAIR, model.currentStep)
        assertFalse(model.regionSkipped)
        assertFalse(model.completed.value)
        assertNull(model.regionLabel.value)
        assertTrue(prefs.snapshot().isEmpty())
    }

    @Test
    fun completePersistsTheOnboardingFlag() = runTest {
        val prefs = InMemoryPreferencesStore()
        val model = vm(prefs)
        model.complete()
        assertEquals(true, prefs.getBoolean(PersistenceKeys.HAS_COMPLETED_ONBOARDING))
        assertTrue(model.completed.value)
    }

    @Test
    fun commitRegionPersistsSelectionAndAdvancesToPreset() = runTest {
        val prefs = InMemoryPreferencesStore()
        val model = vm(prefs)
        model.seedResume(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.REGION))
        model.commitRegion(RegionSelection(countryCode = "US", source = RegionSource.MANUAL))
        assertEquals(OnboardingStep.PRESET, model.currentStep)
        val stored = prefs.getString(PersistenceKeys.REGION_SELECTION)
        assertTrue(stored != null && stored.contains("US"))
        assertEquals("US", model.regionLabel.value)
    }

    @Test
    fun loadRegionLabelFromPrefsReadsCommittedSelection() = runTest {
        val prefs = InMemoryPreferencesStore()
        val writer = vm(prefs)
        writer.seedResume(listOf(OnboardingStep.REGION))
        writer.commitRegion(
            RegionSelection(
                countryCode = "DE",
                administrativeAreaCode = "BY",
                source = RegionSource.MANUAL,
            ),
        )
        val reader = vm(prefs)
        reader.loadRegionLabelFromPrefs()
        assertEquals("DE, BY", reader.regionLabel.value)
    }

    @Test
    fun loadingAMissingOnboardingRegionLabelDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val model = vm(prefs)
        assertNull(model.regionLabel.value)
        assertNull(model.currentStep)
        assertFalse(model.completed.value)

        model.loadRegionLabelFromPrefs()

        assertNull(model.regionLabel.value)
        assertNull(model.currentStep)
        assertFalse(model.completed.value)
        assertFalse(model.regionSkipped)
        assertEquals(emptyMap(), prefs.snapshot())
    }

    @Test
    fun aPersistFaultOnCompleteStillEntersTheShell() = runTest {
        val model = OnboardingViewModel(ThrowingPreferencesStore())
        model.complete()
        assertTrue(model.completed.value)
    }

    @Test
    fun aPersistFaultOnCommitRegionStillAdvancesToPreset() = runTest {
        val model = OnboardingViewModel(ThrowingPreferencesStore())
        model.seedResume(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.REGION))
        model.commitRegion(RegionSelection(countryCode = "US", source = RegionSource.MANUAL))
        assertEquals(OnboardingStep.PRESET, model.currentStep)
        assertEquals("US", model.regionLabel.value)
    }

    @Test
    fun aCancelledCompleteLeavesOnboardingIncomplete() = runTest {
        val cancelled = CancellationException("superseded")
        val model = OnboardingViewModel(ThrowingPreferencesStore { throw cancelled })

        val thrown = assertFailsWith<CancellationException> { model.complete() }
        assertSame(cancelled, thrown)
        assertFalse(model.completed.value)
    }

    @Test
    fun aCompleteErrorLeavesOnboardingIncomplete() = runTest {
        val model = OnboardingViewModel(ThrowingPreferencesStore { throw OnboardingPrefsProbeError() })

        assertFailsWith<OnboardingPrefsProbeError> { model.complete() }
        assertFalse(model.completed.value)
    }

    @Test
    fun aCancelledCommitRegionStaysOnRegion() = runTest {
        val cancelled = CancellationException("superseded")
        val model = OnboardingViewModel(ThrowingPreferencesStore { throw cancelled })
        model.seedResume(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.REGION))

        val thrown = assertFailsWith<CancellationException> {
            model.commitRegion(RegionSelection(countryCode = "US", source = RegionSource.MANUAL))
        }
        assertSame(cancelled, thrown)
        assertEquals(OnboardingStep.REGION, model.currentStep)
        assertNull(model.regionLabel.value)
    }

    @Test
    fun aCommitRegionErrorStaysOnRegion() = runTest {
        val model = OnboardingViewModel(ThrowingPreferencesStore { throw OnboardingPrefsProbeError() })
        model.seedResume(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.REGION))

        assertFailsWith<OnboardingPrefsProbeError> {
            model.commitRegion(RegionSelection(countryCode = "US", source = RegionSource.MANUAL))
        }
        assertEquals(OnboardingStep.REGION, model.currentStep)
        assertNull(model.regionLabel.value)
    }
}

private class OnboardingPrefsProbeError : Error("probe")

private class ThrowingPreferencesStore(private val failure: () -> Nothing = { error("disk full") }) :
    PreferencesStore {
    override suspend fun getString(key: String): String? = null
    override suspend fun getBoolean(key: String): Boolean? = null
    override suspend fun getInt(key: String): Int? = null
    override suspend fun getDouble(key: String): Double? = null
    override suspend fun putString(key: String, value: String) = failure()
    override suspend fun putBoolean(key: String, value: Boolean) = failure()
    override suspend fun putInt(key: String, value: Int) = failure()
    override suspend fun putDouble(key: String, value: Double) = failure()
    override suspend fun remove(key: String) = failure()
}
