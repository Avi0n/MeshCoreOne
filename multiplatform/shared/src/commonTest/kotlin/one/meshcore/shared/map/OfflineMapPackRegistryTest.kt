package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineMapPackRegistryTest {
    @Test
    fun upsertListAndRemove() {
        val registry = OfflineMapPackRegistry()
        val pack = OfflineMapPack(
            id = "a",
            name = "Bay Area",
            layer = OfflineMapLayer.BASE,
            createdAtEpochSeconds = 100,
            completed = true,
        )
        registry.upsert(pack)
        assertEquals(listOf(pack), registry.list())
        assertTrue(registry.hasCompletedPack(OfflineMapLayer.BASE))
        assertFalse(registry.hasCompletedPack(OfflineMapLayer.TOPO))
        registry.remove("a")
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun listSortsNewestFirst() {
        val registry = OfflineMapPackRegistry()
        registry.upsert(
            OfflineMapPack("old", "Old", OfflineMapLayer.BASE, createdAtEpochSeconds = 1),
        )
        registry.upsert(
            OfflineMapPack("new", "New", OfflineMapLayer.TOPO, createdAtEpochSeconds = 99),
        )
        assertEquals(listOf("new", "old"), registry.list().map { it.id })
    }

    @Test
    fun newPackIdIsStable() {
        assertEquals("Home_42", OfflineMapPackRegistry.newPackId("Home", 42))
    }

    @Test
    fun displayNameFallsBackToUnknownRegionWhenBlank() {
        val named = OfflineMapPack("a", "Bay Area", OfflineMapLayer.BASE, createdAtEpochSeconds = 1)
        val blank = OfflineMapPack("b", "  ", OfflineMapLayer.TOPO, createdAtEpochSeconds = 2)
        assertEquals("Bay Area", named.displayNameOrUnknown("Unknown Region"))
        assertEquals("Unknown Region", blank.displayNameOrUnknown("Unknown Region"))
    }

    @Test
    fun revisionBumpsOnMutation() {
        val registry = OfflineMapPackRegistry()
        assertEquals(0, registry.revision.value)
        registry.upsert(
            OfflineMapPack("a", "A", OfflineMapLayer.BASE, createdAtEpochSeconds = 1),
        )
        assertEquals(1, registry.revision.value)
        registry.remove("a")
        assertEquals(2, registry.revision.value)
        registry.clear()
        assertEquals(3, registry.revision.value)
    }
}
