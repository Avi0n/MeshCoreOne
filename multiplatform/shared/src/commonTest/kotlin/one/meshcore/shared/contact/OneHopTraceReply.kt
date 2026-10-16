package one.meshcore.shared.contact

import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.TraceInfo
import one.meshcore.shared.meshcore.TraceNode

/** A one-hop trace reply with hash 0x01, so a ping test can vary only the tag. */
internal fun oneHopTraceReply(tag: Long): MeshEvent = MeshEvent.TraceData(
    TraceInfo(
        tag = tag,
        authCode = 0,
        flags = 0,
        pathLength = 1,
        path = listOf(TraceNode(hashBytes = byteArrayOf(0x01), snr = 1.0)),
    ),
)
