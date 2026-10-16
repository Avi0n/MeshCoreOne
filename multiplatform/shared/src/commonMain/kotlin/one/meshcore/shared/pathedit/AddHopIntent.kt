package one.meshcore.shared.pathedit

/**
 * Drives navigation to the Add-Hop picker, ported from the iOS `AddHopIntent`. Modeled as an enum
 * (rather than a bare boolean) so a future insert-at-index mode adds a case without changing the
 * navigation binding's type. Only [Append] exists today.
 */
enum class AddHopIntent {
    APPEND,
}
