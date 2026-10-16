package one.meshcore.shared.contact

/** iOS `AdvertisementService.handleTraceData` dump. */
fun receivedTraceDataLine(tag: Long, hopCount: Int): String = "Received trace data: tag=$tag, hops=$hopCount"
