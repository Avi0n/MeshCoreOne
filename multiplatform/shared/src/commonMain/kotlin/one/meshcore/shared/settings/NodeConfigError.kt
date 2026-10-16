package one.meshcore.shared.settings

/** Which coordinate a validation failure refers to, mirroring the Swift `CoordinateField`. */
sealed interface CoordinateField {
    data object PositionLatitude : CoordinateField
    data object PositionLongitude : CoordinateField
    data class ContactLatitude(val name: String) : CoordinateField
    data class ContactLongitude(val name: String) : CoordinateField
}

/** Which radio parameter a validation failure refers to. */
enum class RadioField { FREQUENCY, BANDWIDTH, SPREADING_FACTOR, CODING_RATE, TX_POWER }

/**
 * Structural problems the node-config import planner rejects up front, mirroring the subset of the Swift
 * `NodeConfigServiceError` the planner throws. Rejecting before any write means a malformed/poison config
 * can never half-apply after the identity has rotated.
 */
sealed class NodeConfigError(message: String) : Exception(message) {
    data class InvalidPrivateKey(val hexLength: Int) : NodeConfigError("Invalid private key (hex length $hexLength)")
    data class InvalidCoordinate(val field: CoordinateField) : NodeConfigError("Invalid coordinate: $field")
    data class InvalidRadioSettings(val field: RadioField) : NodeConfigError("Invalid radio setting: $field")
    data class InvalidChannelSecret(val index: Int, val hexLength: Int) :
        NodeConfigError("Invalid channel secret at $index (hex length $hexLength)")

    data class NoAvailableChannelSlot(val name: String) : NodeConfigError("No free channel slot for '$name'")
    data class InvalidContactPublicKey(val name: String) : NodeConfigError("Invalid contact public key for '$name'")
    data class ContactCapacityExceeded(val needed: Int, val available: Int) :
        NodeConfigError("Contact capacity exceeded: needed $needed, available $available")

    data class InvalidOutPath(val name: String) : NodeConfigError("Invalid out-path for '$name'")
    data class InvalidPathHashMode(val name: String, val mode: Int) :
        NodeConfigError("Invalid path-hash mode $mode for '$name'")
}
