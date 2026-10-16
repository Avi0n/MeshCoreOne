package one.meshcore.shared.chat.reactions

internal actual fun codePointAt(text: String, index: Int): Int = text.codePointAt(index)

internal actual fun codePointCharCount(codePoint: Int): Int = Character.charCount(codePoint)

internal actual fun unicodeCharacterName(codePoint: Int): String? = Character.getName(codePoint)
