package one.meshcore.shared.contact

/**
 * Device auto-add policy bits from the `autoAddConfig` bitmask. Values are pinned to the wire format.
 */
object AutoAddConfigBits {
    /** Overwrite the oldest non-favorite contact when storage is full. */
    const val OVERWRITE_OLDEST: Int = 0x01
    const val CONTACTS: Int = 0x02
    const val REPEATERS: Int = 0x04
    const val ROOM_SERVERS: Int = 0x08
    const val SENSORS: Int = 0x10

    /** All auto-add type bits (chat/repeater/room/sensor). */
    const val TYPE_BITS_MASK: Int = CONTACTS or REPEATERS or ROOM_SERVERS or SENSORS
}

/**
 * The auto-add behavior for discovered nodes, derived from device settings. Kotlin port of the Swift
 * `AutoAddMode`. In [ALL] and [SELECTED_TYPES] the firmware
 * auto-adds heard nodes (Discover is sparse); in [MANUAL] every heard node surfaces for the user to add.
 */
enum class AutoAddMode {
    /** Review all nodes in Discover before adding. */
    MANUAL,

    /** Auto-add only the types enabled in settings. */
    SELECTED_TYPES,

    /** Auto-add every discovered node. */
    ALL,
    ;

    companion object {
        /**
         * Computes the auto-add mode from device settings, mirroring the Swift protocol mapping:
         * - `manualAddContacts == false` -> [ALL] (firmware auto-adds everything)
         * - `manualAddContacts == true` and no type bits -> [MANUAL] (user reviews all in Discover)
         * - `manualAddContacts == true` and any type bit set -> [SELECTED_TYPES]
         */
        fun mode(manualAddContacts: Boolean, autoAddConfig: Int): AutoAddMode = when {
            !manualAddContacts -> ALL
            autoAddConfig and AutoAddConfigBits.TYPE_BITS_MASK == 0 -> MANUAL
            else -> SELECTED_TYPES
        }
    }
}
