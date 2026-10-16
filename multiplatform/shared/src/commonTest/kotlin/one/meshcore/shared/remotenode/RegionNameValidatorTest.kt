package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Region-name validation, indent-stack tree parse, and named-parent helpers. */
class RegionNameValidatorTest {
    @Test
    fun validateAcceptsAsciiAlphanumericHyphen() {
        assertNull(RegionNameValidator.validate("us-west-2", existingRegions = emptyList()))
        assertTrue(RegionNameValidator.isValid("Region1", existingRegions = emptyList()))
    }

    @Test
    fun validateRejectsEmptyInvalidLongDuplicate() {
        assertIs<RegionNameValidator.ValidationError.Empty>(RegionNameValidator.validate("  ", emptyList()))
        val invalid = RegionNameValidator.validate("bad name!", emptyList())
        assertIs<RegionNameValidator.ValidationError.InvalidCharacters>(invalid)
        val tooLong = RegionNameValidator.validate("a".repeat(31), emptyList())
        assertIs<RegionNameValidator.ValidationError.TooLong>(tooLong)
        assertEquals(30, tooLong.maxBytes)
        assertIs<RegionNameValidator.ValidationError.Duplicate>(RegionNameValidator.validate("us", listOf("us")))
        assertNull(RegionNameValidator.inlineError("  ", emptyList()))
        assertIs<RegionNameValidator.ValidationError.InvalidCharacters>(
            RegionNameValidator.inlineError("bad name!", emptyList()),
        )
        assertIs<RegionNameValidator.ValidationError.TooLong>(
            RegionNameValidator.inlineError("a".repeat(31), emptyList()),
        )
        assertIs<RegionNameValidator.ValidationError.Duplicate>(RegionNameValidator.inlineError("us", listOf("us")))
    }

    @Test
    fun privateRegionDetection() {
        assertTrue("\$secret".isPrivateRegion())
        assertFalse("public".isPrivateRegion())
    }

    @Test
    fun parseRegionTreeKeepsOntarioIndentAndParents() {
        val dump = """
            * F
             can F
              on F
               gta F
               ottawa F
               hamilton F
        """.trimIndent() + "\n"
        val parsed = RegionNameValidator.parseRegionTree(dump)
        assertEquals(listOf("*", "can", "on", "gta", "ottawa", "hamilton"), parsed.map { it.name })
        assertEquals(listOf(null, "*", "can", "on", "on", "on"), parsed.map { it.parentName })
        assertEquals(listOf(0, 1, 2, 3, 3, 3), parsed.map { it.depth })
        assertTrue(parsed.all { it.floodAllowed })
    }

    @Test
    fun parseRegionTreeStripsCaretAndReadsDenyFlood() {
        val parsed = RegionNameValidator.parseRegionTree("* F\n on^ F\n  gta\n")
        assertEquals(listOf("*", "on", "gta"), parsed.map { it.name })
        assertTrue(parsed[1].floodAllowed)
        assertTrue(parsed[1].isHome)
        assertFalse(parsed[0].isHome)
        assertFalse(parsed[2].floodAllowed)
        assertFalse(parsed[2].isHome)
        assertEquals("on", parsed[2].parentName)
    }

    @Test
    fun parseRegionTreeStripsFactoryUnscopedCaret() {
        val parsed = RegionNameValidator.parseRegionTree("*^ F\n")
        assertEquals(listOf("*"), parsed.map { it.name })
        assertTrue(parsed[0].floodAllowed)
        assertTrue(parsed[0].isHome)
        assertNull(parsed[0].parentName)
    }

    @Test
    fun parseRegionTreeRejectsSkippedIndent() {
        assertTrue(RegionNameValidator.parseRegionTree("* F\n   gta F\n").isEmpty())
    }

    @Test
    fun parseRegionTreeRejectsSpaceInName() {
        assertTrue(RegionNameValidator.parseRegionTree("* F\n foo bar F\n").isEmpty())
    }

    @Test
    fun parseRegionTreeCrlfMatchesLf() {
        assertEquals(
            RegionNameValidator.parseRegionTree("* F\n on F\n"),
            RegionNameValidator.parseRegionTree("* F\r\n on F\r\n"),
        )
        assertEquals(
            RegionNameValidator.parseRegionTree("* F\n"),
            RegionNameValidator.parseRegionTree("* F\r\n"),
        )
    }

    @Test
    fun parseRegionTreeRejectsDumpWithoutTrailingNewline() {
        assertTrue(RegionNameValidator.parseRegionTree("* F\n on F").isEmpty())
    }

    @Test
    fun parseRegionTreeRejectsSaturatedDump() {
        val dump = lfTerminatedFloodDump(FIRMWARE_REGION_DUMP_MAX_PAYLOAD_BYTES)
        assertTrue(RegionNameValidator.parseRegionTree(dump).isEmpty())
    }

    @Test
    fun parseRegionTreeAcceptsDumpOneByteUnderCap() {
        val parsed = RegionNameValidator.parseRegionTree(
            lfTerminatedFloodDump(FIRMWARE_REGION_DUMP_MAX_PAYLOAD_BYTES - 1),
        )
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].floodAllowed)
    }

    @Test
    fun namedParentIsNilForUnscopedAndItsChildren() {
        val unscoped = RepeaterRegionEntry(
            name = "*",
            parentName = null,
            depth = 0,
            floodAllowed = true,
            isHome = false,
        )
        val on = RepeaterRegionEntry(
            name = "on",
            parentName = "*",
            depth = 1,
            floodAllowed = true,
            isHome = false,
        )
        assertNull(unscoped.namedParent)
        assertNull(on.namedParent)
        assertTrue(unscoped.isUnscoped)
    }

    @Test
    fun floodSpokenLabelMatchesIosUnscopedAndChildParents() {
        val unscoped = RepeaterRegionEntry("*", null, 0, floodAllowed = true, isHome = false)
        val on = RepeaterRegionEntry("on", "*", 1, floodAllowed = true, isHome = false)
        val gta = RepeaterRegionEntry("gta", "on", 2, floodAllowed = true, isHome = false)
        val orphan = RepeaterRegionEntry("west", null, 0, floodAllowed = true, isHome = false)
        assertEquals(RegionFloodSpokenLabel.Unscoped, regionFloodSpokenLabel(unscoped))
        assertEquals(RegionFloodSpokenLabel.ChildOfUnscoped("on"), regionFloodSpokenLabel(on))
        assertEquals(RegionFloodSpokenLabel.ChildOfNamed("gta", "on"), regionFloodSpokenLabel(gta))
        assertEquals(RegionFloodSpokenLabel.NameOnly("west"), regionFloodSpokenLabel(orphan))
    }

    @Test
    fun namedParentIsTheImmediateNamedParent() {
        val gta = RepeaterRegionEntry(
            name = "gta",
            parentName = "on",
            depth = 2,
            floodAllowed = true,
            isHome = false,
        )
        val downtown = RepeaterRegionEntry(
            name = "downtown",
            parentName = "gta",
            depth = 3,
            floodAllowed = true,
            isHome = false,
        )
        assertEquals("on", gta.namedParent)
        assertEquals("gta", downtown.namedParent)
        assertFalse(
            RepeaterRegionEntry(
                name = "us",
                parentName = "*",
                depth = 1,
                floodAllowed = false,
                isHome = false,
            ).isWildcard,
        )
    }

    @Test
    fun aParentWithAChildCannotBeDeleted() {
        val regions = listOf(
            RepeaterRegionEntry("*", null, 0, floodAllowed = true, isHome = false),
            RepeaterRegionEntry("on", "*", 1, floodAllowed = true, isHome = false),
            RepeaterRegionEntry("gta", "on", 2, floodAllowed = true, isHome = false),
        )
        assertTrue(regionDeleteBlockedByChildren("on", regions))
        assertTrue(regionDeleteBlockedByChildren("*", regions))
        assertFalse(regionDeleteBlockedByChildren("gta", regions))
        assertFalse(regionDeleteBlockedByChildren("missing", regions))
    }

    @Test
    fun addChildOnUnscopedUsesUnscopedParent() {
        val star = RepeaterRegionEntry("*", null, 0, floodAllowed = true, isHome = false)
        val on = RepeaterRegionEntry("on", "*", 1, floodAllowed = true, isHome = false)
        assertEquals(RepeaterRegionEntry.Parent.Unscoped, addChildParentFor(star))
        assertEquals(RepeaterRegionEntry.Parent.Named("on"), addChildParentFor(on))
    }

    @Test
    fun parentPickerShowsWildcardDisplayForUnscoped() {
        assertTrue(parentPickerShowsWildcardDisplay(RepeaterRegionEntry.Parent.Unscoped))
        assertTrue(parentPickerShowsWildcardDisplay(RepeaterRegionEntry.Parent.Named(WILDCARD_REGION_NAME)))
        assertFalse(parentPickerShowsWildcardDisplay(RepeaterRegionEntry.Parent.Named("on")))
        assertNull(parentPickerNamedLabel(RepeaterRegionEntry.Parent.Unscoped))
        assertNull(parentPickerNamedLabel(RepeaterRegionEntry.Parent.Named(WILDCARD_REGION_NAME)))
        assertEquals("on", parentPickerNamedLabel(RepeaterRegionEntry.Parent.Named("on")))
    }

    @Test
    fun regionMutationsStayOffWhileUnloadedLoadingOrSaving() {
        assertTrue(regionMutationsAllowed(regionsLoaded = true, isLoadingRegions = false, isSavingRegions = false))
        assertFalse(regionMutationsAllowed(regionsLoaded = false, isLoadingRegions = false, isSavingRegions = false))
        assertFalse(regionMutationsAllowed(regionsLoaded = true, isLoadingRegions = true, isSavingRegions = false))
        assertFalse(regionMutationsAllowed(regionsLoaded = true, isLoadingRegions = false, isSavingRegions = true))
    }

    private fun lfTerminatedFloodDump(utf8Count: Int): String {
        val suffix = " F\n"
        val nameLen = utf8Count - suffix.encodeToByteArray().size
        val dump = "a".repeat(nameLen) + suffix
        check(dump.encodeToByteArray().size == utf8Count)
        check(dump.last() == '\n')
        return dump
    }
}
