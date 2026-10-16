package one.meshcore.shared.pathedit

/**
 * A surfaced error from a path-editing action, held in [PathEditState] and rendered by the sheet. Typed
 * rather than a raw string because each case resolves to a `PathManagement.Error.*` Compose string
 * resource at render time. [SaveFailed]/[ResetFailed]/[SetFailed] carry mapped user-facing copy (never
 * the exception text), mirroring the iOS `PathManagement.Error.saveFailed(_:)` family.
 */
sealed interface PathEditError {
    /** A hop stored at a narrower hash size can't widen to the device's current size. */
    data object HopResizeRequired : PathEditError

    /** More hops than the current hash size's budget allows. */
    data class TooManyHops(val maxHopCount: Int) : PathEditError

    data class SaveFailed(val message: String) : PathEditError

    data class ResetFailed(val message: String) : PathEditError

    data class SetFailed(val message: String) : PathEditError

    companion object {
        /** Maps a save-guard [PathSaveRejection] to its surfaced error. */
        fun from(rejection: PathSaveRejection): PathEditError = when (rejection) {
            PathSaveRejection.HopResizeRequired -> HopResizeRequired
            is PathSaveRejection.TooManyHops -> TooManyHops(rejection.maxHopCount)
        }
    }
}
