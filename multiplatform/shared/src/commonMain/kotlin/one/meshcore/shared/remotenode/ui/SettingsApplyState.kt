package one.meshcore.shared.remotenode.ui

/** The busy-state transitions owned by one settings operation. */
internal class SettingsApplyState<S, R>(val begin: (S) -> S, val finish: (S, R) -> S, val cancelled: (S) -> S)
