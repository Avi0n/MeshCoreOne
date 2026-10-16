package one.meshcore.shared.settings

/**
 * Selects which node-config sections to include in an export or import, mirroring the Swift `ConfigSections`.
 * All flags default false; [selectAll]/[deselectAll] return a fully-set/-cleared copy (the Swift mutating
 * methods are `copy`-based here).
 */
data class ConfigSections(
    val nodeIdentity: Boolean = false,
    val radioSettings: Boolean = false,
    val positionSettings: Boolean = false,
    val otherSettings: Boolean = false,
    val channels: Boolean = false,
    val contacts: Boolean = false,
) {
    /** Every section is selected. */
    val allSelected: Boolean
        get() = nodeIdentity && radioSettings && positionSettings && otherSettings && channels && contacts

    /** At least one section is selected. */
    val anySectionSelected: Boolean
        get() = nodeIdentity || radioSettings || positionSettings || otherSettings || channels || contacts

    fun selectAll(): ConfigSections = ConfigSections(true, true, true, true, true, true)

    fun deselectAll(): ConfigSections = ConfigSections()
}
