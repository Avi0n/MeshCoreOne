package one.meshcore.shared.pathedit

/**
 * The outcome of a path-discovery request, ported from the iOS `PathDiscoveryResult`. A [Success] with
 * `hopCount == 0` renders as "Direct" because a discovery that returns a zero-length out-path is a
 * genuinely learned direct route — the one place a 0 hop count and the "Direct" out-path mode coincide.
 * The UI maps each case to its `PathDiscovery.*` string resource.
 */
sealed interface PathDiscoveryResult {
    data class Success(val hopCount: Int) : PathDiscoveryResult

    data object NoPathFound : PathDiscoveryResult

    data class Failed(val message: String) : PathDiscoveryResult
}
