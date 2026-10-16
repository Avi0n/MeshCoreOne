package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.flow.update

/** Name, coordinates, and owner info edits. */
interface NodeSettingsProfileEdits {
    fun setName(value: String)

    fun setLatitudeText(value: String)

    fun setLongitudeText(value: String)

    /** Fills the coordinate fields from a map-picked point, mirroring the iOS `setLocationFromPicker`. */
    fun setLocationFromPicker(latitude: Double, longitude: Double)

    fun setOwnerInfo(value: String)
}

internal class NodeSettingsProfileEditor(private val model: NodeSettingsModel) : NodeSettingsProfileEdits {
    private val helper get() = model.helper

    override fun setName(value: String) {
        helper.name = value
        model.stateUpdates.update {
            it.copy(name = value, identityModified = helper.identitySettingsModified, identityValidation = null)
        }
    }

    override fun setLatitudeText(value: String) {
        setCoordinateText(value, writeText = { it.copy(latitudeText = value) }) { helper.latitude = it }
    }

    override fun setLongitudeText(value: String) {
        setCoordinateText(value, writeText = { it.copy(longitudeText = value) }) { helper.longitude = it }
    }

    override fun setLocationFromPicker(latitude: Double, longitude: Double) {
        helper.latitude = latitude
        helper.longitude = longitude
        model.stateUpdates.update {
            it.copy(
                latitudeText = latitude.toString(),
                longitudeText = longitude.toString(),
                identityModified = helper.identitySettingsModified,
                identityValidation = null,
            )
        }
    }

    override fun setOwnerInfo(value: String) {
        helper.ownerInfo = value
        model.stateUpdates.update {
            it.copy(
                ownerInfo = value,
                ownerInfoTooLong = helper.isOwnerInfoTooLong,
                contactInfoModified = helper.contactInfoSettingsModified,
            )
        }
    }

    /**
     * Publishes the typed text and clears validation first. The parsed coordinate is stored next, and
     * modified publishes after that so it sees the new value.
     */
    private fun setCoordinateText(
        value: String,
        writeText: (NodeSettingsState) -> NodeSettingsState,
        assign: (Double?) -> Unit,
    ) {
        model.stateUpdates.update { writeText(it).copy(identityValidation = null) }
        assign(value.toDoubleOrNull())
        model.stateUpdates.update { it.copy(identityModified = helper.identitySettingsModified) }
    }
}
