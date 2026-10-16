package one.meshcore.shared.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.serialization.SerializationException
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_settings_config_import_error_invalid_file
import one.meshcore.shared.resources.settings_config_import_error_contact_capacity_exceeded
import one.meshcore.shared.resources.settings_config_import_error_contact_coordinate_invalid
import one.meshcore.shared.resources.settings_config_import_error_invalid_channel_secret
import one.meshcore.shared.resources.settings_config_import_error_invalid_contact_public_key
import one.meshcore.shared.resources.settings_config_import_error_invalid_out_path
import one.meshcore.shared.resources.settings_config_import_error_invalid_path_hash_mode
import one.meshcore.shared.resources.settings_config_import_error_invalid_private_key
import one.meshcore.shared.resources.settings_config_import_error_no_available_channel_slot
import one.meshcore.shared.resources.settings_config_import_error_position_invalid
import one.meshcore.shared.resources.settings_config_import_error_radio_out_of_range
import one.meshcore.shared.resources.settings_config_import_field_bandwidth
import one.meshcore.shared.resources.settings_config_import_field_coding_rate
import one.meshcore.shared.resources.settings_config_import_field_frequency
import one.meshcore.shared.resources.settings_config_import_field_latitude
import one.meshcore.shared.resources.settings_config_import_field_longitude
import one.meshcore.shared.resources.settings_config_import_field_spreading_factor
import one.meshcore.shared.resources.settings_config_import_field_tx_power
import one.meshcore.shared.settings.CoordinateField
import one.meshcore.shared.settings.NodeConfigError
import one.meshcore.shared.settings.NodeConfigLimits
import one.meshcore.shared.settings.RadioField
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The translated message for a node-config import failure. Each case carries developer English in its
 * constructor; the import and export screens map that through [nodeConfigUserFacingMessage] so an
 * unmapped fault does not show JNI or persist exception text.
 *
 * Exhaustive on purpose: a case added to the planner is a compile error here rather than a silent
 * fallback to English.
 */
fun NodeConfigError.messageResource(): StringResource = when (this) {
    is NodeConfigError.InvalidPrivateKey -> Res.string.settings_config_import_error_invalid_private_key
    is NodeConfigError.InvalidRadioSettings -> Res.string.settings_config_import_error_radio_out_of_range
    is NodeConfigError.InvalidChannelSecret -> Res.string.settings_config_import_error_invalid_channel_secret
    is NodeConfigError.NoAvailableChannelSlot -> Res.string.settings_config_import_error_no_available_channel_slot
    is NodeConfigError.InvalidContactPublicKey ->
        Res.string.settings_config_import_error_invalid_contact_public_key
    is NodeConfigError.ContactCapacityExceeded ->
        Res.string.settings_config_import_error_contact_capacity_exceeded
    is NodeConfigError.InvalidOutPath -> Res.string.settings_config_import_error_invalid_out_path
    is NodeConfigError.InvalidPathHashMode -> Res.string.settings_config_import_error_invalid_path_hash_mode
    is NodeConfigError.InvalidCoordinate -> when (field) {
        // A device coordinate names only the axis; a contact's also names the contact, because a config
        // can carry hundreds and "invalid latitude" alone would not say which one to fix.
        is CoordinateField.PositionLatitude, is CoordinateField.PositionLongitude ->
            Res.string.settings_config_import_error_position_invalid
        is CoordinateField.ContactLatitude, is CoordinateField.ContactLongitude ->
            Res.string.settings_config_import_error_contact_coordinate_invalid
    }
}

/** The shipped name of a radio parameter, filling the `%s` in the out-of-range message. */
fun RadioField.labelResource(): StringResource = when (this) {
    RadioField.FREQUENCY -> Res.string.settings_config_import_field_frequency
    RadioField.BANDWIDTH -> Res.string.settings_config_import_field_bandwidth
    RadioField.SPREADING_FACTOR -> Res.string.settings_config_import_field_spreading_factor
    RadioField.CODING_RATE -> Res.string.settings_config_import_field_coding_rate
    RadioField.TX_POWER -> Res.string.settings_config_import_field_tx_power
}

/** The shipped name of a coordinate axis, filling the `%s` in the coordinate messages. */
fun CoordinateField.axisResource(): StringResource = when (this) {
    is CoordinateField.PositionLatitude, is CoordinateField.ContactLatitude ->
        Res.string.settings_config_import_field_latitude
    is CoordinateField.PositionLongitude, is CoordinateField.ContactLongitude ->
        Res.string.settings_config_import_field_longitude
}

/** Every string [nodeConfigFailureResource] and its label helpers can return. */
private val NODE_CONFIG_ERROR_RESOURCES: Set<StringResource> = setOf(
    Res.string.android_settings_config_import_error_invalid_file,
    Res.string.settings_config_import_error_invalid_private_key,
    Res.string.settings_config_import_error_radio_out_of_range,
    Res.string.settings_config_import_error_invalid_channel_secret,
    Res.string.settings_config_import_error_no_available_channel_slot,
    Res.string.settings_config_import_error_invalid_contact_public_key,
    Res.string.settings_config_import_error_contact_capacity_exceeded,
    Res.string.settings_config_import_error_invalid_out_path,
    Res.string.settings_config_import_error_invalid_path_hash_mode,
    Res.string.settings_config_import_error_position_invalid,
    Res.string.settings_config_import_error_contact_coordinate_invalid,
    Res.string.settings_config_import_field_frequency,
    Res.string.settings_config_import_field_bandwidth,
    Res.string.settings_config_import_field_spreading_factor,
    Res.string.settings_config_import_field_coding_rate,
    Res.string.settings_config_import_field_tx_power,
    Res.string.settings_config_import_field_latitude,
    Res.string.settings_config_import_field_longitude,
)

/**
 * Mapped node-config or file-parse copy, else [fallback]. Never interpolates the exception, matching
 * iOS `error.userFacingMessage` rather than `localizedDescription` for unmapped faults.
 */
fun nodeConfigUserFacingMessage(error: Throwable, mapped: (Throwable) -> String?, fallback: String): String =
    mapped(error) ?: fallback

/**
 * The shipped message for anything the import screen can catch, or null for a failure with no mapping (the
 * caller keeps its own fallback). Covers the parse step as well as the planner: the file the user picked is
 * read by the JSON decoder, whose own message is developer English about tokens and offsets.
 */
fun nodeConfigFailureResource(error: Throwable): StringResource? = when (error) {
    is NodeConfigError -> error.messageResource()
    is SerializationException -> Res.string.android_settings_config_import_error_invalid_file
    else -> null
}

/**
 * Turns a node-config import failure into text for the import screen, which catches inside a coroutine
 * and so cannot read resources at the point of failure. Answers null for an unmapped failure, leaving the
 * caller's existing fallback in place.
 */
@Composable
fun rememberNodeConfigErrorText(): (Throwable) -> String? {
    val templates = NODE_CONFIG_ERROR_RESOURCES.associateWith { stringResource(it) }
    return remember(templates) {
        { error ->
            nodeConfigFailureResource(error)?.let { resource ->
                templates[resource]?.let { template ->
                    formatTemplate(template, (error as? NodeConfigError)?.formatArgs(templates).orEmpty())
                }
            }
        }
    }
}

/** The substitutions each message expects, left to right, with field names already resolved. */
internal fun NodeConfigError.formatArgs(templates: Map<StringResource, String>): List<Any> = when (this) {
    is NodeConfigError.InvalidPrivateKey -> listOf(hexLength, PRIVATE_KEY_HEX_LENGTH)
    is NodeConfigError.InvalidRadioSettings -> listOfNotNull(templates[field.labelResource()])
    is NodeConfigError.InvalidChannelSecret -> listOf(index, hexLength)
    is NodeConfigError.NoAvailableChannelSlot -> listOf(name)
    is NodeConfigError.InvalidContactPublicKey -> listOf(name)
    is NodeConfigError.ContactCapacityExceeded -> listOf(needed, available)
    is NodeConfigError.InvalidOutPath -> listOf(name)
    is NodeConfigError.InvalidPathHashMode -> listOf(name, mode)
    is NodeConfigError.InvalidCoordinate -> coordinateArgs(field, templates)
}

private fun coordinateArgs(field: CoordinateField, templates: Map<StringResource, String>): List<Any> {
    val axis = templates[field.axisResource()].orEmpty()
    return when (field) {
        is CoordinateField.PositionLatitude, is CoordinateField.PositionLongitude -> listOf(axis)
        is CoordinateField.ContactLatitude -> listOf(field.name, axis)
        is CoordinateField.ContactLongitude -> listOf(field.name, axis)
    }
}

/** The hex length the planner requires, so the message quotes the length that would be accepted. */
private val PRIVATE_KEY_HEX_LENGTH = NodeConfigLimits.PRIVATE_KEY_SIZE * 2
