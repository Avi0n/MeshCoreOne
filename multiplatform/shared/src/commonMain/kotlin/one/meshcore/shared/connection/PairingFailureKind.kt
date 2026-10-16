package one.meshcore.shared.connection

/**
 * Which recovery a pairing-failure alert offers, ported from the iOS `PairingFailureKind`. The
 * distinction is an explicit semantic discriminant, never inferred from whether a title happens to be
 * set: mis-routing here would offer a destructive "remove the bond" action for a merely transient
 * failure and destroy a working pairing.
 */
enum class PairingFailureKind {
    /** Authentication failed — the saved bond is dead. Recovery must remove the bond and re-pair. */
    AUTHENTICATION,

    /** A fresh pairing was rejected (typically a wrong PIN). Recovery must remove the half-formed pair. */
    PIN_REJECTED,

    /** A transient failure — the bond is good. Recovery prefers a plain retry. */
    TRANSIENT,
    ;

    /**
     * Whether recovery requires removing the bond. This is the safety gate the destructive
     * "Remove and Retry" action must be conditioned on — only [AUTHENTICATION] and [PIN_REJECTED]. A
     * [TRANSIENT] (or an absent kind) must keep the non-destructive path.
     */
    val requiresBondRemoval: Boolean
        get() = this == AUTHENTICATION || this == PIN_REJECTED
}
