@file:UseSerializers(Base64ByteArraySerializer::class)

package one.meshcore.shared.meshcore

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encodeToString
import one.meshcore.shared.model.Base64ByteArraySerializer

// The flood-region resolve envelopes (v1), frozen together by the golden files under
// `fixtures/bridge/region/`. Every field below is declared in lexicographic order: kotlinx emits
// keys in declaration order and the Swift producer sorts them, so matching the order here is what
// makes the request byte-identical on both sides.

private const val REQUEST_KIND = "regionResolveRequest"
private const val RESPONSE_KIND = "regionResolve"
private const val FAILURE_KIND = "regionResolveFailure"

@Serializable
private data class ResolveRowDto(val payload: ByteArray, val payloadTypeBits: Int, val transportCode: ByteArray)

@Serializable
private data class ResolveRequestPayload(val regionNames: List<String>, val rows: List<ResolveRowDto>)

@Serializable
private data class ResolveRequestEnvelope(val kind: String, val payload: ResolveRequestPayload, val v: Int)

@Serializable
private data class ResolveResponsePayload(val matches: List<List<String>> = emptyList(), val regions: List<String?>)

@Serializable
private data class ResolveFailurePayload(val errorCode: Int, val message: String)

/** Encodes [regionNames] and [rows] as the UTF-8 bytes of a v1 `regionResolveRequest` envelope. */
internal fun encodeRegionResolveRequest(regionNames: List<String>, rows: List<RegionResolveRow>): ByteArray {
    val envelope = ResolveRequestEnvelope(
        kind = REQUEST_KIND,
        payload = ResolveRequestPayload(
            regionNames = regionNames,
            rows = rows.map { ResolveRowDto(it.payload, it.payloadTypeBits, it.transportCode) },
        ),
        v = BRIDGE_ENVELOPE_VERSION,
    )
    return bridgeJson.encodeToString(envelope).encodeToByteArray()
}

/**
 * Decodes a resolve response into exactly [rowCount] entries, padding a short list with nulls rather
 * than letting the answers slip out of line with the rows. A failure envelope raises
 * [BridgeDecodingException] carrying the bridge's own code and message: an unresolved row and a
 * failed call look identical downstream, so the difference has to be reported here or lost.
 */
internal fun decodeRegionResolveResponse(jsonText: String, rowCount: Int): List<RegionResolveHit> {
    val (kind, payload) = splitEnvelope(jsonText)
    val decoded = when (kind) {
        RESPONSE_KIND -> bridgeJson.decodeFromJsonElement(ResolveResponsePayload.serializer(), payload)
        FAILURE_KIND -> {
            val failure = bridgeJson.decodeFromJsonElement(ResolveFailurePayload.serializer(), payload)
            throw BridgeDecodingException("region resolve failed: ${failure.errorCode}: ${failure.message}")
        }
        else -> throw BridgeDecodingException("unexpected region resolve envelope kind '$kind'")
    }
    return List(rowCount) { index ->
        val unique = decoded.regions.getOrNull(index)
        val matches = decoded.matches.getOrNull(index) ?: listOfNotNull(unique)
        RegionResolveHit(uniqueName = unique, matches = matches)
    }
}
