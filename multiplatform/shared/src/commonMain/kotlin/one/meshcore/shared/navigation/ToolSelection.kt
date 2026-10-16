package one.meshcore.shared.navigation

/**
 * The diagnostic tools selectable in the Tools section, ported from the iOS `ToolSelection`. Titles and
 * icons live in the UI layer; the two flags below are the pure facts the shell and the per-radio
 * teardown logic need.
 */
enum class ToolSelection {
    TRACE_PATH,
    LINE_OF_SIGHT,
    RX_LOG,
    NOISE_FLOOR,
    NODE_DISCOVERY,
    CLI,
    ;

    /**
     * Line of Sight runs its analysis offline. Every other tool needs a connected radio, so last-radio
     * forget and disconnect pop Trace Path and RX Log instead of leaving leftover hops or packets.
     */
    val requiresRadio: Boolean
        get() = this != LINE_OF_SIGHT

    /**
     * Tools that collapse the section's sidebar when open to reclaim its width: Line of Sight swaps the
     * content column for its analysis panel, and Trace Path gives its own list/map view the freed width.
     * Other tools keep the sidebar's normal width-driven behavior.
     */
    val prefersCollapsedSidebar: Boolean
        get() = this == LINE_OF_SIGHT || this == TRACE_PATH
}
