package one.meshcore.android

import one.meshcore.shared.ui.OnboardingPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refused-versus-never-asked tri-state the onboarding cards and the resume path read. iOS gets it
 * from `CLAuthorizationStatus` / `UNAuthorizationStatus`; Android has to assemble it from the live grant
 * read, the persisted record of what was prompted for, and the system rationale flag.
 */
class PermissionDeterminationTest {
    private class FakeAskedStore(initial: Set<OnboardingPermission> = emptySet()) : AskedPermissionsStore {
        private val recorded = initial.toMutableSet()

        override fun asked(): Set<OnboardingPermission> = recorded.toSet()

        override fun markAsked(permission: OnboardingPermission) {
            recorded += permission
        }
    }

    private fun reader(
        store: FakeAskedStore = FakeAskedStore(),
        granted: Set<OnboardingPermission> = emptySet(),
        rationale: Set<OnboardingPermission> = emptySet(),
    ) = PermissionStateReader(
        store = store,
        isGranted = { it in granted },
        needsRationale = { it in rationale },
    )

    @Test
    fun aPermissionNobodyHasAskedAboutIsNotDetermined() {
        val state = reader().read()

        assertFalse(OnboardingPermission.LOCATION in state.determined)
        assertFalse(state.isGranted(OnboardingPermission.LOCATION))
    }

    @Test
    fun aRefusedPermissionIsDeterminedButNotGranted() {
        val store = FakeAskedStore()
        val reader = reader(store)

        reader.markAsked(OnboardingPermission.LOCATION)

        val state = reader.read()
        assertTrue("a refusal must count as answered", OnboardingPermission.LOCATION in state.determined)
        assertFalse(state.isGranted(OnboardingPermission.LOCATION))
    }

    @Test
    fun aRefusalSurvivesIntoTheNextLaunch() {
        // A fresh reader over the same persisted record: the process died, the in-memory set is gone, and
        // the refusal still has to read as answered or the resume path drops back to the Permissions step.
        val store = FakeAskedStore(setOf(OnboardingPermission.LOCATION, OnboardingPermission.NOTIFICATIONS))

        val state = reader(store).read()

        assertTrue(OnboardingPermission.LOCATION in state.determined)
        assertTrue(OnboardingPermission.NOTIFICATIONS in state.determined)
    }

    @Test
    fun theSystemRationaleFlagAloneDeterminesAPermission() {
        // A permission refused once through some other surface: the app has no record of prompting, but
        // the system reports the user has already said no.
        val state = reader(rationale = setOf(OnboardingPermission.BLUETOOTH)).read()

        assertTrue(OnboardingPermission.BLUETOOTH in state.determined)
        assertFalse(state.isGranted(OnboardingPermission.BLUETOOTH))
    }

    @Test
    fun aGrantMadeInSystemSettingsLandsOnTheNextRead() {
        // The denied card routes to system Settings, so the grant happens outside the app and arrives only
        // as a changed live read; nothing may be carried over from the state read before it.
        val store = FakeAskedStore(setOf(OnboardingPermission.LOCATION))
        val refused = reader(store).read()
        assertFalse(refused.isGranted(OnboardingPermission.LOCATION))

        val afterSettings = reader(store, granted = setOf(OnboardingPermission.LOCATION)).read()

        assertTrue(afterSettings.isGranted(OnboardingPermission.LOCATION))
        assertTrue(OnboardingPermission.LOCATION in afterSettings.determined)
    }

    @Test
    fun aRevokeMadeInSystemSettingsDropsTheGrant() {
        val store = FakeAskedStore(setOf(OnboardingPermission.LOCATION))
        val store2 = FakeAskedStore(setOf(OnboardingPermission.LOCATION))
        assertTrue(reader(store, granted = setOf(OnboardingPermission.LOCATION)).read().granted.isNotEmpty())

        val afterRevoke = reader(store2).read()

        assertEquals(emptySet<OnboardingPermission>(), afterRevoke.granted)
        assertTrue("a revoked permission stays answered", OnboardingPermission.LOCATION in afterRevoke.determined)
    }
}
