package one.meshcore.shared.connection

import one.meshcore.shared.channel.ChannelService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.settings.NodeConfigSession
import one.meshcore.shared.settings.NodeConfigWrites

/**
 * Bridges the live [MeshSession] plus channel/contact persistence to the [NodeConfigSession] seam
 * [one.meshcore.shared.settings.NodeConfigService] drives. Device reads map 1:1 onto the session. Writes
 * delegate to [NodeConfigSessionWrites], including the radio kHz/Hz → MHz/kHz conversion, the auto-add →
 * manual-add invert, and the channel and contact upserts.
 */
class NodeConfigSessionAdapter(
    private val session: MeshSession,
    channelService: ChannelService,
    contactStore: ContactStore,
) : NodeConfigSession,
    NodeConfigWrites by NodeConfigSessionWrites(session, channelService, contactStore) {
    override suspend fun getSelfInfo(): SelfInfo = session.getSelfInfo()

    override suspend fun exportPrivateKey(): ByteArray = session.exportPrivateKey()

    override suspend fun queryDevice(): DeviceCapabilities = session.queryDevice()

    override suspend fun getContacts(since: Long?): List<MeshContact> = session.getContacts(since)

    override suspend fun getChannel(index: Int): ChannelInfo = session.getChannel(index)
}
