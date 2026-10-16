package one.meshcore.shared.connection

import one.meshcore.shared.chat.hydrate
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.simulator.MockDataProvider
import one.meshcore.shared.simulator.SimulatorMeshLink
import one.meshcore.shared.simulator.SimulatorSeeder

internal suspend fun ConnectionManager.openSimulatorSession(handle: String, generation: Long) {
    val mockLink = SimulatorMeshLink()
    mockLink.connect()
    if (!ownsConnectAttempt(generation)) return
    if (attachSimulatorSession(mockLink, handle, generation) == null) return
    publishSimulatorReady(handle, generation)
}

private suspend fun ConnectionManager.attachSimulatorSession(
    mockLink: SimulatorMeshLink,
    handle: String,
    generation: Long,
): ServiceContainer? {
    link = mockLink
    val newSession = sessionFactory(mockLink)
    session = newSession
    val now = nowEpochSeconds()
    val device = MockDataProvider.simulatorDevice(now)
    database.deviceDao().upsert(device)
    applyLastConnectionPersist(
        persist = {
            lastConnectionStore?.persist(
                device.id,
                device.radioID,
                MockDataProvider.SIMULATOR_DEVICE_NAME,
                handle,
            )
        },
        logger = logger,
        failLine = FAILED_PERSIST_LAST_CONNECTION,
    )
    val container = simulatorServiceContainer(newSession, device)
    if (!ownsConnectAttempt(generation)) {
        container.tearDown()
        return null
    }
    services = container
    container.sendQueue.hydrate()
    SimulatorSeeder.seed(database, now)
    return container
}

private fun ConnectionManager.simulatorServiceContainer(
    newSession: MeshSession,
    device: DeviceEntity,
): ServiceContainer = connectedServiceContainer(
    newSession,
    ServiceContainerIdentity(
        radioId = device.radioID,
        selfPublicKey = device.publicKey,
        selfNodeName = device.nodeName,
        maxContacts = device.maxContacts,
        maxChannels = device.maxChannels,
    ),
)

private suspend fun ConnectionManager.publishSimulatorReady(handle: String, generation: Long) {
    if (!ownsConnectAttempt(generation)) return
    onConversationsChanged()
    onContactsChanged()
    mutableState.value = ConnectionState.Ready(handle)
}
