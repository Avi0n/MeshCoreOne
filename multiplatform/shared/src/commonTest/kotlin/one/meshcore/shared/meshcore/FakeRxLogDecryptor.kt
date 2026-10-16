package one.meshcore.shared.meshcore

/** One key the fake opens rows with, and the plaintext it reports when it does. */
class FakeDecryptKey(val bytes: ByteArray, val text: String, val senderTimestamp: Long)

/**
 * Stands in for the bridge decryptor in pure-JVM tests.
 *
 * It opens a row when one of the submitted keys is in its keyring and reports the plaintext it was
 * configured with — never anything derived from the payload. That is the point: a row's text can
 * only reach a test through this seam, so moving the crypto back above the bridge makes these tests
 * fail rather than quietly keep passing.
 */
class FakeRxLogDecryptor(
    private val keyring: List<FakeDecryptKey> = emptyList(),
    private val whenNoKeyMatches: RxLogDecryptResult = RxLogDecryptResult.NoMatchingKey,
) : RxLogDecryptor {
    /** Batches submitted, so a test can prove a whole re-derivation pass crosses the boundary once. */
    var batchCount: Int = 0
        private set

    /** Rows submitted across every batch. */
    var rowCount: Int = 0
        private set

    override fun decrypt(rows: List<RxLogDecryptRow>): List<RxLogDecryptResult> {
        batchCount++
        rowCount += rows.size
        return rows.map { answer(it) }
    }

    private fun answer(row: RxLogDecryptRow): RxLogDecryptResult {
        for (candidate in row.keys) {
            val known = keyring.firstOrNull { it.bytes.contentEquals(candidate.bytes) } ?: continue
            return RxLogDecryptResult.Success(candidate.index, known.senderTimestamp, known.text)
        }
        return whenNoKeyMatches
    }
}
