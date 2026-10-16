package one.meshcore.shared.settings

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppliedRadioPresetStoreTest {
    @Test
    fun scopesEachStampToItsOwnRadio() = runTest {
        val prefs = AppliedRadioPresetStore(InMemoryPreferencesStore())
        prefs.set(RADIO_A, "br")

        assertEquals("br", prefs.get(RADIO_A))
        assertNull(prefs.get(RADIO_B))
    }

    @Test
    fun clearDropsOnlyThatRadio() = runTest {
        val prefs = AppliedRadioPresetStore(InMemoryPreferencesStore())
        prefs.set(RADIO_A, "br")
        prefs.set(RADIO_B, "us-ca")
        prefs.clear(RADIO_A)

        assertNull(prefs.get(RADIO_A))
        assertEquals("us-ca", prefs.get(RADIO_B))
    }

    @Test
    fun usesAPerRadioKey() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = AppliedRadioPresetStore(store)
        prefs.set(RADIO_A, "br")

        assertEquals("br", store.snapshot()["device.${RADIO_A.value}.appliedRadioPresetID"])
    }

    @Test
    fun savingAnAppliedRadioPresetDoesNotRadio() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = AppliedRadioPresetStore(store)

        prefs.set(RADIO_A, "br")

        assertEquals("br", prefs.get(RADIO_A))
        assertNull(prefs.get(RADIO_B))
        assertEquals(
            mapOf("device.${RADIO_A.value}.appliedRadioPresetID" to "br"),
            store.snapshot(),
        )
    }

    private companion object {
        val RADIO_A = Uuid.normalized("A0000000-0000-4000-8000-000000000001")
        val RADIO_B = Uuid.normalized("A0000000-0000-4000-8000-000000000002")
    }
}
