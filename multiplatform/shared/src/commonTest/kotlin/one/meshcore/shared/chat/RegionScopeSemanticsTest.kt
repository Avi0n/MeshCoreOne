package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RegionScopeSemanticsTest {
    @Test
    fun storageFieldsMapsNoneUniqueAndAmbiguous() {
        assertEquals(null, RegionScopeSemantics.storageFields(RegionMatchResult.None).regionScope)
        assertEquals(emptyList(), RegionScopeSemantics.storageFields(RegionMatchResult.None).regionScopeMatches)

        val unique = RegionScopeSemantics.storageFields(RegionMatchResult.Unique("Germany"))
        assertEquals("Germany", unique.regionScope)
        assertEquals(listOf("Germany"), unique.regionScopeMatches)

        val ambiguous = RegionScopeSemantics.storageFields(RegionMatchResult.Ambiguous(listOf("de-hh", "de-by")))
        assertNull(ambiguous.regionScope)
        assertEquals(listOf("de-by", "de-hh"), ambiguous.regionScopeMatches)
    }

    @Test
    fun coalescePrefersMultiMatchOverStickyScope() {
        val result = RegionScopeSemantics.coalesce("Germany", listOf("de-hh", "de-by"))
        val ambiguous = assertIs<RegionMatchResult.Ambiguous>(result)
        assertEquals(listOf("de-by", "de-hh"), ambiguous.names)
    }

    @Test
    fun coalesceUniqueFromSingleMatch() {
        assertEquals(RegionMatchResult.Unique("USA"), RegionScopeSemantics.coalesce(null, listOf("USA")))
    }

    @Test
    fun coalesceLegacyUniqueFromScopeOnly() {
        assertEquals(RegionMatchResult.Unique("Bavaria"), RegionScopeSemantics.coalesce("Bavaria", emptyList()))
    }

    @Test
    fun coalesceNoneWhenBothEmpty() {
        assertEquals(RegionMatchResult.None, RegionScopeSemantics.coalesce(null, emptyList()))
        assertEquals(RegionMatchResult.None, RegionScopeSemantics.coalesce("  ", listOf("", " ")))
    }

    @Test
    fun chipLabelJoinsAmbiguousWithSlashSeparator() {
        assertNull(RegionScopeSemantics.chipLabel(RegionMatchResult.None))
        assertEquals("Germany", RegionScopeSemantics.chipLabel(RegionMatchResult.Unique("Germany")))
        assertEquals(
            "de-by / de-hh",
            RegionScopeSemantics.chipLabel(RegionMatchResult.Ambiguous(listOf("de-by", "de-hh"))),
        )
    }
}
