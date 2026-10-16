package one.meshcore.shared.chat.translation

/**
 * The composer asks the system overlay only before a translation is showing, and only when that
 * overlay is the preference. A showing translation, or an overlay that is off, runs the action.
 */
internal fun applyComposerTranslate(
    showing: Boolean,
    usesSystemOverlay: Boolean,
    presentSystem: () -> Unit,
    perform: () -> Unit,
) {
    if (!showing && usesSystemOverlay) presentSystem() else perform()
}

/** The message sheet runs the action while a translation is showing. Otherwise it opens the system overlay. */
internal fun applySheetTranslate(showing: Boolean, presentSystem: () -> Unit, perform: () -> Unit) {
    if (showing) perform() else presentSystem()
}
