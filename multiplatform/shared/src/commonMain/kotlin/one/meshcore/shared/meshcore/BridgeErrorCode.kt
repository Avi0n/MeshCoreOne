package one.meshcore.shared.meshcore

/**
 * Stable classification of a command failure, delivered as the `errorCode` integer on the
 * bridge's `onFailure` callback. Mirrors the Swift `BridgeErrorCode` (see
 * `MeshCoreBridge/ENCODING.md`); the raw values are frozen and only ever appended.
 */
enum class BridgeErrorCode(val code: Int) {
    UNKNOWN(0),
    TIMEOUT(1),
    DEVICE_ERROR(2),
    PARSE_ERROR(3),
    NOT_CONNECTED(4),
    COMMAND_FAILED(5),
    INVALID_RESPONSE(6),
    CONTACT_NOT_FOUND(7),
    DATA_TOO_LARGE(8),
    SIGNING_FAILED(9),
    INVALID_INPUT(10),
    BLUETOOTH_UNAVAILABLE(11),
    BLUETOOTH_UNAUTHORIZED(12),
    BLUETOOTH_POWERED_OFF(13),
    CONNECTION_LOST(14),
    SESSION_NOT_STARTED(15),
    FEATURE_DISABLED(16),
    TRANSPORT_CONNECTION_FAILED(17),
    TRANSPORT_SEND_FAILED(18),
    TRANSPORT_DEVICE_NOT_FOUND(19),
    TRANSPORT_SERVICE_NOT_FOUND(20),
    TRANSPORT_CHARACTERISTIC_NOT_FOUND(21),
    INVALID_DESTINATION(22),
    ;

    companion object {
        /** Maps a raw wire code to its case, falling back to [UNKNOWN] for an unrecognized value. */
        fun fromCode(code: Int): BridgeErrorCode = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * A command that failed on the bridge. [code] is the stable classification; [detail] is the
 * human-readable diagnostic the Swift side supplied (including the firmware sub-code for
 * [BridgeErrorCode.DEVICE_ERROR]).
 */
class MeshBridgeException(val code: BridgeErrorCode, val detail: String) : Exception("${code.name}: $detail")
