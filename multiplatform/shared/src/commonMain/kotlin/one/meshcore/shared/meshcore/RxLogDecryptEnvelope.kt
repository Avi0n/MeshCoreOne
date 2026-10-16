@file:UseSerializers(Base64ByteArraySerializer::class)

package one.meshcore.shared.meshcore

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encodeToString
import one.meshcore.shared.model.Base64ByteArraySerializer

// The RX-log decrypt envelopes (v1). Unlike every other boundary value this one travels in both
// directions in the same call, so both shapes are frozen together by the golden files under
// `fixtures/bridge/rxlog/`. Every field below is declared in lexicographic order: kotlinx emits keys
// in declaration order and the Swift producer sorts them, so matching the order here is what makes
// the request byte-identical on both sides.

private const val REQUEST_KIND = "rxLogDecryptRequest"
private const val RESPONSE_KIND = "rxLogDecrypt"
private const val FAILURE_KIND = "rxLogDecryptFailure"

private const val CHANNEL_ROW_KIND = "channel"
private const val DIRECT_ROW_KIND = "direct"

private const val SUCCESS_OUTCOME = "success"
private const val NO_MATCHING_KEY_OUTCOME = "noMatchingKey"
private const val PAYLOAD_TOO_SHORT_OUTCOME = "payloadTooShort"

@Serializable
private data class DecryptKeyDto(val bytes: ByteArray, val index: Int)

@Serializable
private data class DecryptRowDto(
    val keys: List<DecryptKeyDto>,
    val kind: String,
    val payload: ByteArray,
    val privateKey: ByteArray?,
)

@Serializable
private data class DecryptRequestPayload(val rows: List<DecryptRowDto>)

@Serializable
private data class DecryptRequestEnvelope(val kind: String, val payload: DecryptRequestPayload, val v: Int)

@Serializable
private data class DecryptResultDto(
    val keyIndex: Int? = null,
    val outcome: String,
    val senderTimestamp: Long? = null,
    val text: String? = null,
)

@Serializable
private data class DecryptResponsePayload(val results: List<DecryptResultDto>)

@Serializable
private data class DecryptFailurePayload(val errorCode: Int, val message: String)

/** Encodes [rows] as the UTF-8 bytes of a v1 `rxLogDecryptRequest` envelope. */
internal fun encodeRxLogDecryptRequest(rows: List<RxLogDecryptRow>): ByteArray {
    val envelope = DecryptRequestEnvelope(
        kind = REQUEST_KIND,
        payload = DecryptRequestPayload(rows.map { it.toDto() }),
        v = BRIDGE_ENVELOPE_VERSION,
    )
    return bridgeJson.encodeToString(envelope).encodeToByteArray()
}

private fun RxLogDecryptRow.toDto(): DecryptRowDto = DecryptRowDto(
    keys = keys.map { DecryptKeyDto(it.bytes, it.index) },
    kind = if (this is RxLogDecryptRow.Direct) DIRECT_ROW_KIND else CHANNEL_ROW_KIND,
    payload = payload,
    privateKey = (this as? RxLogDecryptRow.Direct)?.privateKey,
)

/**
 * Decodes a decrypt response into exactly [rowCount] outcomes. A failure envelope, an unknown kind,
 * or a short result list all yield [RxLogDecryptResult.Unavailable] for the rows they leave
 * unanswered, so a caller never mistakes a boundary problem for "no key matched" and never has to
 * line up a ragged list against its own rows.
 */
internal fun decodeRxLogDecryptResponse(jsonText: String, rowCount: Int): List<RxLogDecryptResult> {
    val (kind, payload) = splitEnvelope(jsonText)
    val results = when (kind) {
        RESPONSE_KIND -> bridgeJson.decodeFromJsonElement(DecryptResponsePayload.serializer(), payload)
            .results
            .map { it.toResult() }
        FAILURE_KIND -> {
            val failure = bridgeJson.decodeFromJsonElement(DecryptFailurePayload.serializer(), payload)
            unavailable(rowCount, "bridge error ${failure.errorCode}: ${failure.message}")
        }
        else -> throw BridgeDecodingException("unexpected rx-log decrypt envelope kind '$kind'")
    }
    return when {
        results.size >= rowCount -> results.take(rowCount)
        else -> results + unavailable(rowCount - results.size, "bridge answered ${results.size} of $rowCount rows")
    }
}

private fun unavailable(count: Int, reason: String): List<RxLogDecryptResult> =
    List(count) { RxLogDecryptResult.Unavailable(reason) }

private fun DecryptResultDto.toResult(): RxLogDecryptResult = when (outcome) {
    SUCCESS_OUTCOME -> if (keyIndex == null || senderTimestamp == null) {
        RxLogDecryptResult.Unavailable("success outcome without a key index or timestamp")
    } else {
        RxLogDecryptResult.Success(keyIndex, senderTimestamp, text)
    }
    NO_MATCHING_KEY_OUTCOME -> RxLogDecryptResult.NoMatchingKey
    PAYLOAD_TOO_SHORT_OUTCOME -> RxLogDecryptResult.PayloadTooShort
    else -> RxLogDecryptResult.Unavailable("unknown rx-log decrypt outcome '$outcome'")
}
