package one.meshcore.shared.onboarding

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mirrors the iOS `OnboardingStateTests.SuggestedStartingPathTests`, including the no-registry
 * resume case where `lastConnectedDeviceID` is the only "has ever connected" signal, plus
 * the persisted-step-name pinning.
 */
class OnboardingResumeTest {
    // A neutral baseline; each test overrides only the signals it exercises via copy().
    private val base = OnboardingResumeInputs(
        hasCompletedOnboarding = false,
        pairedAccessoriesCount = 0,
        lastConnectedDeviceId = null,
        locationPermissionDetermined = false,
        notificationPermissionDetermined = false,
        regionAlreadySet = false,
    )

    @Test
    fun returnsEmptyWhenOnboardingIsAlreadyComplete() {
        val inputs = base.copy(hasCompletedOnboarding = true, lastConnectedDeviceId = "device")
        assertEquals(emptyList(), suggestedStartingPath(inputs))
    }

    @Test
    fun returnsEmptyWhenNoPairedDevice() {
        val inputs = base.copy(pairedAccessoriesCount = 0, lastConnectedDeviceId = null)
        assertEquals(emptyList(), suggestedStartingPath(inputs))
    }

    @Test
    fun resumesWhenADeviceWasConnectedEvenWithNoSystemPairingRegistry() {
        // pairedAccessoriesCount == 0 (desktop / CDM missing) but a real connect happened,
        // so lastConnectedDeviceID alone must drive the resume. Halts at permissions (location undetermined).
        assertEquals(
            listOf(OnboardingStep.PERMISSIONS),
            suggestedStartingPath(base.copy(pairedAccessoriesCount = 0, lastConnectedDeviceId = "device")),
        )
    }

    @Test
    fun resumesWhenTheSystemPairingRegistryReportsADevice() {
        assertEquals(
            listOf(OnboardingStep.PERMISSIONS),
            suggestedStartingPath(base.copy(pairedAccessoriesCount = 1, lastConnectedDeviceId = null)),
        )
    }

    @Test
    fun resumesPastPermissionsToRegionWhenPermissionsHandledAndNoRegion() {
        val inputs = base.copy(
            lastConnectedDeviceId = "device",
            locationPermissionDetermined = true,
            notificationPermissionDetermined = true,
            regionAlreadySet = false,
        )
        assertEquals(
            listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.REGION),
            suggestedStartingPath(inputs),
        )
    }

    @Test
    fun skipsRegionWhenItIsAlreadySet() {
        val inputs = base.copy(
            lastConnectedDeviceId = "device",
            locationPermissionDetermined = true,
            notificationPermissionDetermined = true,
            regionAlreadySet = true,
        )
        assertEquals(
            listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.PRESET),
            suggestedStartingPath(inputs),
        )
    }

    @Test
    fun haltsAtPermissionsWhenOnlyOnePermissionIsHandled() {
        val inputs = base.copy(
            lastConnectedDeviceId = "device",
            locationPermissionDetermined = true,
            notificationPermissionDetermined = false,
        )
        assertEquals(listOf(OnboardingStep.PERMISSIONS), suggestedStartingPath(inputs))
    }

    @Test
    fun stepNamesArePinnedToTheSwiftRawValues() {
        assertEquals("\"welcome\"", Json.encodeToString(OnboardingStep.serializer(), OnboardingStep.WELCOME))
        assertEquals("\"permissions\"", Json.encodeToString(OnboardingStep.serializer(), OnboardingStep.PERMISSIONS))
        assertEquals("\"pair\"", Json.encodeToString(OnboardingStep.serializer(), OnboardingStep.PAIR))
        assertEquals("\"region\"", Json.encodeToString(OnboardingStep.serializer(), OnboardingStep.REGION))
        assertEquals("\"preset\"", Json.encodeToString(OnboardingStep.serializer(), OnboardingStep.PRESET))
    }
}
