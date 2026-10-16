package one.meshcore.shared.connection

import one.meshcore.shared.runLoggedException

/**
 * A radio command finished with [Value], including a null answer. [Failed] means the command
 * threw, so the caller must not persist a guess.
 */
sealed interface AnsweredRadioRead<out T> {
    data class Value<T>(val value: T) : AnsweredRadioRead<T>

    data object Failed : AnsweredRadioRead<Nothing>
}

/**
 * Runs a radio command whose null answer is real. A thrown failure is logged as [failure] and
 * reported [AnsweredRadioRead.Failed]. Cancellation still stops the caller, and an error still
 * propagates.
 */
suspend fun <T> readAnsweredRadioCommand(
    warning: (String) -> Unit,
    failure: String,
    block: suspend () -> T,
): AnsweredRadioRead<T> = runLoggedException(warning, failure, fallback = { AnsweredRadioRead.Failed }) {
    AnsweredRadioRead.Value(block())
}
