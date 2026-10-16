package one.meshcore.shared.connection

/** Desktop / tests: no OS pairing registry. Connect sheets fall back to in-app BLE scan. */
object NoOpCompanionDeviceRegistry : CompanionDeviceRegistry {
    override val isAvailable: Boolean = false

    override fun associations(): List<CompanionAssociation> = emptyList()

    override suspend fun associate(): CompanionAssociateResult = CompanionAssociateResult.Unavailable

    override fun disassociate(handle: String) = Unit
}
