package one.meshcore.shared.chat.reactions

import java.text.BreakIterator
import java.util.Locale

internal actual fun extendedGraphemeCount(text: String): Int {
    if (text.isEmpty()) return 0
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    var count = 0
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        count++
        end = iterator.next()
    }
    return count
}
