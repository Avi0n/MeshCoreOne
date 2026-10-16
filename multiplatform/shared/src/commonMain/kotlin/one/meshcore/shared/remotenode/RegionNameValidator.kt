package one.meshcore.shared.remotenode

/** Unscoped root name. Children of Unscoped store this as [RepeaterRegionEntry.parentName], not null. */
const val WILDCARD_REGION_NAME: String = "*"

/** Longest region name the firmware stores, in UTF-8 bytes (also the default-flood-scope name cap). */
const val MAX_REGION_NAME_BYTES: Int = 30

/**
 * `RegionMap.exportTo(reply, 160)` yields at most 159 UTF-8 bytes plus a trailing NUL. A dump at this
 * size is treated as truncated.
 */
const val FIRMWARE_REGION_DUMP_MAX_PAYLOAD_BYTES: Int = 159

/** CLI argument when default scope is unset (`region default <null>`). */
const val FIRMWARE_NULL_TOKEN: String = "<null>"

/**
 * One region row parsed from the repeater's `region` tree, mirroring the Swift `RepeaterRegionEntry`.
 * [floodAllowed] comes from a trailing `" F"`, [isHome] from a trailing `"^"`.
 * Home is dump metadata only; origin/dev has no home picker after nested regions.
 */
data class RepeaterRegionEntry(
    val name: String,
    val parentName: String?,
    val depth: Int,
    val floodAllowed: Boolean,
    val isHome: Boolean,
) {
    val isWildcard: Boolean get() = name == WILDCARD_REGION_NAME
    val isUnscoped: Boolean get() = name == WILDCARD_REGION_NAME

    /** Nil for Unscoped and for children of Unscoped. */
    val namedParent: String?
        get() = parentName?.takeUnless { it == WILDCARD_REGION_NAME }

    sealed interface Parent {
        data object Unscoped : Parent
        data class Named(val name: String) : Parent
    }
}

/**
 * Spoken flood-row label, mirroring iOS `RegionFloodToggleRow.accessibilityLabelText`.
 * Unscoped speaks the All Traffic name, not the asterisk display. Children of Unscoped
 * still use the child-of template with that same All Traffic parent.
 */
sealed interface RegionFloodSpokenLabel {
    data object Unscoped : RegionFloodSpokenLabel
    data class NameOnly(val name: String) : RegionFloodSpokenLabel
    data class ChildOfNamed(val name: String, val parent: String) : RegionFloodSpokenLabel
    data class ChildOfUnscoped(val name: String) : RegionFloodSpokenLabel
}

fun regionFloodSpokenLabel(region: RepeaterRegionEntry): RegionFloodSpokenLabel {
    if (region.isUnscoped) return RegionFloodSpokenLabel.Unscoped
    val named = region.namedParent
    return when {
        region.parentName == null -> RegionFloodSpokenLabel.NameOnly(region.name)
        named == null -> RegionFloodSpokenLabel.ChildOfUnscoped(region.name)
        else -> RegionFloodSpokenLabel.ChildOfNamed(region.name, named)
    }
}

/** iOS `hasChildRegions`: a named region still has members in the live tree. */
fun regionDeleteBlockedByChildren(name: String, regions: List<RepeaterRegionEntry>): Boolean =
    regions.any { it.parentName == name }

/** iOS Add Child: unscoped `*` is `.unscoped` (`region put name`), not `.named("*")`. */
fun addChildParentFor(region: RepeaterRegionEntry): RepeaterRegionEntry.Parent =
    if (region.isUnscoped) RepeaterRegionEntry.Parent.Unscoped else RepeaterRegionEntry.Parent.Named(region.name)

/**
 * iOS `RepeaterAddRegionSheet` parent picker: Unscoped shows `allTrafficWildcard` and
 * speaks `allTraffic`. Named parents show and speak the region name.
 */
fun parentPickerShowsWildcardDisplay(parent: RepeaterRegionEntry.Parent): Boolean = when (parent) {
    RepeaterRegionEntry.Parent.Unscoped -> true
    is RepeaterRegionEntry.Parent.Named -> parent.name == WILDCARD_REGION_NAME
}

/** Null when the picker should use the Unscoped strings instead of a region name. */
fun parentPickerNamedLabel(parent: RepeaterRegionEntry.Parent): String? =
    (parent as? RepeaterRegionEntry.Parent.Named)?.name?.takeUnless { parentPickerShowsWildcardDisplay(parent) }

/**
 * iOS `regionMutationsDisabled` inverted. Flood, add, delete, and default-scope
 * stay inert while the tree is unloaded, fetching, or saving.
 */
fun regionMutationsAllowed(regionsLoaded: Boolean, isLoadingRegions: Boolean, isSavingRegions: Boolean): Boolean =
    regionsLoaded && !isLoadingRegions && !isSavingRegions

/** `Named("*")` is the same parent as Unscoped; firmware `region put name *` is not a child-of-root put. */
fun resolvedAddParent(parent: RepeaterRegionEntry.Parent): RepeaterRegionEntry.Parent = when (parent) {
    RepeaterRegionEntry.Parent.Unscoped -> parent
    is RepeaterRegionEntry.Parent.Named ->
        if (parent.name == WILDCARD_REGION_NAME) RepeaterRegionEntry.Parent.Unscoped else parent
}

/** Whether a region name denotes a private region (prefixed with "$"). Mirrors `String.isPrivateRegion`. */
fun String.isPrivateRegion(): Boolean = startsWith("$")

/**
 * Validates region names before adding them, mirroring the Swift `RegionNameValidator`: non-empty, ASCII
 * letters/digits/hyphen only, within the firmware byte cap, and not already present.
 */
object RegionNameValidator {
    sealed interface ValidationError {
        data object Empty : ValidationError
        data object InvalidCharacters : ValidationError
        data class TooLong(val maxBytes: Int) : ValidationError
        data object Duplicate : ValidationError
    }

    fun validate(name: String, existingRegions: List<String>): ValidationError? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> ValidationError.Empty
            !trimmed.all(::isRegionNameChar) -> ValidationError.InvalidCharacters
            trimmed.encodeToByteArray().size > MAX_REGION_NAME_BYTES -> ValidationError.TooLong(MAX_REGION_NAME_BYTES)
            trimmed in existingRegions -> ValidationError.Duplicate
            else -> null
        }
    }

    fun isValid(name: String, existingRegions: List<String>): Boolean = validate(name, existingRegions) == null

    /**
     * The error shown under the add-region name field. Empty is not an inline error: the Add control
     * stays disabled until there is a name, matching iOS `RepeaterAddRegionSheet.validationErrorText`.
     */
    fun inlineError(name: String, existingRegions: List<String>): ValidationError? =
        validate(name, existingRegions)?.takeUnless { it is ValidationError.Empty }

    /**
     * Parses a repeater `region` tree. Indent is parent depth; a truncated dump (at the firmware byte cap,
     * or not LF-terminated) is rejected as empty. Mirrors `RepeaterSettingsViewModel.parseRegionTree`.
     */
    fun parseRegionTree(response: String): List<RepeaterRegionEntry> {
        if (!isCompleteRegionDump(response)) return emptyList()
        val lines = response.replace("\r\n", "\n").split('\n').filter { it.isNotEmpty() }
        val entries = ArrayList<RepeaterRegionEntry>(lines.size)
        val stack = ArrayList<String>()
        for (line in lines) {
            val parsed = parseRegionLine(line)
            if (parsed == null || parsed.depth > stack.size) return emptyList()
            while (stack.size > parsed.depth) {
                stack.removeAt(stack.lastIndex)
            }
            stack.add(parsed.name)
            val parentName = if (parsed.depth == 0) null else stack[parsed.depth - 1]
            entries.add(
                RepeaterRegionEntry(
                    name = parsed.name,
                    parentName = parentName,
                    depth = parsed.depth,
                    floodAllowed = parsed.floodAllowed,
                    isHome = parsed.isHome,
                ),
            )
        }
        return entries
    }

    private fun isRegionNameChar(char: Char): Boolean {
        if (char.code >= ASCII_LIMIT) return false
        return char.isLetter() || char.isDigit() || char == '-'
    }

    private fun isCompleteRegionDump(response: String): Boolean =
        response.encodeToByteArray().size < FIRMWARE_REGION_DUMP_MAX_PAYLOAD_BYTES &&
            response.lastOrNull() == '\n'

    private fun parseRegionLine(line: String): ParsedRegionLine? {
        val depth = line.takeWhile { it == ' ' }.length
        return strippedRegionLine(line.drop(depth), depth)
    }

    private fun strippedRegionLine(raw: String, depth: Int): ParsedRegionLine? {
        if (raw.isEmpty()) return null
        val floodAllowed = raw.endsWith(" F")
        val afterFlood = if (floodAllowed) raw.dropLast(FLOOD_MARKER_LENGTH) else raw
        val isHome = afterFlood.endsWith(HOME_MARKER)
        val name = if (isHome) afterFlood.dropLast(HOME_MARKER.length) else afterFlood
        if (name.isEmpty() || ' ' in name) return null
        return ParsedRegionLine(
            name = name,
            depth = depth,
            floodAllowed = floodAllowed,
            isHome = isHome,
        )
    }

    private class ParsedRegionLine(val name: String, val depth: Int, val floodAllowed: Boolean, val isHome: Boolean)

    private const val ASCII_LIMIT = 128
    private const val FLOOD_MARKER_LENGTH = 2
    private const val HOME_MARKER = "^"
}
