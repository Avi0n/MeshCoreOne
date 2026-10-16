package one.meshcore.shared.settings

/**
 * Identifies which destructive write an [ImportProgress] update precedes, mirroring the Swift `ImportStep`.
 * Localization-free: the UI maps each case to display copy.
 */
sealed interface ImportStep {
    data object Position : ImportStep
    data object OtherParameters : ImportStep
    data object PrivateKey : ImportStep
    data object NodeName : ImportStep
    data object RadioParameters : ImportStep
    data object TxPower : ImportStep
    data class Channel(val name: String) : ImportStep
    data class Contact(val name: String) : ImportStep
}

/** Reports import progress to the UI: the step about to run, and its position in the total. */
data class ImportProgress(val step: ImportStep, val current: Int, val total: Int)

/**
 * A non-destructive summary of what an import would do, driving the confirmation UI before any write.
 * [channelsOverwriteExisting] is true when at least one channel write would replace an already-configured
 * slot whose name or secret differs.
 */
data class ImportPreview(val channelsOverwriteExisting: Boolean)
