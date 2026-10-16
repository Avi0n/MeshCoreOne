package one.meshcore.shared.settings

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_settings_config_import_error_invalid_file
import one.meshcore.shared.resources.settings_config_import_error_contact_coordinate_invalid
import one.meshcore.shared.resources.settings_config_import_error_position_invalid
import one.meshcore.shared.resources.settings_config_import_field_latitude
import one.meshcore.shared.resources.settings_config_import_field_longitude
import one.meshcore.shared.settings.ui.axisResource
import one.meshcore.shared.settings.ui.formatArgs
import one.meshcore.shared.settings.ui.labelResource
import one.meshcore.shared.settings.ui.messageResource
import one.meshcore.shared.settings.ui.nodeConfigFailureResource
import one.meshcore.shared.settings.ui.nodeConfigUserFacingMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The import screen surfaces a planner rejection straight into `errorMessage`, so an unmapped case tells
 * the user in English why their radio config was refused.
 */
class NodeConfigErrorMessageTest {
    @Test
    fun everyRejectionResolvesToItsOwnMessage() {
        val cases = listOf(
            NodeConfigError.InvalidPrivateKey(hexLength = 1),
            NodeConfigError.InvalidRadioSettings(RadioField.FREQUENCY),
            NodeConfigError.InvalidChannelSecret(index = 1, hexLength = 2),
            NodeConfigError.NoAvailableChannelSlot(name = "a"),
            NodeConfigError.InvalidContactPublicKey(name = "a"),
            NodeConfigError.ContactCapacityExceeded(needed = 1, available = 0),
            NodeConfigError.InvalidOutPath(name = "a"),
            NodeConfigError.InvalidPathHashMode(name = "a", mode = 9),
            NodeConfigError.InvalidCoordinate(CoordinateField.PositionLatitude),
        )

        assertEquals(
            cases.size,
            cases.map { it.messageResource() }.toSet().size,
            "two rejections share a message, so one of them explains the wrong problem",
        )
    }

    /**
     * A config can carry hundreds of contacts, so a contact's bad coordinate must name the contact; the
     * device's own position has only one owner and names just the axis.
     */
    @Test
    fun aContactCoordinateUsesTheMessageThatNamesTheContact() {
        val device = NodeConfigError.InvalidCoordinate(CoordinateField.PositionLongitude)
        val contact = NodeConfigError.InvalidCoordinate(CoordinateField.ContactLongitude("Ridge"))

        assertEquals(Res.string.settings_config_import_error_position_invalid, device.messageResource())
        assertEquals(
            Res.string.settings_config_import_error_contact_coordinate_invalid,
            contact.messageResource(),
        )
    }

    /**
     * The planner rejects a private key that is not [NodeConfigLimits.PRIVATE_KEY_SIZE] bytes, so the message
     * must quote that length. A message that names the length the user already supplied leads nowhere.
     */
    @Test
    fun thePrivateKeyMessageStatesTheLengthThePlannerRequires() {
        val thirtyTwoByteKeyHex = "ab".repeat(32)
        val error = assertFailsWith<NodeConfigError.InvalidPrivateKey> {
            planConfigImport(
                config = MeshCoreNodeConfig(privateKey = thirtyTwoByteKeyHex),
                sections = ConfigSections(nodeIdentity = true),
                device = ConfigImportDevice(
                    maxChannels = 4,
                    maxContacts = 100,
                    maxTxPower = 30,
                    existingChannels = emptyList(),
                    existingContacts = emptyMap(),
                ),
            )
        }

        assertEquals(
            listOf(thirtyTwoByteKeyHex.length, NodeConfigLimits.PRIVATE_KEY_SIZE * 2),
            error.formatArgs(emptyMap()),
        )
    }

    /** The parse step decodes the picked file, so its failure is a JSON error, not a planner rejection. */
    @Test
    fun aFileTheDecoderRefusesGetsItsOwnMessage() {
        val decodeFailure = assertFails { NodeConfigJson.decode("{\"name\": ") }

        assertEquals(
            Res.string.android_settings_config_import_error_invalid_file,
            nodeConfigFailureResource(decodeFailure),
        )
        assertNull(nodeConfigFailureResource(IllegalStateException("no mapping")))
    }

    @Test
    fun anUnmappedFaultShowsFallbackNotEnglish() {
        val error = IllegalStateException("radio down")
        assertEquals(
            "export fail",
            nodeConfigUserFacingMessage(error, mapped = { null }, fallback = "export fail"),
        )
        assertEquals(
            "mapped",
            nodeConfigUserFacingMessage(error, mapped = { "mapped" }, fallback = "export fail"),
        )
    }

    @Test
    fun eachRadioFieldNamesItself() {
        val fields = RadioField.entries
        assertEquals(fields.size, fields.map { it.labelResource() }.toSet().size)
    }

    @Test
    fun latitudeAndLongitudeAreNotConfused() {
        assertEquals(
            Res.string.settings_config_import_field_latitude,
            CoordinateField.ContactLatitude("a").axisResource(),
        )
        assertEquals(
            Res.string.settings_config_import_field_longitude,
            CoordinateField.PositionLongitude.axisResource(),
        )
    }
}
