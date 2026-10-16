package one.meshcore.shared.meshcore

/** The value used when a device error's detail carries no readable firmware sub-code. */
const val UNKNOWN_FIRMWARE_CODE: Int = -1

/**
 * The firmware sub-code carried in a [BridgeErrorCode.DEVICE_ERROR] detail.
 *
 * The bridge writes `device error code=<n>` and appends ` (<name>)` whenever it recognises the code, so
 * reading the trailing digits finds nothing for exactly the codes the app can act on. Retry decisions and
 * error copy both switch on this value, so a miss is not cosmetic: an unmatched code makes a full message
 * pool look like a permanent failure and drops the message instead of parking it.
 */
fun MeshBridgeException.firmwareCode(): Int =
    FIRMWARE_CODE.find(detail)?.groupValues?.get(1)?.toIntOrNull() ?: UNKNOWN_FIRMWARE_CODE

private val FIRMWARE_CODE = Regex("""code=(\d+)""")
