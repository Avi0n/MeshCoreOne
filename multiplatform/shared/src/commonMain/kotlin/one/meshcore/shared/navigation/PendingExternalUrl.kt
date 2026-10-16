package one.meshcore.shared.navigation

/**
 * App-level staging for a `meshcore://` URL the OS may deliver at or near cold launch, ported from the
 * iOS `PendingExternalURL`. Routing such a URL synchronously would lose it: a failed auto-reconnect
 * during launch clears pending deep-link state before the confirmation sheet can present, and (on iOS)
 * the before-first-unlock app-state swap drops anything routed into the throwaway instance.
 *
 * The URL is held here and routed only once [markReady] has run at the tail of the launch sequence, after
 * activation and foreground reconciliation, so the teardown a failed reconnect performs cannot wipe a
 * freshly staged link. [submit] routes immediately if already ready; [markReady] routes anything held.
 * The URL is dropped after routing so a warm-app [submit] and the end-of-launch [markReady] cannot both
 * route the same URL.
 *
 * Held outside any connection or view-model object so it survives their teardown and replacement. The
 * routing itself is a caller-supplied sink (the app hands in one that dispatches to [DeepLinkRouter] on a
 * scope), keeping this holder free of coroutines and trivially testable.
 */
typealias PendingExternalUrl = PendingConversationOpen<String>
