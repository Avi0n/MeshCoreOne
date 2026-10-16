package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

class MergedLocalIdMappingTest {
    @Test
    fun anIdentityMappingIsOmitted() {
        val id = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val mapped = mergedLocalIdMapping(
            rows = listOf(id),
            key = { "same" },
            foreignId = { it },
            localIdsByKey = mapOf("same" to id),
        )
        assertEquals(emptyMap(), mapped)
    }

    @Test
    fun aMergedRowPointsAtTheLocalId() {
        val foreign = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val local = Uuid.normalized("B0000000-0000-0000-0000-000000000002")
        val mapped = mergedLocalIdMapping(
            rows = listOf(foreign),
            key = { "merged" },
            foreignId = { it },
            localIdsByKey = mapOf("merged" to local),
        )
        assertEquals(mapOf(foreign to local), mapped)
    }

    @Test
    fun aRowWithNoLocalMatchIsOmitted() {
        val foreign = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val mapped = mergedLocalIdMapping(
            rows = listOf(foreign),
            key = { "missing" },
            foreignId = { it },
            localIdsByKey = emptyMap(),
        )
        assertEquals(emptyMap(), mapped)
    }
}
