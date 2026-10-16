package one.meshcore.shared.tools

/** Wire payload-type values used for RX log decrypt branching (MeshCore `PayloadType`). */
object RxLogPayloadType {
    const val REQUEST: Int = 0
    const val RESPONSE: Int = 1
    const val TEXT_MESSAGE: Int = 2
    const val ACK: Int = 3
    const val ADVERT: Int = 4
    const val GROUP_TEXT: Int = 5
    const val GROUP_DATA: Int = 6
    const val ANON_REQUEST: Int = 7
    const val PATH: Int = 8
    const val TRACE: Int = 9
    const val MULTIPART: Int = 10
    const val CONTROL: Int = 11
    const val RAW_CUSTOM: Int = 15
}
