package one.meshcore.shared.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.StatusPillState
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactSession
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.contact.ui.ContactClipboardFixtures
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.onboarding.OnboardingViewModel
import one.meshcore.shared.persistence.RoomContactStore
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore

internal class NodeRemovalFixture(private val context: Context, coordinator: NavigationCoordinator) {
    private val databaseName = "node-removal-${Uuid.random().value}.db"
    private val database = meshDatabase(context, Dispatchers.IO, databaseName)
    val rxLogDao = database.rxLogEntryDao()
    val contact = ContactClipboardFixtures.contact.copy(radioID = Uuid.random(), name = NAME)
    val store = RoomContactStore(database)
    var removalFailure: Exception? = null
    var removeCalls = 0
        private set
    private val session = object : ContactSession by NoOpContactSession() {
        override suspend fun removeContact(publicKey: ByteArray) {
            removeCalls += 1
            removalFailure?.let { throw it }
        }
    }
    private val service = ContactService(session, store, now = { 0L })
    val dependencies = ShellDependencies(
        session = ShellSessionInputs(
            coordinator = coordinator, onboarding = OnboardingViewModel(InMemoryPreferencesStore()),
            initialOnboardingComplete = true, statusPill = MutableStateFlow(StatusPillState.Hidden),
            battery = MutableStateFlow(null), connectionState = MutableStateFlow(DeviceConnectionState.READY),
            connectionAlert = MutableStateFlow(null), unreadCount = MutableStateFlow(0),
            localNodeName = MutableStateFlow(null), permissions = InertPermissionController(),
            bleScan = InertBleScanController(), actions = InertConnectionActions(), contactService = { service },
        ),
        services = ShellServiceInputs(currentRadioId = { contact.radioID }),
        sharing = ShellSharingInputs(),
        preferences = ShellPreferenceInputs(),
        radio = ShellRadioInputs(settingsStartupReadsAllowed = MutableStateFlow(false), refreshDeviceSettings = {}),
        maintenance = ShellMaintenanceInputs(),
    )

    init {
        runBlocking { store.saveContact(contact) }
    }

    fun close() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    companion object {
        const val NAME = "Selected removal contact"
    }
}
