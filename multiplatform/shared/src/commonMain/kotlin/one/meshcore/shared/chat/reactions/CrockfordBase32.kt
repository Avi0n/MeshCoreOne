package one.meshcore.shared.chat.reactions

/**
 * Crockford Base32 over the alphabet `0123456789abcdefghjkmnpqrstvwxyz` (no i/l/o/u). Encoding packs bytes
 * MSB-first into 5-bit groups; decoding/validation accepts both cases plus the ambiguity substitutions
 * O/o→0, I/i→1, L/l→1, and rejects everything else (notably U/u). Port of the private Crockford helpers in
 * the Swift `ReactionParser`; the reaction hash's on-wire form depends on this being byte-exact.
 */
internal object CrockfordBase32 {
    private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"
    private const val BITS_PER_CHAR = 5
    private const val CHAR_MASK = 0x1F
    private const val BYTE_MASK = 0xFFL
    private const val BITS_PER_BYTE = 8
    private const val ASCII_LIMIT = 128
    private const val INVALID: Int = -1

    private val decodeTable: IntArray = buildDecodeTable()

    private fun buildDecodeTable(): IntArray {
        val table = IntArray(ASCII_LIMIT) { INVALID }
        ALPHABET.forEachIndexed { index, char ->
            table[char.code] = index
            table[char.uppercaseChar().code] = index
        }
        table['O'.code] = 0
        table['o'.code] = 0
        table['I'.code] = 1
        table['i'.code] = 1
        table['L'.code] = 1
        table['l'.code] = 1
        return table
    }

    /** Packs the first [byteCount] bytes of [bytes] MSB-first into `byteCount * 8 / 5` Base32 chars. */
    fun encode(bytes: ByteArray, byteCount: Int): String {
        var bits = 0L
        for (i in 0 until byteCount) {
            bits = (bits shl BITS_PER_BYTE) or (bytes[i].toLong() and BYTE_MASK)
        }
        val charCount = byteCount * BITS_PER_BYTE / BITS_PER_CHAR
        val result = StringBuilder(charCount)
        for (group in charCount - 1 downTo 0) {
            val index = ((bits shr (group * BITS_PER_CHAR)) and CHAR_MASK.toLong()).toInt()
            result.append(ALPHABET[index])
        }
        return result.toString()
    }

    /** Whether every character of [string] is a valid Crockford Base32 char (including the substitutions). */
    fun isValid(string: String): Boolean = string.all { char ->
        char.code < ASCII_LIMIT && decodeTable[char.code] >= 0
    }

    /** Maps a valid Crockford string to its canonical lowercase form, dropping any non-ASCII chars. */
    fun normalize(string: String): String {
        val result = StringBuilder(string.length)
        for (char in string) {
            if (char.code >= ASCII_LIMIT) continue
            val value = decodeTable[char.code]
            if (value >= 0) result.append(ALPHABET[value])
        }
        return result.toString()
    }
}
