package one.meshcore.shared.pathedit

/** The list without [index], or null when [index] is outside the list. */
internal fun <T> List<T>.removingAt(index: Int): List<T>? =
    if (index in indices) filterIndexed { i, _ -> i != index } else null

/**
 * The list after a SwiftUI `onMove` from [from] to [to], or null when either index is outside
 * the insertion range. A downward move lands at `to - 1` because [to] is the index before removal.
 */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T>? {
    if (from !in indices || to !in 0..size) return null
    val list = toMutableList()
    val item = list.removeAt(from)
    list.add(if (to > from) to - 1 else to, item)
    return list
}
