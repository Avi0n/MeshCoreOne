package one.meshcore.shared.contact

import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.meshcore.PathInfo
import one.meshcore.shared.model.toUppercaseHex

/** Bytes of the public-key prefix iOS interpolates in the path-discovery dump line. */
private const val PATH_DISCOVERY_KEY_PREFIX_BYTES: Int = 3

/** iOS `handlePathDiscoveryResponse` hop line, so a radio-drop export names the discovered route. */
internal fun pathDiscoveryDumpLine(path: PathInfo): String {
    val outHash = PathEncoding.decodePathLen(path.outPathLength)?.hashSize ?: 1
    val inHash = PathEncoding.decodePathLen(path.inPathLength)?.hashSize ?: 1
    val outHops = PathEncoding.pathHops(path.outPath, outHash).map { it.hex }
    val inHops = PathEncoding.pathHops(path.inPath, inHash).map { it.hex }
    val prefixLen = minOf(PATH_DISCOVERY_KEY_PREFIX_BYTES, path.publicKeyPrefix.size)
    val pubKeyHex =
        if (prefixLen == 0) "" else path.publicKeyPrefix.copyOfRange(0, prefixLen).toUppercaseHex()
    val outDisplay = if (outHops.isEmpty()) "direct" else outHops.joinToString(" → ")
    val inDisplay = if (inHops.isEmpty()) "direct" else inHops.joinToString(" → ")
    return "Path discovery for $pubKeyHex... - Out: ${outHops.size} hops ($outDisplay), " +
        "In: ${inHops.size} hops ($inDisplay)"
}
