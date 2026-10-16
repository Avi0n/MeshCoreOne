package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import one.meshcore.shared.battery.BatteryOcv
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.battery.OcvEditorState
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.getContact
import one.meshcore.shared.contact.runOcvCurveLoad
import one.meshcore.shared.contact.runOcvCurveSave
import one.meshcore.shared.contact.updateContactOCVSettings
import one.meshcore.shared.model.Uuid

/** What a battery-curve load or save reads from the live connection. */
internal class OcvCurveAccess(
    val publicKey: ByteArray,
    val contactService: () -> ContactService?,
    val radioId: () -> Uuid?,
    val isRadioConnected: () -> Boolean,
)

/** Curve fields both status screens publish. */
internal interface OcvCurveState {
    val ocvLoaded: Boolean
    val ocvPreset: OCVPreset
    val ocvValues: List<Int>
    val ocvError: NodeSectionError?
    val isSavingOcv: Boolean
}

internal data class OcvCurveFields(
    val loaded: Boolean,
    val preset: OCVPreset,
    val values: List<Int>,
    val error: NodeSectionError?,
    val saving: Boolean,
)

/** Error keys the battery section maps to localized copy. */
internal object OcvCurveKeys {
    const val LOAD_FAILED = "ocv_load_failed"
    const val SAVE_NO_CONTACT = "ocv_save_no_contact"
    const val SAVE_FAILED = "ocv_save_failed"
}

/**
 * Loads, selects, and saves one contact's battery curve.
 * A missing radio leaves the curve unset. A failed read or save is shown on the section.
 */
internal class OcvCurveController<S : OcvCurveState>(
    private val scope: CoroutineScope,
    private val access: OcvCurveAccess,
    private val state: MutableStateFlow<S>,
    private val copyOcv: S.(OcvCurveFields) -> S,
) {
    internal var job: Job? = null
        private set
    private var contactId: Uuid? = null
    private var generation = 0L

    /** Selects a named preset (not custom) and persists it. */
    fun select(preset: OCVPreset) {
        if (!scope.isActive) return
        if (preset == OCVPreset.CUSTOM) return
        val values = preset.ocvArray
        write { curve(preset = preset, values = values, error = null) }
        persist(preset, values)
    }

    /**
     * Commits a custom curve after validation. No-op when values still match the selected non-custom
     * preset (avoids flipping to custom on a focus-only commit).
     */
    fun commit(values: List<Int>) {
        if (!scope.isActive) return
        val current = state.value
        if (current.ocvPreset != OCVPreset.CUSTOM && values == current.ocvPreset.ocvArray) return
        val errorKey = BatteryOcv.validationErrorKey(values)
        if (errorKey != null) {
            write { curve(error = NodeSectionError.Message(errorKey)) }
            return
        }
        write { curve(preset = OCVPreset.CUSTOM, values = values, error = null) }
        persist(OCVPreset.CUSTOM, values)
    }

    fun load() {
        if (!scope.isActive || job?.isActive == true) return
        val service = access.contactService() ?: return
        val radio = access.radioId() ?: return
        if (contactId != null && state.value.ocvLoaded) return
        val token = ++generation
        job?.cancel()
        job = scope.launch {
            // A failed curve read is shown on the section. Cancellation and a thrown error still propagate.
            runOcvCurveLoad(
                onFailure = {
                    if (scope.isActive && generation == token) {
                        write { curve(loaded = true, error = NodeSectionError.Message(OcvCurveKeys.LOAD_FAILED)) }
                    }
                },
            ) {
                val contact = currentRemoteRequest({
                    scope.isActive &&
                        generation == token &&
                        access.contactService() === service &&
                        access.radioId() == radio
                }) { service.getContact(radio, access.publicKey) }
                if (contact == null) {
                    apply(BatteryOcv.loadEditorState(null, null), contactId = null)
                } else {
                    contactId = contact.id
                    apply(
                        BatteryOcv.loadEditorState(contact.ocvPreset, contact.customOCVArrayString),
                        contactId = contact.id,
                    )
                }
            }
        }
    }

    private fun apply(editor: OcvEditorState, contactId: Uuid?) {
        this.contactId = contactId
        write { curve(loaded = true, preset = editor.preset, values = editor.values, error = null) }
    }

    private fun persist(preset: OCVPreset, values: List<Int>) {
        val service = access.contactService()
        val id = contactId
        if (service == null || id == null || !access.isRadioConnected()) {
            write { curve(error = NodeSectionError.Message(OcvCurveKeys.SAVE_NO_CONTACT)) }
            return
        }
        val radio = access.radioId()
        val token = ++generation
        job?.cancel()
        write { curve(saving = true, error = null) }
        job = scope.launch {
            // A failed curve save is shown on the section. Cancellation clears the saving flag and still propagates.
            runOcvCurveSave(
                onCancel = { if (scope.isActive && generation == token) write { curve(saving = false) } },
                onFailure = {
                    write {
                        curve(saving = false, error = NodeSectionError.Message(OcvCurveKeys.SAVE_FAILED))
                    }
                },
            ) {
                val isCurrent = {
                    scope.isActive &&
                        generation == token &&
                        access.contactService() === service &&
                        access.radioId() == radio
                }
                currentRemoteRequest(isCurrent) {
                    if (preset == OCVPreset.CUSTOM) {
                        service.updateContactOCVSettings(
                            contactID = id,
                            preset = OCVPreset.CUSTOM.rawValue,
                            customArray = BatteryOcv.encodeCustomArray(values),
                            isCurrent = isCurrent,
                        )
                    } else {
                        service.updateContactOCVSettings(
                            contactID = id,
                            preset = preset.rawValue,
                            customArray = null,
                            isCurrent = isCurrent,
                        )
                    }
                }
                write { curve(saving = false) }
            }
        }
    }

    private fun write(change: S.() -> OcvCurveFields) {
        state.update { current -> current.copyOcv(current.change()) }
    }

    private fun OcvCurveState.curve(
        loaded: Boolean = ocvLoaded,
        preset: OCVPreset = ocvPreset,
        values: List<Int> = ocvValues,
        error: NodeSectionError? = ocvError,
        saving: Boolean = isSavingOcv,
    ) = OcvCurveFields(loaded, preset, values, error, saving)
}
